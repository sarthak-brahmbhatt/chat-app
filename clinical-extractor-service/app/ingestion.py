import json

from app.config import settings
from app.documents import coding_chunks, instruction_sections, quality_measure_chunks
from app.reference_store import ReferenceStore


def run() -> dict[str, int]:
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY is required to generate reference embeddings")

    source = settings.resources_dir / "source"
    processed = settings.resources_dir / "processed"
    processed.mkdir(parents=True, exist_ok=True)

    instructions = instruction_sections(settings.resources_dir)
    (processed / "instructions.json").write_text(
        json.dumps(instructions, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    chunks = [
        *coding_chunks(source / "coding-reference.docx"),
        *quality_measure_chunks(source / "quality-measures-spec.docx"),
    ]
    return ReferenceStore(settings).upsert_chunks(chunks)


if __name__ == "__main__":
    print(json.dumps(run(), indent=2))
