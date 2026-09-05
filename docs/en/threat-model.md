# Threat model — v0.1 synthetic demo

## Assets and boundaries

Assets are route integrity, rollout decisions, synthetic event integrity, release artifacts, and service availability. Edge is the only host-exposed application service. Gateway crosses from the entry network to `aegis-internal`; Control, Worker, PostgreSQL, Redpanda, Prometheus, Grafana, and mock nodes remain internal. The development override is an explicit local exception for Control.

## Principal threats and controls

| Threat | v0.1 control | Residual boundary |
|---|---|---|
| Stale or forged route | SHA-256 checksum, monotonic version, atomic replace, LKG | No signed Control-to-Gateway channel in local Compose |
| Replay/double mutation | 24-hour idempotency record plus transaction advisory lock | No production identity binding |
| Lost update | strong `If-Match` and optimistic version update | Authorized actor is supplied synthetic metadata |
| Shadow resource exhaustion | message/byte bounds, independent thread and connection, bounded delivery | Demo defaults need measured tuning before production |
| Secret or personal-data leak | synthetic fixtures only, no Authorization logging, secret scan | No production DLP system |
| Decision tampering | append-only table with database trigger rejecting update/delete | Database administrator remains trusted |
| Supply-chain substitution | Wrapper, pinned Actions SHAs, image digests/SBOM/provenance at release | Base-image patch policy remains operational work |
| CI token misuse | Supply-chain jobs default to read-only contents; checkout never persists credentials; Gitleaks receives the ephemeral token only in its scan step with PR comments disabled | A compromised third-party Action can still read repository contents and the job-scoped token during that step |

## Explicit non-claims

This model does not establish GDPR compliance, tenant isolation, Internet-safe IAM, penetration-test coverage, or production readiness. Those require different data, identity, hosting, and operational boundaries.


## Reliability v2 and local fault rehearsal

The v2 event carries the full immutable route and business identity. Worker validates
its schema, checksum, lifecycle selection, deployment and immutable Control revision
before durable admission. A valid self-computed checksum alone is not authority for an
arbitrary candidate URL. Replay uses the complete sample identity; mismatches are
quarantined and cannot create a second count. Tests cover wrong candidate, route mismatch,
new event IDs, sealed replay and persistent local results. SQLite is a trusted private
Worker volume; disk tampering and disk loss are outside the process-restart claim.

Gateway summaries are bounded memory only; missing/partial boot or capacity-loss reports
remain unknown. Control accepts internal evidence only on its internal network, stores
append-only decisions/ACK tuples and treats missing targets as unknown. Internal peers and
synthetic actor strings are still trusted; this is not authenticated production IAM.

`/internal/faults` and `/internal/stats` exist only when `AEGIS_MOCK_FAULT_CONTROLS=true`.
They return 404 by default, accept a fixed bounded synthetic mode enum and retain at most
8192 bounded request IDs for call/cancellation evidence. They never accept commands,
URLs, credentials or real prompts. Regression tests verify default denial, mode bounds,
and instance isolation. The dedicated reliability Compose opt-in exposes these internal
services through a task-specific Nginx bound only to 127.0.0.1, including Control/Grafana
for inspection. This local exception must not be used as an Internet-facing deployment.
No fault switch or runtime data is global to other Compose projects.
