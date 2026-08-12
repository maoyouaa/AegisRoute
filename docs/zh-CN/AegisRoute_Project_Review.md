# AegisRoute 项目审阅与求职优化结论

> 审阅日期：2026-08-12
> 审阅范围：历史设计稿、当前源码、测试、Compose 与文档
> 结论性质：初始审阅归档；当前实现与证据状态以根 README 和 `docs/zh-CN/product.md` 为准

## 1. 结论先行

AegisRoute 的选题是成立的：它没有把“统一调用多个模型”当作终点，而是聚焦模型变更进入生产流量时的风险控制，把影子流量、持续评测、渐进式灰度、节点路由和自动回滚串成一条闭环。这条主线比普通的聊天应用、RAG 应用或简单 API 聚合器更能证明后端、平台和可靠性工程能力。

项目已经从架构提案进入 **v0.1 implementation in progress**：仓库现有源码、测试、构建脚本、Compose 与本地双 Gateway 自动回滚证据。它仍不是生产级平台；GitHub CI、Release 供应链产物、截图/视频和可推广性能结果尚未完成。

最重要的优化不是再增加功能，而是把设计收敛成一个可重复运行、可注入故障、可生成证据的纵向闭环：

```text
baseline 请求成功
→ candidate 影子执行
→ 确定性评测形成证据
→ 人工批准进入 canary
→ candidate 故障触发自动回滚
→ 指标、Trace、事件时间线证明全过程
```

如果这个闭环真实完成并有 Linux CI、Testcontainers、故障注入、k6 报告和短视频，AegisRoute 会成为一项有说服力的爱尔兰后端/平台/云运维求职项目。若只完成设计文档，它的求职价值有限。

## 2. 当前证据边界

| 项目证据 | 当前状态 | 可以得出的结论 |
|---|---:|---|
| 产品设计文档 | 有 | 已完成较系统的概念设计 |
| Git 仓库与提交历史 | 无 | 不能判断开发过程与代码所有权 |
| Java/Spring 源码 | 无 | 所有工程能力仍是计划 |
| Docker Compose | 无 | 不能一键运行 |
| 自动化测试 | 无 | 不能证明状态机、配额、路由等语义 |
| CI | 无 | 不能证明 Linux 构建与质量门禁 |
| Benchmark/Chaos 结果 | 无 | 不能使用“高性能”“高可用”等完成时表述 |
| UI、截图、演示视频 | 无 | 面试官不能快速看懂或验证闭环 |

当前最准确的状态标签是：

> **Design complete enough to start implementation; implementation and performance claims are unverified.**

## 3. 现有设计最强的部分

### 3.1 问题定义比“又一个 AI Gateway”更清晰

文档识别到模型发布的真实问题：离线 benchmark 不等于线上表现；候选模型可能在质量、成本、延迟之间产生冲突；SSE 开始后不能无痕重试；错误版本不能一次性影响全部流量。产品核心是“安全变更”，而不是“接入更多 Provider”。

### 3.2 有值得深入追问的工程决策

以下决策具有真实面试价值：

- SSE 输出首个 token 后禁止透明重试；
- Etcd 只维护推理节点 membership，不承载高频遥测；
- shadow 失败不能消耗 baseline 的重试和并发预算；
- LLM Judge 只是证据，不是系统真值；
- Control Plane 故障时 Data Plane 使用已发布的 last-known-good snapshot；
- 故障必须通过指标、Trace 和故障注入证明，而非只写“高可用”。

### 3.3 已主动避免课程项目拼装

原稿知道不应复制四个参考项目的业务、包结构和数据库表，并把自研边界放在 routing、shadow、rollout、failure policy 和 evaluation integration。这一方向正确。

## 4. 当前设计的主要问题

### 4.1 v1 过宽，个人项目很难形成可信完成度

原 v1 同时包含 WebFlux Gateway、多个 Provider、SSE、Redis/Lua、Kafka、Etcd、PostgreSQL、SDK、UI、LLM Judge、自动回滚、回归库、Prometheus、Grafana、OpenTelemetry、Chaos 和基准测试。每项单独都合理，但同时进入六周 v1，会把时间消耗在集成和配置上，使真正差异化的 rollout 闭环缺少深度。

优化原则：

- v0.1 先证明一个完整纵向闭环；
- v1.0 再补节点发现、配额、adaptive routing 和生产证据；
- Java SDK、Multi-Judge、Agent、Kubernetes、完整 IAM 延后；
- UI 只做一页 Rollout Evidence Dashboard，不做通用管理后台。

### 4.2 技术栈先于失败模型

原文说明了各中间件“能做什么”，但还需更精确地回答：

- shadow 请求入队失败是否重试，是否允许丢样本；
- baseline 和 candidate 结果如何关联，缺失一侧时如何过期；
- rollout 更新如何防止两个管理员或两个控制器并发推进；
- route snapshot 如何版本化，旧消息能否覆盖新回滚；
- Redis reserve 成功但请求中断时如何释放并发额度；
- gateway 与 broker 共用资源时，如何证明 shadow 不影响 critical path。

最终版将这些问题落实为 best-effort shadow、单调 route version、optimistic concurrency、独立 worker/budget 和明确的过期/丢弃指标。

### 4.3 自动晋级风险过高

固定“500 个样本 + 若干阈值”不足以证明 candidate 更好。P95 对样本量和窗口很敏感，LLM Judge 也可能漂移。如果平台在没有人工确认的情况下自动从 shadow 升级到真实流量，会让一个求职项目显得追求自动化多于理解风险。

更稳妥的 v1 策略是：

- 系统自动计算 `ELIGIBLE` / `BLOCKED` 和证据；
- 每次增加真实流量都需要人工批准；
- 明确安全阈值触发自动回滚；
- 报告样本数、时间窗和置信区间，不把点估计包装成确定结论。

### 4.4 “影子流量”有隐私和外部副作用风险

把真实 prompt 复制给另一个模型提供方，可能改变数据处理方、区域、保留策略和合同边界。工具调用请求还可能产生重复写操作。原文提到 PII redaction，但不足以覆盖整个数据流。

v1 应明确：

- shadow 默认关闭，按 route 明确启用；
- candidate 默认不获得生产写工具和用户凭据；
- 请求正文、completion 和 queue retention 采用最小保留；
- 演示只用合成数据；
- 文档给出数据流、删除路径和 Provider 边界；
- 项目不宣称“GDPR compliant”，只展示 privacy-by-design 控制和未完成项。

### 4.5 路由公式会出现抖动和惊群

简单选择最低分节点会让多个 Gateway 同时涌向同一节点。延迟、错误率和 active request 的采样频率不同，也不能直接线性相加。

建议实现 Adaptive P2C：按容量加权随机取两个健康节点，比较经过归一化、EWMA 和 stale penalty 处理的分数，再选择较优者。样本不足时退回 Least Active；熔断节点不参与抽样。这样比单纯 `min(score)` 更接近可解释的工程方案。

### 4.6 求职叙事偏向高级岗位，缺少“我亲手交付”的证据

原稿映射 AWS、Google Cloud、Qualcomm 等公司，但公司名映射不能替代岗位要求。2026-08-12 对爱尔兰公开职位的抽样核查显示：

- [MongoDB Cloud Operations Engineer](https://www.mongodb.com/careers/job/?gh_jid=7453323) 强调监控、事件诊断、MTTR、根因分析、Linux、网络、云平台和自动化；
- [Intercom AI Infrastructure Engineer](https://job-boards.greenhouse.io/intercom/jobs/7820671) 强调低延迟、高可靠推理、autoscaling、routing、fallback 和 operational excellence，但它是 Senior+ 方向信号；
- [Toast Senior Software Engineer](https://careers.toasttab.com/jobs?gh_jid=8104971) 强调 Java/Kotlin、API/平台思维、测试质量、跨系统集成和事件架构；
- [Stripe Backend Engineer, Core Technology](https://stripe.com/jobs/search?gh_jid=6686634) 强调大规模分布式系统、可靠性、调试和跨团队交付，但要求资深经验。

这些职位是技术方向样本，不等于用户当前一定满足岗位年限、工作许可或 sponsorship 条件。AegisRoute 最适合支撑以下求职方向：

1. Graduate/Junior Backend Engineer；
2. Platform/Infrastructure Engineer 的初级岗位；
3. Cloud Operations/DevOps/SRE Graduate；
4. AI Platform/ML Infrastructure 的初级或实习岗位。

作品集应突出“我在有限环境中构建并验证了可靠性机制”，不要暗示处理过真实生产规模或真实用户数据。

## 5. 优化优先级

### P0：不完成就不要投递为主项目

1. 初始化 Git 仓库，使用英文 README 和清晰提交历史。
2. 完成 baseline → shadow → evaluation → canary → rollback 纵向闭环。
3. 提供 mock inference node，可注入 latency、HTTP 500、断流和节点死亡。
4. 为状态机、FailureClassifier、route version 和评测策略写单元/属性测试。
5. 使用 Testcontainers 跑 PostgreSQL、Redpanda、Redis、Etcd 集成测试。
6. 使用 Docker Compose 一键启动，确保 Ubuntu CI 可构建和测试。
7. 生成机器可读 benchmark、Chaos 报告和 3 分钟演示视频。
8. 所有公开数字带硬件、提交 SHA、参数和时间戳。

### P1：显著提高面试转化率

1. 实现 Adaptive P2C，并与 Round Robin、Least Active 做同流量对照。
2. 增加一页 Rollout Evidence Dashboard，展示状态时间线、流量比例、回滚原因、节点健康和关键指标。
3. 增加 5 篇 ADR：协议、Etcd 边界、shadow 隔离、SSE 重试、Judge 边界。
4. 写一篇 incident report：注入 candidate 故障后如何检测、回滚和验证恢复。
5. 提供 threat model、数据流图、保留策略和“尚未生产就绪”清单。
6. 在 Linux 上完成复现；Windows 只作为开发环境兼容性补充。

### P2：有真实需求后再做

- Java SDK；
- LLM Multi-Judge；
- Agent 辅助解释；
- Kubernetes/Helm/Operator；
- 多区域容灾；
- 完整 IAM 与 OIDC；
- 计费支付；
- RAG、MCP 或通用 Agent 平台。

## 6. 简历可写与不可写边界

### 当前可写

> Designed AegisRoute, a build-ready architecture for evidence-based AI model rollout, covering shadow evaluation, canary routing, stream-aware failure handling and deterministic rollback policies.

这只能放在“Design/Independent Project in progress”语境中，不能使用 `Built`、`Implemented`、`Load-tested`。

### 完成 v1 并取得真实证据后可写

> Built an OpenAI-compatible Java gateway that isolates candidate-model shadow traffic from the user critical path and supports human-approved canary rollout with deterministic automatic rollback.

> Implemented leased inference-node discovery and adaptive power-of-two-choice routing; validated failure removal, broker degradation and stream-aware retry rules with Testcontainers and fault-injection tests.

> Sustained **[measured throughput]** on **[documented hardware]** at **[measured P95 overhead]**, with candidate traffic removed within **[measured time]** after an injected SLO breach.

方括号必须替换成真实报告中的数值。

### 即使完成本地项目也不应写

- “production-proven” 或 “serving millions of users”；
- “GDPR compliant” 或任何未经审计的合规结论；
- “zero latency overhead”；
- “exactly once”；
- “亿级流量”；
- 未记录环境和原始结果的性能数字。

## 7. 最终评价

| 维度 | 当前评价 | 优化后潜力 |
|---|---:|---:|
| 选题差异化 | 8/10 | 9/10 |
| 产品问题清晰度 | 8/10 | 9/10 |
| v1 可交付性 | 4/10 | 8/10 |
| 工程证据 | 1/10 | 9/10 |
| 爱尔兰后端/平台岗位相关性 | 7/10 | 9/10 |
| 当前简历可信度 | 2/10 | 9/10 |

最终判断：**值得做，但必须停止扩功能，先把一条可靠性闭环做深、做真、做得可复现。**
