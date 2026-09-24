# Clinical Note Extractor

One-shot clinical extraction service used by the Clinical Note Extractor chat
contact. It accepts one note, removes PHI locally, retrieves relevant quality
measures, lets GPT-4o call local ICD-10/CPT lookup tools, and returns a validated
`ClinicalEncounterExtraction` JSON object.

## Start locally

Set `OPENAI_API_KEY` in the repository `.env`, then run:

```bash
docker compose up -d --build qdrant clinical-extractor-service
docker compose run --rm clinical-extractor-service python -m app.ingestion
```

The ingestion command reads the DOCX files in `resources/source`, writes the
fixed prompt sections to `resources/processed/instructions.json`, and updates
Qdrant. It is idempotent: unchanged chunks are not embedded again.

Start or rebuild the rest of the chat application with:

```bash
docker compose up -d --build user-service chat-service frontend
```

Open `http://localhost:4200` and select **Clinical Note Extractor**. Each pasted
note is independent; no OpenAI previous response ID is used.

## HTTP API

- `GET /health` checks the service process.
- `POST /extract` accepts `{ "note": "..." }` and returns extraction JSON.
- `POST /extract/stream` accepts the same body and returns newline-delimited
  status events followed by one `result` event.

The service sends only the redacted note to OpenAI. The original note and the
restored final response stay local and are recorded in MySQL for audit. Status
events describe pipeline steps and tool calls; they do not expose hidden model
reasoning.

## Phase 1 boundaries

- One note is processed per request. Batch retries and a dead-letter queue are
  deferred.
- ICD-10 and CPT lookup are included. A drug database tool is deferred because
  no drug reference or tool contract was supplied.
- The included regex redactor covers the formats in the supplied sample notes.
  A production zero-leakage claim requires a broader local PHI detector and a
  labelled privacy test set.
- Accuracy targets require expert-labelled expected outputs. The supplied
  sample notes alone cannot prove agreement percentages.

## Tests

```bash
docker compose run --rm --no-deps clinical-extractor-service pytest -q
```
