# chat-service

The real-time service: it **holds every live WebSocket**, routes messages,
owns the tick protocol, persists through Kafka, serves history, and hosts both
DoctorAssistant bots. **Stateful** — a live socket exists in exactly one
instance's memory — which is why it is a separate service from user-service,
and why it does not yet scale past one instance (see §Limits).

Port `8082` locally (`8080` inside). Design reasoning: [`CLAUDE.md`](../CLAUDE.md)
§3.1 (protocol), §3.4 (Kafka), §3.9–3.10 (bots), §4 (flows).

## Surface

| Kind | Path / type | Does |
|---|---|---|
| WebSocket | `/ws/chat` | One connection per logged-in user; first frame is the raw JWT |
| WS in | `message` | Send to `recipientId` |
| WS in | `delivered_ack` | Recipient's client confirming an `incoming_message` |
| WS out | `ack` (`single` / `double`) | Tick updates to the sender |
| WS out | `incoming_message` | A delivered message |
| WS out | `bot_stream_start` / `bot_stream_delta` / `bot_status` | The tool-calling bot's reply, streamed |
| REST | `GET /conversations/{otherUserId}/messages?before=` | History, 50 per page, cursor-paginated |
| REST | `GET /actuator/health` | Liveness |

## Architecture

```mermaid
flowchart TB
    B["Browser"] -->|wss| H["ChatWebSocketHandler"]
    B -->|HTTPS| CC["ConversationController\n/conversations"]
    H --> JV["JwtValidator"]
    CC --> JV
    H --> CR["ConnectionRegistry\nuserId → session (in-memory)"]
    H --> P["ChatMessagePublisher"]
    P -->|"key = ConversationKey(a,b)"| K[["Kafka · chat-messages\nChatMessageEvent · MessageDeliveredEvent"]]
    K --> CO["ChatMessageConsumer"]
    CO --> MR["ChatMessageRepository"]
    H --> MS["ChatMessageService\nhistory · reconnect sweep"]
    CC --> MS
    MS --> MR
    MR --> DB[("MySQL chatappdb\nmessages · users · clinic · bot_*")]
    H --> BD["bot/routing\nBotDirectory"]
    BD -->|BOT| V1["bot/promptstuffing\nDoctorAssistantBotService\nOpenAiBotBrain"]
    BD -->|BOT_TOOL| V2["bot/toolcalling\nToolCallingBotService\nOpenAiToolCallingBrain"]
    V1 --> CL["bot/clinic\nAvailabilityService · BookingService"]
    V2 --> CL
    V1 --> CV["bot/conversation\nstate · token usage · prompt log · round log"]
    V2 --> CV
    CL --> DB
    CV --> DB
    V1 --> OA[["OpenAI Responses API"]]
    V2 --> OA
```

| Package | Holds |
|---|---|
| `websocket` | `ChatWebSocketHandler` (auth, dispatch, ticks, bot branch), `ConnectionRegistry` |
| `kafka` | `ChatMessagePublisher`, `ChatMessageConsumer`, sealed `ChatTopicEvent` → `ChatMessageEvent` / `MessageDeliveredEvent`, `KafkaTopicConfig` |
| `service` | `ChatMessageService` — history (cursor), `markDelivered`, `sweepUndeliveredForRecipient` |
| `controller` | `ConversationController` — history REST, inline JWT check |
| `entity` / `repository` | `ChatMessage`, `AppUser` (read-only view of `users`, no password column), repositories |
| `support` | `ConversationKey` — `min(a,b):max(a,b)`, shared by Kafka keying and bot state |
| `bot/routing` | `BotDirectory` — is this recipient a bot, and which kind |
| `bot/clinic` | Doctors, weekly availability, appointments; `AvailabilityService` (free = pattern − bookings, one query), `BookingService` (re-validate, then insert under a unique constraint) |
| `bot/conversation` | `bot_conversation_state` (`last_response_id`), `bot_token_usage`, `bot_prompt_log` (per turn), `bot_round_log` (per API call) |
| `bot/promptstuffing` | V1: clinic snapshot in the prompt, structured-output `BotDecision`, `OpenAiBotBrain` |
| `bot/toolcalling` | V2: five `ClinicTool`s, `ClinicToolExecutor`, the tool loop in `OpenAiToolCallingBrain`, streaming via `BotStreamListener` |
| `security` / `config` | `JwtValidator`; WebSocket + CORS registration, `ClockConfig` (injectable clock for the bots) |

## Sequence diagrams

### Connecting — the token is the first frame

```mermaid
sequenceDiagram
    participant B as Browser
    participant H as ChatWebSocketHandler
    participant JV as JwtValidator
    participant CR as ConnectionRegistry
    participant MS as ChatMessageService
    B->>H: open wss://…/ws/chat
    Note over B,H: Browsers cannot set headers on the handshake
    B->>H: frame 1: "<raw JWT>"
    H->>JV: validate
    alt invalid
        H-->>B: close 1008 (policy violation)
    else valid
        H->>H: session.attributes.userId = sub
        H->>CR: register(userId, session)
        H->>MS: sweepUndeliveredForRecipient(userId)
        Note over MS: direct UPDATE per row — no Kafka, no ack (none was ever produced)
        MS-->>H: [{messageId, senderId}…]
        loop each original sender still connected
            H-->>B: ack double → that sender
        end
    end
```

### Sending to a human — ticks, Kafka, live delivery

```mermaid
sequenceDiagram
    participant S as Sender
    participant H as ChatWebSocketHandler
    participant P as ChatMessagePublisher
    participant K as Kafka
    participant CO as ChatMessageConsumer
    participant DB as MySQL
    participant R as Recipient
    S->>H: message {messageId, recipientId, content}
    H-->>S: ack single  (before anything else — never delayed by persistence)
    H->>P: publish ChatMessageEvent  (fire-and-forget)
    P-->>K: key = ConversationKey(sender, recipient)
    H->>R: incoming_message  (if a session exists — else nothing is queued)
    R->>H: delivered_ack {messageId, senderId}
    H->>P: publish MessageDeliveredEvent — same key, same partition
    H-->>S: ack double  (live, immediate)
    K->>CO: ChatMessageEvent
    CO->>DB: INSERT messages (delivered = false)
    K->>CO: MessageDeliveredEvent
    CO->>DB: UPDATE delivered = true
    Note over K,DB: Same partition ⇒ insert always lands before the update. Structural, not timing.
```

Between "delivered live" and "row inserted" there is a real, accepted window
where a history read would miss the message — history is only read on open,
so the paths rarely meet. If the Kafka publish itself fails, the message is
**lost**: the sender keeps the single tick, and there is no retry or outbox
(a named limit, not an oversight).

### History — cursor pagination

```mermaid
sequenceDiagram
    participant B as Browser
    participant CC as ConversationController
    participant MS as ChatMessageService
    participant DB as MySQL
    B->>CC: GET /conversations/42/messages  Bearer token
    CC->>CC: validate JWT inline (caller id from sub, never from the path)
    CC->>MS: getConversationHistory(me, 42, before = null)
    MS->>DB: SELECT … ORDER BY sent_at DESC LIMIT 50
    MS-->>B: 200 {messages oldest→newest, hasMore}
    B->>CC: …?before=<oldest sentAt>   (user scrolled to the top)
    CC->>MS: getConversationHistory(me, 42, before)
    MS->>DB: … AND sent_at < :before … LIMIT 50
    MS-->>B: 200 {older page, hasMore}
    Note over B,DB: A timestamp cursor, not an offset — new live messages cannot shift the boundary
```

### Bot V1 — prompt stuffing, one call, structured output

```mermaid
sequenceDiagram
    participant U as User
    participant H as ChatWebSocketHandler
    participant BS as DoctorAssistantBotService
    participant DB as MySQL
    participant OA as OpenAI
    U->>H: message → recipientId = DoctorAssistant
    H-->>U: ack single  ("the bot has your message")
    H->>H: BotDirectory: BOT → divert, no ConnectionRegistry lookup
    H->>BS: handle(turn)
    BS->>DB: INSERT user message (delivered = false, sync, bypassing Kafka)
    BS->>DB: doctors, availability, bookings (taken vs THIS patient's), last_response_id
    BS->>OA: responses.create — instructions = whole clinic, previous_response_id, strict JSON schema
    OA-->>BS: BotDecision {reply_to_user, action, availability_id, booked_for_date}
    alt action = BOOK
        BS->>DB: BookingService: re-check free, weekday matches, INSERT (unique slot+date)
        Note over BS,DB: On any failure the model's text is discarded — Java writes the reply
    end
    BS->>DB: INSERT reply; UPDATE user message delivered = true
    BS->>DB: bot_conversation_state, bot_token_usage, bot_prompt_log
    H-->>U: ack double  ("the bot has answered")
    H->>U: incoming_message from the bot's user id
```

### Bot V2 — tool calling, streamed

```mermaid
sequenceDiagram
    participant U as User
    participant H as ChatWebSocketHandler
    participant TS as ToolCallingBotService
    participant TB as OpenAiToolCallingBrain
    participant TE as ClinicToolExecutor
    participant OA as OpenAI
    U->>H: message → recipientId = DoctorAssistant (Tools)
    H-->>U: ack single
    H->>TS: handle(turn)   (user message persisted sync, as V1)
    TS->>TB: run(prompt, tools, previous_response_id)
    H->>U: bot_stream_start
    loop up to MAX_ROUNDS = 5
        TB->>OA: responses.create — instructions re-sent every round
        alt response contains function calls
            H->>U: bot_status "Checking availability…"
            TB->>TE: execute(list_specialties | find_doctors | get_available_slots | get_my_appointments | book_appointment)
            Note over TE: get_my_appointments takes no patient id — it closes over the session's user
            TE-->>TB: tool outputs
            TB->>OA: send outputs, chained to that response
        else text reply
            OA-->>TB: streamed tokens
            TB-->>H: deltas
            H->>U: bot_stream_delta ×N
        end
    end
    TS->>TS: persist reply, mark user message delivered, log every round (bot_round_log)
    H-->>U: ack double
    H->>U: incoming_message  (replaces the streamed bubble with the final text)
```

`book_appointment` still goes through `BookingService` — the model requests,
Java validates and writes. Measured against V1 on the same conversations:
average system prompt 14,915 → 3,341 chars; input tokens per turn 5,536 →
2,768; latency 3–6 s → 10–18 s.

## Running and testing

```bash
docker compose up -d chat-service          # needs mysql, kafka, and user-service healthy first (schema ordering)
./gradlew test                             # 152 tests, incl. an embedded-Kafka end-to-end ordering test
```

`OPENAI_API_KEY` (via the repo-root `.env`) is optional: without it both bots
answer that they are unavailable and human chat is unaffected.

## Limits worth knowing

- **One instance only.** `ConnectionRegistry` is a `ConcurrentHashMap` in this
  JVM; a second instance cannot see its sessions. The cross-instance
  registry + pub/sub is designed (Redis) but not built.
- **The socket outlives its token.** The JWT is checked once at connect; no
  timer closes the socket at expiry.
- **Kafka publish failure loses the message** (see above).
- **Bot calls run on the WebSocket's inbound thread**, bounded by
  `openai.timeout-seconds` — turns stay strictly ordered, at the cost of
  blocking that connection for the duration.
