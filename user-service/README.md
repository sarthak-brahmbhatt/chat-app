# user-service

The identity service: register, log in, refresh, list users. **Stateless
HTTP** — it holds nothing in memory between requests, so any instance can serve
any request. It is the **only writer** to the `users` table (chat-service reads
it, never writes) and the only issuer of tokens.

Port `8081` locally (`8080` inside the container). Full design reasoning:
[`CLAUDE.md`](../CLAUDE.md) §3.2 (boundaries), §3.3 (auth).

## Endpoints

| Method | Path | Auth | Does |
|---|---|---|---|
| `POST` | `/register` | none | Create a user; `400` on missing fields, `409` on a taken username |
| `POST` | `/login` | none | Verify credentials; issue access + refresh token pair |
| `POST` | `/refresh` | refresh token in body | Rotate the refresh token; issue a fresh pair |
| `GET` | `/users` | `Bearer` access token | Every user (including bots and the caller — the frontend filters itself out) |
| `GET` | `/actuator/health` | none | Liveness incl. MySQL + Redis |

Everything not in the exclusion list (`/register`, `/login`, `/refresh`,
`/actuator/**`) is guarded by `JwtAuthenticationInterceptor` — deny by
default, so an endpoint added later is protected without remembering to.

## Architecture

```mermaid
flowchart LR
    C["Client"] -->|HTTP| I["JwtAuthenticationInterceptor\n(excludes register/login/refresh)"]
    I --> AC["AuthController\n/login /refresh"]
    I --> UC["UserController\n/register /users"]
    AC --> AS["AuthService"]
    UC --> US["UserService"]
    AS --> JS["JwtService\nHS256, 15 min"]
    AS --> RT["RefreshTokenService\nrotate · detect reuse"]
    AS --> PE["PasswordEncoder\nbcrypt"]
    US --> PE
    US --> UR["UserRepository"]
    AS --> UR
    UR --> DB[("MySQL chatappdb\nusers")]
    RT --> R[("Redis\nrefresh-token:sha256\nrefresh-family:id")]
    B["BotUserSeeder\n(at startup)"] --> UR
```

| Package | Holds |
|---|---|
| `controller` | `AuthController`, `UserController` — HTTP in, DTO out, nothing else |
| `service` | `AuthService` (login, refresh), `UserService` (register, list) |
| `security` | `JwtService` (mint/parse), `RefreshTokenService` (Redis registry), `JwtAuthenticationInterceptor` |
| `entity` / `repository` | `User` + `UserType` (`USER`, `BOT`, `BOT_TOOL`), `UserRepository` |
| `bootstrap` | `BotUserSeeder` — idempotently seeds the two bot users at boot with an unloginnable password |
| `config` | `PasswordEncoderConfig`, `WebMvcConfig` (interceptor registration + CORS) |
| `exception` | `GlobalExceptionHandler` → `400` / `401` / `409` with an `ErrorResponse` body |

## Sequence diagrams

### Register

```mermaid
sequenceDiagram
    participant C as Client
    participant UC as UserController
    participant US as UserService
    participant DB as MySQL
    C->>UC: POST /register {username, password, firstName, lastName}
    UC->>US: register(request)
    US->>DB: existsByUsername?
    alt taken
        US-->>C: 409 "Username 'x' is already taken"
    else free
        US->>US: bcrypt(password)
        US->>DB: INSERT user (user_type USER)
        US-->>C: 201 {id, username, firstName, lastName}
    end
    Note over C,DB: No tokens issued — registering does not log you in
```

### Login — two tokens, two jobs

```mermaid
sequenceDiagram
    participant C as Client
    participant AC as AuthController
    participant AS as AuthService
    participant DB as MySQL
    participant R as Redis
    C->>AC: POST /login {username, password}
    AC->>AS: login(request)
    AS->>DB: findByUsername
    AS->>AS: bcrypt.matches (always runs, even for unknown users — timing-safe)
    alt mismatch or unknown user
        AS-->>C: 401 "Invalid username or password"
    else ok
        AS->>AS: JWT access token (sub = userId, 15 min)
        AS->>R: SET refresh-token:sha256(RT1) = {userId, familyId, rotated:false} TTL 7d
        AS->>R: SET refresh-family:familyId = sha256(RT1)
        AS-->>C: 200 {accessToken, refreshToken, expiresInSeconds}
    end
```

The access token is a signed JWT: verified by signature alone, no lookup,
which is why it must expire quickly — nothing can revoke it early. The
refresh token is 256 random bits, stored only as its SHA-256, and checked
against Redis on every use — which is exactly what makes it revocable.

### Refresh — rotation, and what a replay triggers

```mermaid
sequenceDiagram
    participant C as Client
    participant AS as AuthService
    participant RT as RefreshTokenService
    participant R as Redis
    C->>AS: POST /refresh {refreshToken: RT1}
    AS->>RT: rotate(RT1)
    RT->>R: GET refresh-token:sha256(RT1)
    alt not found or expired
        RT-->>C: 401 "Invalid refresh token"
    else found, rotated = true (already used once)
        Note over RT,R: Theft signal — server cannot tell victim from attacker
        RT->>R: DEL currently-valid token for the family
        RT->>R: DEL refresh-family:familyId
        RT-->>C: 401 "Invalid refresh token" (identical message, no signal)
    else found, valid
        RT->>R: mark RT1 rotated = true (kept, not deleted — so a replay is detectable)
        RT->>R: SET sha256(RT2) for the same family, TTL 7d
        RT->>R: refresh-family:familyId = sha256(RT2)
        AS-->>C: 200 {new access token, RT2}
    end
```

### An authenticated request

```mermaid
sequenceDiagram
    participant C as Client
    participant I as JwtAuthenticationInterceptor
    participant JS as JwtService
    participant UC as UserController
    C->>I: GET /users  Authorization: Bearer <access token>
    I->>JS: parseToken — verify HS256 signature + expiry
    alt invalid or expired
        I-->>C: 401
    else valid
        I->>UC: proceed (userId from sub claim)
        UC-->>C: 200 {users: [...], message}
    end
    Note over I,JS: No database, no Redis — stateless by design
```

## Running and testing

```bash
docker compose up -d user-service          # from the repo root; needs mysql + redis
./gradlew test                             # 34 tests: controllers (MockMvc), services, JWT, refresh rotation + reuse
```

Configuration is `${ENV_VAR:default}` in `src/main/resources/application.yml`;
`JWT_SECRET` must be byte-identical to chat-service's, which docker-compose
guarantees with a shared YAML anchor.
