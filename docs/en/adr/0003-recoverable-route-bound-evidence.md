# ADR 0003: Recoverable route-bound evidence and exact rollback confirmation

- Status: Accepted for the synthetic v0.1 reliability worktree
- Date: 2026-09-05
- Run: `aegisroute-20260905-002645`

## Observed problems and current flow

Current flow: HTTP -> snapshot + candidate sampling -> unconditional shadow-requested v1
-> bounded queue -> publisher -> broker -> globally configured Worker candidate.
Baseline and candidate observations meet in memory by sample UUID only. A new event ID
can count the same pair twice. Eligibility is once per rollout, serving windows are
cleared before Control acknowledges them, and neither survives Worker restart.
Control counts breaches in arrival order. Human approval checks only the ratio sequence.
Automatic rollback has a decision; manual rollback does not. Mutable latest ACKs and
`version >= target` allow skipped revisions and an empty target set to look converged.
Two failing pairing regressions are saved in the run's `red-pairing.log`.

## Route lifecycle and ownership

| Snapshot phase | Candidate serving | Shadow of baseline requests |
|---|---|---|
| DRAFT | 0% | Disabled |
| SHADOW, ELIGIBLE | 0% | Stable configured sample rate (demo 100%) |
| BLOCKED | 0% | Disabled; new rollout required to change candidate |
| CANARY | Human 1/10/50% | Stable sample of baseline-served requests only |
| FULL | Human 100% | No baseline requests; no duplicate candidate call |
| PAUSED, ROLLBACK_PROPAGATING, ROLLED_BACK | 0% | Disabled |

Snapshot v2 includes phase, shadow percentage and checksum version. Checksums cover
these policy inputs and all immutable deployment identities/URLs. Legacy snapshots
retain their original checksum algorithm and default to shadow disabled. A new route
revision is required to change serving/shadow policy. Eligibility status may change
without a route change because SHADOW and ELIGIBLE have the same routing behavior.
The global v0.1 route has one active owner: creation is serialized and rejected while
an existing rollout is not terminal. A database constraint enforces that rule.

## Events and migration

Published v1 schemas remain byte-for-byte unchanged. New producers/consumers use
`aegis.observation.v2` and `aegis.shadow-requested.v2` with new schemas and fixtures.
The full immutable snapshot travels with every event. Identity includes Gateway
instance and boot UUID, generated sample UUID, request ID, rollout, route ID, exact
version/checksum, both deployment IDs, and admission-time bucket. Worker verifies
snapshot checksum and checks the immutable route at Control before first candidate
execution (bounded cache; no Gateway serving lookup). Event-supplied URL alone is
not trusted. Wrong identities and malformed events are recorded in bounded quarantine.
Candidate-served observations use their own sample identity and never generate shadow.

Upgrade the isolated stack together, preserve old topics for inspection, and use a
new consumer group. No dual counting or reinterpretation of historical v1 events.
The legacy evidence/eligibility write endpoints fail closed after migration; new
window submissions use an explicit v2 internal contract. Public mutation headers
and status codes remain unchanged. No external publication is part of this work.

## Durable Worker processing

Use a private SQLite file on a task-specific named volume, WAL with FULL synchronous
durability, explicit transactions, and one writer. Kafka records are acknowledged
only after durable ingestion; business identity, not event UUID, deduplicates samples.
Candidate results are stored before a bounded broker publication; retries reuse that
result. A crash after provider execution but before its result commits can execute
the synthetic candidate again; this is not an exactly-once external-call guarantee.
Evidence still counts the business sample once. Consumer failures retry without
silently recovering/committing failed records. Poison data is durably quarantined.

Windows use fixed UTC 5-second half-open admission buckets, stable route+bucket UUID,
explicit format version, and a configurable finalization grace at least the provider
deadline. Sealing counts/IDs and creating the immutable outbox payload are atomic.
Unacknowledged windows remain on disk and replay unchanged after restart or timeout.
Late records for sealed buckets are quarantined; an insufficient sealed window is
never silently repaired. Raw identity tombstones and sealed windows remain until
operator cleanup; hard capacity bounds pause ingestion rather than silently evict.
This local single-Worker design proves process restart, not disk loss or HA failover.

## Coverage without a baseline persistence dependency

Gateway admission records only bounded in-memory counters and queue offers. A separate
scheduled publisher closes per-(instance, boot, route, bucket) summaries after the
request deadline, then retries immutable summaries until Control confirms them.
Summaries include total requests, candidate-served and shadow-eligible requests, and
queue/serialization drops. Idle buckets are reported as zero. Missing summaries,
Gateway restart within a bucket, or local summary capacity loss produce UNKNOWN
coverage, never an assumed zero denominator. Configured expected Gateway IDs freeze
the required set; without configuration, known registered gateways are used, and an
empty set is UNKNOWN. No network or disk write is reachable from baseline admission.

Worker counts observed serving samples, complete pairs, incomplete pairs and pending
candidate execution. Control compares counts to the complete set of admission reports.
Promotion requires at least ten complete pairs, sufficient total/paired coverage
(95%), no baseline errors, candidate shadow errors <= 5%, no unknown denominators,
and a window on the current route/candidate ending within the last two minutes.
Window duration/thresholds are explicit demo policy, not measured production targets.

## Control transactions and ordering

V2 submission locks the rollout, checks an existing window ID and canonical hash before
current lifecycle validation, and returns its original saved result on exact replay.
Different content at the same ID is 409. Window identity must match route, bucket and
format; overlaps and older unseen windows are rejected. A forward gap is accepted
with streak reset. Consecutive breach means adjacent complete 5-second buckets on
the same immutable route with adequate serving coverage and >=10 candidate requests;
missing/insufficient evidence resets the streak. Late old-route evidence can be
retained as non-actionable; it cannot alter the current route or authorize approval.

Every human approval consumes one fresh, adequate current-route evidence window in
the same transaction as optimistic version checking, route creation and audit. Each
next stage has a new route and requires new evidence. Existing Idempotency-Key replay
returns the original result even with the old If-Match. Expired keys are removed
under the existing advisory lock before replacement; database conflicts are not
swallowed. Missing precondition is 428, stale 412, invalid state/input reuse 409.

Manual and automatic rollback call one transactional service. It persists manual
actor/reason evidence or an already stored deterministic policy evaluation, reserves
the target version and UUID/checksum, saves the append-only decision and exact target
tuple/instance set, creates the zero-ratio revision, updates state and writes audit.
Any failure rolls back all rows (sequence gaps are allowed, version reuse is not).

## Exact ACK history

A new append-only table records each (instance, route ID, exact version, checksum)
application once. Heartbeat liveness remains a separate mutable projection and cannot
substitute for history. ACKs validate against the requested immutable revision, so
valid delayed ACKs are accepted. Future apply times are rejected. Decision targets
include configured/registered offline instances. Only the exact target tuple counts;
skipping a target leaves that instance unconfirmed. Empty targets mean NO_TARGETS /
UNKNOWN and do not transition to ROLLED_BACK. Existing higher-version ACKs are not
backfilled as proof that earlier routes were applied.

## Validation and limits

Append migrations only; protect route revisions, window results, policy/decision
evidence, target rows and ACK history from updates/deletes. Add runtime schema caching
and validate producers on publisher threads. Update the threat model for trusted
internal v2 submissions and scoped synthetic mock fault controls.

Required evidence is listed in the run PLAN.md: directed regressions, real PostgreSQL
and Redpanda process failures, Worker restart replay, real HTTP/SSE cancellation and
failure boundaries, three matched shadow-off/on measurements, exact two-Gateway
ACKs, live Grafana screenshots and an independent final review. No production,
security certification, throughput, compliance or zero-overhead claim follows from
this design. All performance statements await saved observations.


### Review clarifications (2026-09-05)

Approval selects only the latest evaluated current-route window. A subsequent failed or
insufficient window supersedes any older, unused healthy qualification. Local candidate
results enter the durable pairing fact and result outbox in one transaction, so restart
scheduling cannot change pair counts. Sealing is a terminal expiry for unstarted work;
each candidate execution rechecks that boundary. An already started call remains bounded
by its original deadline. Expired tasks remain countable in the store and immutable window.
The status endpoint and fixed-cardinality metrics are read-only projections of these rows.


### Actual outage experiment correction

The first Control recovery rehearsal saved a complete 20/20 window across SIGKILL, but
Gateway ACK/report publication did not return before the 90-second coverage timeout.
The returned Control was reachable with container curl while old JVM clients could not
reach it. Docker may reassign stopped service addresses; HTTP clients now cap positive
DNS caching at two seconds and negative caching at one second, and disable implicit
Reactor Netty connection retries. This is bounded local service-name resolution, not a
new discovery subsystem. Actual process rehearsal is the regression gate. A committed
SSE response propagates its error without trying to replace committed headers.


### Final integrity and observability review

A complete pair must include an observed baseline serving result, so `completePairs`
cannot exceed `servingObserved - candidateRequests`. Rejected impossible DTOs never
reach policy persistence. Metric refresh failures clear the projection; Grafana uses
instant stat queries gated by the last successful projection age (<5s), so a blocked
or failed database refresh cannot preserve an old healthy last-non-null value. SSE
observations preserve the HTTP 504 deadline cause before and after the first token.
