from enum import Enum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class SchemaModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Confidence(str, Enum):
    HIGH = "High"
    MEDIUM = "Medium"
    LOW = "Low"


class PatientDemographics(SchemaModel):
    mrn: str = Field(description="Medical record number or its redacted placeholder")
    age: int = Field(description="Patient age in years")
    sex: Literal["Male", "Female", "Other", "Unknown"]
    date_of_birth: str | None = Field(description="Date of birth in YYYY-MM-DD format when documented")


class EncounterDetails(SchemaModel):
    note_type: Literal[
        "Admission_HP", "Progress_Note", "Discharge_Summary", "ED_Note",
        "Operative_Note", "Consultation", "Nursing_Assessment", "Procedure_Note",
    ]
    note_date: str = Field(description="Note date in YYYY-MM-DD format")
    author: str = Field(description="Author name or its redacted placeholder")
    author_role: str | None = Field(description="Clinical role of the author")
    encounter_type: Literal["Inpatient", "ED", "Observation", "Outpatient", "Procedure"]
    admission_date: str | None = Field(description="Admission date when documented")
    discharge_date: str | None = Field(description="Discharge date when documented")
    length_of_stay_days: int | None = Field(description="Length of stay in whole days")
    discharge_disposition: Literal[
        "Home", "Home_with_services", "SNF", "Rehab", "LTACH", "Hospice",
        "AMA", "Expired", "Transfer", "Other",
    ] | None


class Diagnosis(SchemaModel):
    description: str = Field(description="Diagnosis exactly as supported by the note")
    icd10_code: str | None = Field(description="ICD-10-CM code returned by lookup_icd10")
    icd10_description: str | None = Field(description="Standard description for the selected ICD-10-CM code")
    type: Literal["Primary", "Secondary", "Admitting", "Complication", "Comorbidity"]
    present_on_admission: Literal["Yes", "No", "Unknown", "Clinically_undetermined"] | None
    clinical_evidence: str = Field(description="Short passage from the note supporting this diagnosis")
    confidence: Confidence


class Procedure(SchemaModel):
    description: str = Field(description="Procedure exactly as supported by the note")
    cpt_code: str | None = Field(description="CPT code returned by lookup_cpt")
    cpt_description: str | None = Field(description="Standard description for the selected CPT code")
    date_performed: str | None = Field(description="Procedure date when documented")
    provider: str | None = Field(description="Provider or redacted provider placeholder")
    laterality: Literal["Left", "Right", "Bilateral", "N/A"] | None
    clinical_evidence: str = Field(description="Short passage from the note supporting this procedure")
    confidence: Confidence


class Medication(SchemaModel):
    name: str
    dose: str | None
    route: str | None
    frequency: str | None
    indication: str | None
    status: Literal["Active", "Held", "Discontinued", "New", "Modified"] | None


class MedicationReconciliation(SchemaModel):
    admission_medications: list[Medication]
    discharge_medications: list[Medication]
    new_medications: list[Medication]
    discontinued_medications: list[Medication]
    reconciliation_completed: bool
    reconciliation_documented_at: Literal["Admission", "Transfer", "Discharge", "Not_documented"]


class MeasureElement(SchemaModel):
    element_name: str
    status: Literal["Met", "Not_met", "Not_documented", "Not_applicable"]
    value: str | None
    evidence: str | None = Field(description="Short passage from the note supporting the status")
    confidence: Confidence


class QualityMeasureAssessment(SchemaModel):
    measure_id: str
    measure_name: str
    applicable: bool
    compliance_status: Literal["Compliant", "Non_compliant", "Excluded", "Unable_to_determine"]
    elements: list[MeasureElement]
    non_compliance_reason: str | None
    confidence: Confidence


class HumanReviewFlag(SchemaModel):
    reason: str
    field_path: str
    details: str


class PhiDetection(SchemaModel):
    type: Literal["Name", "DOB", "MRN", "Address", "Phone", "SSN", "Date", "Provider_Name"]
    original_text: str
    redacted: bool


class ExtractionMetadata(SchemaModel):
    extraction_timestamp: str
    model_used: str
    total_tokens_used: int
    extraction_duration_ms: int
    overall_confidence: Confidence
    flags_for_human_review: list[HumanReviewFlag]
    phi_detected: list[PhiDetection]


class ClinicalEncounterExtraction(SchemaModel):
    encounter_id: str
    patient_demographics: PatientDemographics
    encounter_details: EncounterDetails
    diagnoses: list[Diagnosis]
    procedures: list[Procedure]
    medications: MedicationReconciliation
    quality_measures: list[QualityMeasureAssessment]
    extraction_metadata: ExtractionMetadata


# Supporting schemas from extraction-schemas.docx. They are kept as Pydantic
# models even though Phase 1 returns ClinicalEncounterExtraction.
class SepsisTimeZero(SchemaModel):
    timestamp: str | None
    trigger_criteria: Literal["SIRS", "qSOFA", "Clinical_judgment", "Not_documented"]
    trigger_details: str | None
    confidence: Confidence


class LactateMeasurement(SchemaModel):
    completed: bool
    time_from_zero_minutes: int | None
    value: float | None
    units: str | None
    within_3_hours: bool | None


class BloodCultures(SchemaModel):
    completed: bool
    time_from_zero_minutes: int | None
    before_antibiotics: bool | None
    number_of_sets: int | None


class AntibioticAdministration(SchemaModel):
    completed: bool
    time_from_zero_minutes: int | None
    within_3_hours: bool | None
    antibiotic_names: list[str]
    appropriate_spectrum: bool | None


class SepsisThreeHourBundle(SchemaModel):
    lactate_measured: LactateMeasurement
    blood_cultures_drawn: BloodCultures
    antibiotics_administered: AntibioticAdministration
    bundle_complete: bool


class FluidResuscitation(SchemaModel):
    completed: bool
    volume_ml_per_kg: float | None
    meets_30ml_kg: bool | None
    completion_time_minutes: int | None


class VasopressorAdministration(SchemaModel):
    required: bool
    initiated: bool | None
    agent: str | None


class RepeatLactate(SchemaModel):
    required: bool
    completed: bool | None
    value: float | None
    time_from_initial_hours: float | None


class VolumeReassessment(SchemaModel):
    completed: bool
    method: str | None


class SepsisSixHourBundle(SchemaModel):
    applicable: bool
    fluid_resuscitation: FluidResuscitation
    vasopressors: VasopressorAdministration
    repeat_lactate: RepeatLactate
    volume_reassessment: VolumeReassessment
    bundle_complete: bool


class SepsisBundleExtraction(SchemaModel):
    time_zero: SepsisTimeZero
    three_hour_bundle: SepsisThreeHourBundle
    six_hour_bundle: SepsisSixHourBundle
    overall_compliance: Literal[
        "Fully_compliant", "Partially_compliant", "Non_compliant", "Unable_to_determine"
    ]
    non_compliance_details: str | None


class MedicationStatusChange(SchemaModel):
    action: Literal["New", "Continued", "Modified", "Held", "Discontinued", "Restarted"]
    reason: str | None


class DetailedMedication(SchemaModel):
    drug_name: str
    brand_name: str | None
    drug_class: str | None
    dose: str | None
    dose_unit: str | None
    route: Literal["PO", "IV", "IM", "SubQ", "INH", "SL", "PR", "TOP", "IO", "IT", "Other"] | None
    frequency: str | None
    prn: bool
    prn_reason: str | None
    indication: str | None
    duration: str | None
    context: Literal[
        "Home_medication", "Admission_order", "Inpatient_order",
        "Discharge_medication", "Discontinued", "Held",
    ]
    status_change: MedicationStatusChange | None
    high_alert: bool
    confidence: Confidence


class MedicationExtraction(SchemaModel):
    medications: list[DetailedMedication]


class SpecificityHints(SchemaModel):
    laterality: Literal["Left", "Right", "Bilateral", "Unspecified"] | None
    episode: Literal["Initial", "Subsequent", "Sequela"] | None
    severity: Literal["Mild", "Moderate", "Severe", "Unspecified"] | None
    anatomic_site: str | None


class DiagnosisCodingRequest(SchemaModel):
    clinical_description: str
    context: str | None
    specificity_hints: SpecificityHints | None


class CodingCandidate(SchemaModel):
    code: str
    description: str
    confidence: Confidence


class AlternativeCode(SchemaModel):
    code: str
    description: str
    reason_for_alternative: str


class DiagnosisCodingResponse(SchemaModel):
    primary_code: CodingCandidate | None
    alternative_codes: list[AlternativeCode]
    coding_notes: str | None


class ExtractionRequest(SchemaModel):
    note: str = Field(min_length=1, description="One free-text clinical note")
