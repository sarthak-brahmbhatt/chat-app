from collections.abc import Iterable

from openai import OpenAI
from qdrant_client import QdrantClient, models

from app.config import Settings
from app.documents import ReferenceChunk


VECTOR_SIZE = 1536


class ReferenceStore:
    def __init__(self, settings: Settings, openai_client: OpenAI | None = None):
        self.settings = settings
        self.openai = openai_client or OpenAI(api_key=settings.openai_api_key)
        self.qdrant = QdrantClient(url=settings.qdrant_url)

    def ensure_collection(self) -> None:
        if self.qdrant.collection_exists(self.settings.qdrant_collection):
            return
        self.qdrant.create_collection(
            collection_name=self.settings.qdrant_collection,
            vectors_config=models.VectorParams(size=VECTOR_SIZE, distance=models.Distance.COSINE),
        )

    def upsert_chunks(self, chunks: Iterable[ReferenceChunk]) -> dict[str, int]:
        self.ensure_collection()
        chunks = list(chunks)
        existing = self._existing_checksums()
        changed = [chunk for chunk in chunks if existing.get(chunk.point_id) != chunk.payload["checksum"]]

        if changed:
            vectors = self._embed([chunk.text for chunk in changed])
            points = [
                models.PointStruct(id=chunk.point_id, vector=vector, payload=chunk.payload)
                for chunk, vector in zip(changed, vectors, strict=True)
            ]
            self.qdrant.upsert(self.settings.qdrant_collection, points=points, wait=True)

        current_ids = {chunk.point_id for chunk in chunks}
        stale_ids = [point_id for point_id in existing if point_id not in current_ids]
        if stale_ids:
            self.qdrant.delete(
                self.settings.qdrant_collection,
                points_selector=models.PointIdsList(points=stale_ids),
                wait=True,
            )

        return {"added_or_updated": len(changed), "skipped": len(chunks) - len(changed), "deleted": len(stale_ids)}

    def search_codes(self, query: str, code_type: str, limit: int = 5) -> list[dict]:
        return self._search(query, limit, [
            models.FieldCondition(key="chunk_type", match=models.MatchValue(value="coding")),
            models.FieldCondition(key="code_type", match=models.MatchValue(value=code_type)),
        ])

    def search_quality_measures(self, redacted_note: str, limit: int = 5) -> list[dict]:
        return self._search(redacted_note, limit, [
            models.FieldCondition(key="chunk_type", match=models.MatchValue(value="quality_measure")),
        ])

    def _search(self, query: str, limit: int, conditions: list[models.FieldCondition]) -> list[dict]:
        vector = self._embed([query])[0]
        result = self.qdrant.query_points(
            collection_name=self.settings.qdrant_collection,
            query=vector,
            query_filter=models.Filter(must=conditions),
            limit=limit,
            with_payload=True,
        )
        return [{**(point.payload or {}), "score": point.score} for point in result.points]

    def _embed(self, texts: list[str]) -> list[list[float]]:
        response = self.openai.embeddings.create(model=self.settings.embedding_model, input=texts)
        return [item.embedding for item in response.data]

    def _existing_checksums(self) -> dict[str, str]:
        result: dict[str, str] = {}
        offset = None
        while True:
            points, offset = self.qdrant.scroll(
                collection_name=self.settings.qdrant_collection,
                limit=256,
                offset=offset,
                with_payload=["checksum"],
                with_vectors=False,
            )
            for point in points:
                result[str(point.id)] = (point.payload or {}).get("checksum", "")
            if offset is None:
                return result
