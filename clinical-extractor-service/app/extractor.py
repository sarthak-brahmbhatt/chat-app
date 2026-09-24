import time
import uuid
from datetime import datetime, timezone
from typing import Callable

from app.audit import AuditRecord, AuditRepository
from app.config import Settings
from app.openai_extractor import OpenAiClinicalExtractor
from app.phi import PhiRedactor
from app.prompt import build_instructions
from app.reference_store import ReferenceStore
from app.schemas import ClinicalEncounterExtraction, Confidence, HumanReviewFlag, PhiDetection


class ClinicalExtractionService:
    def __init__(
        self,
        settings: Settings,
        store: ReferenceStore,
        model: OpenAiClinicalExtractor,
        audit: AuditRepository,
        redactor: PhiRedactor | None = None,
    ):
        self.settings = settings
        self.store = store
        self.model = model
        self.audit = audit
        self.redactor = redactor or PhiRedactor()

    def extract(self, note: str, on_status: Callable[[str], None]) -> ClinicalEncounterExtraction:
        started = time.monotonic()
        extraction_id = str(uuid.uuid4())
        redacted_note = ""
        instructions = ""
        measures: list[dict] = []
        traces: list[dict] = []
        input_tokens = 0
        output_tokens = 0

        try:
            on_status("Redacting PHI")
            redaction = self.redactor.redact(note)
            redacted_note = redaction.redacted_text

            on_status("Finding applicable quality measures")
            measures = self.store.search_quality_measures(redacted_note)
            instructions = build_instructions(self.settings.resources_dir, measures)

            on_status("Extracting clinical data")
            model_result = self.model.extract(redacted_note, instructions, on_status)
            traces = [trace.as_dict() for trace in model_result.tool_traces]
            input_tokens = model_result.input_tokens
            output_tokens = model_result.output_tokens

            on_status("Validating structured result")
            result = self._finalize(
                model_result.extraction, redaction, extraction_id,
                input_tokens + output_tokens, int((time.monotonic() - started) * 1000),
            )
            response = result.model_dump(mode="json")
            self.audit.save(AuditRecord(
                extraction_id=extraction_id,
                status="COMPLETED",
                original_note=note,
                redacted_note=redacted_note,
                model_instructions=instructions,
                model=self.settings.openai_model,
                response=response,
                tool_calls=traces,
                retrieved_context=measures,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                duration_ms=result.extraction_metadata.extraction_duration_ms,
            ))
            return result
        except Exception as error:
            duration_ms = int((time.monotonic() - started) * 1000)
            self.audit.save(AuditRecord(
                extraction_id=extraction_id,
                status="FAILED",
                original_note=note,
                redacted_note=redacted_note,
                model_instructions=instructions,
                model=self.settings.openai_model,
                response=None,
                tool_calls=traces,
                retrieved_context=measures,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                duration_ms=duration_ms,
                error_message=str(error),
            ))
            raise

    def _finalize(self, result, redaction, extraction_id, total_tokens, duration_ms):
        restored = redaction.restore(result.model_dump(mode="json"))
        restored["encounter_id"] = extraction_id
        metadata = restored["extraction_metadata"]
        metadata["extraction_timestamp"] = datetime.now(timezone.utc).isoformat()
        metadata["model_used"] = self.settings.openai_model
        metadata["total_tokens_used"] = total_tokens
        metadata["extraction_duration_ms"] = duration_ms
        metadata["phi_detected"] = [
            PhiDetection(type=item.type, original_text=item.original_text, redacted=True).model_dump()
            for item in redaction.detected
        ]

        flags = [HumanReviewFlag.model_validate(item) for item in metadata["flags_for_human_review"]]
        flags.extend(self._low_confidence_flags(restored))
        unique = {(flag.field_path, flag.reason): flag for flag in flags}
        metadata["flags_for_human_review"] = [flag.model_dump() for flag in unique.values()]
        if metadata["flags_for_human_review"]:
            metadata["overall_confidence"] = Confidence.LOW.value

        return ClinicalEncounterExtraction.model_validate(restored)

    @staticmethod
    def _low_confidence_flags(result: dict) -> list[HumanReviewFlag]:
        flags: list[HumanReviewFlag] = []
        groups = ["diagnoses", "procedures", "quality_measures"]
        for group in groups:
            for index, item in enumerate(result.get(group, [])):
                if item.get("confidence") == "Low":
                    flags.append(HumanReviewFlag(
                        reason="Low confidence extraction",
                        field_path=f"{group}[{index}]",
                        details="The source note is ambiguous or incomplete.",
                    ))
        return flags
