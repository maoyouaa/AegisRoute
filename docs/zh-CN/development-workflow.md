# 开发流程

AegisRoute 使用短分支、Conventional Commits、双语 Issue/PR、必需 CI 和 squash merge。可执行规则以根目录 `AGENTS.md` 与 `CONTRIBUTING.md` 为准。

v0.1 依次经过仓库基线、Streaming Gateway、版本化 Control Plane、隔离的 Shadow Evaluation、Canary/回滚和 Release Evidence 六个门禁。只有测试和证据产物存在时门禁才算完成，经过的时间不能代替验收。

Release Please 维护 Release PR、CHANGELOG、tag 和 GitHub Release。只有同一条 `main` workflow 中 `release_created=true` 时才运行发布 job，避免 PAT 和递归 workflow 假设。
