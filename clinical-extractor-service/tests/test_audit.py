from app.audit import AuditRepository


def test_field_audit_flattens_all_fields_and_keeps_sources():
    response = {
        "diagnoses": [{
            "description": "pneumonia",
            "icd10_code": "J18.9",
            "clinical_evidence": "Assessment: pneumonia",
            "confidence": "High",
        }],
        "procedures": [],
        "quality_measures": [{
            "measure_id": "PN-6",
            "compliance_status": "Compliant",
            "confidence": "High",
        }],
    }
    context = [{
        "measure_id": "PN-6",
        "source_document": "quality-measures-spec.docx",
        "source_section": "Measure 4: PN-6",
    }]
    tool_calls = [{
        "result": {"codes": [{
            "code": "J18.9",
            "source_document": "coding-reference.docx",
            "source_section": "Respiratory",
        }]},
    }]

    repository = object.__new__(AuditRepository)
    rows = repository._field_rows("extraction-1", response, context, tool_calls)
    by_path = {row[1]: row for row in rows}

    assert "diagnoses[0].description" in by_path
    assert "diagnoses[0].clinical_evidence" in by_path
    assert "procedures" in by_path
    assert by_path["diagnoses[0].icd10_code"][5:] == (
        "coding-reference.docx", "Respiratory",
    )
    assert by_path["quality_measures[0].compliance_status"][5:] == (
        "quality-measures-spec.docx", "Measure 4: PN-6",
    )
