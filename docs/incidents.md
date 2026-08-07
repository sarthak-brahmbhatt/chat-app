# Incident log

Condensed postmortems for real problems found and fixed during this project —
each entry is what broke, why, and how it was fixed. CLAUDE.md links here
wherever a current decision exists *because* of one of these; this file is
where the full story lives so CLAUDE.md itself can stay focused on current
state rather than session narrative.

## JVM heap capped by container-memory percentage, not the container ceiling

**When**: build-order step 11, k6 load test against chat-service.

**What broke**: chat-service, running in a Docker container with a 512MB
memory limit, entered an unrecoverable state at ~1,300–1,400 concurrent
WebSocket connections — repeated `java.lang.OutOfMemoryError: Java heap
space`, CPU pegged at ~100-105% (GC thrashing), and it did not self-recover
after the load stopped (needed a manual `docker restart`). At the moment of
failure, total container memory (RSS) was only ~338MB of the 512MB limit
(66%) — comfortable headroom by that measure alone, which made the failure
look unexplained at first.

**Root cause**: Java's container-aware default ergonomics cap max heap at
25% of the container's memory limit (`-XX:MaxRAMPercentage=25`, the
default) unless overridden. Confirmed via `-XX:+PrintFlagsFinal`:
`MaxHeapSize` was 128MB on this 512MB container — so only a quarter of the
container's memory was ever usable as heap, and that quarter (holding
WebSocket session state, Jackson JSON buffers, connection-registry entries)
filled up well before the container's own ceiling did.

**Also corrected**: the original napkin-math assumption (~1MB of
thread-stack memory per connection) was wrong — real measured overhead was
~90-100KB per connection, consistent with Tomcat's NIO connector not
pinning a dedicated blocking thread per idle WebSocket.

**Fix / actionable takeaway**: for real AWS instance sizing (step 12),
explicitly raise `-Xmx`/`-XX:MaxRAMPercentage` past the 25% default, and
configure `-XX:+ExitOnOutOfMemoryError` (or an orchestrator health check)
so a wedged instance restarts automatically instead of silently serving
from a permanently-degraded state.

**Scope caveat**: these are Docker Desktop numbers (512MB/1vCPU, one Mac),
not AWS-representative — `load-test/ws-ramp-test.js` is committed as a
reusable script; rerun it against the real deployed EC2 instance for
actual sizing numbers.

## CloudFormation-bypassing deploy script → orphaned instance + a secrets wipe

**When**: build-order step 12, AWS deployment.

**What broke, in two parts that compounded into one incident**:

1. **Empty-secrets cascade.** A CloudFormation stack update that specified
   only the one parameter being changed (without `UsePreviousValue: true`
   on the rest) reset every *unlisted* parameter to the template's
   default — silently wiping `MysqlRootPassword`/`JwtSecret` back to empty
   and taking the whole stack down.
2. **Two simultaneously running user-service instances.** The original
   `deploy-user-service.yml` bypassed CloudFormation entirely: it patched
   the Launch Template's UserData directly via the EC2 API, then called
   `run-instances` / `register-targets` / `deregister-targets` /
   `terminate-instances` by hand to swap the running instance. When a
   later, legitimate CloudFormation stack update (a secrets rotation) also
   tried to replace `UserServiceInstance`, the hand-launched instance —
   invisible to CloudFormation since it was never created through it —
   lingered as an orphan neither system cleaned up. Confirmed live, not
   theoretical: this produced two simultaneously running, simultaneously
   healthy user-service instances after a secrets rotation collided with a
   workflow run.

**Root cause**: two systems (a hand-rolled deploy script and
CloudFormation) both believed they owned the same EC2 instance resource,
and CloudFormation's own parameter semantics silently discard any
parameter not explicitly told to keep its previous value.

**Fix**: `deploy-user-service.yml` now does exactly one AWS-mutating
thing — a CloudFormation stack update that sets only
`UserServiceImageTag`, with `UsePreviousValue: true` on every other
parameter. This works specifically because
`UserServiceInstance.Properties.LaunchTemplate.Version` is wired to
`!GetAtt UserServiceLaunchTemplate.LatestVersionNumber`, not a pinned
version or `$Default`. Changing the image tag renders different
`LaunchTemplateData`, which creates a new Launch Template *version*
(confirmed via `describe-change-set`: the Launch Template resource itself
is never replaced, a new version is just added) — `LatestVersionNumber`
changes as a result, which changes `UserServiceInstance`'s own
`LaunchTemplate.Version` property within the same update, and that
property has `recreation: Always` for `AWS::EC2::Instance` (also confirmed
via `describe-change-set`, then watched happen: `i-0bbc3604e498eefe5` →
`i-02fc3a9650973b0ca` on execute). Bumping `$Default` alone would not have
been enough — only the `$Latest` wiring makes a plain parameter change
self-sufficient.

**Accepted tradeoff, not solved**: the hand-built swap this replaced
explicitly created the new instance, verified it healthy in the target
group, and only then destroyed the old one — zero-downtime by
construction. Plain CloudFormation replacement of `AWS::EC2::Instance` has
no equivalent wait (no `CreationPolicy`/`cfn-signal` wired into the
template), so a bad deploy can have a brief window where the old instance
is already gone before the new one is confirmed healthy.

**Not affected**: chat-service's ASG Instance Refresh
(`deploy-chat-service.yml`) was never the source of this conflict — only
user-service's bespoke, CloudFormation-bypassing path was.

## CloudFormation inline template size limit

**When**: build-order step 12, after step 12's hardening comments landed
in `chat-app-stack.yaml`.

**What broke**: `aws cloudformation validate-template` and `update-stack`
began failing with a generic "1 validation error detected" that echoed
the entire template back — an error message that looks nothing like a
size limit and gives no direct clue what's wrong.

**Root cause**: the AWS API caps an inline template (`--template-body`)
at 51,200 bytes; `chat-app-stack.yaml` crossed that threshold once the
deliberately-verbose architecture-decision comments this project keeps
were added.

**Fix**: templates are staged in a private, versioned, encrypted S3
bucket (`chat-app-cfn-templates-<account-id>`) and deployed via
`--template-url` instead, which raises the ceiling to 460,800 bytes.
`cloudformation/deploy.sh` re-uploads the template on every
validate/deploy invocation, so the staged copy can never silently drift
from the local file. The staging bucket itself is deliberately *not*
created by either template (a template can't live in the bucket the same
template creates) — a one-time, out-of-band resource that intentionally
survives `delete-stack`.
