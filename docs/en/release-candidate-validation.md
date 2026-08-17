# Release candidate validation

Release Please opens its PR with the default `GITHUB_TOKEN`. GitHub records the resulting `pull_request` workflow runs as `action_required` without jobs, so those entries are not test evidence. A maintainer must manually validate the immutable Release PR head before any merge decision.

## Preconditions

- `.github/workflows/release-candidate.yml` exists on `main` and on the Release Please branch.
- The PR is open, targets `main`, comes from `release-please--branches--main` in this repository, and changes only `.release-please-manifest.json`, `CHANGELOG.md`, and `gradle.properties`.
- The maintainer has inspected the live PR file list before dispatch and confirmed that no workflow file is changed.
- The maintainer has copied the current full 40-character head SHA. If the branch moves, previous evidence is stale.

## Dispatch

Dispatch the workflow on the Release PR branch, not on `main`. This binds the workflow run and required check names to the candidate commit:

```powershell
$candidatePr = 8
$candidateBranch = 'release-please--branches--main'
$candidateSha = gh pr view $candidatePr --repo maoyouaa/AegisRoute --json headRefOid --jq .headRefOid

gh workflow run release-candidate.yml `
  --repo maoyouaa/AegisRoute `
  --ref $candidateBranch `
  -f pr_number=$candidatePr `
  -f head_sha=$candidateSha
```

The first job fails closed unless the dispatch SHA, live PR head SHA, repository, branch, base, and exact three-file allowlist all match. No repository source tree is checked out before these checks pass.

## Gates and evidence

The workflow runs Linux build/integration tests, Windows checks, coverage, Compose config, Dependency Review, Gitleaks, CodeQL, and the two-Gateway acceptance test. Every checkout uses the validated head SHA with `persist-credentials: false`. A final artifact records the PR number, base/head SHAs, run identity, actor, and every job result.

The workflow is read-only except for CodeQL `security-events: write`. It has no package, provenance, attestation, release, tag, or image publication permission. A successful run is validation evidence only; it does not authorize merging the PR or publishing any artifact.

## Trust boundary and residual risk

GitHub loads the workflow definition from the selected candidate ref so that checks are associated with the candidate commit. The runtime allowlist therefore cannot independently prove the integrity of the workflow that performs that same check. The v0.1 trust boundary combines a live pre-dispatch human review, the same-repository Release Please branch, an exact candidate SHA, read-only default workflow permissions, and the runtime checks above. A stronger independent trust anchor would require a separately protected workflow controller or GitHub App and is outside v0.1. If the PR changes a workflow file, do not dispatch or merge it as a Release Please candidate.
