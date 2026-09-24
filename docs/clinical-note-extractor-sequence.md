# Clinical Note Extractor — Sequence Design

The extractor is a separate Python service inside this repository. It uses the
existing chat UI and WebSocket connection, while keeping PHI processing inside
the local system.

## One-time document ingestion

```mermaid
sequenceDiagram
    actor Developer
    participant Ingest as Python ingestion command
    participant Docs as Reference DOCX files
    participant Qdrant

    Developer->>Ingest: Run ingestion command
    Ingest->>Docs: Read headings, sections, and tables
    Ingest->>Ingest: Extract fixed instruction sections
    Ingest->>Ingest: Build ICD-10/CPT row chunks
    Ingest->>Ingest: Build one chunk per quality measure
    Ingest->>Qdrant: Upsert chunks, metadata, version, and checksum
    Qdrant-->>Ingest: Upsert result
    Ingest-->>Developer: Added, updated, skipped, and failed counts
```

The command uses stable point IDs and checksums. Running it again skips
unchanged content and updates changed content.

## One clinical note extraction

```mermaid
sequenceDiagram
    actor User
    participant UI as Angular chat UI
    participant Chat as Java chat-service
    participant Extractor as Python extractor
    participant PHI as Local PHI redactor
    participant Qdrant
    participant OpenAI as OpenAI GPT-4o
    participant Audit as MySQL audit tables

    User->>UI: Paste a clinical note
    UI->>Chat: WebSocket message to extractor bot
    Chat-->>UI: Single tick and stream-start
    Chat->>Extractor: Start one-shot streaming extraction
    Extractor-->>Chat: Status: Redacting PHI
    Chat-->>UI: bot_status
    Extractor->>PHI: Detect and replace PHI locally
    PHI-->>Extractor: Redacted note and local replacement map
    Extractor->>Qdrant: Retrieve relevant quality measures
    Qdrant-->>Extractor: Measures with source metadata
    Extractor->>OpenAI: Redacted note, instructions, measures, tools, schema

    loop Tool calls requested by the model
        OpenAI-->>Extractor: lookup_icd10 or lookup_cpt
        Extractor-->>Chat: Safe lookup status
        Chat-->>UI: bot_status
        Extractor->>Qdrant: Search matching code rows
        Qdrant-->>Extractor: Candidates with source metadata
        Extractor->>OpenAI: Tool result
    end

    OpenAI-->>Extractor: Structured extraction JSON
    Extractor->>Extractor: Validate with Pydantic
    Extractor->>PHI: Restore permitted response fields locally
    Extractor->>Audit: Store request, evidence, tools, sources, confidence, result
    Extractor-->>Chat: Final validated JSON
    Chat-->>UI: Streamed text and authoritative incoming_message
    UI-->>User: Display extraction result
```

`bot_status` messages describe visible work, such as code lookup. They do not
expose the model's private reasoning. The audit trace stores concise field-level
rationale, supporting note passages, tool calls, and source references.
