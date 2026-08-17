# Release 权限边界

## 目的

AegisRoute 使用仓库级、临时的 `GITHUB_TOKEN` 运行 Release Please。GitHub 把“允许 Actions 创建 PR”和“允许 Actions 批准 PR”合并为同一个仓库设置。AegisRoute 启用该设置，只是因为 Release Please 必须创建 Release 提案；workflow 本身不批准或合并 PR。

这是最小权限边界，不构成生产安全或合规声明。

## 两个独立验证层

### 可变的 GitHub 仓库设置

远端预期设置为：

```json
{
  "default_workflow_permissions": "read",
  "can_approve_pull_request_reviews": true
}
```

使用只读 API 调用审计：

```powershell
gh api repos/maoyouaa/AegisRoute/actions/permissions/workflow
```

仓库 CI 无法证明这种可变的外部状态。每次设置变化后，以及作出 Release 授权决定前，都必须单独保存 API 回读证据。

### 静态 workflow 策略

`.github/workflows/release.yml` 必须保持以下属性：

- 顶层 `contents: read`；
- 所有第三方 Action 固定到完整 commit SHA；
- 写权限只授予实际需要该能力的 job；
- Release Please 使用默认 `GITHUB_TOKEN`，不使用 PAT、自定义 secret 或 GitHub App 凭据；
- Release checkout 设置 `persist-credentials: false`，并使用 Release Please 输出的准确 SHA；
- 镜像、SBOM 与 provenance 发布以 `release_created == 'true'` 为门禁。

根 Gradle 任务 `verifyReleaseWorkflowSecurity` 检查该静态策略，并已接入 `check`：

```powershell
.\gradlew.bat verifyReleaseWorkflowSecurity
```

## 授权与故障边界

创建或更新 Release Please PR，不授权任何后续副作用。合并 Release PR、创建 tag、创建 GitHub Release 和发布 GHCR 镜像，仍然是四项必须分别授权的动作。

如果 Release Please 没有报告 Release，`images` job 必须跳过。Release Please job 失败或取消时也不得发布产物。启用 GitHub 的组合设置会产生剩余能力：被攻陷且具有 pull-request 写权限的 Release job 可能尝试批准 PR。完整 SHA 固定、job 级权限、分支保护、人工审查和明确授权能够降低风险，但不能消除这一平台级风险。

## 2026-08-17 审计快照

- 仓库设置回读：默认权限为 `read`，创建/批准 PR 的组合设置已启用。
- Release workflow [32005220447，第 2 次尝试](https://github.com/maoyouaa/AegisRoute/actions/runs/32005220447) 成功完成。
- Release Please 创建了面向 `0.1.0` 的 [PR #8](https://github.com/maoyouaa/AegisRoute/pull/8)。
- `images` job 已跳过；运行后不存在 tag 或 GitHub Release。
- 操作者 token 缺少 `read:packages`，因此未能独立枚举 GHCR package。job 跳过属于 workflow 证据，不等于完整 registry 审计。

该快照具有时效性。作出 Release 决策前必须重新执行远端审计。
