from pathlib import Path

from app.documents import coding_chunks, instruction_sections, quality_measure_chunks


RESOURCES = Path(__file__).parents[1] / "resources"


def test_coding_tables_become_one_chunk_per_row():
    chunks = coding_chunks(RESOURCES / "source" / "coding-reference.docx")

    assert len(chunks) > 100
    assert len({chunk.point_id for chunk in chunks}) == len(chunks)
    assert any(chunk.payload["code"] == "A41.9" for chunk in chunks)
    assert any(chunk.payload["code"] == "44970" for chunk in chunks)
    assert all(chunk.payload["source_document"] == "coding-reference.docx" for chunk in chunks)


def test_each_quality_measure_is_one_chunk():
    chunks = quality_measure_chunks(RESOURCES / "source" / "quality-measures-spec.docx")

    assert len(chunks) == 8
    assert {chunk.payload["measure_id"] for chunk in chunks} == {
        "SEP-1", "VTE-1", "STK-4", "PN-6", "HF-2", "MORT-30-AMI", "PC-01", "CAUTI",
    }
    assert "Blood cultures" in chunks[0].text


def test_fixed_instruction_sections_are_extracted_programmatically():
    instructions = instruction_sections(RESOURCES)

    assert "Specificity Rules" in instructions["coding_guidelines"]
    assert "lookup_icd10" in instructions["tool_call_specification"]
    assert "Confidence Scoring" in instructions["general_extraction_guidelines"]
