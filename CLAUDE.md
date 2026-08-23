# Multi-User Chat Application — Project Context

This file is the source of truth for architecture decisions made during system design.
Read this before writing any code. Do not introduce components or patterns not listed
here without flagging it as a new decision first.

Detailed incident postmortems (what broke, root cause, fix) live in
[`docs/incidents.md`](docs/incidents.md) — this file links to them from
wherever a current decision exists because of one, rather than containing
the full narrative.

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
  lean on.
- BOSH is not relevant — native WebSocket support is universal now.
- **Message/ack payload schema (decided, build-order steps 6 & 9).** After the JWT
  auth message (step 5 — always the raw token string, no envelope, and always the
  first message on a connection), every subsequent WebSocket message is a JSON
  envelope with a `type` field so a client (or the server) can dispatch without
  inspecting which other fields are present. Four shapes so far:
  - Client → Server, send a message:
    ```json
    { "type": "message", "messageId": "<client-generated>", "recipientId": "<userId>", "content": "..." }
    ```
    `messageId` is deliberately **client-generated** (e.g. a UUID), not
    server-assigned — a WhatsApp-style chat window (section 2) needs to render
    the outgoing bubble optimistically before any server round trip, and a
    client-generated id is what a client can correlate that bubble to a later
    tick update with, without waiting on the server first. This id is a
    correlation token for the live round trip, not a permanent record id —
    step 8's Kafka/DB persistence assigns its own separate primary key; don't
    conflate the two.
  - Server → Sender, tick acknowledgment:
    ```json
    { "type": "ack", "tick": "single" | "double", "messageId": "<echoed back>" }
    ```
    One `ack` shape with a `tick` field rather than separate message types per
    tick — single/double are two states of one concept, not two unrelated
    events. Single tick fires the moment Chat service receives the message
    (see 3.4), before attempting delivery or persistence. Double tick fires
    only once the RECIPIENT's client has actually confirmed delivery (see
    `delivered_ack` below) — not merely "the recipient was connected."
  - Server → Recipient, a delivered message:
    ```json
    { "type": "incoming_message", "messageId": "<same id>", "senderId": "<userId>", "content": "..." }
    ```
    A distinct `type` from the client→server shape even though the payload is
    nearly identical (`recipientId` swapped for `senderId`) — dispatch should
    never require inferring direction from which fields happen to be present.
  - Client → Server, delivered acknowledgment (**new, step 9**):
    ```json
    { "type": "delivered_ack", "messageId": "<the id being acknowledged>", "senderId": "<original sender's userId>" }
    ```
    Sent automatically by the RECIPIENT's client the instant it receives an
    `incoming_message` — no user action required. This mirrors WhatsApp's
    actual double-tick semantics: "reached the device," not "the user opened
    it" (read receipts/blue tick remain explicitly out of scope, section 5).
    `senderId` is echoed back by the client (it's already present in the
    `incoming_message` just received) rather than tracked server-side in a
    messageId → senderId map — a deliberate choice to avoid Chat service
    holding state that grows per undelivered/unacked message and needs its
    own cleanup/expiry, for a value the client already has for free.
    **Accepted tradeoff**: this trusts the client not to lie about `senderId`.
    A forged value can only cause a spurious double-tick delivered to some
    other connected user for a `messageId` their own client doesn't recognize
    (a harmless no-op on the receiving end) — consistent with this project's
    existing bearer-token trust model for an internal-only tool (3.3). Revisit
    if this protocol is ever exposed to less-trusted clients.
  - **If the original sender has disconnected by the time a `delivered_ack`
    arrives**, the LIVE double-tick is silently dropped — there is no live
    connection left to deliver it over, and this was never solvable without
    a whole offline-message-queue (see §5's precisely-scoped bullet on what's
    still not built there). What changed (message-history feature, §4): the
    *persisted* delivery status no longer depends on the sender sticking
    around at all — `delivered_ack` publishes a `MessageDeliveredEvent` to
    Kafka (§4) regardless, so whenever the sender's connection next opens
    that conversation, history already shows it correctly, even though they
    never saw a live tick change for it.
  - **If the RECIPIENT was never connected at all when the message was
    sent** (so no `incoming_message` was ever delivered live, and therefore
    no `delivered_ack` was ever produced by anything), this used to be a
    permanent gap — nothing would ever mark that message delivered, not
    even the recipient later opening the conversation, since reading
    history is a pure read (§4). **Closed (message-history feature, §4):**
    `ChatWebSocketHandler.authenticate()` sweeps every message where the
    just-connected user is the recipient and delivery was never recorded,
    marks each delivered, and live-notifies the original sender if they
    happen to be connected right now — see §4's `sweepUndeliveredForRecipient`
    entry for the full design and its accepted concurrency tradeoff.

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
- **Access token**: short-lived (15 minutes — see user-service's
  `jwt.access-token-expiration-minutes`).
- **Refresh token (fully decided, build-order step 10)**: longer-lived, used only
  against a `/refresh` endpoint, checked against a revocable registry.
  - **Format**: opaque, cryptographically random (256 bits via `SecureRandom`),
    deliberately NOT a JWT. Unlike the access token, a refresh token is checked
    against a store on every single use anyway (that's what "revocable registry"
    means) — there's nothing to gain from making it self-contained/stateless the
    way the access token needs to be, and an opaque value can't be tampered with
    (there are no claims in it to forge).
  - **Lifetime: 7 days.** Long enough that an actively-used internal tool doesn't
    force a daily re-login; short enough to bound how long a stolen refresh token
    stays useful. A deliberate middle ground for a ~10,000-employee internal tool
    — not a public consumer app, where 30+ day silent sessions are common
    specifically for retention reasons that don't apply here.
  - **Store**: Redis, keyed by SHA-256(token) — never the raw token value — with a
    TTL matching the lifetime above, so an expired one is simply gone with no
    manual cleanup. A fast, un-salted hash, deliberately NOT bcrypt: this is 256
    bits of already-high-entropy random data, not a low-entropy human-chosen
    secret vulnerable to dictionary/brute-force attack, so bcrypt's deliberate
    slowness (the whole reason it's used for passwords) buys nothing here. Hashing
    at all exists so that IF Redis itself were ever compromised (a leaked backup,
    misconfigured access), an attacker would see only hashes, not directly-usable
    bearer credentials — worth one extra hash call given this token lives for days,
    a much larger exposure window than the access token's 15 minutes.
  - **Rotation**: every successful `/refresh` call issues a brand-new access +
    refresh token pair and invalidates the presented refresh token. "Invalidates"
    specifically means marked rotated and left to expire naturally, not deleted
    outright — deleting it would make a REPLAY of that same token indistinguishable
    from a token that never existed, losing the ability to detect reuse at all.
  - **Reuse detection (decided, step 10)**: presenting an already-rotated refresh
    token again is treated as a theft signal. The response is to revoke the ENTIRE
    token family — including whichever token is CURRENTLY valid for that chain —
    not just reject the replay. Reasoning: once a token has been used twice, the
    server can no longer tell whether the legitimate user or an attacker is
    holding the current valid token (whoever rotated first "won" that round is
    unknowable from here), so the safe response is to kill the whole chain and
    force a fresh login — bounding a detected compromise to one wasted round trip,
    rather than leaving a window where a stolen, already-rotated-forward token
    might still work. Both "invalid/expired" and "reuse detected" return the
    identical 401 + message — no signal to the caller distinguishing which
    happened, same anti-enumeration reasoning as /login's identical-message design.
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
- **Partitioning/ordering (resolved, step 8)**: messages are keyed by a
  canonical, sender-order-independent pair (`min(userId1,userId2):max(userId1,userId2)`),
  not by sender alone. This guarantees every message in a given conversation —
  regardless of direction — lands on the same Kafka partition, which is what
  actually preserves send-order, since Kafka only guarantees ordering within a
  single partition, not across partitions or across different keys.
- **Kafka availability as a failure point (decided, step 8)**: if the publish
  to Kafka fails outright (not just slow), that message is not retried or
  persisted anywhere — it's accepted as lost for the purposes of this project.
  The sender still gets their single tick regardless (that's the whole point
  of the async design), but there is currently no dead-letter queue, retry
  policy, or outbox pattern to recover a failed publish. Flagged as a known,
  deliberate scope limit — not a gap to silently carry forward.

### 3.5 Data stores
- **`chatappdb` — ONE database for everything (revised, step 18).** This
  REVERSES the original userdb/messagedb split. It holds users and
  credentials, persisted chat messages, and the bot's tables (3.9).
  - **Why the reversal**: the bot needs to join across users, messages,
    doctors, availability and appointments in single queries — most
    concretely, chat-service has to read `users.user_type` to know a
    recipient is the bot at all, and the availability subtraction (3.9)
    joins three tables in one statement. Two databases make that impossible
    *in SQL* and push the join into Java. Both databases already lived in
    the same MySQL container, so the isolation was convention, never
    enforcement.
  - **What it costs, deliberately**: the per-service data-ownership boundary
    the split represented. Mitigated at the code level rather than the
    database level — user-service remains the ONLY writer to `users`, and
    chat-service's view of it (`entity/AppUser.java`) maps a subset of its
    columns, pointedly not `password`, and exposes only finders. Nothing
    enforces that any more; it is a convention held by review.
  - **One real consequence**: chat-service's Hibernate `ddl-auto: update`
    would create a PARTIAL `users` table if it won the startup race, leaving
    user-service to ALTER a NOT NULL `password` column onto it. Ordered
    away rather than relied upon — user-service gained a healthcheck and
    chat-service a `depends_on` against it. That dependency is schema
    ordering only; chat-service still never calls user-service (3.2).
  - **No migration, by design**: local dev drops the volume and recreates
    (`docker compose down -v`), and AWS is torn down. `mysql-init/` creates
    only this one database; everything else is still Hibernate's.
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
- **Step 11 load test (k6) empirically validated single-instance capacity —
  full write-up in [`docs/incidents.md`](docs/incidents.md).** Corrected the
  thread-per-connection assumption above (~90-100KB measured per connection,
  not ~1MB) and found a JVM/container-memory ceiling around 1,300-1,400
  connections under a 512MB Docker Desktop container (not AWS-representative
  — rerun `load-test/ws-ramp-test.js` against the real deployed instance for
  actual sizing). **Actionable for step 12**: explicitly configure
  `-Xmx`/`-XX:MaxRAMPercentage` past its 25% default and
  `-XX:+ExitOnOutOfMemoryError` — the JVM's own default heap cap is what
  failed first, not total container memory.
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
  payload protocol — schema decided, see 3.1.
- **Design intent**: the stack should scale from a single EC2 instance today to
  ALB + ASG + externalized Redis registry later without a rewrite — i.e. avoid
  building anything into the Chat service that assumes in-memory-only state
  beyond what's already flagged as "needs the registry once multi-instance."

### 3.8 AWS deployment architecture (build-order step 12)

Full stack design for the real deployment step 11's load test is meant to be
rerun against once this lands. Captures decisions reached across design
discussion that weren't written down anywhere else yet.

- **Region: `us-east-1`.** Cheapest baseline pricing across the standard AWS
  region tiers, and also a hard, non-negotiable AWS constraint if CloudFront
  ever gets a custom domain (it does — see below): an ACM certificate attached
  to a CloudFront distribution MUST be requested in `us-east-1` regardless of
  which region everything else lives in. Picking `us-east-1` for everything
  else too avoids having two regions in play for no reason.
- **VPC: public subnets only, no NAT Gateway.** An explicit cost tradeoff, not
  an oversight — a NAT Gateway bills a fixed ~$32+/month whether or not
  anything uses it, on top of per-GB data processing charges, and this
  project's whole AWS posture is "minimize fixed recurring cost, timeboxed
  learning exercise" (see 3.6's identical reasoning for skipping ALB
  initially). Without private subnets, Security Groups become the actual
  protection layer instead of network-level isolation — every instance is
  reachable in principle, but Security Group rules scope what can actually
  connect (e.g. the data-layer instance below only accepts MySQL/Redis/Kafka
  ports from the backend instances' Security Group, not from the internet).
- **Compute: plain EC2 + Launch Template + ASG — deliberately not
  ECS/Fargate.** The stated purpose of this project (3.6, 6) is hands-on AWS
  experience; ECS/Fargate would abstract away exactly the ASG/ALB/target-group
  mechanics this project wants direct, hands-on visibility into. Same
  reasoning as choosing Kafka over a managed queue in 3.4, and EC2+ALB+ASG
  over Lightsail in section 6 — learning value over operational convenience,
  called out explicitly per section 1's stated intent.
- **Only chat-service is auto-scaled (min 1, max 4).** It's the one with
  actual connection-capacity pressure worth demonstrating (see 3.6's step 11
  results — a real, empirically-found ceiling exists per instance). user-service
  is stateless HTTP with a much lighter, flatter load profile (3.2) — a single
  fixed EC2 instance, no ASG, is enough to demonstrate the deployment without
  needing a second scaling story. Both share ONE ALB via two separate target
  groups (path/host-based routing) rather than provisioning a second ALB —
  one public HTTPS entry point for the whole backend, and a second ALB would
  just be a second ~$20+/month fixed cost (3.6) for no added capability here.
- **Data layer: one single, non-scaled EC2 instance running MySQL + Redis +
  Kafka**, via the same docker-compose pattern already used for local dev
  (section 6) rather than three separate managed services (RDS/ElastiCache/MSK)
  — keeps the deployment's shape close to what's already built and tested
  locally. Accepted single point of failure — consistent with the Kafka SPOF
  already accepted in 3.4 ("if the publish to Kafka fails outright... accepted
  as lost"); this extends the same accepted-risk posture to the whole data
  layer rather than introducing a new, inconsistent standard just for this
  instance. HA/DR for this layer remains explicitly deferred, same as 3.6.
- **Frontend: S3 + CloudFront, fully static, outside the VPC entirely.**
  Angular's build output is static files — no compute needed to serve it, so
  it isn't part of the EC2/ASG/VPC story above at all. CloudFront also is what
  forces the custom-domain / ACM requirement below.
- **Custom domain: `sarthak-chat-app.beer`** (registered via Porkbun), with
  `api.sarthak-chat-app.beer` dedicated to the ALB/backend (the root domain
  points at CloudFront/the frontend). An ACM certificate is already issued for
  this subdomain:
  `arn:aws:acm:us-east-1:786566430552:certificate/cddcb649-0fb0-4d07-8b25-e6927cadb7c4`.
  Required, not cosmetic: CloudFront forces HTTPS on the frontend, and a
  browser blocks mixed HTTPS→HTTP content — so once the frontend is served
  over HTTPS via a real domain, the backend it calls must also present a real
  TLS certificate for a real domain, not AWS's default `*.amazonaws.com` /
  ALB DNS name (which browsers would flag, and which doesn't match this
  project's own domain anyway).
  - **A second, SEPARATE ACM certificate covers the frontend itself**:
    `arn:aws:acm:us-east-1:786566430552:certificate/caf0ea5c-6352-476e-9c4a-e2cb217446c1`,
    with `sarthak-chat-app.beer` and `www.sarthak-chat-app.beer` as SANs on
    one certificate. Not the same cert as the ALB's above — CloudFront and
    the ALB each terminate TLS independently, so each needs its own
    certificate covering only the name(s) it actually presents; there's no
    mechanism to share one ACM resource between the two. `frontend-stack.yaml`
    now sets both names as CloudFront `Aliases` and wires this ARN into
    `ViewerCertificate` (`SslSupportMethod: sni-only` — the modern,
    free-by-default option; the legacy `vip` method provisions a dedicated
    IP for pre-SNI clients, effectively unneeded traffic today, and
    CloudFront bills for it).
  - **CloudFormation only controls the AWS side of this — Porkbun's DNS
    is a separate, manual, out-of-band step**, same reasoning as 3.3's
    stance on IAM user creation staying a human's call: no template in
    this repo can reach into Porkbun's zone, so the ALIAS record (root
    domain) and CNAME record (`www`) — both currently pointed at Porkbun's
    own parking target, `pixie.porkbun.com` — have to be repointed by hand
    at the CloudFront distribution's domain name
    (`frontend-stack.yaml`'s `CloudFrontDomainName` output) after every
    time that value changes (it's stable across ordinary deploys since the
    distribution itself isn't replaced by them, but would change if the
    distribution ever were).
- **Images: ECR**, pulled by EC2 instances via an **IAM instance role** — no
  stored credentials on the instances themselves, consistent with 3.3's
  general stance against hardcoding secrets. Deployment on merge is **Launch
  Template versioning + ASG Instance Refresh**, not ECS task definitions —
  follows directly from the EC2/ASG (not ECS/Fargate) compute choice above;
  there's no task-definition concept to use once ECS itself is out of the
  picture.
  - **user-service's deploy-on-merge is a CloudFormation stack update, not a
    direct `ec2:RunInstances` call** — revised after a real incident where a
    hand-rolled, CloudFormation-bypassing version of this workflow produced
    an orphaned instance that collided with a later stack update and also
    caused a secrets-wiping cascade; full story in
    [`docs/incidents.md`](docs/incidents.md). `deploy-user-service.yml` now
    does exactly one AWS-mutating thing: a stack update setting only
    `UserServiceImageTag`, with `UsePreviousValue: true` on every other
    parameter (load-bearing — without it, an update silently resets
    unlisted parameters, including secrets, to the template's default).
    This works because `UserServiceInstance.Properties.LaunchTemplate.Version`
    is wired to `!GetAtt UserServiceLaunchTemplate.LatestVersionNumber`
    (not pinned or `$Default`) — a new image tag creates a new Launch
    Template version, `LatestVersionNumber` changes, and that property has
    `recreation: Always` for `AWS::EC2::Instance`, so CloudFormation
    replaces the instance itself on a plain parameter change.
  - **Accepted tradeoff, not solved**: the hand-built swap this replaced
    was zero-downtime by construction (create new, verify healthy, then
    destroy old). Plain CloudFormation replacement of `AWS::EC2::Instance`
    has no equivalent wait — no `CreationPolicy`/`cfn-signal` wired into
    this template — so a bad deploy can have a brief window where the old
    instance is already gone before the new one is confirmed healthy.
  - **chat-service keeps its existing mechanism unchanged** — its ASG
    Instance Refresh (`deploy-chat-service.yml`) was never the source of
    the conflict; only user-service's bespoke, CloudFormation-bypassing
    path was.
- **IaC: CloudFormation**, one template capturing the full stack. Chosen
  specifically because this project's cost posture is "spin up to demo, tear
  down when not in use," not "leave running" — a single template makes
  `create-stack`/`delete-stack` the actual day-to-day workflow. This matters
  because stopping EC2 instances alone does NOT stop the ALB's hourly billing
  (3.6) — an ALB bills for existing, not for traffic — so a real teardown has
  to delete the whole stack, not just stop instances, and CloudFormation is
  what makes that a single reliable operation instead of manually chasing
  every resource.
  - **Templates are staged in S3 and deployed via `--template-url`, not
    `--template-body`** — the AWS API caps an inline template at 51,200
    bytes, which `chat-app-stack.yaml` exceeded once step 12's hardening
    comments landed (failure details in
    [`docs/incidents.md`](docs/incidents.md)). Referencing from S3 raises
    the ceiling to 460,800 bytes. The staging bucket
    (`chat-app-cfn-templates-<account-id>`) is private, versioned, and
    encrypted; `cloudformation/deploy.sh` re-uploads on every invocation so
    the staged copy can't drift from the local file. The bucket itself is
    deliberately NOT created by either template (a template can't live in
    the bucket it creates) — a one-time out-of-band resource that
    intentionally survives `delete-stack`.
- **IAM: a scoped-down (not admin) IAM user**, created manually by the
  developer directly in the AWS console — a deliberate human checkpoint, not
  something generated by Claude Code. Its access keys are what later get
  pushed to GitHub Secrets for the CI/CD workflow (deploy-on-merge). Keeping
  IAM user creation manual and out-of-band is intentional: credential
  provisioning is exactly the kind of action that stays a human's call.

### 3.9 DoctorAssistant bot — Version 1 (build-order step 18)

A conversational appointment assistant for a fictional clinic, reachable as an
ordinary chat contact. Local Docker only — not deployed.

- **Version 1 deliberately uses NO tool calling.** All doctor/availability/
  appointment data is stuffed into the prompt on every turn, and **structured
  outputs** let the model signal a booking that Java then executes. This is
  knowingly the naive approach: it will hit prompt bloat, token cost that grows
  with every doctor added, and an inability to reason over anything not
  pre-injected. **Feeling those limits is the point** — Version 2's tools should
  solve a problem that has actually been measured, not merely described. Hence
  `bot_token_usage` (below): the argument for tools should be a query, not a
  claim. Same "learning value over convenience" reasoning as choosing Kafka in
  3.4 and EC2+ASG in 3.8.
- **The bot is a real `users` row** (`username: doctorassistant`,
  `user_type: BOT`, `last_name: NULL`, password = bcrypt of discarded
  `SecureRandom` noise so nothing can ever present it at `/login`). This is what
  keeps the frontend user list, the WebSocket envelope (3.1),
  `messages.sender_id`/`recipient_id`, and conversation history (§4) working
  **completely unchanged** — to all of them it is just another user. **No
  frontend code changed for this feature at all.** Seeded by user-service
  (`BotUserSeeder`), which owns `users` and already has the PasswordEncoder.
- **`users.user_type`** (`VARCHAR(20) NOT NULL DEFAULT 'USER'`, values
  `USER | BOT`) is the only thing that distinguishes it, and only chat-service's
  routing branch looks. AGENT and DOCTOR are deferred (§5) — doctors are
  reference data here and never log in.

**Where the code lives**: a new `com.chatapp.chatservice.bot` package inside
chat-service, NOT a separate microservice. It is one branch off an existing
handler plus its supporting domain; a fourth deployable would be pure overhead.

**Tables** (all in `chatappdb`, all Hibernate-managed):
- `doctors` — name, specialty (free text, not an enum — see below), `active`.
- `doctor_availability` — the RECURRING WEEKLY pattern only: `day_of_week`,
  `start_time`/`end_time` (30-minute slots), `status: AVAILABLE | BLOCKED`.
  Says nothing about bookings.
- `appointments` — actual bookings on specific dates. Times are **denormalised
  deliberately**: a doctor changing their pattern later must not retroactively
  rewrite what time an existing appointment was booked for.
- `bot_conversation_state` — `conversation_key` (the SAME canonical pair key
  Kafka partitions on, now shared via `support/ConversationKey` so there is one
  implementation rather than two that agree today) → `last_response_id`.
- `bot_token_usage` — one row per model call, with `turn_number`.

**Availability is derived by SUBTRACTION, never stored as a flag** — free time is
a property of (slot, date), not of the slot, so there is nowhere to put a flag:
> free(doctor, date) = availability rows matching date's weekday, `status =
> AVAILABLE`, `doctors.active = TRUE` **MINUS** appointments for that doctor on
> that date with `status = BOOKED`

- **Expressed in exactly ONE place** — `AvailabilityService`, over a single
  three-table query. The realistic second implementation subtracts bookings but
  forgets `doctors.active`, reads as obviously correct, and silently offers
  appointments with a doctor on leave. `AvailabilityServiceTest` asserts each
  filter separately against a real database (H2) rather than a mock, since the
  thing under test *is* a query.
- **Soft deletes are FORWARD-LOOKING only.** `BLOCKED` and `active = FALSE` mean
  "no NEW bookings from here on" — never "cancel what exists", never a physical
  delete (historical appointments still reference those rows). Already-booked
  appointments are honoured regardless; the doctor shows up. So there are two
  query directions and confusing them is how a patient with a real appointment
  gets told they have none: forward-looking ("what can I book?") applies every
  filter; backward-looking ("what exists?") reads `appointments` directly and
  applies none.

**Message flow** — no new endpoint, no new socket path, no frontend change. The
user sends a normal message with `recipientId` = the bot's user id.
1. `ChatWebSocketHandler` sends the **single tick** exactly as always (3.1 —
   unchanged and deliberately not reordered: 3.4's "a write failure must never
   surface as a failed message" applies here too).
2. If `BotDirectory.isBot(recipientId)`, divert — **no `ConnectionRegistry`
   lookup**, which for the bot could only ever miss.
3. The user's message is persisted **synchronously, bypassing Kafka**, written
   **already delivered**, and **double-ticked immediately**. Kafka exists to
   decouple acking from durably storing when a recipient may be offline (3.4);
   the bot never is, and it must read its own conversation within the same turn
   — an insert landing after the reply would leave the next turn's history
   missing the message it answers. The double tick is sent because the bot has
   no browser to send a `delivered_ack`, so the message would otherwise sit on
   one tick forever despite plainly having been received.
4. Fresh clinic data is queried **every turn, uncached** — at five doctors that
   is trivial, and a cache's staleness would be a correctness bug (offering a
   slot that is gone), not a performance trade.
5. The Responses API is called over **plain blocking HTTP**, with
   `previous_response_id` chaining if a prior turn exists.
6. **If `action = BOOK`: validate and write BEFORE any reply is sent** (below).
7. The reply is persisted (delivered = false — the user DOES have a browser and
   will ack it the ordinary way) and sent as a normal `incoming_message` from
   the bot's user id, indistinguishable on the wire from a human's.
8. `last_response_id` is stored and a `bot_token_usage` row written.

**The OpenAI call**:
- **Prompt stuffing (§6.1's deliberate naivety)**, injected every turn: all
  active doctors + specialties; all `AVAILABLE` pattern rows; all `BOOKED`
  appointments for the next 7 days; today's date and current time (the model has
  no clock); and **the DISTINCT specialty list read from the database at request
  time**, which the prompt declares closed. That last one is what makes "sorry,
  we have no dermatologist" work instead of the model inventing one — inventing
  a plausible specialty is a far more fluent continuation than refusing. Free
  text, not an enum, so seeding a doctor is the whole operation.
  - Also injected, beyond the spec: an explicit **date → weekday list** for the
    horizon. The model has no calendar any more than it has a clock, and
    "next Tuesday" otherwise resolves to a confident, frequently wrong date.
  - The prompt goes in the API's `instructions` field, NOT as a conversation
    message. With chaining, instructions apply to the current call only and are
    not carried forward — so each turn gets fresh data. As a message, every
    turn's snapshot would accumulate, be re-billed forever, and leave the model
    reading several contradictory versions of what is booked.
- **Structured output** (`BotDecision`): `reply_to_user`, `action: BOOK | NONE`,
  `availability_id` (nullable), `booked_for_date` (nullable). **The model
  decides; Java acts** — the model can never write to the database. The SDK
  derives a strict JSON schema from the record, so a reply that omits or invents
  a field is not something to handle, it is something that cannot be produced.
- **Booking: validate and write before replying** — (1) re-check the slot is
  genuinely still free under the rules above, since the prompt was a snapshot
  and not a lock; (2) verify the date's weekday matches the availability row's;
  (3) insert; (4) only then send `reply_to_user`. On any failure the model's text
  is **discarded entirely** and a Java-written message sent instead — that text
  was written assuming success, and sending it tells a patient they have an
  appointment nobody made. Also rejected: dates in the past (the subtraction is
  date-agnostic and would report last Monday free), and unparseable dates (the
  schema constrains the field to a *string*, so "next Tuesday" satisfies it).
- **Explicitly not needed** (§7 of the design): no WebSocket to OpenAI (one
  request, one reply — that is HTTP); no WebFlux (reactive is a concurrency
  model, and the existing WebSocket already pushes to the browser from ordinary
  blocking MVC code); no tool calling; no streaming.
- **Not Spring AI**, called against the official `com.openai:openai-java` SDK
  directly — same learning-value reasoning as 3.4/3.8. The cost is named rather
  than discovered later: swapping to Bedrock is real rewrite work against
  SDK-specific classes, not a config change.

**Config**: `OPENAI_API_KEY` via a **gitignored `.env`** at the repo root, which
Compose reads automatically (`.env.example` is the committed template). Model
name configurable, never hardcoded. **A missing key is a valid state, not a
startup failure** — chat-service's real job is human chat, so an optional
feature must not become a hard dependency of the whole service; the bot simply
replies that it is unavailable.

**Accepted tradeoffs, named:**
- `appointments` has an **unconditional** unique constraint on
  `(availability_id, booked_for_date)`. It closes the check-then-write race a
  re-read cannot, and is only correct while cancellation is out of scope — a
  `CANCELLED_*` row would otherwise block that slot permanently, and MySQL has
  no partial unique index to say "at most one BOOKED row".
- **The model call runs inline on the WebSocket's inbound thread**, bounded by
  `openai.timeout-seconds`. That keeps turns strictly ordered, so two
  overlapping calls cannot chain off the same `previous_response_id`. Moving it
  to an executor is where to start if bot conversations get concurrent enough to
  matter, and it would need its own answer to that ordering question.
- **A database failure during a bot turn IS visible to the user**, as an apology
  rather than a reply — unlike the human path, which Kafka insulates. Honest:
  without a persisted turn the bot could not have answered coherently anyway.
- **A failed model call leaves `last_response_id` untouched**, so the next turn
  still chains onto the last good response rather than the failure silently
  wiping the conversation's memory.


## 4. Finalized API / sequence flows

- `POST /register` (username, password, firstName, lastName) → User service checks
  User DB for duplicate username → inserts → 201 Created, or 400 on missing
  fields/duplicate.
- `POST /login` (username, password) → User service verifies against User DB →
  issues an access token (JWT) + refresh token (opaque, see 3.3) → 200 OK, or 401
  on mismatch.
- `POST /refresh` (refresh token) → User service validates + rotates it against
  Redis (see 3.3) → issues a new access + refresh token pair → 200 OK, or 401 if
  the presented token is invalid, expired, or a detected reuse of a rotated token.
- `GET /users` (JWT in header) → User service fetches all users from User DB →
  200 OK + list, or an appropriate "no users" message if empty.
- Chat flow (WebSocket, assumes both browsers hold an authenticated connection):
  Browser A opens chat with B (new or existing) → sends message → Chat service
  returns single tick immediately → publishes async to Kafka for DB persistence
  → delivers live to Browser B if connected → Browser B acknowledges → Chat
  service returns double tick to Browser A.
- Bot chat flow (WebSocket, same envelope as any other chat — CLAUDE.md 3.9):
  Browser A sends a message with `recipientId` = the bot's user id → Chat
  service returns single tick immediately → recognises `user_type = BOT` and
  diverts instead of doing a ConnectionRegistry lookup → persists the message
  synchronously (bypassing Kafka), already marked delivered, and returns the
  double tick → queries fresh clinic data and the conversation's
  `last_response_id` → calls the OpenAI Responses API → validates and writes
  any booking BEFORE replying → persists the reply and delivers it to Browser A
  as an ordinary `incoming_message` from the bot's id → records the new
  response id and a token-usage row. Browser A's client auto-acks that reply
  exactly as it would a human's, so the bot's own message reaches double tick
  through the normal path.

- `GET /conversations/{otherUserId}/messages` (JWT in header) → Chat service
  fetches the persisted conversation between the authenticated caller and
  `otherUserId` from `chatappdb`'s `messages` table, oldest-to-newest → 200 OK
  + a message list
  (empty list + "No messages yet." if there's no history), or 401 on
  missing/invalid/expired token. The Angular chat window calls this once, on
  open, to populate history before the live WebSocket connection is made (see
  ChatComponent.ngOnInit) — this is chat-service's first plain REST endpoint,
  sitting alongside its existing WebSocket-only surface.
  - **Auth: inline `JwtValidator` in the controller, not promoted to an
    interceptor.** chat-service already has exactly one other authenticated
    concern — the WebSocket handshake (3.3) — but that auth happens through
    Spring's `WebSocketHandlerRegistration`/`HandshakeInterceptor` machinery,
    a completely different mechanism from Spring MVC's `HandlerInterceptor`
    that guards user-service's REST endpoints (3.2's `JwtAuthenticationInterceptor`).
    A `HandlerInterceptor` here would do nothing to unify with WS auth — it
    would only be reusable across chat-service's *other* REST endpoints, and
    there's exactly one of those (this one). Introducing an interceptor
    layer, a registration config, and an exclusion list to protect a single
    endpoint would be structure built for a second REST endpoint that
    doesn't exist yet. Revisit and promote to an interceptor the moment a
    *second* REST endpoint is added to chat-service — at that point the
    duplication becomes real, not hypothetical.
  - **Page size: 50 messages, oldest-to-newest within a page.** Selected via
    a `DESC ... LIMIT 50` query (`Pageable`), reversed in memory before
    returning — the DB has to select by recency to get the *right* 50 rows,
    even though the response itself reads oldest-first.
  - **Pagination (resolved, chat UI improvements pass): cursor-based via an
    optional `?before=<ISO-8601 instant>` query param, not offset/page-number
    based.** The access pattern this endpoint actually serves is "page
    backward into the past while new messages keep arriving at the live
    end" — ChatComponent's scroll-to-top handler asks for the page just
    older than whatever it already has. That's exactly the case OFFSET
    pagination gets wrong: a new message arriving mid-scroll shifts every
    existing row's offset by one, silently skipping or duplicating a row at
    the next page's boundary. A `sentAt <` cursor is anchored to a value
    that never changes retroactively, so new messages arriving during
    pagination can't corrupt it. Omitting `before` means "the first
    (most recent) page" — unchanged from before this pass.
    `ChatMessageRepository.findConversationBeforeMostRecentFirst` is the new
    cursor query, same shape as the original `findConversationMostRecentFirst`
    plus a `sentAt < :before` predicate. No secondary tie-break column for
    two messages landing in the exact same microsecond — accepted, not
    solved, given `datetime(6)` precision and this app's human-typing-speed
    message volume; revisit only if that assumption is ever shown wrong.
  - **`hasMore` (boolean, added to `ConversationHistoryResponse` alongside
    `messages`/`message`)**: true when a full 50-row page came back, false
    otherwise. Computed off the page size rather than a separate `COUNT`
    query — the accepted imprecision (a conversation with *exactly* one
    more full page left still reports `true`, costing one harmless empty
    fetch on the next scroll-to-top) is cheaper than a second query on every
    single request just to avoid that one wasted round trip.
  - **Malformed `before`** (fails `Instant.parse`) → 400 with an
    `ErrorResponse` body, same shape as the existing 401 responses.
  - **A conversation with an `otherUserId` that doesn't correspond to any
    real user returns the exact same response as a real user with no shared
    history: `{"messages": [], "message": "No messages yet."}`.** This is
    deliberate, not an unhandled edge case. **The original reasoning was that
    this was structurally impossible** — messagedb had no users table, and
    checking would have meant a new cross-service call to user-service purely
    to validate a path parameter (3.2), which that pass declined to add.
    **Step 18's chatappdb consolidation (3.5) removed that impossibility**:
    chat-service can now read `users` directly, so the endpoint COULD
    distinguish the two cases with a local join and no new coupling at all.
    It deliberately still doesn't. Telling an authenticated caller which user
    ids exist turns this endpoint into a user-enumeration oracle, and the
    identical-response design is the same anti-enumeration reasoning /login
    already uses (3.3). What changed is the justification, not the behaviour —
    it is now a choice rather than a constraint, which is worth knowing before
    someone "fixes" it.
  - **CORS**: a new `WebMvcConfig` (chat-service's first) scopes
    `addCorsMappings` to `/conversations/**` specifically, allowing the same
    origins already trusted for the WebSocket handshake (`localhost:4200`,
    the CloudFront domain, the custom domain) — kept as its own, narrowly
    scoped mapping rather than a blanket `/**` rule, since this is the first
    time chat-service has needed plain-HTTP CORS at all.
  - **Delivered-flag persistence — Kafka-ordered, not a synchronous race
    (revised).** Originally, double-tick status was written via a direct
    synchronous `markDelivered` `UPDATE`, called the instant the live
    double-tick fired. Because Kafka's publish-then-async-consume insert
    path (3.4) is slower than that direct socket-triggered `UPDATE`, the
    `UPDATE` routinely — confirmed empirically, not a theoretical corner
    case — ran before the message's own row existed yet, matched zero rows,
    and was silently dropped, leaving history stuck showing single-tick for
    a message that really was delivered live moments earlier. **This is now
    fixed structurally, not mitigated:** `handleDeliveredAck` no longer runs
    a direct `UPDATE` at all. It instead publishes a `MessageDeliveredEvent`
    to the SAME Kafka topic and, critically, the SAME partition key
    (`conversationKey(senderId, recipientId)`) as the original message's
    `ChatMessageEvent`. Kafka only guarantees ordering within one partition
    — but a `delivered_ack` can only ever be produced after the message it
    acknowledges was already produced (there's nothing to acknowledge
    otherwise), so same-partition placement guarantees `ChatMessageConsumer`
    always processes the insert before the delivery update, every time, not
    "usually." The live double-tick itself (`sendDoubleTickIfConnected`)
    stays entirely synchronous and immediate, unaffected by any of this —
    same "don't couple a live confirmation to how fast the durable write
    lands" reasoning 3.4 already uses for single-tick vs. Kafka insert.
  - **Reconnect-time delivery sweep** (`ChatMessageService.
    sweepUndeliveredForRecipient`, called from `ChatWebSocketHandler.
    authenticate()` on every successful WebSocket auth, not just first-time
    connects): closes the one gap Kafka-ordering above can't reach on its
    own — a message sent while the recipient had NO live connection at all,
    so no `delivered_ack` was ever produced, Kafka-ordered or otherwise (see
    3.1's now-split disconnected-sender/disconnected-recipient bullets).
    Queries every `delivered=false` row where the just-connected user is the
    recipient, marks each delivered, and live-double-ticks each original
    sender if they happen to be connected right now — reusing the same
    `sendDoubleTickIfConnected` helper `handleDeliveredAck` uses. Runs
    unconditionally on every connect (the query is a cheap no-op when
    nothing's pending, which is the overwhelmingly common case) and
    deliberately never re-sends message CONTENT to the reconnecting
    recipient — `GET /conversations/{otherUserId}/messages`, already called
    by the frontend before this connection opens, remains the only content
    path; re-pushing as `incoming_message` here would risk a duplicate
    bubble. Also incidentally catches any row left stuck `false` from before
    this whole fix shipped, since marking an already-delivered row delivered
    again is a harmless no-op. **Accepted concurrency tradeoff, not locked
    against**: two near-simultaneous connects for the same recipient (two
    tabs, or a stale session's close racing a fresh one) could both sweep
    the same rows and both fire a duplicate live double-tick — harmless,
    since `TickAck.doubleTick` is idempotent on the frontend (a keyed
    `.map`, not an append) and a duplicate `UPDATE` is a no-op, consistent
    with `ConnectionRegistry`'s own no-locking, single-instance design.

## 5. Explicitly out of scope for now

- XMPP / BOSH
- OAuth 2.0 delegation model
- Read receipts (blue tick)
- Detailed HA/DR design
- Multi-instance registry + pub/sub implementation (only needed once single-instance
  capacity is proven insufficient)
- **Bot Version 2's tool calling** — the entire point of Version 1 being naive
  (3.9). Deferred until `bot_token_usage` shows the cost curve rather than
  merely predicting it.
- Bot appointment CANCELLATION. Load-bearing beyond its own absence: the
  unconditional unique constraint on `appointments` (3.9) assumes cancelled
  rows never appear, and `AppointmentStatus`'s `CANCELLED_*` constants exist
  only so the subtraction predicate is already `= BOOKED` rather than "any row".
- Bot response STREAMING — Version 1 sends one whole reply. Adding it later
  means relaying OpenAI's SSE chunks over the existing WebSocket.
- Human-agent transfer and the `AGENT` user type; `DOCTOR` as a user type
  (doctors are reference data — they never log in or chat).
- Amazon Bedrock, and any other provider swap. Noted rather than merely
  deferred: bypassing Spring AI (3.9) means this is real rewrite work against
  OpenAI-SDK-specific classes, not a config change.
- AWS deployment OF THE BOT — local Docker only. The CloudFormation template's
  database bootstrap was still updated to `chatappdb` (3.5), because a stack
  brought up with the old names would fail every query against a database that
  was never created, pointing at nothing.
- Offline message CONTENT re-delivery / a full offline-message queue — the
  reconnect-time delivery sweep (§4) closes the DELIVERY-STATUS gap (a
  reconnecting recipient's pending messages get marked delivered, and the
  original sender gets live-notified if reachable), but message CONTENT
  itself is still only ever delivered via `GET /conversations/{otherUserId}/messages`
  — never re-pushed live to a reconnecting recipient as a fresh
  `incoming_message`. (This bullet corrects a previously stale
  cross-reference: §3.1 used to cite "no offline-message-queue, explicitly
  out of scope, section 5" while this section had no such line at all.)

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
7. Angular frontend, first pass — covers everything built through step 6: register form,
   login form, user list with "Start Chat" buttons, and a basic chat window (send/receive
    + single tick visible live). This is the first end-to-end demoable milestone — nothing
      visual existed before this point; all prior steps were verified via Postman/scripts only.
8. Kafka async persistence
9. Double tick
10. Refresh token flow
11. Load testing to validate single-instance connection capacity
12. AWS deployment (single instance first, ALB/ASG later if justified by #11)
13. Angular frontend, second pass — double tick's visual state was already bundled into
    step 9 directly (seeing it live immediately mattered more than waiting for this pass).
    Remaining scope: wire AuthService to call `POST /refresh` when the access token
    expires (the WebSocket connection force-disconnects at that point, per step 5's
    server-enforced-expiry design — CLAUDE.md 3.3), store the newly-issued access +
    refresh token pair, then reconnect the WebSocket with the fresh access token.
14. Message history — `GET /conversations/{otherUserId}/messages` (see §4), fetched
    by ChatComponent on open before the live WebSocket connection is made, so a
    reopened chat shows its past messages instead of starting empty every time.
15. Delivery-status correctness fix (see §3.1/§4) — closes two gaps found once
    message history made delivery status something durably read back, not just
    a live-only signal: the delivered_ack/Kafka-insert persistence race (fixed
    structurally via same-partition-key Kafka ordering) and messages sent while
    the recipient was fully offline (fixed via a reconnect-time delivery sweep).
16. Chat UI polish, first pass — a back/list link in `ChatComponent`'s header
    (`routerLink`), the chat header showing the other user's real first+last
    name (forwarded via Angular Router navigation state from `UserListComponent`'s
    `startChat()`, since `GET /users` already has it on hand — no extra lookup
    call), and WhatsApp-style message timestamps (time-only for today, date +
    time for older). The timestamp needed one small backend addition:
    `IncomingChatMessage` (the live WebSocket envelope) gained a `sentAt`
    field it didn't have before — `ChatWebSocketHandler.handleChatMessage`
    now mints exactly ONE `Instant.now()` per message and shares it with both
    the Kafka-persisted event and this live envelope, so a message's
    live-delivered timestamp and its later history-read timestamp are always
    identical, not two independent clock reads that could disagree by a few
    milliseconds. `ConversationMessageResponse.sentAt` (history) already
    existed from step 14 and needed no change.
17. Split-pane chat layout + scroll-back pagination.
    - **Split view: a parent route with a child outlet, not one component
      managing both panels' state.** A new `ChatShellComponent` renders a
      persistent sidebar (`UserListComponent`, reused essentially as-is) and
      a `<router-outlet>` for the right panel; `app.routes.ts` nests
      `chat/:userId` (rendering `ChatComponent`, also reused as-is) and an
      empty-path placeholder route (`chat` with no id — "select a
      conversation") as children of a `chat` parent route. Chosen over a
      single fat component because it keeps `UserListComponent` and
      `ChatComponent` as independent, mostly-unchanged classes (this pass's
      explicit reuse goal) while preserving what routing already buys the
      app for free: a specific open conversation stays a real, bookmarkable,
      refreshable URL (`/chat/:userId`), consistent with how every other
      screen in this app already works. The old standalone `/users` route
      now redirects to `/chat` — the sidebar's presence there makes a
      separate full-page user list redundant.
      - **Load-bearing gotcha this surfaces**: Angular's default
        `RouteReuseStrategy` reuses the same routed component instance when
        only a URL param changes on the same route config — clicking a
        different sidebar user does NOT destroy/recreate `ChatComponent`,
        so `ngOnInit` (which used to read `recipientId` once from the route
        snapshot and fetch history exactly once) does not re-run on switch.
        Fixed by making `recipientId`/`recipientLabel`/history-loading all
        reactive to `ActivatedRoute.paramMap` instead of a one-time
        snapshot read, guarded by a monotonically increasing generation
        counter so a slow, now-stale history response for a
        since-abandoned user switch can't overwrite the newly selected
        conversation's (already-cleared) bubble list.
      - **Live-message ordering across a switch, accepted tradeoff**: the
        WebSocket connection is now genuinely persistent across chat
        switches (one connection for the whole login session, not
        reconnected per conversation), so a live `incoming_message` for the
        conversation being switched INTO can arrive during that
        conversation's brief history-fetch round trip. Rather than
        buffering live messages per-conversation (real complexity for a
        narrow, sub-second window), a live append is simply gated on that
        conversation's history having already loaded; a message arriving
        in that gap is dropped from the LIVE view only — it's already
        durably persisted via Kafka regardless, and self-heals the next
        time that conversation is opened. Same "named, accepted, harmless,
        self-healing" posture this project already uses elsewhere (e.g.
        the reconnect-sweep's concurrent-double-sweep race, §4) rather than
        building new machinery to close a window this narrow.
    - **Pagination: see §4's `GET /conversations/{otherUserId}/messages`
      entry for the cursor-vs-offset decision.** On the frontend,
      `ChatComponent`'s message list fires a scroll handler that requests
      the next page once the user scrolls within a small threshold of the
      top, guarded against concurrent/repeated fetches and against firing
      again once `hasMore` is false.
      - **Scroll-position preservation on prepend**: naively prepending
        older messages above the currently visible content, then leaving
        `scrollTop` untouched, visually yanks the view down to the newly
        taller top of the list — the user loses their place. Fixed with
        the standard technique: capture the container's `scrollHeight`
        (and `scrollTop`) immediately before the prepend, then once the
        browser has actually laid out and painted the new content
        (`requestAnimationFrame`, not a plain signal update or
        `setTimeout(0)` — both can fire before layout/paint have caught
        up), set `scrollTop = newScrollHeight - oldScrollHeight + oldScrollTop`.
        That's exactly the height the content grew by, so the same pixels
        the user was already looking at stay in view.
      - **Bundled, closely-related fix**: initial history load now scrolls
        the message list to the bottom once rendered. Previously (step 14)
        a conversation with enough messages to overflow the container
        opened scrolled to the TOP (the browser's default for a freshly
        rendered scrollable div) rather than the most recent message —
        harmless with short test conversations, but impossible to miss
        once pagination makes 50+ message conversations the normal case
        being tested here, so it's fixed as part of this same pass rather
        than filed separately.
18. DoctorAssistant appointment bot, Version 1 — no tool calling (see 3.9).
    Consolidates userdb + messagedb into `chatappdb` first (3.5), then adds the
    bot as a real BOT-typed `users` row, four new tables, and one routing branch
    in `ChatWebSocketHandler`. No frontend change of any kind. Version 2's tool
    calling is deliberately deferred until the naive approach's cost is
    measurable in `bot_token_usage` rather than asserted (§5).
