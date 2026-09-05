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


## 可靠性 v2 本地演练补充

Worker 在持久化入库前校验完整业务身份、schema、路由 checksum 和 Control 保存的不可变版本；
事件自带 URL 与自算 checksum 不能单独授权候选调用。SQLite 私有卷被视为可信；本轮证明进程重启，
不证明磁盘丢失、篡改恢复或 HA。默认关闭 mock 的 `/internal/faults`、`/internal/stats`，关闭时返回 404；
显式开启后仅接受有限的合成故障枚举与有界参数。测试验证默认拒绝、参数边界和实例隔离。
任务专属 Compose 的 Nginx 仅绑定 127.0.0.1，方便检查 Control、故障端点与 Grafana；
这是本地演练例外，不能视为面向公网的身份认证。内部节点仍受信任，不声明生产安全或合规。
