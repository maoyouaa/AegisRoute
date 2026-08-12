# 架构说明

AegisRoute v0.1 将 baseline serving 与 candidate evaluation 隔离。Gateway 只读取不可变内存 Route Snapshot，在请求路径上不访问 PostgreSQL。Shadow selection 仅向有界本地队列执行非阻塞 offer，独立 Publisher 再把事件发送到 Redpanda。Worker 调用 candidate，并通过 Control 所拥有的持久化合同写入确定性证据。

Control 发布不可变、带 checksum 的 Route Revision。增加 candidate 流量必须由人工操作；确定性安全策略可以创建 append-only rollback decision 和 candidate 为零的更高版本 Route Revision。Gateway acknowledgement 用于证明所有实例已经收敛，闭合证据链。

默认 Compose 将 Control、Worker、PostgreSQL、Redpanda 和 Mock Provider 放在 `aegis-internal`。Control 在容器内监听 `0.0.0.0:8081`，但不发布宿主机端口；开发 override 可绑定 `127.0.0.1:8081`。
