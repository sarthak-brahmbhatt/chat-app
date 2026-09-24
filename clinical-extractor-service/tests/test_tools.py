import json

from app.tools import CodingTools


class FakeStore:
    def search_codes(self, query, code_type):
        assert query
        return [{
            "code": "I21.4" if code_type == "ICD-10" else "44970",
            "description": "Test description",
            "score": 0.9,
            "source_document": "coding-reference.docx",
            "source_section": "Test section",
            "version": "FY2024",
        }]


def test_icd_tool_returns_candidates_and_source_metadata():
    trace = CodingTools(FakeStore()).execute(
        "lookup_icd10", json.dumps({"clinical_term": "NSTEMI", "context": "acute"})
    )

    assert trace.result["codes"][0]["code"] == "I21.4"
    assert trace.result["codes"][0]["confidence"] == "High"
    assert trace.result["codes"][0]["source_document"] == "coding-reference.docx"


def test_cpt_tool_uses_the_documented_arguments():
    trace = CodingTools(FakeStore()).execute(
        "lookup_cpt",
        json.dumps({
            "procedure_description": "appendectomy",
            "approach": "laparoscopic",
            "additional_details": "converted to open",
        }),
    )

    assert trace.result["codes"][0]["code"] == "44970"
