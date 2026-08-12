# AegisRoute v0.1 最终产品文档

## 产品定位

AegisRoute 是一个面向 OpenAI-compatible 推理服务的可靠性发布闭环，也是用于爱尔兰后端、平台工程与 SRE 求职的公开作品。它重点证明：如何在不把 shadow 工作放进 baseline 响应关键路径的前提下评估候选部署，如何只由人工增加流量，以及如何通过可追溯的确定性证据自动移除故障 candidate。

v0.1 只使用合成流量与确定性 Mock Provider，不是生产服务，也不声明 GDPR、安全认证或生产规模。

## 问题与目标结果

推理部署不能只依赖离线测试完成发布判断。一个可信的发布过程必须保护当前服务、明确控制操作的并发语义，并让事故后的每个决策都可以重建。

v0.1 验收链路为：

```text
baseline serving
  -> 隔离 shadow
  -> baseline/candidate 确定性配对证据
  -> 人工批准 canary [1, 10, 50, 100]
  -> 连续三个 5 秒窗口违规
  -> immutable rollback decision
  -> 更高版本、candidate ratio 为 0 的 Route Revision
  -> 所有决策时活跃 Gateway acknowledgement
```

## 用户与任务

- 平台工程师创建 rollout 并启动 shadow，且不降低 baseline 可用性；
- 发布负责人检查证据并逐级人工批准 canary；
- 事故响应者可以重建 Evidence、Policy Evaluation、Decision、Route Revision 与 Gateway Convergence；
- 面试官或代码审阅者可以从公开仓库复现合同、启动、隔离、持久化与 Compose 检查。

## 功能合同

Control mutation 使用幂等与 optimistic concurrency。创建接口要求 `Idempotency-Key`，状态变更还要求强 `If-Match`。缺少前置条件返回 428，版本过期返回 412，非法状态转换返回 409，同一幂等 key 跨 payload/endpoint 复用返回 409；结果保留 24 小时。

Gateway 每秒拉取 checksum-valid、不可变 Route Snapshot。第一次得到有效 Snapshot 前 live 为 UP、ready 为 DOWN，serving 返回 `503 ROUTE_SNAPSHOT_UNAVAILABLE`；初始化完成后即使 Control 故障也继续使用 LKG。Gateway 不查询 PostgreSQL。

Shadow 入口只是向按消息数和字节数双重限界的本地队列执行 non-blocking offer。独立 Publisher 线程承担 Kafka metadata、ack、retry 和 delivery timeout。队列压力与 broker 故障只能按有限原因丢弃 shadow，不能让 baseline 失败。

Worker 先用仓库内 JSON Schema 校验事件，再调用 candidate；它负责事件去重、按 `sampleId` 配对 baseline/candidate、清理超时未配对结果，并聚合 candidate serving window。v0.1 不允许自动晋级，只有确定性 breach policy 可以发起回滚。

## 质量属性

- 安全：首个 SSE token 后禁止透明 retry；v0.1 不存在自动 promotion；
- 可审计：Route Revision 与 Rollback Decision 是 append-only、版本化证据；
- 可用性：baseline 响应工作没有数据库或 broker 依赖；
- 可复现：Java 21、Spring Boot 4.1.0、Gradle 8.14.5、JSON Schema、完整 SHA 固定的 GitHub Actions 与合成数据；
- 范围控制：不做 React、IAM 产品、Redis quota、Etcd、LLM Judge、Schema Registry、公网部署与真实个人数据。

## 证据与发布门槛

单元/合同测试不是完整 Release 证据。v0.1.0 还必须保存 Ubuntu/Windows CI、Testcontainers、Compose smoke、双 Gateway convergence chaos、rollback decision 样例、Grafana 时间线、双语 ADR/威胁模型/事故报告、三分钟真实演示、镜像 digest、SBOM、provenance 与 commit SHA。

这些产物齐全前，公开状态保持 “implementation in progress”。2026-08-12 的一次本地双 Gateway Compose 验收保存了 1.111 秒回滚收敛结果，以及启动/LKG 与 broker 故障证据；它证明本地验收链路，不代表生产性能。在 CI 与 Release 产物齐全前，`<5s` 仍是发布门槛。

## 可复现本地验收

从全新的合成 Compose 数据库开始，将 Gateway 扩为两个实例并运行验收脚本：

```powershell
docker compose down --volumes
docker compose up --build --wait --scale gateway=2
.\scripts\acceptance.ps1
```

脚本验证人工 Canary 阶梯、连续三个确定性 breach window、不可变 decision 和 ratio-zero revision、两个 Gateway acknowledgement、无 Snapshot 启动、Control 故障时 LKG，以及 Redpanda 故障时 baseline 独立性。它在本地保存原始 evidence，CI 会上传等价目录作为 artifact。

## 爱尔兰求职叙事

项目用于展示可被深入追问的后端与平台工程能力：Reactor critical-path 隔离、HTTP precondition/幂等、immutable evidence、SSE failure boundary、PostgreSQL 数据库约束、Kafka backpressure 边界、多实例收敛和软件供应链控制。简历在证据未完成前使用 `Designed` 或 `In progress`；只有完成并保存对应证据后才使用 `Built`、`Implemented`、`Validated` 和实测数字。
