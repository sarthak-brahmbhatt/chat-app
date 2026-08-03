/**
 * Backend base URLs, hardcoded rather than using Angular's
 * environment.ts/environment.prod.ts build-time file-replacement mechanism —
 * that machinery is for swapping config between build TARGETS (dev build vs.
 * prod build); this app only has one target so far, so a plain constants
 * file is simpler and avoids setting up infrastructure this pass doesn't
 * need yet.
 *
 * Why these specific ports (8081/8082, not 8080): user-service and
 * chat-service both default to server.port: 8080 when run NATIVELY
 * (IntelliJ/gradle bootRun) — see each service's application.yml — so only
 * ONE of them can run natively at a time without a port clash. Docker
 * Compose is what maps them to distinct HOST ports (8081, 8082 — see
 * docker-compose.yml), and since a real end-to-end frontend session needs
 * BOTH services running AT ONCE, Docker Compose is the only way to do that
 * today without extra manual config. So: run `docker compose up` before
 * using this app.
 */
export const USER_SERVICE_BASE_URL = 'http://localhost:8081';
export const CHAT_SERVICE_WS_URL = 'ws://localhost:8082/ws/chat';
