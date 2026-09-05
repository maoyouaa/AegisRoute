# Current status and v0.1 roadmap

[中文](../zh-CN/status-and-roadmap.md)

Snapshot: **2026-09-05 UTC**. Local implementation, frozen synthetic evidence and
GitHub state are different layers. This document authorizes no external mutation.

## Current delivery

The first reliability slice is implemented outside `main`. The frozen run is
`aegisroute-20260905-002645`, source HEAD `9983b647225cd4ec6ba6702785c1839be3225df9`,
source inventory SHA-256 `39086a2711ffa095286abffb8ab9cbf81f4ddfc4533962217641c5c28dcf4778`.
Its original files remain unchanged. The [curated bundle](../evidence/reliability-20260905/README.md)
keeps enough raw synthetic facts to recompute windows, receipts, ACKs and measurements.

| Workstream | Current evidence | Remaining boundary |
|---|---|---|
| Shadow lifecycle and candidate ownership | Snapshot-bound policy; DRAFT/PAUSED/ROLLED_BACK excluded; baseline-only shadow; wrong-route/candidate rejection | Synthetic global-route owner; no elastic membership claim |
| Durable evidence and deduplication | SQLite recovery, business identities, stable windows, receipt retry, new-event-ID replay | Candidate side effects may repeat after a crash; disk loss/HA unverified |
| Human promotion | Each 1/10/50/100 approval uses a new sufficient current-route window | Human-initiated only; no automatic promotion |
| Rollback/convergence | Shared manual/policy transaction, immutable target, exact append-only ACKs, offline target pending | Fixed A/B reliability topology |
| HTTP/SSE | 14 real-client cases, deadline, cancellation, `[DONE]`, isolation during faults | #12's distinct status-mapping decision remains to reconcile |
| Contracts/observability | New v2 schemas/topics, runtime validation, bounded metrics, actual Grafana evidence | Published v1 schemas retained; no v1 reinterpretation/dual counting |
| Measurement | Six runs, 1,440 requests, 720 matched shadow samples, no observed errors/drops | Fixed order and shared-host noise; no capacity or zero-overhead claim |

The original wrapper run executed 85 tests, including 17 PostgreSQL integration
tests, with no failures, errors or skips. These results describe the frozen source,
not a future merge. Delivery packaging adds the [v2 acceptance integration](adr/0004-v2-acceptance-delivery.md):
explicit Worker volume, a fresh-project runner, complete localhost port mapping,
and CI wiring. The [delivery rerun](../evidence/reliability-20260905/delivery-validation.json)
passed 85 tests (17 integration), 14 HTTP/SSE cases and full process reconciliation
of 20 sealed windows. Online policy convergence took 1101.66 ms (<5000 ms); snapshot
startup/LKG behavior and dependency PID 1 recovery also passed. Failed attempts are
retained separately. No Java behavior, v1 schema or applied migration is changed by
packaging. The benchmark and UI captures above remain from the original frozen run.

## Live GitHub snapshot

Read-back data: [github-snapshot.json](../evidence/reliability-20260905/github-snapshot.json).
`main` is `e0260134b5e438534606fb3a08ba9364334cd36c`. Its [CI run](https://github.com/maoyouaa/AegisRoute/actions/runs/32005220401)
and latest observed [CodeQL run](https://github.com/maoyouaa/AegisRoute/actions/runs/33379484126)
passed. These checks cover main, not the delivery branch.

| PR | Exact head | Read-back state | Relationship to delivery |
|---|---|---|---|
| [#15 runtime recovery](https://github.com/maoyouaa/AegisRoute/pull/15) | `9983b64` | Draft, 9 successful checks | Delivery base; one commit above main |
| [#12 stream failure contracts](https://github.com/maoyouaa/AegisRoute/pull/12) | `129b9d7` | Draft, 9 successful checks | Sibling, not ancestor; Gateway/test/ADR conflicts |
| [#13 release candidate validation](https://github.com/maoyouaa/AegisRoute/pull/13) | `d42c13b` | Draft, 8 successful checks | Sibling; CI/threat-model overlap and legacy acceptance invocation |
| [#9 release permission boundary](https://github.com/maoyouaa/AegisRoute/pull/9) | `0ca73ad` | Draft, 8 successful checks | AST verifier fix is already pushed; not part of this branch |
| [#8 release 0.1.0](https://github.com/maoyouaa/AegisRoute/pull/8) | `b6d310a` | Open, no effective PR checks | Release preparation only; no release authorization |

The four listed stabilization PRs remain unmerged. No tags or GitHub Releases were found.
Main protection requires `linux`, `windows`, `compose-config`, `analyze`; other green
checks are not currently required. No protection or permission settings were changed.

## Integration order and decisions

1. Review this as a **stacked Draft PR against `fix/14-compose-runtime-recovery`**.
   That keeps #15's existing commit outside the new review diff. Refresh both heads
   immediately before any authorized publication; do not push to an old PR's branch.
2. Resolve #15 review and merge only with action-specific authorization. Then
   reconcile the delivery branch with the new main and retarget; a squash merge
   changes ancestry, so inspect the actual diff rather than assuming it disappears.
3. Keep #12 separate until a reviewer resolves upstream HTTP 500 → 500 versus this
   slice's tested 502 mapping, nested transport-error classification and v1/v2 test
   fixtures. Both branches have an ADR numbered 0003 with different filenames; give
   the retained decision an unambiguous number and explicit supersession note.
4. Integrate #9/#13 separately. Preserve #9's AST validation and least-privilege
   rules. #13 still uses regex/string validation: replace it with structured YAML
   validation that rejects duplicate keys before treating its gate as trusted.
   Update #13's release-candidate topology/acceptance command
   and artifact path when it is reconciled with v2. Its existing checks do not prove
   a real release-candidate run. Do not auto-close or overwrite sibling PRs.
5. After publication is explicitly authorized, require checks on the exact new PR
   head and subsequent merge result: Linux, Windows, PostgreSQL integration,
   coverage, Compose configuration, `two-gateway`, CodeQL, dependency review and
   secret scan. An ordinary branch push alone does not trigger CI's main-only push
   rule; PR creation or an authorized dispatch is needed.

## Remaining release gates

- Local delivery verification has passed. Remote checks on the exact new PR head,
  review and authorized integration remain necessary before Ready or mainline claims.
- Expired 24-hour idempotency-key replacement (the earlier C5 finding) remains a
  separate workstream. This delivery does not include the independently named local
  expiry worktree or claim that finding fixed.
- Default Compose dynamic Gateway membership after recreation remains outside the
  full acceptance topology; use fixed A/B membership for the validated slice.
- Resolve sibling PR design conflicts, review release permission/candidate gates,
  then collect exact merged-SHA evidence. Release #8, tags, image publication,
  SBOM/provenance and release claims require their own later decision.

v0.1 still excludes React, Redis/Etcd, real models/personal data, LLM judges, cloud,
production IAM and compliance. Those exclusions are not automatic follow-up work.
