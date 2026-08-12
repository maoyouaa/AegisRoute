# ADR 0002: Serve from immutable versioned route snapshots

- Status: Accepted
- Date: 2026-08-12

## Context

Gateway must stay independent from Control persistence on the serving path and converge predictably during rollback.

## Decision

Control publishes immutable global route revisions with monotonically increasing versions and SHA-256 checksums. Gateway polls outside the request path, validates the checksum, and atomically replaces only with a newer revision. Initial absence means live/UP, ready/DOWN, and serving 503; later Control loss preserves the last-known-good revision.

## Consequences

Control availability is required for initial readiness but not for established serving. v0.1 observes snapshot age without enforcing a stale cutoff.
