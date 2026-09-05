# Reproduce the v2 synthetic reliability acceptance

Run from the repository root with Git, Python 3.12+, Docker Linux containers and
Java 17+ to launch the committed Gradle Wrapper (Java toolchain 21). Python clients
use the standard library. No paid provider or external model service is contacted.

## One entrypoint for local use and CI

```powershell
.\scripts\acceptance.ps1
# Independent ports if another demo is running:
.\scripts\acceptance.ps1 -Project aegis-my-run-01 -EvidenceDirectory build-evidence/my-run-01 -PortOffset 10000 -Benchmark -KeepRunning
```

Equivalent cross-platform invocation:

```bash
python3 scripts/reliability/run.py --project aegis-my-run-01 --output build-evidence/my-run-01 --port-offset 10000 --benchmark --keep-running
```

Choose new project/output names for each attempt. The runner refuses existing
project containers, volumes or networks, occupied ports and existing output paths.
`--skip-build` / `-SkipBuild` is only for jars already built by the wrapper. Without
that option the runner executes:

```powershell
.\gradlew.bat clean check integrationTest :apps:gateway:bootJar :apps:control:bootJar :apps:worker:bootJar :apps:mock-provider:bootJar --max-workers=2 --no-build-cache --console=plain
```

On Linux the entrypoint uses `./gradlew`. It then validates Compose, starts the
fixed A/B topology, waits for application readiness, and runs transport, process
faults, replay, reconciliation and PID 1 dependency recovery. `--benchmark` adds
three matched off/on measurement pairs, reconciliation and raw-data recomputation.
`RESULT.json`, `commands.json`, parameters, raw client frames, provider counts,
Worker snapshots, PostgreSQL rows, broker records and logs are saved under the new
output directory. A failure is retained and exits nonzero; it is not a skipped gate.

Default ports are 18080/18083 (A/B), 18081 (Control inspection), 18082 (Worker),
18084/18085 (synthetic mocks), 13000 (Grafana), 19090 (Prometheus). `--port-offset`
adds the same value to all eight ports and maps every client and Compose binding.
All published ports bind 127.0.0.1. The default Compose Control port remains internal.
The runtime image defaults to `eclipse-temurin:21-jre-jammy`; an explicitly selected
local compatible JRE image may be passed via `AEGIS_RUNTIME_IMAGE`. It replaces
`/app/application.jar` with the newly built jar and does not publish any image.

## Actual acceptance sequence

1. DRAFT baseline serving and zero shadow; 14 actual HTTP/SSE cases including JSON
   stream routing, DONE, deadline, pre/post-token failures and cancellation.
   Before transport, stop Control: both cached Gateways remain ready with growing
   snapshot age; restarting them gives live 200, ready/serving 503 until recovery.
2. Start shadow; stop Control, retain pending windows, kill Worker, restart it while
   Control is still unavailable, then require durable receipts after recovery.
3. Replay 60 real recorded business events in reverse order using new event IDs;
   wait for both consumer groups to commit through log end before comparing counts.
4. Stop/restore Redpanda while baseline JSON/SSE succeed; inject candidate timeout
   and HTTP 500 and refuse promotion from failing or insufficient evidence.
   Require publisher timeout/unavailable loss counters to grow and each baseline
   request to remain below the previous two-second acceptance bound.
5. Explicitly approve 1/10/50/100 using separate current-route windows; FULL executes
   candidate only once. Three adjacent complete breach windows produce one rollback.
6. Pause and manually roll back with Gateway B offline; require A only, then exact
   A+B tuple confirmation after B returns. Reconcile immutable decisions and receipts.
7. Exit PostgreSQL PID 1 with SIGQUIT and Redpanda PID 1 with SIGTERM. Observe their
   automatic RestartCount increase, dependency/Control/Worker health and unchanged
   or increasing route version. Five real baseline JSON/SSE probes must succeed.
   No docker start/up is used to claim automatic restart in this step.

The driver explicitly models human API actions; the product never promotes itself.
Policies: 2s deadlines, 5s windows, 15s finalization grace, minimum 10 complete pairs,
95% coverage, maximum 5% shadow errors and 2-minute freshness. Measurement parameters
are seed 20260905, 240 requests/run, concurrency 4, 20/s, warm processes and fixed
OFF then ON order. Resource snapshots and latency include shared-host noise.

## Grafana and saved evidence

With `--keep-running`, open the printed Grafana dashboard URL.
Inspect current route and phase, evidence route/age, coverage, queue/backlog, rollback
source/target and separate A/B confirmations. The dashboard is a projection of
persisted Control state, not a second business-state implementation. Good historical
coverage does not authorize a new canary stage.

The original run also tested PostgreSQL outage display: ACK queries guarded by
`(time() - aegis_control_updated_seconds) < 5` became empty, the live page showed
unknown health, baseline SSE still completed and exact confirmation returned after
recovery. That optional UI exercise is recorded in the curated bundle, not silently
counted as a new screenshot check by this command-line runner.

```powershell
# Offline verification of the selected historical run, including decompression.
python scripts/reliability/verify_bundle.py
# Reconcile a new run's saved state without touching running services.
python scripts/reliability/reconcile.py --scenario build-evidence/my-run-01/scenario
python scripts/reliability/verify_online_convergence.py --scenario build-evidence/my-run-01/scenario
python scripts/reliability/analyze_benchmark.py --benchmark build-evidence/my-run-01/benchmark
```

The common runner also executes the saved-state online convergence verifier, retaining the
existing strict <5s decision-to-confirmation bound for the selected policy rollback.
The deliberate offline manual rollback uses its separate pending-state gate.

Final export waits for the last bucket's grace, committed consumer offsets and
window receipts. It freezes Worker facts before reading PostgreSQL so a window
cannot appear between a Control export and a later Worker snapshot. Every captured
sample must belong to a sealed window; failed or incomplete captures exit nonzero.

[Curated report](../../docs/evidence/reliability-20260905/README.md) describes source
hashes and limitations. `freeze.py --verify` applies to the original frozen worktree
with its full original archive; it must not be run against a modified delivery tree
as if both had the same source manifest.

## Cleanup and boundaries

Without `--keep-running`, the runner stops only its fresh project after capturing
logs and preserves its named volumes. To stop a retained demo:

```powershell
docker compose -p aegis-my-run-01 -f compose.reliability.yml down
```

No global prune or automatic volume removal. Keep failed attempts. Do not target
original `aegisroute-*` or other tasks' containers. Published v1 schemas and already
applied V1/V2 migrations are immutable. Disk loss, Worker HA, provider side effects,
real data, cloud, production IAM and compliance are outside this acceptance.

Default `compose.yml` remains a baseline smoke topology with dynamic Gateway
identities; container recreation can retain old ACK members. Full evidence and
convergence acceptance uses fixed A/B membership in `compose.reliability.yml`.
The old acceptance `BaseUrl`/`SkipChaos` arguments are rejected to avoid silently
mutating a default stack or omitting mandatory checks.
