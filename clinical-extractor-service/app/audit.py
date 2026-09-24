import json
from dataclasses import dataclass
from datetime import datetime, timezone

import pymysql

from app.config import Settings
from app.schemas import ClinicalEncounterExtraction


@dataclass(frozen=True)
class AuditRecord:
    extraction_id: str
    status: str
    original_note: str
    redacted_note: str
    model_instructions: str
    model: str
    response: dict | None
    tool_calls: list[dict]
    retrieved_context: list[dict]
    input_tokens: int
    output_tokens: int
    duration_ms: int
    error_message: str | None = None


class AuditRepository:
    def __init__(self, settings: Settings):
        self.settings = settings

    def ensure_tables(self) -> None:
        statements = [
            """
            CREATE TABLE IF NOT EXISTS clinical_extractions (
                extraction_id VARCHAR(36) PRIMARY KEY,
                encounter_id VARCHAR(36) NULL,
                status VARCHAR(20) NOT NULL,
                original_note LONGTEXT NOT NULL,
                redacted_note LONGTEXT NOT NULL,
                model_instructions LONGTEXT NOT NULL,
                model_name VARCHAR(100) NOT NULL,
                response_json JSON NULL,
                tool_calls_json JSON NOT NULL,
                retrieved_context_json JSON NOT NULL,
                input_tokens INT NOT NULL DEFAULT 0,
                output_tokens INT NOT NULL DEFAULT 0,
                duration_ms INT NOT NULL DEFAULT 0,
                error_message TEXT NULL,
                created_at DATETIME(6) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS clinical_field_audit (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                extraction_id VARCHAR(36) NOT NULL,
                field_path VARCHAR(500) NOT NULL,
                value_json JSON NOT NULL,
                evidence TEXT NULL,
                confidence VARCHAR(20) NULL,
                source_document VARCHAR(255) NULL,
                source_section VARCHAR(500) NULL,
                CONSTRAINT fk_clinical_field_extraction
                    FOREIGN KEY (extraction_id) REFERENCES clinical_extractions(extraction_id)
                    ON DELETE CASCADE
            )
            """,
        ]
        with self._connection() as connection:
            with connection.cursor() as cursor:
                for statement in statements:
                    cursor.execute(statement)
            connection.commit()

    def save(self, record: AuditRecord) -> None:
        response = record.response or {}
        with self._connection() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO clinical_extractions (
                        extraction_id, encounter_id, status, original_note, redacted_note,
                        model_instructions, model_name, response_json, tool_calls_json,
                        retrieved_context_json, input_tokens, output_tokens, duration_ms,
                        error_message, created_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        record.extraction_id, response.get("encounter_id"), record.status,
                        record.original_note, record.redacted_note, record.model_instructions,
                        record.model, self._json_or_none(record.response), json.dumps(record.tool_calls),
                        json.dumps(record.retrieved_context), record.input_tokens, record.output_tokens,
                        record.duration_ms, record.error_message,
                        datetime.now(timezone.utc).replace(tzinfo=None),
                    ),
                )
                if record.response:
                    cursor.executemany(
                        """
                        INSERT INTO clinical_field_audit (
                            extraction_id, field_path, value_json, evidence, confidence,
                            source_document, source_section
                        ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                        """,
                        self._field_rows(
                            record.extraction_id,
                            record.response,
                            record.retrieved_context,
                            record.tool_calls,
                        ),
                    )
            connection.commit()

    def _field_rows(
        self,
        extraction_id: str,
        response: dict,
        context: list[dict],
        tool_calls: list[dict],
    ) -> list[tuple]:
        rows: list[tuple] = []
        measure_sources = {item.get("measure_id"): item for item in context}
        code_sources = {}
        for trace in tool_calls:
            for candidate in trace.get("result", {}).get("codes", []):
                code_sources[candidate.get("code")] = candidate

        def walk(value, path, evidence=None, confidence=None, source=None):
            if isinstance(value, dict):
                evidence = value.get("clinical_evidence") or value.get("evidence") or evidence
                confidence = value.get("confidence") or confidence

                measure_id = value.get("measure_id")
                code = value.get("icd10_code") or value.get("cpt_code")
                source = measure_sources.get(measure_id) or code_sources.get(code) or source

                for key, item in value.items():
                    child_path = f"{path}.{key}" if path else key
                    walk(item, child_path, evidence, confidence, source)
                return

            if isinstance(value, list):
                if not value:
                    rows.append(self._row(extraction_id, path, value, evidence, confidence,
                                          self._source_value(source, "source_document"),
                                          self._source_value(source, "source_section")))
                for index, item in enumerate(value):
                    walk(item, f"{path}[{index}]", evidence, confidence, source)
                return

            rows.append(self._row(
                extraction_id, path, value, evidence, confidence,
                self._source_value(source, "source_document"),
                self._source_value(source, "source_section"),
            ))

        walk(response, "")
        return rows

    @staticmethod
    def _source_value(source: dict | None, key: str):
        return source.get(key) if source else None

    @staticmethod
    def _row(extraction_id, path, value, evidence, confidence, source_document=None, source_section=None):
        return (
            extraction_id, path, json.dumps(value), evidence, confidence,
            source_document, source_section,
        )

    @staticmethod
    def _json_or_none(value: dict | None) -> str | None:
        return json.dumps(value) if value is not None else None

    def _connection(self):
        return pymysql.connect(
            host=self.settings.mysql_host,
            port=self.settings.mysql_port,
            user=self.settings.mysql_user,
            password=self.settings.mysql_password,
            database=self.settings.mysql_database,
            charset="utf8mb4",
        )
