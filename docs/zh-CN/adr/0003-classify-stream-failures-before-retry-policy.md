# ADR 0003：先分类 Stream failure，再设计 retry policy

- 状态：Accepted
- 日期：2026-08-17

## 背景

SSE 故障的安全含义取决于客户端已经观察到什么。首 token 前的连接失败或 HTTP 429 可能符合有界 fallback 条件，而首 token 后 retry 会重复或改变用户已经看到的输出。把所有 Provider 故障统一记为 HTTP 502，也会丢失自动回滚分析需要的证据。

故障分类不能悄悄变成 retry policy。未来如果增加 fallback，还必须共享一个总 deadline 和一个 retry budget，明确多 deployment evidence 语义，并用回归测试证明预算不会重置。

## 决策

在 Provider SPI 中增加无副作用的 `FailureClassifier`，只返回有限 failure kind、状态码与 retry eligibility：

- response output 前的 HTTP 429 与上游 5xx 具备 eligibility，但 v0.1 不执行 retry；
- output 前的连接与 timeout 故障具备 eligibility，并分别映射为 502 与 504；
- 429 以外的 HTTP 4xx、已经开始响应的歧义边界、取消与未知故障不具备 eligibility；
- 首个 SSE token 后的任何故障都属于 `STREAM_FAILURE`，绝不可 retry。

Gateway 只使用分类结果写入准确的 serving evidence。Classifier 不调用 Provider、不修改 Route Snapshot，也不重置 deadline。

## 后果

- Rollback evidence 可以保留上游 429/5xx 与 timeout 语义，不再把所有故障压平为 502；
- Provider 与 Gateway contract test 可以分别证明 HTTP、连接、deadline、取消与 post-token 边界；
- 本决策不增加透明 retry 或 fallback。以后如需增加，必须使用独立 ADR 并重新审查 evidence model。
