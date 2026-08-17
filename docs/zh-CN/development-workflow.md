# 开发流程

AegisRoute 使用短分支、Conventional Commits、双语 Issue/PR、必需 CI 和 squash merge。可执行规则以根目录 `AGENTS.md` 与 `CONTRIBUTING.md` 为准。

v0.1 依次经过仓库基线、Streaming Gateway、版本化 Control Plane、隔离的 Shadow Evaluation、Canary/回滚和 Release Evidence 六个门禁。只有测试和证据产物存在时门禁才算完成，经过的时间不能代替验收。

Release Please 维护 Release PR、CHANGELOG、tag 和 GitHub Release。只有同一条 `main` workflow 中 `release_created=true` 时才运行发布 job，避免 PAT 和递归 workflow 假设。

仓库默认 workflow 权限始终保持只读。GitHub 的“创建并批准拉取请求”组合设置，仅用于允许 Release Please 创建提案；workflow 中没有批准或合并步骤。可变的远端仓库设置与静态 workflow 边界必须按照 [Release 权限边界](release-permission-boundary.md) 分别审计。创建 Release PR 不代表获得合并或发布 Release 产物的授权。
