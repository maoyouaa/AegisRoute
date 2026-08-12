# ADR 0001: Isolate shadow publication from baseline serving

- Status: Accepted
- Date: 2026-08-12

## Context

Calling Kafka directly from a Reactor request chain can wait for metadata, buffer allocation, acknowledgements, retry, or broker timeout and therefore delay the baseline first token.

## Decision

The request path performs only a non-blocking offer into a count- and byte-bounded local queue. A dedicated publisher thread, producer connection, bounded retry count, and delivery timeout own all Redpanda interaction. Queue or broker failure records a bounded drop reason and never fails baseline serving.

## Consequences

Shadow telemetry is intentionally lossy under pressure. Metrics and evidence-availability gates make loss visible; canary promotion is blocked when required serving evidence is absent.
