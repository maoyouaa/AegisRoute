# AegisRoute v0.1 product document

## Positioning

AegisRoute is a portfolio-grade reliability control loop for OpenAI-compatible inference. It demonstrates how a team can evaluate a candidate deployment without placing shadow work on the baseline response path, move traffic only after a human decision, and remove a failing candidate through an evidence-backed deterministic policy.

The intended audience is an engineer or hiring panel evaluating backend, distributed-systems, platform, and SRE judgement. v0.1 uses synthetic traffic and deterministic mock providers; it is not a production service or a compliance claim.

## Problem and outcome

Inference changes are difficult to validate with offline checks alone. A useful release path must compare real request shapes while protecting current serving, establish concurrency semantics for operator actions, and make rollback decisions explainable after the fact.

The v0.1 acceptance path is:

```text
baseline serving
  -> isolated shadow
  -> paired deterministic evidence
  -> human-approved canary [1, 10, 50, 100]
  -> three breached five-second windows
  -> immutable rollback decision
  -> higher route revision with candidate ratio 0
  -> acknowledgement from every decision-time Gateway
```

## Users and jobs

- A platform engineer creates a rollout and starts shadow evaluation without affecting baseline availability.
- A release owner examines evidence and explicitly approves each canary step.
- An incident responder can reconstruct evidence, policy evaluation, decision, route revision, and Gateway convergence.
- A reviewer can reproduce contract, startup, isolation, persistence, and Compose checks from the public repository.

## Functional contract

Control mutations use idempotency and optimistic concurrency. Creation needs `Idempotency-Key`; state changes also need a strong `If-Match`. Missing preconditions return 428, stale versions 412, invalid state transitions 409, and cross-payload idempotency reuse 409. Idempotency results are retained for 24 hours.

Gateway polls a checksum-valid immutable Route Snapshot once per second. Before the first valid snapshot it remains live but not ready and serving returns `503 ROUTE_SNAPSHOT_UNAVAILABLE`. After initialization a Control outage leaves the last-known-good snapshot active. Gateway never queries PostgreSQL.

Shadow ingress is a non-blocking offer into a count- and byte-bounded local queue. An independent publisher thread owns Kafka metadata, acknowledgements, retry, and delivery timeout. Queue pressure or broker failure drops shadow work with a bounded reason label; it cannot fail the baseline request.

Worker validates repository-owned JSON Schemas, calls the candidate, deduplicates events, pairs baseline and candidate observations by `sampleId`, expires incomplete pairs, and aggregates candidate serving windows. Promotion remains human-initiated. Only deterministic breach policy may initiate rollback.

## Quality attributes

- Safety: no transparent retry after the first SSE token; automatic promotion is impossible in v0.1.
- Auditability: route revisions and rollback decisions are append-only, versioned evidence.
- Availability: baseline response work has no database or broker dependency.
- Reproducibility: Java 21, Spring Boot 4.1.0, Gradle 8.14.5, JSON Schema contracts, pinned GitHub Actions, synthetic data.
- Scope control: no React, IAM product, Redis quota, Etcd, LLM judge, Schema Registry, public deployment, or real personal data.

## Evidence and release gates

Unit and contract tests are necessary but not sufficient. v0.1.0 release also requires saved Ubuntu and Windows CI, Testcontainers, Compose smoke, two-Gateway convergence chaos evidence, a rollback decision sample, Grafana timeline, bilingual ADR/threat/incident documents, a three-minute real demo, image digests, SBOM, provenance, and commit SHA.

Until those artifacts exist, the public status remains “implementation in progress.” A local two-Gateway Compose run on 2026-08-12 saved a 1.111-second rollback-convergence result plus startup/LKG and broker-outage evidence under the ignored `build-evidence/` directory. This proves the local acceptance path, not production performance; `<5s` remains the release gate until CI and release artifacts exist.

## Reproducible local acceptance

Start from a fresh synthetic Compose database, scale Gateway to two instances, and run the acceptance harness:

```powershell
docker compose down --volumes
docker compose up --build --wait --scale gateway=2
.\scripts\acceptance.ps1
```

The harness verifies the human canary sequence, three deterministic breached windows, immutable decision and ratio-zero revision, acknowledgements from both Gateways, startup without a Snapshot, LKG behavior during a Control outage, and baseline independence during a Redpanda outage. It writes raw evidence locally and CI uploads the equivalent directory as an artifact.

## Irish job-search narrative

This project is designed to provide concrete interview material for backend/platform/SRE roles in Ireland: Reactor critical-path isolation, HTTP preconditions and idempotency, immutable evidence, failure-aware SSE, PostgreSQL constraints, Kafka backpressure boundaries, multi-instance convergence, supply-chain controls, and honest evidence-based claims. CV wording should use “Designed” or “In progress” until the corresponding release gate is saved; “Built”, “Implemented”, “Validated”, and measured numbers belong only to completed evidence.
