import json
from dataclasses import asdict, dataclass

from app.reference_store import ReferenceStore


TOOLS = [
    {
        "type": "function",
        "name": "lookup_icd10",
        "description": "Find ICD-10-CM candidates from the provided FY2024 coding reference.",
        "parameters": {
            "type": "object",
            "properties": {
                "clinical_term": {"type": "string", "description": "Diagnosis description from the note"},
                "context": {"type": "string", "description": "Acute/chronic status, cause, site, severity, and laterality"},
            },
            "required": ["clinical_term", "context"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "lookup_cpt",
        "description": "Find CPT candidates from the provided coding reference.",
        "parameters": {
            "type": "object",
            "properties": {
                "procedure_description": {"type": "string", "description": "Procedure exactly as documented"},
                "approach": {"type": "string", "description": "Open, laparoscopic, percutaneous, endoscopic, or empty"},
                "additional_details": {"type": "string", "description": "Laterality, complexity, or other details"},
            },
            "required": ["procedure_description", "approach", "additional_details"],
            "additionalProperties": False,
        },
        "strict": True,
    },
]


@dataclass(frozen=True)
class ToolTrace:
    name: str
    arguments: dict
    result: dict

    def as_dict(self) -> dict:
        return asdict(self)


class CodingTools:
    def __init__(self, store: ReferenceStore):
        self.store = store

    def execute(self, name: str, raw_arguments: str) -> ToolTrace:
        arguments = json.loads(raw_arguments)
        if name == "lookup_icd10":
            query = f"{arguments['clinical_term']} {arguments['context']}"
            matches = self.store.search_codes(query, "ICD-10")
            result = {
                "codes": [self._candidate(item) for item in matches],
                "coding_guidance": "Choose only the most specific code supported by the note.",
            }
        elif name == "lookup_cpt":
            query = " ".join([
                arguments["procedure_description"], arguments["approach"], arguments["additional_details"],
            ])
            matches = self.store.search_codes(query, "CPT")
            result = {
                "codes": [self._candidate(item) for item in matches],
                "modifier_suggestions": [],
            }
        else:
            raise ValueError(f"Unsupported tool: {name}")
        return ToolTrace(name, arguments, result)

    @staticmethod
    def _candidate(item: dict) -> dict:
        score = float(item.get("score", 0))
        confidence = "High" if score >= 0.82 else "Medium" if score >= 0.70 else "Low"
        return {
            "code": item["code"],
            "description": item["description"],
            "confidence": confidence,
            "score": round(score, 4),
            "source_document": item["source_document"],
            "source_section": item["source_section"],
            "version": item["version"],
        }
