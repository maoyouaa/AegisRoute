# AegisRoute

[中文](README.zh-CN.md)

Evidence-based rollout and adaptive routing for AI inference.

> Status: **v0.1 implementation in progress.** The core local synthetic-data path is implemented and reproducible; GitHub CI and release evidence remain pending. Do not interpret local acceptance results as production performance.

AegisRoute sits between an application and OpenAI-compatible inference deployments. Its v0.1 vertical slice keeps baseline serving independent from shadow work, collects deterministic candidate evidence, requires a human to approve canary traffic, and automatically removes the candidate after a policy breach.

```text
Baseline serving
  -> isolated shadow
  -> deterministic evidence
  -> human-approved canary
  -> automatic rollback
  -> gateway convergence evidence
```

## Engineering focus

- Java 21, Spring Boot 4.1, WebFlux, and Gradle Kotlin DSL
- immutable, monotonically versioned route snapshots
- two-stage shadow isolation: bounded local queue then Redpanda publisher
- JSON Schema event contracts without a Schema Registry
- optimistic concurrency and idempotent control-plane mutations
- stream-aware failure handling: no transparent retry after the first SSE token
- append-only rollback decisions and multi-gateway convergence evidence

## Current capability status

| Capability | Status |
|---|---|
| Product and architecture specification | Documented |
| Repository and build baseline | Implemented; local Windows checks pass, GitHub CI pending |
| Streaming gateway | Implemented; full end-to-end SSE matrix pending |
| Shadow/evaluation pipeline | Implemented; local Compose path verified |
| Human canary and automatic rollback | Implemented; local two-Gateway convergence verified |
| Performance and reliability results | Not yet measured |
| Production IAM/GDPR compliance | Out of scope |

## Build

Prerequisites: Git and Docker Desktop. The Gradle Wrapper can run on Java 17+ and provisions the Java 21 toolchain through Foojay.

```powershell
.\gradlew.bat clean check
docker compose up --build --wait
docker compose up --build --wait --scale gateway=2
.\scripts\acceptance.ps1
```

The acceptance script uses only synthetic data and verifies the automatic rollback evidence chain, two-Gateway convergence, startup/LKG behavior, and broker-failure isolation. Raw local output is written to ignored `build-evidence/`; CI uploads the equivalent output as an artifact. See [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md) for the repository workflow and invariants.

## Documentation

- [Architecture](docs/en/architecture.md)
- [Product document](docs/en/product.md)
- [Threat model](docs/en/threat-model.md)
- [Development workflow](docs/en/development-workflow.md)
- [Release permission boundary](docs/en/release-permission-boundary.md)
- [Product specification (Chinese)](docs/zh-CN/AegisRoute_Product_Spec_Final.md)
- [Project review (Chinese)](docs/zh-CN/AegisRoute_Project_Review.md)
- [Original design (Chinese)](docs/zh-CN/AegisRoute_Product_Design.md)

## Explicit non-goals for v0.1

React UI, Redis quotas, Etcd node discovery, LLM judges, Schema Registry, public cloud hosting, production IAM, real user data, and compliance certification.

## License

Apache License 2.0.
