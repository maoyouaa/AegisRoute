# ADR 0003: Classify stream failures before adding retry policy

- Status: Accepted
- Date: 2026-08-17

## Context

SSE failures have different safety implications depending on what the client has observed. A connection failure or an HTTP 429 before the first token may be eligible for a bounded fallback, while retrying after any token can duplicate or change user-visible output. Treating every provider failure as HTTP 502 also removes useful evidence from rollback analysis.

Classification must not silently become retry policy. A future fallback would also need one total deadline, one retry budget, explicit deployment-evidence semantics, and regression tests proving that the budget is not reset.

## Decision

Introduce a side-effect-free `FailureClassifier` in the Provider SPI. It returns a bounded failure kind, status code, and retry-eligibility flag:

- HTTP 429 and upstream 5xx before response output are eligible but are not retried by v0.1;
- connection and timeout failures before output are eligible and map to 502 and 504 respectively;
- HTTP 4xx other than 429, ambiguous already-started responses, cancellations, and unknown failures are not eligible;
- every non-cancellation failure after the first SSE token is `STREAM_FAILURE` and is never eligible; client cancellation remains `CANCELLED` and is also never eligible.

Gateway uses the classification only to write accurate serving evidence. The classifier never invokes a Provider, changes a Route Snapshot, or resets a deadline.

## Consequences

- Rollback evidence retains upstream 429/5xx and timeout semantics instead of flattening every failure to 502.
- Provider and Gateway contract tests can prove HTTP, connection, deadline, cancellation, and post-token boundaries independently.
- No transparent retry or fallback is added by this decision. Adding one requires a separate ADR and evidence model review.
