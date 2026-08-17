# 威胁模型 — v0.1 合成演示

## 资产与边界

保护对象包括 Route 完整性、Rollout Decision、合成事件、Release 产物与服务可用性。Edge 是唯一发布到宿主机的应用服务；Gateway 横跨入口与 `aegis-internal`，Control、Worker、PostgreSQL、Redpanda、Prometheus、Grafana 和 Mock 节点只在内部网络。dev override 是 Control 本机调试的显式例外。

## 主要威胁与控制

| 威胁 | v0.1 控制 | 剩余边界 |
|---|---|---|
| 过期或伪造 Route | SHA-256 checksum、单调版本、原子替换、LKG | 本地 Compose 不提供签名信道 |
| Mutation replay/双执行 | 24 小时幂等记录与事务 advisory lock | 没有生产身份绑定 |
| Lost update | 强 `If-Match` 与 optimistic version update | actor 仍是合成元数据 |
| Shadow 资源耗尽 | 消息/字节双重限界、独立线程/连接、有界 delivery | 生产前需要实测调参 |
| Secret/个人数据泄漏 | 仅合成 fixture、不记录 Authorization、secret scan | 没有生产 DLP |
| Decision 篡改 | append-only 表与数据库触发器拒绝 update/delete | 数据库管理员仍属于信任边界 |
| 供应链替换 | Wrapper、Action 完整 SHA、Release digest/SBOM/provenance | 基础镜像补丁策略仍需运营流程 |
| CI token 滥用 | Supply Chain job 默认仅可读仓库内容；checkout 不持久化凭据；Gitleaks 只在扫描 step 获得临时 token，且禁用 PR 评论 | 被攻陷的第三方 Action 在该 step 中仍可读取仓库内容与 job-scoped token |

## 明确不声明

本文不证明 GDPR、租户隔离、公网 IAM、渗透测试覆盖或生产就绪。这些能力需要不同的数据、身份、托管和运维边界。

## Release candidate 验证边界

Release candidate 检查由维护者针对本仓库 Release Please 分支的不可变 head SHA 手动触发。在 checkout 仓库源码树之前，workflow 会验证远端 open PR、`main` base、Release Please 分支、dispatch/head SHA 完全一致、提交祖先关系，以及严格的三个文件 changed-path allowlist。此后除 CodeQL 结果上传外，所有 job 都保持只读；这能检测正常 candidate 漂移，并排除发布权限。

剩余风险仍属于运维边界：维护者可能复制错误的 PR 编号或 SHA，candidate 可能在运行后移动，GitHub 仓库设置也可以在 Git 之外变化。GitHub 还会从 candidate ref 加载 workflow 定义，因此运行时 allowlist 不能独立证明同一 workflow 的完整性。workflow 会在发现不一致时失败并保存 run manifest，但 dispatch 与 Release 授权仍要求重新审计远端状态并人工审查。参见 [Release candidate 验证](release-candidate-validation.md)。
