# 当前状态与 v0.1 路线图

[English](../en/status-and-roadmap.md)

快照：**2026-09-05 UTC**。本地实现、冻结的合成验收和 GitHub 状态分别陈述。
本文档不授权任何外部写入。

## 当前交付

第一轮可靠性链路已在 `main` 之外实现。冻结 run 为 `aegisroute-20260905-002645`，
源 HEAD 为 `9983b647225cd4ec6ba6702785c1839be3225df9`，源码清单 SHA-256 为
`39086a2711ffa095286abffb8ab9cbf81f4ddfc4533962217641c5c28dcf4778`。
原始文件保持不变。[精选证据包](../evidence/reliability-20260905/README.md)保留了
复算窗口、回执、ACK 和测量所需的原始合成事实。

| 工作项 | 当前证据 | 边界 |
|---|---|---|
| Shadow 生命周期与候选归属 | 快照绑定策略，排除 DRAFT/PAUSED/ROLLED_BACK，仅 baseline 请求 shadow，拒绝错路由/候选 | 单一合成全局路由 owner，不声明弹性成员管理 |
| 持久证据和去重 | SQLite 恢复、业务身份、稳定窗口、回执重试、换 event ID 重放 | 崩溃可能重复候选副作用；磁盘丢失与 HA 未验证 |
| 人工晋级 | 1/10/50/100 每档均使用新的、足量的当前路由证据 | 人工发起，无自动晋级 |
| 回滚及收敛 | 手动/策略共用事务、不可变目标、追加精确 ACK、离线目标待确认 | 固定 A/B 验收拓扑 |
| HTTP/SSE | 14 个真实客户端用例，deadline、取消、`[DONE]`、故障隔离 | 与 #12 的错误码语义仍须协调 |
| 契约和观测 | v2 schema/topic、运行时校验、低基数指标、实际 Grafana 证据 | 保留已发布 v1，不重解读或重复计数 |
| 测量 | 六轮 1,440 请求，720 个完整 shadow 样本，未观察到错误/丢弃 | 固定顺序及共享主机噪声，不声明容量或零开销 |

原始 Wrapper 运行了 85 个测试，其中 17 个 PostgreSQL 集成测试，失败、错误、跳过
均为 0。该结果绑定冻结源码，不能代替后续合并验证。交付整理追加了
[v2 验收集成说明](../en/adr/0004-v2-acceptance-delivery.md)：Worker 持久卷、新项目
驱动、完整 localhost 端口映射及 CI 入口。[交付树重新验收](../evidence/reliability-20260905/delivery-validation.json)
通过 85 测试（17 集成）、14 个 HTTP/SSE 用例及 20 个封闭窗口的完整进程对账。
在线策略回滚为 1101.66 ms（<5000 ms），缓存服务/无快照启动及依赖 PID 1 自动恢复
也通过，失败尝试分别保留。整理阶段没有改变 Java 业务行为、v1 schema 或已应用
迁移；上面的基准测量和 UI 截图仍属于原冻结运行。

## GitHub 实时核对快照

原始读回：[github-snapshot.json](../evidence/reliability-20260905/github-snapshot.json)。
`main` 为 `e0260134b5e438534606fb3a08ba9364334cd36c`，其
[CI](https://github.com/maoyouaa/AegisRoute/actions/runs/32005220401)和最近观测到的
[CodeQL](https://github.com/maoyouaa/AegisRoute/actions/runs/33379484126)通过；
这些结果只覆盖 main，不能据此声称本交付分支通过远程验证。

| PR | 精确 head | 读回状态 | 与本轮关系 |
|---|---|---|---|
| [#15 运行时恢复](https://github.com/maoyouaa/AegisRoute/pull/15) | `9983b64` | Draft，9 项成功 | 交付基线，比 main 多一个提交 |
| [#12 流式故障契约](https://github.com/maoyouaa/AegisRoute/pull/12) | `129b9d7` | Draft，9 项成功 | 兄弟分支，非祖先；Gateway/测试/ADR 冲突 |
| [#13 Release 候选验证](https://github.com/maoyouaa/AegisRoute/pull/13) | `d42c13b` | Draft，8 项成功 | CI/威胁模型重叠，仍调用旧验收流程 |
| [#9 Release 权限边界](https://github.com/maoyouaa/AegisRoute/pull/9) | `0ca73ad` | Draft，8 项成功 | AST verifier 修复已推送，未包含于本分支 |
| [#8 release 0.1.0](https://github.com/maoyouaa/AegisRoute/pull/8) | `b6d310a` | Open，无有效 PR 检查 | 仅发布准备，不代表发布授权 |

表中的四个稳定化 PR 均未合并；没有发现 tag 或 GitHub Release。main 保护要求
`linux`、`windows`、`compose-config`、`analyze`，其他检查目前不是必需项。
本轮没有修改保护规则或权限。

## 集成顺序与决策

1. 本轮先作为以 `fix/14-compose-runtime-recovery` 为 base 的 **stacked Draft PR**
   审阅，让 #15 的已有提交留在依赖层。任何获准发布前都要再次刷新双方 head，
   不向旧 PR 的分支推送本轮代码。
2. #15 通过审阅并获得对应合并授权后，再与新 main 协调、重定向本轮 PR。
   squash merge 会改变祖先关系，必须检查实际差异，不能假定依赖会自动消失。
3. #12 单独协调：它要求上游 500 返回 500，本轮实际验收为 502；还要处理嵌套
   传输异常分类、v1/v2 测试夹具，以及两个不同文件均占 ADR 0003 的冲突。
   对保留的决定明确编号和取代关系，不机械覆盖已验收 Gateway。
4. #9/#13 单独集成，保留 #9 的 AST 校验和最小权限。#13 仍使用正则/字符串
   校验，须替换为拒绝重复键的结构化 YAML 校验后才能信任其门禁；与 v2 协调时更新
   Release 候选拓扑、验收入口和证据路径。其绿灯不证明已执行真实候选验收。
   不自动关闭或覆盖兄弟 PR。
5. 明确获准提交/发布后，在新的精确 PR head 和最终合并结果上要求 Linux、
   Windows、PostgreSQL 集成、coverage、Compose、`two-gateway`、CodeQL、
   dependency review、secret scan。普通分支 push 不触发 CI 的 main-only push
   规则；须创建 PR 或执行获准的 workflow dispatch。

## 仍待通过的发布门槛

- 本地交付验收已通过；仍须精确新 PR head 的远程检查、审阅和获准集成，才能
  转 Ready 或声称主线完成。
- 24 小时过期 Idempotency-Key 的替换问题（旧 C5）仍属独立工作项。本轮未纳入
  另一个 expiry 工作树，也未声称已修复该问题。
- 默认 Compose 容器重建后的动态 Gateway 成员管理不在完整验收拓扑内；
  当前已验证链路使用固定 A/B 成员。
- 协调兄弟 PR 的设计冲突，审阅 Release 权限/候选门禁，并保存精确合并 SHA
  的证据。#8、tag、镜像、SBOM/provenance 和发布声明须后续单独决定。

v0.1 仍排除 React、Redis/Etcd、真实模型/个人数据、LLM judge、云、生产 IAM 和
合规。这些排除项不会被自动转为后续任务。
