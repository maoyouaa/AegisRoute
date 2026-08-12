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

## Explicit non-claims

This model does not establish GDPR compliance, tenant isolation, Internet-safe IAM, penetration-test coverage, or production readiness. Those require different data, identity, hosting, and operational boundaries.
