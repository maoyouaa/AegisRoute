# Incident report: Compose dependencies did not recover after the container runtime resumed

- Status: mitigated and locally verified; CI verification pending
- Window (UTC): 2026-08-15 03:50:01 to 2026-08-17 09:44
- Synthetic environment and commit SHA: local Docker Desktop; base `e026013`
- Customer impact: none — v0.1 synthetic demo only

## Summary and detection

During a routine repository-state inspection, Control and Worker were found in restart loops while the mock providers and Gateway were running. PostgreSQL and Redpanda had both exited with code 255 at the same timestamp and had not restarted. Control failed with `UnknownHostException: postgres`; Worker failed with `No resolvable bootstrap urls given in bootstrap.servers`.

Running `docker compose up -d --wait` restarted both dependencies and restored Control and Worker health. Gateway remained live but unready at route version 0 even after Control served route version 7 successfully. Restarting Gateway allowed it to acquire version 7. No real user request or personal data was involved.

The original interactive terminal output was not retained as a release artifact. This report therefore treats exact runtime timing as diagnostic evidence, not a performance claim. The corrective recovery script writes reproducible evidence under ignored `build-evidence/compose/runtime-recovery/`, and CI uploads that directory through the existing Compose artifact.

## Timeline

| Time (UTC) | Event / evidence reference |
|---|---|
| 2026-08-15 03:50:01 | Docker inspection recorded PostgreSQL and Redpanda exiting with code 255. Both had restart policy `no`. |
| 2026-08-17 08:33:29 | Gateway container started while its dependencies remained unavailable. |
| 2026-08-17 09:41 | Inspection detected Control and Worker restart loops. Logs identified unresolved `postgres` and `redpanda` service names. |
| 2026-08-17 09:42 | `docker compose up -d --wait` restored PostgreSQL, Redpanda, Control, and Worker health without deleting volumes. |
| 2026-08-17 09:44 | Control returned route version 7, while Gateway readiness still returned 503 with route version 0. |
| 2026-08-17 09:44 | Restarting Gateway restored readiness at route version 7. |

## Evidence chain

This was an infrastructure-recovery incident, not a candidate-policy incident, so it did not create a new Evidence Window or Rollback Decision. Existing immutable route revision 7 remained in PostgreSQL and was applied after Gateway restarted. The regression scenario injects PostgreSQL `SIGQUIT` and Redpanda `SIGTERM`, then records dependency restart counts, five baseline responses during the recovery window, route version before/after, and final Control/Worker health.

The local corrective run at `2026-08-17T09:55:35Z` increased both dependency restart counts, returned five of five baseline responses, preserved route version 7, and ended with Control and Worker health `UP`. Its machine-readable output is local-only until an authorized PR run uploads the CI artifact.

## Root cause and contributing factors

Verified root cause:

- Only Java services inherited `restart: unless-stopped`; PostgreSQL, Redpanda, Edge, Prometheus, and Grafana used Docker's default `no` restart policy.
- PostgreSQL and Redpanda therefore remained stopped after the container runtime interruption, while dependent Java services repeatedly restarted.

Verified contributing code risk:

- Route Snapshot polling and acknowledgement used blocking WebClient calls without a bound. Code inspection showed that an unavailable Control request could occupy the single scheduled poll execution indefinitely.
- The default Compose configuration did not give Redpanda an explicit healthcheck, so `docker compose up --wait` treated process startup as readiness.

Unknowns and observability gaps:

- The external reason for the simultaneous dependency exit code 255 was not retained.
- No thread dump was captured from the unready Gateway, so the precise network operation that held the poll execution is not proven.

## Corrective actions

| Action | Owner | Due | Verification |
|---|---|---|---|
| Apply `restart: unless-stopped` to every long-running Compose service. | Maintainer | v0.1.0 | Static Compose policy verifier and dependency crash test. |
| Add an explicit Redpanda cluster-health probe. | Maintainer | v0.1.0 | `docker compose up --wait` plus policy verifier. |
| Bound Gateway Control polling and acknowledgement requests. | Maintainer | v0.1.0 | Unit test proves timeout followed by successful next poll. |
| Preserve machine-readable recovery evidence in CI and fail closed on any baseline probe failure. | Maintainer | v0.1.0 | Readiness preflight plus `verify-compose-crash-recovery.ps1` artifact. |

These controls improve the reproducible local/CI environment. They do not establish production availability or disaster-recovery guarantees.
