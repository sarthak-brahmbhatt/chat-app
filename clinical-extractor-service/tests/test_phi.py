from app.phi import PhiRedactor


def test_redacts_sample_header_before_external_processing():
    note = (
        "Patient: Garcia, Rosa | MRN: 6691234 | DOB: 07/08/1972 "
        "Author: Dr. Amanda Foster, Internal Medicine | Date: 01/18/2024\n"
        "Dr. Park reviewed the patient. Call 212-555-0198."
    )

    result = PhiRedactor().redact(note)

    assert "Garcia, Rosa" not in result.redacted_text
    assert "6691234" not in result.redacted_text
    assert "07/08/1972" not in result.redacted_text
    assert "Amanda Foster" not in result.redacted_text
    assert "Dr. Park" not in result.redacted_text
    assert "212-555-0198" not in result.redacted_text


def test_restores_placeholders_only_after_model_response():
    result = PhiRedactor().redact("Patient: Patel, S. | MRN: 2213456 | DOB: 08/20/2001")
    restored = result.restore({"mrn": "[MRN_1]", "patient": "[NAME_1]"})

    assert restored == {"mrn": "2213456", "patient": "Patel, S."}


def test_redacts_iso_dob_and_service_dates():
    note = (
        "Patient: Test Patient\nDOB: 1959-03-14\n"
        "Admission Date: 2026-09-20\nProcedure performed on 2026-09-21."
    )

    result = PhiRedactor().redact(note)

    assert "1959-03-14" not in result.redacted_text
    assert "2026-09-20" not in result.redacted_text
    assert "2026-09-21" not in result.redacted_text
