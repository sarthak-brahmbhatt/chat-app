/**
 * Step 11 — WebSocket connection-capacity load test for chat-service
 * (CLAUDE.md 3.6, build-order step 11).
 *
 * WHAT THIS VALIDATES: the theoretical capacity reasoning in CLAUDE.md 3.6 —
 * default Spring Boot/Tomcat is thread-per-connection, roughly 1MB of thread
 * stack per open WebSocket, so a small instance should hit a connection
 * ceiling well before 10,000. This script ramps real concurrent WebSocket
 * connections upward in fixed steps against the Dockerized chat-service,
 * holding each one open, so the point where new connections start failing
 * (or the container's CPU/memory visibly saturates, see
 * capture-docker-stats.sh) can be observed directly instead of guessed at.
 *
 * WHY k6: this needs to (a) open many real concurrent WebSocket connections,
 * not simulate them, (b) speak our EXACT handshake — a raw JWT string as the
 * literal first WS frame, no envelope, per CLAUDE.md 3.1 — which requires
 * actual per-connection scripting, not a declarative/YAML load profile, (c)
 * hold connections open rather than immediately closing them, and (d) ramp in
 * controlled steps while exposing failure counts as first-class metrics.
 * Artillery's WebSocket engine could technically be coerced into this via its
 * processor-function escape hatch, but the connection lifecycle (open, send
 * one raw frame, hold, close) is exactly what k6's `k6/ws` module is built
 * for directly — no escape hatch needed. Gatling/Locust were also considered
 * (both listed alongside these in CLAUDE.md 3.6) but neither has k6's
 * combination of native WebSocket scripting + a built-in stepped-ramp
 * executor without extra plumbing.
 *
 * SCOPE CAVEAT (see also the new CLAUDE.md section this run's results are
 * documented in): this runs against chat-service in Docker Desktop on a
 * single Mac, constrained only by the memory/cpu ceiling this project's own
 * docker-compose.yml sets (deploy.resources.limits, added alongside this
 * script). Docker Desktop's resource allocation on a laptop has NO fixed
 * relationship to a real EC2 instance's actual CPU/memory/network
 * characteristics. The absolute connection count this run finds is a
 * methodology validation for THIS environment, not an AWS sizing number —
 * the real sizing exercise happens once this is rerun against an actual
 * deployed instance (build-order step 12+).
 *
 * USAGE:
 *   node seed-users.js 1000 load-test/users.json   # once, or whenever more
 *                                                    # headroom is needed
 *   k6 run load-test/ws-ramp-test.js
 *
 * Tunable via environment variables (all optional, see defaults below):
 *   CHAT_WS_URL     ws://localhost:8082/ws/chat (8082 = docker-compose's
 *                   published port for chat-service's container port 8080)
 *   USERS_FILE      load-test/users.json
 *   STEP_SIZE       how many additional concurrent connections per step
 *   STEP_HOLD       how long to hold at each step's plateau before ramping
 *                   to the next one (gives docker stats time to settle and
 *                   be sampled at a representative, non-transient value)
 *   MAX_VUS         the ceiling this run ramps toward, if nothing breaks
 *                   first
 *   HOLD_SECONDS    how long an individual connection stays open once
 *                   established, in the absence of an error — deliberately
 *                   set well beyond this test's total planned duration by
 *                   default, so connections accumulate across steps instead
 *                   of self-closing partway through and understating the
 *                   concurrent count at later steps.
 */

import ws from 'k6/ws';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const CHAT_WS_URL = __ENV.CHAT_WS_URL || 'ws://localhost:8082/ws/chat';
// k6's open() resolves relative paths relative to the SCRIPT file's own
// directory, not the shell's current working directory — './users.json'
// here means load-test/users.json, since this script itself lives in
// load-test/.
const USERS_FILE = __ENV.USERS_FILE || './users.json';
const STEP_SIZE = parseInt(__ENV.STEP_SIZE || '50', 10);
const STEP_HOLD = __ENV.STEP_HOLD || '15s';
const MAX_VUS = parseInt(__ENV.MAX_VUS || '1000', 10);
const HOLD_SECONDS = parseInt(__ENV.HOLD_SECONDS || '900', 10);

// SharedArray loads the file once and shares it read-only across all VUs
// (k6 VUs are separate JS runtimes) instead of every VU re-reading/
// re-parsing the JSON independently.
const users = new SharedArray('users', function () {
  const data = JSON.parse(open(USERS_FILE));
  if (data.length < MAX_VUS) {
    // Not a hard failure — k6 script init runs before any HTTP/WS traffic,
    // so this is the only place a loud warning is guaranteed to be seen
    // before the run gets underway. Tokens will simply be reused (VU index
    // modulo pool size) if the pool is smaller than MAX_VUS; that's a
    // correctness compromise (see the modulo below), not a crash.
    console.warn(
      `users.json has ${data.length} users but MAX_VUS is ${MAX_VUS} — tokens will be reused across VUs. ` +
      `Run: node load-test/seed-users.js ${MAX_VUS} ${USERS_FILE}`,
    );
  }
  return data;
});

// Custom metrics k6's default summary doesn't give us out of the box — these
// are what actually answer "where did it break": a rising ws_connect_failure
// count (refused/errored before the handshake even completed) is a
// different failure mode than a rising ws_auth_rejected count (connection
// opened fine, but chat-service's JwtValidator rejected the token and closed
// with 1008) — the latter would indicate a test-harness bug (bad/expired
// token), not a capacity ceiling, so keeping them separate matters for
// reading the results correctly.
const connectSuccess = new Counter('ws_connect_success');
const connectFailure = new Counter('ws_connect_failure');
const authRejected = new Counter('ws_auth_rejected');
const connectDuration = new Trend('ws_connect_duration', true);

function buildStages() {
  const stages = [];
  for (let target = STEP_SIZE; target <= MAX_VUS; target += STEP_SIZE) {
    // Two stages per step: a short ramp UP to the new target, then a hold AT
    // that target. The hold is what gives docker stats (sampled by the
    // companion capture-docker-stats.sh, run in parallel) a stable window to
    // capture a representative reading for that specific connection count,
    // rather than only ever seeing numbers mid-ramp.
    stages.push({ duration: '5s', target });
    stages.push({ duration: STEP_HOLD, target });
  }
  return stages;
}

export const options = {
  scenarios: {
    ramp_ws_connections: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: buildStages(),
      // How long an already-started VU is given to finish its current
      // iteration when the target VU count drops (not really exercised here
      // since this script only ramps up, but required by the executor) and
      // when the whole test ends.
      gracefulRampDown: '5s',
      gracefulStop: '10s',
    },
  },
};

export default function () {
  const user = users[(__VU - 1) % users.length];
  const startedAt = Date.now();

  const res = ws.connect(CHAT_WS_URL, {}, function (socket) {
    socket.on('open', function () {
      // Per CLAUDE.md 3.1: the FIRST message on every connection is always
      // the raw JWT access token string, no JSON envelope — this is the
      // exact handshake real Angular clients perform, not a simplified
      // stand-in for it.
      socket.send(user.accessToken);
      connectDuration.add(Date.now() - startedAt);
      connectSuccess.add(1);
    });

    // chat-service has no explicit "auth succeeded" acknowledgment (CLAUDE.md
    // 3.1 doesn't define one — see ChatWebSocketHandler.authenticate()); a
    // successful handshake is inferred from the socket simply staying open.
    // A close with code 1008 (Policy Violation) is JwtValidator explicitly
    // rejecting the token (see ChatWebSocketHandler.authenticate()'s catch
    // block) — the one unambiguous failure signal available to observe from
    // the client side.
    socket.on('close', function (code) {
      if (code === 1008) {
        authRejected.add(1);
      }
    });

    socket.on('error', function () {
      connectFailure.add(1);
    });

    // Holds the connection open rather than closing it right after auth —
    // this is what makes concurrently-open connections actually accumulate
    // across ramp steps instead of each VU opening and immediately
    // disconnecting. Torn down early by gracefulRampDown/gracefulStop if the
    // test ends first.
    socket.setTimeout(function () {
      socket.close();
    }, HOLD_SECONDS * 1000);
  });

  // A non-101 (or entirely absent) response means the handshake itself never
  // completed — connection refused, timed out, or the server otherwise
  // rejected the WebSocket upgrade before ChatWebSocketHandler ever ran.
  // This is the "new connections start failing" signal step 11 is looking
  // for at the capacity ceiling.
  const handshakeOk = check(res, {
    'WS handshake returned 101': (r) => r && r.status === 101,
  });

  if (!handshakeOk) {
    connectFailure.add(1);
  }
}
