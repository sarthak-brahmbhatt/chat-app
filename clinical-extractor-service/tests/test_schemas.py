from app.schemas import ClinicalEncounterExtraction


def test_final_schema_forbids_unknown_fields():
    schema = ClinicalEncounterExtraction.model_json_schema()

    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {
        "encounter_id", "patient_demographics", "encounter_details", "diagnoses",
        "procedures", "medications", "quality_measures", "extraction_metadata",
    }


def test_final_schema_keeps_confidence_enum():
    schema = ClinicalEncounterExtraction.model_json_schema()

    assert schema["$defs"]["Confidence"]["enum"] == ["High", "Medium", "Low"]
