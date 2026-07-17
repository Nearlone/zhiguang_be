# Canal 到 Kafka 的数据变更链路

> 整理来源：[Canal Kafka/RocketMQ QuickStart](https://github.com/alibaba/canal/wiki/Canal-Kafka-RocketMQ-QuickStart)

## Canal 的角色

Canal 模拟 MySQL Slave 读取 Binlog，把数据库行级变更解析成事件。它适合把数据库变化同步到搜索索引、缓存、消息系统或其他存储，从而减少业务代码中的直接双写。

Canal 不会自动理解业务意图。一次 SQL 更新可能只是中间状态，也可能来自补数据脚本。消费者需要根据表名、操作类型、字段变化和业务状态判断是否处理。

## MySQL 前置条件

自建 MySQL 需要开启 Binlog，并使用 ROW 模式记录行变化。Canal 账号通常需要 `SELECT`、`REPLICATION SLAVE` 和 `REPLICATION CLIENT` 等权限。生产环境应遵循最小权限，而不是直接授予所有数据库权限。

`server_id` 需要唯一，Canal 的 Slave ID 不能与其他复制节点冲突。连接字符集要与数据库和 Java 解码方式一致，否则中文字段可能出现乱码。

## Canal Server 到 MQ

Canal Server 可以把解析后的 Binlog 事件投递到 Kafka、RocketMQ 等消息系统。启用 MQ 模式后，需要配置服务地址、topic、分区方式和消息格式。

常见链路如下：

```text
MySQL Binlog
  -> Canal Instance
  -> Canal Server
  -> Kafka Topic
  -> 业务消费者
  -> Elasticsearch / Redis / RAG 索引
```

数据库中的一条业务变更可能产生多个行事件。消费者要明确按事务、表还是单行处理，并保存足够的事件标识用于排查。

## 分区和顺序

Kafka 只保证同一分区内的顺序。需要保持同一文章事件顺序时，可以使用文章 ID 作为分区 key。随机分区可能让发布、更新和删除被不同消费者乱序处理。

即使进入同一分区，消费者重试也可能造成重复。因此索引消费者仍要实现幂等，并根据内容 SHA-256、ETag、更新时间或事件版本判断是否需要重建。

## 位点与重放

Canal 需要维护读取 Binlog 的文件名和位置。组件重启后从错误位点恢复，可能遗漏或重复读取事件。配置和位点数据需要持久化，并监控解析延迟、连接状态和最后成功时间。

重放历史事件时，下游必须能够承受重复。删除事件尤其要谨慎，不能因为历史删除晚到就清理已经重建的新版本数据。

## 工程边界

Canal 提供数据变更事实，不等于完整业务事件。对强业务语义、严格原子性和可审计事件结构要求较高的场景，可以结合 Outbox：业务事务写入结构化事件，Canal 只负责可靠捕获 Outbox 表变化。
