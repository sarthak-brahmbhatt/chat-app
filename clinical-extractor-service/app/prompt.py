import json
from pathlib import Path


BASE_INSTRUCTIONS = """
You are a clinical note extraction assistant for a hospital quality department.
Extract structured facts from one physician, nursing, operative, progress, ED,
consultation, or discharge note.

Rules:
- Extract only facts supported by the supplied note. Never invent missing facts.
- Preserve ambiguity and contradictions. Do not silently resolve them.
- Extract every diagnosis, procedure, medication, complication, discharge fact,
  follow-up plan, and applicable quality measure supported by the note.
- Call lookup_icd10 for each diagnosis and lookup_cpt for each procedure before
  assigning a code. Use only codes returned by those tools.
- If no returned code is adequately supported, leave the code null and flag it
  for human review.
- Evidence must be a short passage from the note, not hidden reasoning.
- Mark low-confidence or conflicting fields for human review.
- PHI placeholders such as [MRN_1] must be copied exactly. The local application
  restores permitted values after the API response.
- Set encounter_id, extraction timestamps, duration, token counts, PHI findings,
  and model name to harmless placeholder values. The application replaces them.
- Return only the ClinicalEncounterExtraction structured result.
""".strip()


def load_instructions(resources_dir: Path) -> dict[str, str]:
    path = resources_dir / "processed" / "instructions.json"
    if not path.exists():
        raise RuntimeError(
            "Reference documents are not ingested. Run: "
            "docker compose run --rm clinical-extractor-service python -m app.ingestion"
        )
    return json.loads(path.read_text(encoding="utf-8"))


def build_instructions(resources_dir: Path, measures: list[dict]) -> str:
    fixed = load_instructions(resources_dir)
    measure_context = "\n\n".join(item["text"] for item in measures)
    return "\n\n".join([
        BASE_INSTRUCTIONS,
        fixed["general_extraction_guidelines"],
        fixed["coding_guidelines"],
        fixed["tool_call_specification"],
        "RETRIEVED QUALITY MEASURES\n" + measure_context,
    ])
