# ADR 0002：使用不可变版本化 Route Snapshot 提供服务

- 状态：Accepted
- 日期：2026-08-12

## 背景

Gateway serving path 必须独立于 Control persistence，并在回滚时可预测地收敛。

## 决策

Control 发布全局不可变、版本单调递增且带 SHA-256 checksum 的 Route Revision。Gateway 在请求路径外轮询，校验 checksum，只用更高版本原子替换。首次缺少 Snapshot 时 live/UP、ready/DOWN、serving 503；初始化后 Control 丢失则继续使用 LKG。

## 后果

初始 ready 依赖 Control，但稳定 serving 不依赖。v0.1 只观测 Snapshot age，不实施 stale cutoff。
