import json
from dataclasses import dataclass
from typing import Callable

from openai import OpenAI

from app.config import Settings
from app.schemas import ClinicalEncounterExtraction
from app.tools import TOOLS, CodingTools, ToolTrace


StatusCallback = Callable[[str], None]


@dataclass(frozen=True)
class ModelResult:
    extraction: ClinicalEncounterExtraction
    tool_traces: list[ToolTrace]
    input_tokens: int
    output_tokens: int


class OpenAiClinicalExtractor:
    MAX_ROUNDS = 8

    def __init__(
        self,
        settings: Settings,
        tools: CodingTools,
        client: OpenAI | None = None,
    ):
        self.settings = settings
        self.tools = tools
        self.client = client or OpenAI(api_key=settings.openai_api_key)

    def extract(
        self,
        redacted_note: str,
        instructions: str,
        on_status: StatusCallback,
    ) -> ModelResult:
        if not self.settings.openai_api_key:
            raise RuntimeError("OPENAI_API_KEY is not configured")

        input_items: list = [{"role": "user", "content": redacted_note}]
        traces: list[ToolTrace] = []
        input_tokens = 0
        output_tokens = 0

        for _ in range(self.MAX_ROUNDS):
            response = self.client.responses.parse(
                model=self.settings.openai_model,
                instructions=instructions,
                input=input_items,
                tools=TOOLS,
                text_format=ClinicalEncounterExtraction,
                parallel_tool_calls=True,
                store=False,
            )
            if response.usage:
                input_tokens += response.usage.input_tokens
                output_tokens += response.usage.output_tokens

            calls = [item for item in response.output if item.type == "function_call"]
            if not calls:
                if response.output_parsed is None:
                    raise RuntimeError("OpenAI returned neither tool calls nor a structured extraction")
                return ModelResult(response.output_parsed, traces, input_tokens, output_tokens)

            input_items.extend(response.output)
            for call in calls:
                status = "Searching ICD-10 codes" if call.name == "lookup_icd10" else "Searching CPT codes"
                on_status(status)
                trace = self.tools.execute(call.name, call.arguments)
                traces.append(trace)
                input_items.append({
                    "type": "function_call_output",
                    "call_id": call.call_id,
                    "output": json.dumps(trace.result),
                })

        raise RuntimeError(f"Tool calling exceeded {self.MAX_ROUNDS} rounds")
