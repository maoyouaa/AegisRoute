# AegisRoute

[English](README.md)

为 OpenAI 兼容推理服务提供基于证据的发布控制。

AegisRoute 保持 baseline 响应独立于 shadow 工作，持久化候选评估证据，要求人工逐档
批准 canary，并在确定性策略违规后移除候选。网关确认必须匹配回滚目标的精确
route ID、version 和 checksum。

```text
baseline 服务 → 隔离 shadow → 可恢复证据
              → 人工 canary → 自动回滚 → 精确网关 ACK
```

**状态，2026-09-05：**第一轮可靠性链路已实现并在本地合成环境中验收。本交付分支
没有合入 `main` 或发布。各 PR 的检查结果只覆盖各自的提交。
依赖关系见[当前路线图](docs/zh-CN/status-and-roadmap.md)。

| 能力 | 证据及边界 |
|---|---|
| Java 21 / Spring Boot / WebFlux 网关 | 14 个实际 HTTP/SSE 用例，覆盖 JSON `stream`、`[DONE]`、取消和首 token 前后故障 |
| Shadow 隔离 | 仅 baseline-served 请求入队；有界队列、独立 broker 发布线程，候选执行绑定不可变路由 |
| 可恢复证据 | SQLite inbox/outbox、业务样本去重、稳定窗口及 Control 回执；已验证进程终止与重放 |
| 人工 canary | 显式 1/10/50/100 批准，每档要求新的、足量的当前路由窗口 |
| 回滚及收敛 | 手动与策略回滚共用决策链，追加目标和精确 A/B ACK；离线网关保持待确认 |
| 本地测量 | 六轮各 240 请求，未观察到错误或丢弃；p95 开减关为 −1.724、−0.325、+0.653 ms |

测量使用同一 Windows/Docker 主机上的合成 mock 和热进程，顺序固定为先关后开。
报告披露了噪声及额外 Worker/candidate CPU，不能据此声称零开销或生产性能。

## 运行与验证

需要 Git、运行 Linux 容器的 Docker、Python 3.12+，以及用于启动仓库 Gradle Wrapper
的 Java 17+。Wrapper 通过 Foojay 获取 Java 21。在仓库目录运行：

```powershell
# 创建新项目与新证据目录，使用 localhost 独立端口。
.\scripts\acceptance.ps1
# 若 18080–18085 / 13000 / 19090 已占用，可加 -PortOffset 10000。
# -Benchmark 追加六轮测量；-KeepRunning 保留 Grafana 服务供查看。
```

Linux 入口：

```bash
python3 scripts/reliability/run.py --project aegis-demo-$(date +%Y%m%d-%H%M%S) --output build-evidence/demo-$(date +%Y%m%d-%H%M%S)
```

入口执行 `clean check integrationTest`、构建 jar、启动固定 A/B 拓扑，并运行实际
传输、Worker 恢复、重放、新鲜证据晋级、回滚及依赖自动恢复。原始输出写入忽略的
`build-evidence/`；只停止自己的项目，保留持久卷。失败或中断后重跑须用新的项目和
输出目录。参数及分步命令见[复现指南](scripts/reliability/README.md)。

`docker compose up --build --wait` 保留为 **baseline 冒烟拓扑**。它使用动态 Gateway
身份，不能证明容器重建后的 v2 收敛；完整生命周期验收必须使用
`compose.reliability.yml` 的固定 A/B 成员。默认拓扑的 Control 仅内部可达，
合成验收 inspector 只绑定 localhost。

无需 Docker 即可复算精选历史证据：

```powershell
python scripts/reliability/verify_bundle.py
```

## 查看结果

- [精选证据与测量](docs/evidence/reliability-20260905/README.md)
- [当前状态与集成计划](docs/zh-CN/status-and-roadmap.md)
- [可恢复证据 ADR](docs/en/adr/0003-recoverable-route-bound-evidence.md)
- [验收入口集成 ADR](docs/en/adr/0004-v2-acceptance-delivery.md)
- [架构](docs/zh-CN/architecture.md) · [产品范围](docs/zh-CN/product.md)
- [威胁模型](docs/zh-CN/threat-model.md) · [贡献流程](CONTRIBUTING.md)

## v0.1 范围

单一合成全局路由 owner、单个持久化 Worker。不包含 React、Redis quota、Etcd、
LLM judge、Schema Registry、真实模型/数据、云部署、生产 IAM 或合规声明。
磁盘丢失、Worker HA 和 provider 副作用恰好一次尚未验证。崩溃后候选执行可能重复，
证据计数仍按业务身份去重。晋级保持人工发起。

Apache License 2.0。
