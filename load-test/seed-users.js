#!/usr/bin/env node

/**
 * Seeds test users for build-order step 11's WebSocket load test (CLAUDE.md
 * 3.6). Registers N users through the REAL /register endpoint (not a direct
 * DB insert) so each one exists exactly the way a real user would, then logs
 * each in through the REAL /login endpoint to obtain a genuine access-token
 * JWT — the k6 script (ws-ramp-test.js) needs one real, chat-service-
 * verifiable JWT per simulated connection to perform the CLAUDE.md 3.1
 * "raw token as first WS message" auth handshake.
 *
 * Idempotent by design: rerunning against a DB that already has these users
 * is fine. /register replying 400 (duplicate username) is treated as "this
 * user already exists from a previous seeding run," not a failure — this
 * script only needs a valid login at the end, not a fresh row.
 *
 * Usage:
 *   node seed-users.js [count] [outputFile]
 *   node seed-users.js 1000 load-test/users.json
 *
 * Requires user-service reachable at USER_SERVICE_URL (default matches
 * docker-compose.yml's published port, 8081 -> container's 8080).
 */

const USER_SERVICE_URL = process.env.USER_SERVICE_URL || 'http://localhost:8081';
const COUNT = parseInt(process.argv[2] || '1000', 10);
const OUTPUT_FILE = process.argv[3] || 'load-test/users.json';

// Deliberately modest, not "as many as possible": /register and /login each
// pay a real ~100ms bcrypt cost per call (see AuthService's timing-safety
// design, CLAUDE.md 3.3) — a large concurrency here would just make
// user-service itself the bottleneck while seeding, which has nothing to do
// with chat-service's WebSocket capacity, the actual thing step 11 measures.
const CONCURRENCY = 20;

const PASSWORD = 'load-test-password-not-a-real-credential';

async function registerAndLogin(index) {
  const username = `loadtest-user-${index}`;

  const registerResponse = await fetch(`${USER_SERVICE_URL}/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      password: PASSWORD,
      firstName: 'Load',
      lastName: `Test${index}`,
    }),
  });

  // 201 = created this run; 409 = already exists from an earlier run of this
  // script (duplicate username, per user-service's GlobalExceptionHandler —
  // 409 Conflict, not 400, since the request itself is well-formed and the
  // conflict is with existing server state, not the input) — both are fine
  // to proceed from. Anything else means something about user-service itself
  // is actually broken, and should stop the whole seeding run rather than
  // silently produce a short users.json that then makes the k6 run's results
  // misleading.
  if (registerResponse.status !== 201 && registerResponse.status !== 409) {
    throw new Error(
      `register ${username} failed: ${registerResponse.status} ${await registerResponse.text()}`,
    );
  }

  const loginResponse = await fetch(`${USER_SERVICE_URL}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: PASSWORD }),
  });

  if (loginResponse.status !== 200) {
    throw new Error(
      `login ${username} failed: ${loginResponse.status} ${await loginResponse.text()}`,
    );
  }

  const { accessToken } = await loginResponse.json();
  return { username, accessToken };
}

async function main() {
  console.log(`Seeding ${COUNT} users against ${USER_SERVICE_URL} (concurrency ${CONCURRENCY})...`);

  const results = [];
  let nextIndex = 0;
  let completed = 0;

  // A small fixed-size worker pool, not Promise.all(everything at once) —
  // COUNT can be in the thousands, and firing that many concurrent HTTP
  // requests simultaneously would just move the bottleneck from "slow" to
  // "user-service falls over," which isn't what this script is testing.
  async function worker() {
    while (nextIndex < COUNT) {
      const index = nextIndex++;
      const result = await registerAndLogin(index);
      results.push(result);
      completed++;
      if (completed % 50 === 0 || completed === COUNT) {
        console.log(`  ${completed}/${COUNT} users ready`);
      }
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));

  const fs = await import('node:fs/promises');
  await fs.writeFile(OUTPUT_FILE, JSON.stringify(results, null, 2));
  console.log(`Wrote ${results.length} user tokens to ${OUTPUT_FILE}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
