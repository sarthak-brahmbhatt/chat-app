# frontend

The Angular 22 single-page app: register, log in, a WhatsApp-style split view,
live messaging with ticks, scroll-back history, and the two DoctorAssistant
bots as ordinary contacts. Standalone components, signals for state, RxJS for
the WebSocket streams. Served by the Angular dev server on `:4200` in Docker;
in production a static build on S3 behind CloudFront.

Design notes: [`CLAUDE.md`](../CLAUDE.md) build-order steps 7, 13, 16–17, 19.

## Structure

```mermaid
flowchart TB
    R["app.routes.ts"] --> L["LoginComponent"]
    R --> RG["RegisterComponent"]
    R -->|"/chat  (authGuard)"| SH["ChatShellComponent\nowns the WebSocket lifecycle"]
    SH --> UL["UserListComponent\nsidebar · badges bots"]
    SH -->|"/chat/:userId"| CH["ChatComponent\nbubbles · ticks · pagination · streaming"]
    SH -->|"/chat"| PH["ChatPlaceholderComponent"]
    L --> AS["AuthService\naccess token in memory (signal)"]
    RG --> AS
    UL --> US["UserService\nGET /users, filters self"]
    CH --> CS["ChatService\none WebSocket · incomingMessages$ · tickAcks$ · botStream$ · getHistory"]
    SH --> CS
    AS -.-> AI["authInterceptor\nBearer on every HttpClient call"]
    AS -.-> AG["authGuard"]
    US --> USvc[["user-service :8081"]]
    CS -->|wss| CSvc[["chat-service :8082"]]
    CS -->|HTTPS history| CSvc
```

| Folder | Holds |
|---|---|
| `core/` | `AuthService` (login/logout, token as a signal, `currentUserId()` from the JWT), `ChatService` (the socket + history), `UserService`, `authInterceptor`, `authGuard`, `api-config` (env-switched base URLs) |
| `features/login`, `features/register` | Forms |
| `features/chat-shell` | The split view: persistent sidebar + `<router-outlet>`; connects the socket on entry, disconnects on leaving `/chat` |
| `features/user-list` | Sidebar; forwards the recipient's name and user type through router state so the chat header needs no second lookup |
| `features/chat` | The conversation: history, live messages, ticks, scroll-back, bot streaming |
| `models/` | The wire shapes — mirrors chat-service's DTOs |

Routes: `/login`, `/register`, `/chat` (guarded) with children `''`
(placeholder) and `:userId`; `/users` redirects to `/chat`.

## Sequence diagrams

### Login, and what holds the token

```mermaid
sequenceDiagram
    participant U as User
    participant LC as LoginComponent
    participant AS as AuthService
    participant USvc as user-service
    participant AG as authGuard
    U->>LC: submit
    LC->>AS: login(username, password)
    AS->>USvc: POST /login
    USvc-->>AS: {accessToken, refreshToken}
    AS->>AS: accessToken signal ← token  (memory only — gone on page reload)
    LC->>AG: navigate /chat
    AG->>AS: accessToken() present?
    AG-->>LC: allow
    Note over AS: authInterceptor adds Bearer to every HttpClient call from here
    Note over AS,USvc: Refresh is built server-side; the client does not call POST /refresh yet (step 13)
```

### Opening a conversation — history first, then live

```mermaid
sequenceDiagram
    participant U as User
    participant UL as UserListComponent
    participant CH as ChatComponent
    participant CS as ChatService
    participant CSvc as chat-service
    U->>UL: click a contact
    UL->>CH: navigate /chat/:userId + router state {name, userType}
    Note over CH: Same instance is REUSED on switch — reacts to paramMap, ngOnInit does not re-run
    CH->>CH: generation++ · clear bubbles · historyLoaded = false
    CH->>CS: getHistory(userId)
    CS->>CSvc: GET /conversations/:userId/messages
    CSvc-->>CH: {messages, hasMore}
    alt generation still current
        CH->>CH: bubbles ← history · historyLoaded = true · scroll to bottom
    else user switched again meanwhile
        CH->>CH: discard stale response
    end
    Note over CH,CS: Live incoming_message for this chat is appended only once historyLoaded — a message in that gap is dropped from the live view and self-heals on next open
```

### Sending a message — optimistic bubble, then ticks

```mermaid
sequenceDiagram
    participant U as User
    participant CH as ChatComponent
    participant CS as ChatService
    participant CSvc as chat-service
    U->>CH: send("hi")
    CH->>CS: sendMessage(recipientId, content)
    CS->>CS: messageId = crypto.randomUUID()
    CS->>CSvc: {type: message, messageId, recipientId, content}
    CS-->>CH: messageId
    CH->>CH: append bubble {tickState: pending, sentAt: now}  (before any reply)
    CSvc-->>CS: {type: ack, tick: single, messageId}
    CS-->>CH: tickAcks$
    CH->>CH: bubble.tickState = single  (matched by messageId)
    CSvc-->>CS: {type: ack, tick: double, messageId}
    CH->>CH: bubble.tickState = double
```

When a message *arrives*, `ChatService` sends `delivered_ack` automatically
before the component ever sees it — that is what produces the other side's
double tick, with no user action.

### Scrolling back — prepend without the view jumping

```mermaid
sequenceDiagram
    participant U as User
    participant CH as ChatComponent
    participant CS as ChatService
    participant DOM as message list
    U->>CH: scroll near top  (scrollTop ≤ 60, hasMore, not already loading)
    CH->>DOM: read scrollHeight₀, scrollTop₀
    CH->>CS: getHistory(userId, before = oldest.sentAt)
    CS-->>CH: {older, hasMore}
    CH->>CH: bubbles ← older ++ bubbles
    CH->>DOM: requestAnimationFrame
    DOM->>DOM: scrollTop = scrollHeight₁ − scrollHeight₀ + scrollTop₀
    Note over DOM: Same pixels stay in view — the content grew above, and scrollTop grew by exactly that much
```

### A streamed bot reply

```mermaid
sequenceDiagram
    participant CSvc as chat-service
    participant CS as ChatService
    participant CH as ChatComponent
    CSvc-->>CS: bot_stream_start {messageId, senderId}
    CS-->>CH: botStream$
    CH->>CH: add empty bubble {streaming: true}
    CSvc-->>CS: bot_status "Checking availability…"
    CH->>CH: bubble.status shown instead of empty content
    loop tokens
        CSvc-->>CS: bot_stream_delta {text}
        CH->>CH: bubble.content += text
    end
    CSvc-->>CS: incoming_message {messageId, content}
    CH->>CH: replace streamed text with final · streaming = false
    CS->>CSvc: delivered_ack  (the bot's message double-ticks like anyone's)
```

Only the tool-calling bot streams; V1 arrives as a single `incoming_message`.
The tick pair means something different in a bot chat — single: the bot has
your message; double: the bot has answered — so the single tick sits for
exactly as long as the model is thinking.

## Running and testing

```bash
docker compose up -d frontend              # dev server on :4200, source bind-mounted, hot reload
npx ng build --configuration production    # what CI ships to S3 (needs Node 24)
```

`src/environments/` switches the API base URLs: `localhost:8081/8082` in dev,
`api.sarthak-chat-app.beer` in production. There are no frontend unit tests
yet; behaviour is verified through the backend suites and in-browser checks.
