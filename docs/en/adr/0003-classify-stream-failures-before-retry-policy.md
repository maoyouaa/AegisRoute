# ADR 0003: Classify stream failures before adding retry policy

- Status: Accepted
- Date: 2026-08-17

## Context

SSE failures have different safety implications depending on what the client has observed. A connection failure or an HTTP 429 before the first token may be eligible for a bounded fallback, while retrying after any token can duplicate or change user-visible output. Treating every provider failure as HTTP 502 also removes useful evidence from rollback analysis.

Classification must not silently become retry policy. A future fallback would also need one total deadline, one retry budget, explicit deployment-evidence semantics, and regression tests proving that the budget is not reset.

## Decision

Keep retry eligibility in the dependency-light Domain `FailureClassifier`. Introduce a side-effect-free `ProviderFailureClassifier` adapter in the Provider SPI that unwraps transport exceptions, delegates the policy decision to Domain, and returns the Domain failure kind with a bounded evidence status and retry-eligibility flag:

- HTTP 429 and upstream 5xx before response output are eligible but are not retried by v0.1;
- connection and timeout failures before output are eligible and map to 502 and 504 respectively;
- HTTP 4xx other than 429, ambiguous already-started responses, cancellations, and unknown failures are not eligible;
- every non-cancellation failure after the first SSE token is `STREAM_ERROR` and is never eligible; client cancellation remains `CANCELLED` and is also never eligible.

Gateway uses the classification only to write accurate serving evidence. Neither classifier invokes a Provider, changes a Route Snapshot, or resets a deadline.

Before any SSE item is committed, a `ProviderException` is exposed through a bounded `PROVIDER_FAILURE` JSON envelope with its validated upstream HTTP status. The upstream exception message is never returned. Once an SSE item has been emitted, the response status cannot be rewritten; the stream terminates without retry.

## Consequences

- Rollback evidence retains upstream 429/5xx and timeout semantics instead of flattening every failure to 502.
- Provider and Gateway contract tests can prove HTTP, connection, deadline, cancellation, and post-token boundaries independently.
- HTTP-layer tests distinguish a real pre-token 429/500 response from internal evidence classification.
- No transparent retry or fallback is added by this decision. Adding one requires a separate ADR and evidence model review.
