# Kafka 投递语义与可靠性

> 整理来源：[Apache Kafka Design](https://kafka.apache.org/41/design/design/)

## 三种投递语义

At most once 表示消息可能丢失，但不会被重复处理。At least once 表示消息尽量不丢，但失败重试可能带来重复。Exactly once 表示每条消息的最终业务效果只发生一次，但它通常需要生产端、Kafka 和下游处理共同配合。

Kafka 默认更接近 at least once。生产者遇到网络错误时，无法立刻确定消息是在提交前失败，还是已经提交但响应丢失，因此重试可能产生重复。消费者在处理完成后、提交 offset 前崩溃，也会在恢复后再次收到已经处理过的消息。

## 生产者可靠性

`acks` 决定生产者等待何种确认。`acks=0` 不等待 Broker 确认，延迟低但更容易丢消息；`acks=1` 等待分区 Leader 写入；`acks=all` 等待同步副本集合满足提交条件，可靠性更高但延迟可能增加。

`min.insync.replicas` 与 `acks=all` 配合，限定写入时必须存在的最少同步副本。只设置 `acks=all` 而不合理配置副本数和最少同步副本，不能自动获得理想可靠性。

启用幂等生产者后，Broker 可以结合 Producer ID 和序列号识别重试产生的重复记录。事务生产者还能把多个分区写入与 offset 提交组合到同一 Kafka 事务中。

## 消费者 offset

如果消费者先提交 offset 再处理业务，处理过程中崩溃会导致消息无法重放，对应 at most once。如果先处理业务再提交 offset，崩溃后可能重复处理，对应 at least once。

实际项目通常选择先处理再提交，并让业务操作幂等。幂等可以通过业务唯一键、事件 ID、去重表或状态机实现。不能因为 Kafka 支持重试，就假设数据库更新天然不会重复。

## Exactly once 的边界

Kafka Streams 在读取 Kafka、处理并写回 Kafka 的链路中，可以利用事务和 `read_committed` 实现端到端的 exactly-once 语义。消费者写入外部 MySQL、Redis 或 HTTP 服务时，还需要协调外部系统状态。

外部系统通常无法加入 Kafka 事务。常见工程方案包括幂等消费、Transactional Outbox、CDC 或把 offset 与业务结果保存在同一个支持事务的数据存储中。

## 监控与故障处理

可靠消息链路要监控生产失败、重试次数、消费积压、死信、重复事件和端到端延迟。异常日志应包含 topic、partition、offset 和事件 ID，但不应输出敏感业务正文。

重试需要退避和次数限制。无限快速重试会放大故障，阻塞分区并增加下游压力；完全吞掉异常则会造成数据静默丢失。
