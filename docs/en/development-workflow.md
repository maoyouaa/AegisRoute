# Development workflow

AegisRoute uses short-lived branches, Conventional Commits, bilingual Issues and PRs, required CI, and squash merges. The detailed executable rules live in `AGENTS.md` and `CONTRIBUTING.md`.

The v0.1 evidence gates are repository baseline, streaming gateway, versioned control plane, isolated shadow evaluation, canary/rollback, and release evidence. A gate is complete only when its tests and artefacts exist; elapsed time does not make a gate complete.

Release Please maintains the release PR, changelog, tag, and GitHub Release. Publication jobs run in the same `main` workflow only when Release Please reports `release_created=true`, avoiding long-lived PATs and recursive workflow assumptions.

The repository's default workflow permission remains read-only. GitHub's combined "create and approve pull requests" setting is enabled only so Release Please can open its proposal; the workflow contains no approval or merge step. Audit the mutable repository setting and the static workflow boundary separately as documented in [Release permission boundary](release-permission-boundary.md). Creating a Release PR never authorizes merging it or publishing release artifacts.
