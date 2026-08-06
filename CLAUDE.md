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
    arrives**, the double-tick is silently dropped — there is no live
    connection left to deliver it over, and this is not a new gap: with no
    offline-message-queue (explicitly out of scope, section 5), double tick
    was already only ever meaningful for a sender who's still connected to
    receive it. If the recipient hadn't been connected at delivery time
    either, the message was never delivered live in the first place, so
    there'd have been nothing to double-tick regardless of whether the
    sender stuck around.

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
- **Step 11 load test results — empirically validates (and partly corrects) the
  capacity reasoning above.** Tool: **k6**, chosen over Artillery/Gatling/Locust
  because it can script the EXACT connection lifecycle this test needs directly
  (open a real WebSocket, send one raw-string frame as the CLAUDE.md 3.1 auth
  handshake — no envelope — hold the connection open, observe the close code),
  via its native `k6/ws` module, combined with a built-in stepped-ramp executor
  (`ramping-vus`) — no YAML/processor-function workaround needed the way
  Artillery's WebSocket engine would require. Test users were seeded through the
  REAL `/register` + `/login` endpoints (not a DB bypass), so each simulated
  connection carries a genuine chat-service-verifiable JWT. Script + companion
  `docker stats` sampler are committed at `load-test/` (`seed-users.js`,
  `ws-ramp-test.js`, `capture-docker-stats.sh`) as a reusable artifact — rerun
  the same way against the real AWS deployment once step 12 lands, for actual
  sizing numbers.

  **SCOPE CAVEAT (per the task that requested this): these are Docker Desktop
  numbers, on one Mac, NOT AWS-representative.** chat-service was run under an
  explicit, arbitrary 512MB memory / 1 CPU ceiling (`docker-compose.yml`'s
  `deploy.resources.limits` — added specifically so this test would have a
  ceiling to hit at all; Docker Desktop's own VM has no fixed relationship to
  any real EC2 instance type). The absolute connection count below is a
  methodology/failure-pattern validation for THIS environment, not a number to
  plan AWS capacity around — that exercise happens once this script is rerun
  against a real deployed instance.

  - **First pass (1000 concurrent connections, ramped 50 at a time): zero
    failures.** All 1000 WebSocket connections authenticated successfully;
    chat-service's actual memory usage grew from a ~246MB baseline (JVM +
    Spring context + Kafka consumer, before any test traffic) to only ~337MB
    (66% of the 512MB ceiling) at 1000 concurrent connections — roughly 90-100KB
    of REAL (resident) memory per connection, not the ~1MB assumed above. CPU
    only spiked (briefly, to under 90%) during each ramp-up burst, then idled
    near 0% at every plateau. This run did not find a ceiling at all.
  - **Second pass (ramped toward 3000, 100 at a time): ceiling found around
    ~1300-1400 concurrent connections** (chat-service's own logs show 1328
    successful auth handshakes, zero closes, zero rejections, in the window
    immediately before the crisis below started) — but NOT via total container
    memory filling up. At the moment of failure, total container memory (RSS)
    was still only ~338MB of the 512MB ceiling (66%) — comfortable headroom by
    that measure. What actually broke was the **JVM's heap specifically**:
    Java's container-aware default ergonomics caps max heap at 25% of the
    container's memory limit (confirmed via `-XX:+PrintFlagsFinal`:
    `MaxHeapSize` = 128MB for this 512MB container, `MaxRAMPercentage` = 25,
    default) — so only 128MB, not 512MB, was ever available as heap, and IT is
    what filled up first, well before the broader container ceiling. The
    result was repeated `java.lang.OutOfMemoryError: Java heap space` across
    HTTP/WebSocket acceptor and worker threads, sustained CPU pegged at
    ~100-105% (the classic GC-thrashing "death spiral" — the JVM endlessly
    running GC trying to free heap it can't, at the cost of doing any other
    work), and cascading failure of essentially every subsequent connection
    attempt (k6: `ws_connect_success` = 1349, `ws_connect_failure` = 12746 —
    the failure count is inflated well past the true ceiling by k6's
    `ramping-vus` executor immediately retrying with a new connection attempt
    every time a VU's fast-failing iteration completed, a test-harness
    amplification effect worth naming, not a second independent finding).
  - **It did not self-recover.** Minutes after the offending load stopped and
    every test connection had disconnected, chat-service was still resetting
    new connections and logging fresh `OutOfMemoryError`s — a full container
    restart (`docker restart`) was required to bring it back to a healthy
    state. A JVM that has genuinely exhausted its heap under this kind of
    sustained load does not degrade gracefully back to normal on its own here.
  - **What this corrects vs. the theoretical reasoning above**: the assumed
    bottleneck — ~1MB of thread-stack memory per connection accumulating
    toward the instance's total memory — was NOT what actually happened. Real
    per-connection memory overhead measured far lower (~90-100KB), consistent
    with Tomcat's NIO connector not pinning a dedicated blocking OS thread to
    every idle WebSocket connection the naive thread-per-connection model
    assumes. The ceiling that WAS hit is heap object churn (WebSocket session
    state, Jackson JSON buffers, connection-registry entries, Tomcat's
    internal per-connection structures) exhausting a heap that was already
    artificially small — 25% of container memory by JVM default — not the
    container's own memory ceiling. **Practical, immediately-actionable
    implication for real sizing (step 12)**: an instance sized purely by total
    RAM, without also explicitly raising `-Xmx`/`-XX:MaxRAMPercentage` past the
    25% default, will hit its real ceiling far earlier than its advertised
    memory would suggest. Configuring `-XX:+ExitOnOutOfMemoryError` (or an
    orchestrator health check that detects a JVM wedged in this state) so a
    real deployment restarts automatically instead of silently serving from a
    permanently-degraded instance is also now a concrete, evidence-backed
    recommendation rather than boilerplate advice.
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
    direct `ec2:RunInstances` call** (revised after hitting this in
    practice). The original `deploy-user-service.yml` bypassed
    CloudFormation entirely — patched the Launch Template's UserData
    directly via the EC2 API, then called `run-instances` /
    `register-targets` / `deregister-targets` / `terminate-instances` by
    hand to swap the running instance. That put two systems in charge of
    the same resource: a later CloudFormation stack update (e.g. rotating
    MysqlRootPassword/JwtSecret) would ALSO try to replace
    `UserServiceInstance`, and the workflow's own hand-launched instance —
    invisible to CloudFormation, since it was never created through it —
    would linger as an orphan neither system cleaned up. Confirmed live,
    not theoretical: this is exactly what produced two simultaneously
    running, simultaneously-healthy user-service instances after a secrets
    rotation collided with a workflow run.
  - **The fix works because of one specific property wiring, not because
    "a stack update" is magic.** `UserServiceInstance.Properties.LaunchTemplate.Version`
    is `!GetAtt UserServiceLaunchTemplate.LatestVersionNumber`, not a
    pinned number or `$Default`. Changing `UserServiceImageTag` causes
    `Fn::Sub` to render different `LaunchTemplateData`, which creates a new
    Launch Template VERSION (confirmed via `describe-change-set`:
    `UserServiceLaunchTemplate [Replacement: False]` — a LaunchTemplate
    resource is never "replaced," a new version is just added).
    `LatestVersionNumber` changes as a result, which changes
    `UserServiceInstance`'s own `LaunchTemplate.Version` PROPERTY within
    the SAME update — and that property is `recreation: Always` for
    `AWS::EC2::Instance` (also confirmed via `describe-change-set`, then
    watched happen: `i-0bbc3604e498eefe5` → `i-02fc3a9650973b0ca` on
    execute). Bumping which version is `$Default` would NOT have been
    enough on its own — `$Default` only matters for launches that don't
    pin a version explicitly, which this instance never does. It's the
    `$Latest` wiring specifically that makes a plain parameter change
    self-sufficient here.
  - **`deploy-user-service.yml` now does exactly one AWS-mutating thing**:
    fetches the live stack's current parameter keys (not hardcoded, so it
    can't drift from the template's own Parameters section), builds a
    parameters file setting only `UserServiceImageTag` to the new
    short-SHA tag with `UsePreviousValue: true` for every other key, and
    calls `cloudformation/deploy.sh deploy`. No more direct
    `run-instances`/target-group calls, and therefore no more path for
    this workflow to create an instance CloudFormation doesn't know about.
    `UsePreviousValue` on every other parameter is load-bearing, not
    boilerplate: without it, an update that only specifies
    `UserServiceImageTag` would reset every unlisted parameter to the
    TEMPLATE's default — silently wiping `MysqlRootPassword`/`JwtSecret`
    back to empty, the exact failure that took the whole stack down
    before (3.3's refresh-token secret-rotation incident).
  - **Accepted tradeoff, not silently dropped**: the hand-built swap this
    replaced explicitly created the new instance, verified it healthy in
    the target group, and only then destroyed the old one —
    zero-downtime by construction. Plain CloudFormation replacement of
    `AWS::EC2::Instance` has no equivalent wait; there's no
    `CreationPolicy`/`cfn-signal` wired into this template, so a bad
    deploy can have a brief window where the old instance is already gone
    before the new one is confirmed healthy. Revisit if that gap ever
    actually matters in practice — not solved here, since it wasn't the
    problem this change was fixing.
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
    `--template-body`** (decided after hitting this in practice). The AWS
    API caps an inline template at 51,200 bytes; `chat-app-stack.yaml`
    crossed that once step 12's hardening comments landed, and both
    `validate-template` and `update-stack` began failing with a generic
    "1 validation error detected" that echoes the entire template back and
    looks nothing like a size limit. Referencing the template from S3
    raises the ceiling to 460,800 bytes, so staging is the fix rather than
    stripping the deliberately-verbose comments this project keeps. The
    staging bucket (`chat-app-cfn-templates-<account-id>`) is private,
    versioned, and encrypted — versioned specifically so every deployed
    template revision stays retrievable for comparison after the fact.
    `cloudformation/deploy.sh` performs the upload on EVERY invocation as
    part of validate/deploy, so the staged copy cannot silently drift from
    the local file the way a separate "remember to upload first" step
    would. Note the bucket itself is deliberately NOT created by either
    template: a template can't live in the bucket that the same template
    creates, so it's a one-time out-of-band resource that intentionally
    survives `delete-stack`.
- **IAM: a scoped-down (not admin) IAM user**, created manually by the
  developer directly in the AWS console — a deliberate human checkpoint, not
  something generated by Claude Code. Its access keys are what later get
  pushed to GitHub Secrets for the CI/CD workflow (deploy-on-merge). Keeping
  IAM user creation manual and out-of-band is intentional: credential
  provisioning is exactly the kind of action that stays a human's call.

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
- `GET /conversations/{otherUserId}/messages` (JWT in header) → Chat service
  fetches the persisted conversation between the authenticated caller and
  `otherUserId` from messagedb, oldest-to-newest → 200 OK + a message list
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
  - **Limit: fixed at the most recent 50 messages, oldest-to-newest within
    that window. No "load more" / pagination in this pass.** Selected via a
    single `DESC ... LIMIT 50` query (`Pageable`), reversed in memory before
    returning — the DB has to select by recency to get the *right* 50 rows,
    even though the response itself reads oldest-first. "Load more" is a
    real, deliberately deferred future item, not an oversight: it raises UX
    questions (prepend-on-scroll-up vs. an explicit button, a cursor/offset
    contract) that haven't been designed yet, and building the mechanics
    ahead of those decisions would be guessing rather than deciding.
  - **A conversation with an `otherUserId` that doesn't correspond to any
    real user returns the exact same response as a real user with no shared
    history: `{"messages": [], "message": "No messages yet."}`.** This is
    deliberate, not an unhandled edge case: messagedb has no users table and
    chat-service has no dependency on user-service for this endpoint (see
    3.2's service-boundary reasoning), so there is structurally no way to
    distinguish "this user doesn't exist" from "this user exists but you've
    never messaged them" without adding a new cross-service call purely to
    validate a path parameter — real, unrequested coupling this pass
    intentionally avoids.
  - **CORS**: a new `WebMvcConfig` (chat-service's first) scopes
    `addCorsMappings` to `/conversations/**` specifically, allowing the same
    origins already trusted for the WebSocket handshake (`localhost:4200`,
    the CloudFront domain, the custom domain) — kept as its own, narrowly
    scoped mapping rather than a blanket `/**` rule, since this is the first
    time chat-service has needed plain-HTTP CORS at all.
  - **Delivered-flag persistence race (accepted, not retried)**: double-tick
    status is written to messagedb via a `markDelivered` bulk `UPDATE`,
    called the instant the live double-tick fires (`ChatWebSocketHandler.
    handleDeliveredAck`). Because Kafka's publish-then-async-consume path
    (3.4) is slower than the delivered_ack's direct socket round trip, this
    `UPDATE` routinely — confirmed empirically during this feature's own
    manual verification, not just a theoretical corner case — finds the
    message's row not yet written, and matches zero rows. When that happens
    the update is silently dropped, not retried or queued: the *live*
    double-tick the sender's screen shows in that moment is unaffected (that
    path never touches the database), but a *later* history fetch can
    correctly show that same message as still single-tick, even though it
    really was delivered live moments earlier. Expect this on nearly any
    fast/local exchange, not just as a rare corner case. Consistent with
    3.4's existing accepted-tradeoff posture on Kafka timing rather than a
    new, inconsistent standard just for this one field; a retry or a
    pending-acks table to close this gap is real, unrequested scope beyond
    what this pass asked for.

## 5. Explicitly out of scope for now

- XMPP / BOSH
- OAuth 2.0 delegation model
- Read receipts (blue tick)
- Detailed HA/DR design
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