# ADR 0001：Shadow 发布与 baseline serving 隔离

- 状态：Accepted
- 日期：2026-08-12

## 背景

如果 Reactor 请求链直接调用 Kafka，它可能等待 metadata、buffer、ack、retry 或 broker timeout，从而拖慢 baseline 首 token。

## 决策

请求路径只向按消息数和字节数双重限界的本地队列执行 non-blocking offer。Redpanda 交互全部由独立 Publisher 线程、独立 Producer 连接、有限 retry 和 delivery timeout 承担。队列或 broker 故障只记录有界 drop reason，不能让 baseline 失败。

## 后果

压力下允许有意丢失 shadow telemetry。指标与 evidence availability gate 暴露丢失；必要 serving evidence 缺失时阻止 canary 晋级。
