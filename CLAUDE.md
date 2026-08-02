# Multi-User Chat Application — Project Context

This file is the source of truth for architecture decisions made during system design.
Read this before writing any code. Do not introduce components or patterns not listed
here without flagging it as a new decision first.

## 1. Scope

Internal chat tool for an organization of ~10,000 employees. Not a public/federated
product — no third-party integration, no cross-org messaging. Built as a learning +
mentorship project: some choices below are made explicitly for hands-on learning value
rather than pure production-necessity (called out where relevant).

## 2. Functional requirements (source spec)

- Register: username, password, first name, last name. Error on missing required
  fields or duplicate username.
- Login: username + password. Error on mismatch.
- List all other users in the system. Appropriate message if none exist.
- Start a new chat with a user via a "Start Chat" button → opens a WhatsApp-style
  chat window.
- Send/receive messages with single tick (sent) and double tick (delivered).
  No read-receipt (blue tick) requirement in scope.

## 3. Core architecture decisions

### 3.1 Protocol
- **XMPP dropped.** Plain WebSocket is used for real-time chat. This means we own
  the entire message/ack payload format ourselves — no XMPP stanza vocabulary to
  lean on. Payload/ack schema is not yet designed (see Open Questions).
- BOSH is not relevant — native WebSocket support is universal now.

### 3.2 Service boundaries
- **User service** (stateless, HTTP): Register, Login, List Users. Originally
  considered splitting register/login into separate services; merged into one
  service with different endpoints since both are identity-related and this is a
  fixed-size internal user base, not high-scale public signup.
- **Chat service** (stateful, WebSocket): holds live connections, handles
  send/receive, single/double tick logic. Kept separate from User service because
  it has fundamentally different scaling characteristics (stateful vs stateless).

### 3.3 Authentication
- **JWT-based stateless auth.** Explicitly chose NOT to use OAuth 2.0 — OAuth solves
  delegated third-party authorization, which doesn't apply here (single app, single
  org, no delegation). JWT alone (issued by User service, verified independently by
  any service) is the right fit.
- **Access token**: short-lived (minutes).
- **Refresh token**: longer-lived, used only against a `/refresh` endpoint, checked
  against a revocable registry (supports revocation + rotation — each use issues a
  new refresh token and invalidates the old one).
- **Token delivery to Chat service**: browsers can't set custom headers on the
  WebSocket handshake request, so the token is passed at handshake time / as the
  first message immediately after the socket opens (query-param approach rejected
  due to logging/leak risk).
- **Long-lived socket vs short-lived token**: starting approach is server-enforced
  expiry — a per-connection timer force-closes the socket when the access token
  expires; client re-authenticates and opens a new connection. (Smoother
  alternative — client proactively refreshes and sends a `reauth` message over the
  same socket without disconnecting — is a future upgrade, not the starting point.)
- **Transport security**: `wss://` (TLS) is required. Cost concern resolved —
  certificates are free (Let's Encrypt or AWS ACM); the only real recurring cost is
  the domain name. TLS can be terminated directly on the instance — an ALB is not
  required just for TLS.
- Known risk accepted: JWT is a bearer credential — anyone holding it can
  impersonate the user (XSS, network sniffing, or log/URL leakage are the main
  theft vectors). Mitigated via short token lifetime, `wss://`, and the revocation
  registry. Storage location (localStorage vs httpOnly cookie) and CSRF protection
  still to be decided at implementation time.

### 3.4 Message persistence
- **Kafka** sits between Chat service and the Message DB. A consumer asynchronously
  persists messages from a Kafka topic into the DB.
- Explicit reason: decouple "confirming receipt to the sender" from "durably
  writing to DB" — a transient DB write failure should never surface as a failed
  message to the sender.
- **Chosen partly for learning purposes** — acknowledged this is not strictly
  necessary at this message volume/scale; a direct synchronous DB write would
  likely be fast enough. Being explicit about this tradeoff for the mentor review.
- Tick semantics tie directly to this:
  - **Single tick** = fired the moment the Chat service receives the message
    (before Kafka/DB persistence).
  - **Double tick** = fired only after the receiving user's client acknowledges
    delivery.
- Kinesis Data Streams considered as the AWS-native analog to Kafka (ordered
  within a shard, replayable) for later cloud-native comparison. SNS considered
  and rejected for this specific job — it's fan-out pub/sub with no ordering or
  replay, which doesn't satisfy the durability requirement.
- Open/parked: Kafka's own availability as a failure point, and cross-partition
  message ordering for a single conversation — acknowledged as solvable, not yet
  designed in detail.

### 3.5 Data stores
- **User DB**: users, credentials.
- **Message DB**: persisted chat messages (written via Kafka consumer, async).
- **S3**: image storage for chat attachments (mentioned, not yet designed in
  detail).

### 3.6 Scaling & infrastructure
- Starting point: **single instance** for the Chat service — no ALB/ASG, no
  registry/pub-sub — chosen for budget/simplicity, but this is NOT yet validated
  against real connection capacity.
- Capacity reasoning so far: default Spring Boot/Tomcat is thread-per-connection
  (~1MB per thread stack). A t2.micro (1 GB RAM) realistically holds only a few
  hundred concurrent WebSocket connections this way — nowhere near 10,000,
  and adding more small instances just multiplies the same bad ratio. The real
  fix, if needed, is a non-blocking/event-loop runtime (Netty / Spring WebFlux),
  which drastically lowers per-connection memory cost. This needs to be validated
  with real load testing (Artillery/k6/Gatling/Locust), not just napkin math,
  before deciding final instance sizing.
- **If/when multiple Chat service instances are needed**: a shared registry
  (userId → instance) plus a pub/sub mechanism becomes necessary, since a live
  WebSocket connection physically exists in only one instance's memory. This is
  a separate problem from message persistence (Kafka) — routing a live message
  to the right instance vs. durably saving it are two different jobs.
- **ALB cost reality check**: AWS free tier does cover ALB (750 hrs + 15 LCUs) for
  new accounts, but only for a limited window (historically 12 months; restructured
  around a $200 credit / 6-month free plan as of mid-2025). After that, an ALB
  bills continuously by the hour it's running (~$20+/month baseline) regardless of
  traffic. Decision: skip ALB during the initial learning phase (terminate TLS
  directly on the instance); introduce ALB + ASG deliberately later, specifically
  for hands-on AWS experience, timeboxed within the free-tier/credit window.
- HA (fast recovery from an instance/AZ failure) and DR (recovery from a
  catastrophic/regional event with defined RPO/RTO) are explicitly recognized as
  two different concerns. Neither has been designed yet — deferred.

### 3.7 Technology stack
- **Backend**: Java (Spring Boot) for both User service and Chat service.
- **Build tool**: Gradle for both services (migrated from Maven).
- **Frontend**: Angular.
- **Primary datastore**: MySQL — used for both User DB and Message DB.
- **Registry**: Redis — proposed for the refresh-token revocation store. It also
  naturally covers two other needs already identified in this doc without adding
  a second component: (a) the userId → instance connection registry, and (b) the
  pub/sub channel for cross-instance message routing — both only needed once the
  Chat service scales beyond one instance (see 3.6). Maps directly to AWS
  ElastiCache for Redis when this moves to the cloud. Still open for discussion —
  revisit if a different store fits better once implementation starts.
- **Async message persistence**: Kafka, as decided in 3.4.
- **Real-time transport**: WebSocket with a custom (non-XMPP) message/ack
  payload protocol (schema still TBD — see Open Questions).
- **Design intent**: the stack should scale from a single EC2 instance today to
  ALB + ASG + externalized Redis registry later without a rewrite — i.e. avoid
  building anything into the Chat service that assumes in-memory-only state
  beyond what's already flagged as "needs the registry once multi-instance."

## 4. Finalized API / sequence flows

- `POST /register` (username, password, firstName, lastName) → User service checks
  User DB for duplicate username → inserts → 201 Created, or 400 on missing
  fields/duplicate.
- `POST /login` (username, password) → User service verifies against User DB →
  issues access + refresh JWT → 200 OK, or 401 on mismatch.
- `GET /users` (JWT in header) → User service fetches all users from User DB →
  200 OK + list, or an appropriate "no users" message if empty.
- Chat flow (WebSocket, assumes both browsers hold an authenticated connection):
  Browser A opens chat with B (new or existing) → sends message → Chat service
  returns single tick immediately → publishes async to Kafka for DB persistence
  → delivers live to Browser B if connected → Browser B acknowledges → Chat
  service returns double tick to Browser A.

## 5. Explicitly out of scope for now

- XMPP / BOSH
- OAuth 2.0 delegation model
- Read receipts (blue tick)
- Detailed HA/DR design
- Full custom WebSocket message/ack payload schema (not yet designed)
- Exact Kafka partitioning/ordering strategy
- Multi-instance registry + pub/sub implementation (only needed once single-instance
  capacity is proven insufficient)

## 6. Deployment plan

- Local dev: Docker Compose running User service, Chat service, MySQL, Redis, and
  Kafka (+ coordination layer) together, mirroring the eventual cloud topology —
  built and validated locally before any AWS deployment.
- AWS target: leaning toward EC2 + ALB + ASG over Lightsail, specifically for
  hands-on AWS experience (the stated purpose of this project), with ALB usage
  timeboxed to the free-tier/credit window given its ongoing cost.

## 7. Suggested build order

1. Docker Compose skeleton (services + MySQL + Redis, empty endpoints)
2. Register endpoint
3. Login endpoint + JWT issuance
4. List users endpoint
5. Basic WebSocket connect + echo (JWT validated at handshake)
6. Send/receive message + single tick
7. Kafka async persistence to Message DB
8. Double tick (delivery acknowledgment)
9. Refresh token flow
10. Load testing to validate single-instance connection capacity
11. AWS deployment (single instance first, ALB/ASG later if justified by #10)
