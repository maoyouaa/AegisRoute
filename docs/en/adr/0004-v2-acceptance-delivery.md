# ADR 0004: Run acceptance against the v2 reliability topology

Status: implemented locally; remote integration pending. Date: 2026-09-05.

The original acceptance script predates ADR 0003. It starts an already-failing
candidate and attempts successive canary approvals without obtaining new evidence
for each route. Both behaviors conflict with the current evidence gate. Its rollout
API remains `/api/v1/rollouts`; the defect is the test flow, not that API version.

Use `scripts/reliability/run.py` as the common local/CI entrypoint. It creates a
fresh named project, checks all localhost ports, builds with the committed wrapper,
starts `compose.reliability.yml`, and runs actual transport, process/replay and
reconciliation checks. It retains the previous real-process startup/LKG boundary:
cached routes keep serving during Control outage, then restarted Gateways are live
but unready and return `503 ROUTE_SNAPSHOT_UNAVAILABLE` until Control recovers.
Broker outage also requires observed publisher-loss counters and baseline requests
under the existing two-second bound. It exits PostgreSQL and Redpanda PID 1, observes their
restart counters and health, and probes baseline JSON/SSE during automatic recovery.
Measurements are an explicit additional option, not a CI performance threshold.

The PowerShell acceptance command forwards to this entrypoint. Its former
`BaseUrl` and `SkipChaos` parameters are rejected; the command cannot silently
target an existing default stack or skip mandatory faults. Each run uses a new
output directory. A failed run keeps its evidence and volumes; cleanup targets only
the new project. All service URLs and Compose bindings share the same port mapping.

The default Compose Worker gets a UID 10001 writable named volume at
`/var/lib/aegis`. Default Compose remains a baseline smoke topology: dynamic
HOSTNAME Gateway identities can leave historical ACK membership after recreation.
Full v2 lifecycle/convergence acceptance requires the fixed `gateway-a`/`gateway-b`
identities and explicit expected membership in `compose.reliability.yml`. No new
claim is made about elastic Gateway membership.

Keep the GitHub `two-gateway` check name, pinned Actions, read-only permissions,
and full artifact upload. Update path filters to cover scripts, contracts, Gradle
configuration and the workflow itself. Retain the strict <5s online policy rollback
bound through a separate saved-server-timestamp verifier; the deliberate offline
manual rollback is checked for correct pending state instead.
PR #13 is a sibling branch with a separate
release-candidate workflow; its old default-topology invocation must be reconciled
when that PR is integrated. This change does not merge or authorize that workflow.

No Java serving behavior, evidence policy, public event schema or migration changes
are introduced by this delivery adjustment. The original frozen acceptance hashes
remain historical. A curated bundle retains original artifact hashes and can
recompute counts and measurements offline; delivery validation is recorded separately.

A fresh Windows/Docker rerun exposed a client-clock alignment assumption: a burst
labelled 07:20:00 by the host was admitted as 38 requests in the preceding server
window and two in the next. The correct latest-window policy refused advancement.
The driver now uses the localhost inspector HTTP Date (Docker clock), starts inside
the next server window and retains the existing count/freshness assertions. It does
not relax policy or accept the older eligible window to turn the failure green.

The next run passed the process scenario but detected a torn final export: one
window obtained its Control receipt between the PostgreSQL read and Worker snapshot;
ten final samples were still inside the grace period. Final capture now waits for
grace, consumer offsets and receipts, freezes Worker first, verifies every saved
sample is sealed, and only then exports append-only Control rows. Reconciliation
still requires every sealed payload and receipt; no missing row is ignored.
