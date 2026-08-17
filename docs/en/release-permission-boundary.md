# Release permission boundary

## Purpose

AegisRoute uses Release Please with the repository-scoped, ephemeral `GITHUB_TOKEN`. GitHub exposes permission for Actions to create and approve pull requests through one combined repository setting. AegisRoute enables that setting only because Release Please must create a release proposal. The workflow does not approve or merge pull requests.

This is a least-privilege boundary, not a production-security or compliance claim.

## Two independently verified layers

### Mutable GitHub repository setting

The expected live setting is:

```json
{
  "default_workflow_permissions": "read",
  "can_approve_pull_request_reviews": true
}
```

Audit it with a read-only API call:

```powershell
gh api repos/maoyouaa/AegisRoute/actions/permissions/workflow
```

Repository CI cannot prove this mutable external state. Record the API read-back separately whenever the setting changes and before a release authorization decision.

### Static workflow policy

`.github/workflows/release.yml` must retain all of these properties:

- top-level `contents: read`;
- an exact `push`-to-`main` trigger plus a reviewed two-job and Action allowlist;
- full commit SHA pins for every third-party Action;
- safe YAML parsing with duplicate keys, recursive keys, collection aliases, extra documents, and any semantic structure outside the reviewed workflow rejected;
- exact, block-form permissions on the job that needs each capability, with no `write-all` or inline permissions;
- no arbitrary `run` steps or unreviewed environment injection in privileged jobs;
- Release Please using the default `GITHUB_TOKEN`, with no PAT, custom secret, or GitHub App credential;
- release checkouts using `persist-credentials: false` and the exact Release Please output SHA;
- image, SBOM, and provenance publication gated by `release_created == 'true'`.

The root Gradle task `verifyReleaseWorkflowSecurity` checks this static policy and is part of `check`:

```powershell
.\gradlew.bat verifyReleaseWorkflowSecurity
```

The task compares the complete parsed workflow tree with the reviewed policy and runs negative mutation fixtures for approval/merge steps, permission drift, trigger drift, duplicate keys, and misplaced checkout controls. Because the verifier and workflow live in the same repository, this is a review and drift-detection control, not an independent trust anchor; branch protection and human review must cover changes to either one.

## Authorization and failure boundary

Creating or updating the Release Please PR does not authorize any later side effect. Before merging that PR, authorization must explicitly cover the merge and the tag, GitHub Release, GHCR images, SBOMs, and provenance that the merge automatically triggers. Authorization for “merge” alone is insufficient.

If Release Please reports no release, the `images` job must be skipped. A failed or cancelled Release Please job must not publish artifacts. Enabling GitHub's combined setting does create residual capability: a compromised release job with pull-request write permission could attempt to approve a PR. SHA pins, job-scoped permissions, branch protection, human review, and explicit authorization reduce but do not eliminate this platform-level risk.

## 2026-08-17 audit snapshot

- Repository setting read-back: default `read`, combined create/approve setting enabled.
- Release workflow run [32005220447, attempt 2](https://github.com/maoyouaa/AegisRoute/actions/runs/32005220447) completed successfully.
- Release Please created [PR #8](https://github.com/maoyouaa/AegisRoute/pull/8) for `0.1.0`.
- The `images` job was skipped; no tag or GitHub Release existed after the run.
- The operator token lacked `read:packages`, so GHCR package enumeration was not independently verified. The skipped job is workflow evidence, not a complete registry audit.

This snapshot is time-bound. Re-run the live audits before making a release decision.
