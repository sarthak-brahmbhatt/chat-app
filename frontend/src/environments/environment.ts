/**
 * DEVELOPMENT environment (the default — this is the file compiled into any
 * build that doesn't explicitly swap it out).
 *
 * Angular's `fileReplacements` mechanism (see angular.json's production
 * configuration) is what substitutes environment.production.ts for this file
 * when building with `--configuration production`. Nothing imports the
 * production file directly; everything imports THIS path and the build
 * rewrites it. That's why the two files must always export the same shape.
 *
 * Why these specific ports (8081/8082, not 8080): user-service and
 * chat-service both default to server.port: 8080 when run NATIVELY
 * (IntelliJ/gradle bootRun) — see each service's application.yml — so only
 * ONE of them can run natively at a time without a port clash. Docker
 * Compose is what maps them to distinct HOST ports (see docker-compose.yml),
 * and a real end-to-end frontend session needs BOTH services at once. So:
 * run `docker compose up` before using this app locally.
 */
export const environment = {
  production: false,
  userServiceBaseUrl: 'http://localhost:8081',
  chatServiceWsUrl: 'ws://localhost:8082/ws/chat',
};
