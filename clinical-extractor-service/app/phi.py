import re
from dataclasses import dataclass


@dataclass(frozen=True)
class DetectedPhi:
    type: str
    original_text: str
    placeholder: str


@dataclass(frozen=True)
class RedactionResult:
    redacted_text: str
    detected: list[DetectedPhi]

    def restore(self, value):
        if isinstance(value, str):
            restored = value
            for item in self.detected:
                restored = restored.replace(item.placeholder, item.original_text)
            return restored
        if isinstance(value, list):
            return [self.restore(item) for item in value]
        if isinstance(value, dict):
            return {key: self.restore(item) for key, item in value.items()}
        return value


class PhiRedactor:
    """Local deterministic PHI redaction for the supplied clinical note format."""

    PATTERNS = [
        ("SSN", re.compile(r"\b\d{3}-\d{2}-\d{4}\b")),
        ("Phone", re.compile(r"\b(?:\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]\d{3}[-.\s]\d{4}\b")),
        ("MRN", re.compile(r"(?i)(?<=\bMRN:\s)\d+")),
        ("DOB", re.compile(r"(?i)(?<=\bDOB:\s)(?:\d{1,2}/\d{1,2}/\d{2,4}|\d{4}-\d{2}-\d{2})")),
        ("Date", re.compile(r"\b(?:\d{1,2}/\d{1,2}/\d{2,4}|\d{4}-\d{2}-\d{2})\b")),
        ("Address", re.compile(r"\b\d{1,6}\s+[A-Za-z0-9.' -]+\s(?:Street|St|Road|Rd|Avenue|Ave|Boulevard|Blvd|Lane|Ln|Drive|Dr)\b", re.I)),
        ("Provider_Name", re.compile(r"(?i)(?<=\bAuthor:\s)[^|\n]+")),
        ("Provider_Name", re.compile(r"\bDr\.\s+[A-Z][A-Za-z'’-]+(?:\s+[A-Z][A-Za-z'’-]+)?")),
        ("Name", re.compile(r"(?i)(?<=\bPatient:\s)[^|\n]+")),
    ]

    def redact(self, note: str) -> RedactionResult:
        redacted = note
        detected: list[DetectedPhi] = []
        known: dict[tuple[str, str], str] = {}

        for phi_type, pattern in self.PATTERNS:
            def replace(match: re.Match) -> str:
                original = match.group(0).strip()
                key = (phi_type, original)
                placeholder = known.get(key)
                if not placeholder:
                    number = sum(item.type == phi_type for item in detected) + 1
                    placeholder = f"[{phi_type.upper()}_{number}]"
                    known[key] = placeholder
                    detected.append(DetectedPhi(phi_type, original, placeholder))
                return match.group(0).replace(original, placeholder)

            redacted = pattern.sub(replace, redacted)

        self._assert_common_phi_removed(redacted)
        return RedactionResult(redacted, detected)

    @staticmethod
    def _assert_common_phi_removed(text: str) -> None:
        unsafe = [
            r"(?i)\bMRN:\s*\d+",
            r"(?i)\bDOB:\s*(?:\d{1,2}/\d{1,2}/\d{2,4}|\d{4}-\d{2}-\d{2})",
            r"\b\d{3}-\d{2}-\d{4}\b",
            r"\b\d{4}-\d{2}-\d{2}\b",
        ]
        if any(re.search(pattern, text) for pattern in unsafe):
            raise ValueError("PHI redaction safety check failed")
