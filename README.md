# AegisRoute

[中文](README.zh-CN.md)

Evidence-based rollout safety for OpenAI-compatible inference.

AegisRoute keeps baseline serving independent from shadow work, collects durable
candidate evidence, requires a human to approve each canary stage, and removes the
candidate after a deterministic policy breach. Gateway convergence is confirmed
against the exact rollback route ID, version and checksum.

```text
baseline serving → isolated shadow → recoverable evidence
                 → human canary → automatic rollback → exact Gateway ACKs
```

**Status, 2026-09-05:** the first reliability slice is implemented and verified in a
local synthetic environment. This delivery branch has not been merged into
`main` or released. Each PR's checks cover its own head. See the
[current roadmap](docs/en/status-and-roadmap.md) for integration dependencies.

| Capability | Evidence and boundary |
|---|---|
| Java 21 / Spring Boot / WebFlux gateway | 14 actual HTTP/SSE cases; JSON `stream`, `[DONE]`, cancellation and pre/post-token failures |
| Shadow isolation | Baseline-only admission, bounded queue and separate broker publisher; candidate execution follows the immutable route |
| Recoverable evidence | SQLite inbox/outbox, business-sample deduplication, stable windows and durable Control receipts; process kill and replay tested |
| Human canary | Explicit 1/10/50/100 approvals, each requiring a new sufficient current-route window |
| Rollback and convergence | Shared manual/policy decision path, append-only targets and exact A/B ACKs; offline Gateway remains pending |
| Local measurements | Six matched 240-request runs; 0 observed errors/drops; p95 on-minus-off −1.724, −0.325 and +0.653 ms |

Measurements use synthetic mocks and warm processes on one shared Windows/Docker
host, in fixed off-then-on order. Noise and additional Worker/candidate CPU are
reported; these results do not establish zero overhead or production performance.

## Run and verify

Use Git, Docker with Linux containers, Python 3.12+ and Java 17+ to start the committed
Gradle Wrapper. The wrapper provisions Java 21 through Foojay. Run from this repository:

```powershell
# New project and new evidence directory; reserves localhost task ports.
.\scripts\acceptance.ps1
# Add -PortOffset 10000 if the default 18080–18085 / 13000 / 19090 ports are occupied.
# Add -Benchmark for all six measurement rounds; -KeepRunning keeps Grafana open.
```

On Linux (where Docker and Java are available):

```bash
python3 scripts/reliability/run.py --project aegis-demo-$(date +%Y%m%d-%H%M%S) --output build-evidence/demo-$(date +%Y%m%d-%H%M%S)
```

The entrypoint runs `clean check integrationTest`, builds jars, launches the fixed
A/B reliability topology, and exercises transport, Worker recovery, replay, fresh
canary evidence, rollback and automatic dependency recovery. It writes raw output
to ignored `build-evidence/`, stops only its own project and preserves its volumes.
A failed or interrupted run must use a new project/output name on retry. Details and
individual commands: [reproduction guide](scripts/reliability/README.md).

`docker compose up --build --wait` remains a **baseline smoke topology**. Its dynamic
Gateway identities do not establish v2 convergence across container recreation.
Full lifecycle acceptance uses `compose.reliability.yml` and fixed A/B membership.
Control stays internal in the default topology; the synthetic inspector is localhost only.

To recompute the selected saved evidence without Docker:

```powershell
python scripts/reliability/verify_bundle.py
```

## Inspect the result

- [Curated evidence and measured results](docs/evidence/reliability-20260905/README.md)
- [Current status and integration plan](docs/en/status-and-roadmap.md)
- [Recoverable evidence design](docs/en/adr/0003-recoverable-route-bound-evidence.md)
- [Acceptance integration decision](docs/en/adr/0004-v2-acceptance-delivery.md)
- [Architecture](docs/en/architecture.md) · [Product scope](docs/en/product.md)
- [Threat model](docs/en/threat-model.md) · [Contribution workflow](CONTRIBUTING.md)

## v0.1 scope

One synthetic global-route owner and one durable Worker. No React UI, Redis quota,
Etcd, LLM judge, Schema Registry, real model/data integration, cloud deployment,
production IAM or compliance claim. Disk loss, Worker HA and provider exactly-once
side effects are unverified. A crash can repeat candidate execution while evidence
counts remain deduplicated. Promotion remains human-initiated.

Apache License 2.0.
