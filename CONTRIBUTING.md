# Contributing

## Workflow

1. Open an Issue with an English section followed by a Chinese section.
2. Branch from current `main` using `feat/<issue>-<slug>`, `fix/<issue>-<slug>`, or `docs/<issue>-<slug>`.
3. Add a failing test or reproducible scenario before behavior changes.
4. Implement the smallest coherent change and update affected contracts/docs.
5. Run the relevant Gradle checks and `git diff --check`.
6. Use an English Conventional Commit message.
7. Open a bilingual Draft PR and include actual validation evidence and limitations.
8. Squash merge only after required checks pass.

No push, merge, tag, release, or package publication is implied by local implementation work. Those actions require explicit authorization.

## Commits

Examples:

```text
feat(gateway): add route snapshot readiness gate
fix(worker): keep broker retries outside serving path
docs(adr): record human promotion policy
```

## Design changes

Public APIs, event schemas, Flyway migrations, routing, rollback, or trust-boundary changes require an ADR or an Issue design section that records context, alternatives, consequences, and verification.

## Evidence

Never replace a failed or skipped check with a claim. PRs must separate:

- checks that passed;
- checks that failed;
- checks that were not run;
- design targets that remain unmeasured.

## 中文说明

每项工作使用双语 Issue 和 PR；代码、标识符、提交信息使用英文。先提供失败测试或复现场景，再做最小实现。任何 push、merge、tag、Release 或镜像发布都需要对该动作的明确授权。
