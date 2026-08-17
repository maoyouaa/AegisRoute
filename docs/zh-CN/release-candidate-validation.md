# Release candidate 验证

Release Please 使用默认 `GITHUB_TOKEN` 创建 PR。GitHub 会把由此产生的 `pull_request` workflow 记录为没有 job 的 `action_required`；这些记录不是测试证据。任何合并决策前，维护者必须手动验证不可变的 Release PR head。

## 前置条件

- `.github/workflows/release-candidate.yml` 同时存在于 `main` 与 Release Please 分支；
- PR 仍为 open、目标为 `main`、来源是本仓库的 `release-please--branches--main`，并且只修改 `.release-please-manifest.json`、`CHANGELOG.md` 与 `gradle.properties`；
- 维护者已在 dispatch 前检查远端 PR 的实时文件列表，并确认没有 workflow 文件变更；
- 维护者已复制当前完整 40 位 head SHA。分支一旦移动，旧证据即失效。

## 触发方式

必须在 Release PR 分支而不是 `main` 上 dispatch，使 workflow run 和 required check 名称绑定到候选提交：

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

第一个 job 会 fail closed：dispatch SHA、远端 PR head SHA、仓库、分支、base 和严格的三个文件 allowlist 必须全部一致。在验证通过前不会 checkout 仓库源码树。

## 门禁与证据

workflow 会运行 Linux build/integration test、Windows check、coverage、Compose config、Dependency Review、Gitleaks、CodeQL 和双 Gateway acceptance。所有 checkout 都使用已验证的 head SHA，并设置 `persist-credentials: false`。最终 artifact 会记录 PR 编号、base/head SHA、run identity、actor 与每个 job 结果。

除 CodeQL 的 `security-events: write` 外，workflow 保持只读；它不具备 package、provenance、attestation、Release、tag 或镜像发布权限。运行成功只构成验证证据，不授权合并 PR 或发布任何产物。

## 信任边界与剩余风险

为了让 checks 归属 candidate commit，GitHub 会从选定的 candidate ref 加载 workflow 定义。因此，运行时 allowlist 不能独立证明执行这项检查的 workflow 自身完整。v0.1 的信任边界由 dispatch 前的实时人工审查、本仓库 Release Please 分支、精确 candidate SHA、默认只读 workflow permission，以及上述运行时校验共同组成。若要建立更强的独立信任锚，需要单独保护的 workflow controller 或 GitHub App，这超出 v0.1。只要 PR 修改了 workflow 文件，就不得把它作为 Release Please candidate 触发或合并。
