import json
import threading
from functools import lru_cache
from queue import Queue

from fastapi import FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import StreamingResponse

from app.audit import AuditRepository
from app.config import settings
from app.extractor import ClinicalExtractionService
from app.openai_extractor import OpenAiClinicalExtractor
from app.reference_store import ReferenceStore
from app.schemas import ClinicalEncounterExtraction, ExtractionRequest
from app.tools import CodingTools


app = FastAPI(title="Clinical Note Extractor")


@app.get("/health")
def health() -> dict[str, str]:
    """Let Docker confirm that the service is running."""
    return {"status": "up"}


@app.post("/extract", response_model=ClinicalEncounterExtraction)
async def extract(request: ExtractionRequest):
    try:
        return await run_in_threadpool(get_service().extract, request.note, lambda _: None)
    except Exception as error:
        raise HTTPException(status_code=500, detail=str(error)) from error


@app.post("/extract/stream")
def extract_stream(request: ExtractionRequest) -> StreamingResponse:
    def events():
        queue: Queue[dict | None] = Queue()

        def run() -> None:
            try:
                result = get_service().extract(
                    request.note,
                    lambda text: queue.put({"type": "status", "text": text}),
                )
                queue.put({"type": "result", "data": result.model_dump(mode="json")})
            except Exception as error:
                queue.put({"type": "error", "message": str(error)})
            finally:
                queue.put(None)

        threading.Thread(target=run, daemon=True).start()
        while True:
            event = queue.get()
            if event is None:
                return
            yield json.dumps(event, ensure_ascii=False) + "\n"

    return StreamingResponse(events(), media_type="application/x-ndjson")


@lru_cache
def get_service() -> ClinicalExtractionService:
    store = ReferenceStore(settings)
    audit = AuditRepository(settings)
    audit.ensure_tables()
    return ClinicalExtractionService(
        settings=settings,
        store=store,
        model=OpenAiClinicalExtractor(settings, CodingTools(store)),
        audit=audit,
    )
