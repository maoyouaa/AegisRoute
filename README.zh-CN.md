# AegisRoute

[English](README.md)

面向 AI 推理流量的证据驱动发布与自适应路由平台。

> 当前状态：**v0.1 正在实现。** 本地合成数据核心闭环已实现并可复现；GitHub CI 与 Release 证据仍待完成。本地验收结果不代表生产性能。

AegisRoute 位于应用与 OpenAI-compatible 推理部署之间。v0.1 重点证明一条可靠性闭环：baseline 服务不受 shadow 工作影响，系统收集候选模型的确定性证据，由人工批准 canary 流量，并在策略违规后自动移除 candidate。

```text
Baseline serving
  -> isolated shadow
  -> deterministic evidence
  -> human-approved canary
  -> automatic rollback
  -> gateway convergence evidence
```

## 工程重点

- Java 21、Spring Boot 4.1、WebFlux、Gradle Kotlin DSL；
- 不可变、单调递增的 Route Snapshot；
- 有界本地队列到 Redpanda Publisher 的两级 shadow 隔离；
- 仓库内 JSON Schema 事件合同；
- optimistic concurrency 和幂等 Control mutation；
- 首个 SSE token 后禁止透明重试；
- append-only rollback decision 和多 Gateway 收敛证据。

## 当前能力状态

| 能力 | 状态 |
|---|---|
| 产品与架构规格 | 已文档化 |
| 仓库与构建基线 | 已实现；Windows 本地检查通过，GitHub CI 待运行 |
| Streaming Gateway | 已实现；完整端到端 SSE 矩阵待完成 |
| Shadow/Evaluation Pipeline | 已实现；本地 Compose 链路已验证 |
| 人工 Canary 与自动回滚 | 已实现；本地双 Gateway 收敛已验证 |
| 性能与可靠性数据 | 尚未测量 |
| 生产 IAM/GDPR 合规 | 不在范围内 |

## 构建

前置条件为 Git 与 Docker Desktop。Gradle Wrapper 可在 Java 17+ 启动，并通过 Foojay 获取 Java 21 Toolchain。

```powershell
.\gradlew.bat clean check
docker compose up --build --wait
docker compose up --build --wait --scale gateway=2
.\scripts\acceptance.ps1
```

验收脚本只使用合成数据，验证自动回滚证据链、双 Gateway 收敛、启动/LKG 行为和 broker 故障隔离。原始本地输出写入被忽略的 `build-evidence/`，CI 会上传等价证据 artifact。仓库工作流与架构不变量见 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [AGENTS.md](AGENTS.md)。

## 文档

- [架构说明](docs/zh-CN/architecture.md)
- [最终产品文档](docs/zh-CN/product.md)
- [威胁模型](docs/zh-CN/threat-model.md)
- [开发流程](docs/zh-CN/development-workflow.md)
- [Release 权限边界](docs/zh-CN/release-permission-boundary.md)
- [最终产品规格](docs/zh-CN/AegisRoute_Product_Spec_Final.md)
- [项目审阅](docs/zh-CN/AegisRoute_Project_Review.md)
- [原始设计](docs/zh-CN/AegisRoute_Product_Design.md)

## v0.1 明确不做

React、Redis 配额、Etcd 节点发现、LLM Judge、Schema Registry、公网部署、生产 IAM、真实用户数据和合规认证。

## 许可证

Apache License 2.0。
