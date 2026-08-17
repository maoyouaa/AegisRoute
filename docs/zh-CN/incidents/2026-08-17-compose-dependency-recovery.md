# 事故报告：容器运行时恢复后 Compose 依赖未自动恢复

- 状态：本地已缓解并验证；等待 CI 验证
- 时间窗口（UTC）：2026-08-15 03:50:01 至 2026-08-17 09:44
- 合成环境与 commit SHA：本地 Docker Desktop；基线 `e026013`
- 用户影响：无——仅影响 v0.1 合成演示

## 摘要与发现

在例行仓库状态检查中，发现 Control 与 Worker 处于重启循环，而 Mock Provider 和 Gateway 仍在运行。PostgreSQL 与 Redpanda 在同一时间以 exit code 255 退出，之后没有自动恢复。Control 报告 `UnknownHostException: postgres`，Worker 报告 `No resolvable bootstrap urls given in bootstrap.servers`。

执行 `docker compose up -d --wait` 后，两个依赖恢复，Control 与 Worker 也重新健康。虽然此时 Control 已能返回 route version 7，Gateway 仍处于 live 但 unready、route version 0 的状态；重启 Gateway 后才成功应用 version 7。整个过程不涉及真实用户请求或个人数据。

最初的交互式终端输出没有作为 Release artifact 保存，因此本报告只把精确运行时间视为诊断证据，不作为性能声明。整改后的恢复脚本会把可复现证据写入被忽略的 `build-evidence/compose/runtime-recovery/`，CI 则通过现有 Compose artifact 上传该目录。

## 时间线

| UTC 时间 | 事件/证据引用 |
|---|---|
| 2026-08-15 03:50:01 | Docker inspection 显示 PostgreSQL 和 Redpanda 均以 code 255 退出，restart policy 均为 `no`。 |
| 2026-08-17 08:33:29 | Gateway 在依赖仍不可用时启动。 |
| 2026-08-17 09:41 | 检查发现 Control 与 Worker 重启循环，日志分别指向无法解析的 `postgres` 和 `redpanda` 服务名。 |
| 2026-08-17 09:42 | `docker compose up -d --wait` 在不删除 volume 的情况下恢复 PostgreSQL、Redpanda、Control 与 Worker。 |
| 2026-08-17 09:44 | Control 返回 route version 7，但 Gateway readiness 仍返回 503、route version 0。 |
| 2026-08-17 09:44 | 重启 Gateway 后 readiness 恢复并应用 route version 7。 |

## 证据链

这是基础设施恢复事故，不是 candidate policy 事故，因此没有创建新的 Evidence Window 或 Rollback Decision。PostgreSQL 中已有的不可变 route revision 7 得以保留，并在 Gateway 重启后重新应用。回归场景分别向 PostgreSQL 注入 `SIGQUIT`、向 Redpanda 注入 `SIGTERM`，然后记录依赖 restart count、恢复窗口中的五次 baseline response、故障前后 route version，以及最终 Control/Worker health。

本地整改验证于 `2026-08-17T09:55:35Z` 开始：两个依赖的 restart count 都成功增加，五次 baseline request 全部返回，route version 保持为 7，最终 Control 与 Worker health 均为 `UP`。在获得授权并由 PR CI 上传 artifact 之前，这份机器可读输出仍只属于本地证据。

## 根因与促成因素

已验证根因：

- 只有 Java 服务继承了 `restart: unless-stopped`；PostgreSQL、Redpanda、Edge、Prometheus 与 Grafana 使用 Docker 默认的 `no` restart policy；
- 因此容器运行时中断后，PostgreSQL 与 Redpanda 一直停止，而依赖它们的 Java 服务不断重启。

已验证的代码风险：

- Route Snapshot polling 与 acknowledgement 使用没有边界的阻塞 WebClient 调用。代码检查确认，不可用的 Control 请求可能无限占用单一 scheduled poll execution；
- 默认 Compose 没有为 Redpanda 定义显式 healthcheck，因此 `docker compose up --wait` 只能把进程启动当作就绪。

未知项与可观测性缺口：

- 没有保留两个依赖同时以 code 255 退出的外部原因；
- 没有采集 unready Gateway 的 thread dump，因此无法证明具体是哪一次网络操作占用了 polling execution。

## 整改项

| 行动 | 负责人 | 截止 | 验证方式 |
|---|---|---|---|
| 所有长驻 Compose 服务统一使用 `restart: unless-stopped`。 | Maintainer | v0.1.0 | 静态 Compose policy verifier 与依赖进程崩溃测试。 |
| 为 Redpanda 增加显式集群健康探针。 | Maintainer | v0.1.0 | `docker compose up --wait` 与 policy verifier。 |
| 限制 Gateway 对 Control 的 polling 与 acknowledgement 请求时间。 | Maintainer | v0.1.0 | 单元测试证明 timeout 后下一次 poll 能成功。 |
| 在 CI 保存机器可读的恢复证据，任何 baseline probe 失败都必须 fail closed。 | Maintainer | v0.1.0 | Readiness preflight 与 `verify-compose-crash-recovery.ps1` artifact。 |

这些控制只改善可复现的本地/CI 环境，不构成生产可用性或灾难恢复保证。
