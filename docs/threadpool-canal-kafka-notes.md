# 线程池、Canal 与 Kafka 笔记

本文档整理项目中的线程池配置，以及 Canal + Kafka + Outbox 异步事件链路。

涉及核心文件：

- `src/main/java/com/tongji/config/ThreadPoolConfig.java`
- `src/main/java/com/tongji/relation/outbox/CanalKafkaBridge.java`
- `src/main/java/com/tongji/relation/outbox/CanalOutboxConsumer.java`
- `src/main/java/com/tongji/relation/outbox/OutboxMapper.java`
- `src/main/resources/application.yml`
- `docker/canal/conf/canal.properties`
- `docker/canal/conf/example/instance.properties`
- `compose.yaml`

## 1. 整体关系

这几块可以放在同一条链路里理解：

```text
业务操作
  ↓
写 MySQL 主表 + outbox 表
  ↓
MySQL 产生 binlog
  ↓
Canal 监听 binlog
  ↓
CanalKafkaBridge 读取 Canal 消息
  ↓
发送到 Kafka
  ↓
Kafka Consumer 异步处理业务事件
```

其中：

| 组件 | 作用 |
| --- | --- |
| ThreadPoolConfig | 创建后台任务线程池 |
| Canal | 监听 MySQL binlog |
| Kafka | 保存和分发消息 |
| Outbox | 数据库中的业务事件表 |
| CanalKafkaBridge | Canal 到 Kafka 的桥接器 |
| CanalOutboxConsumer | Kafka 消费者，处理 outbox 事件 |

## 2. ThreadPoolConfig 的作用

文件：

```text
src/main/java/com/tongji/config/ThreadPoolConfig.java
```

代码：

```java
@Configuration
public class ThreadPoolConfig {
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("NoteExecutor-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
```

它创建了一个名叫：

```text
taskExecutor
```

的线程池 Bean。

当前主要使用位置：

```text
CanalKafkaBridge
```

`CanalKafkaBridge` 使用这个线程池启动一个后台循环，持续从 Canal 拉取消息。

## 3. 线程池参数解释

### 3.1 corePoolSize

```java
executor.setCorePoolSize(10);
```

核心线程数是 10。

可以理解成线程池长期保留的“常驻员工”数量。

当有任务提交时，线程池会优先使用这些核心线程执行任务。

### 3.2 maxPoolSize

```java
executor.setMaxPoolSize(50);
```

最大线程数是 50。

当任务很多，核心线程已经忙不过来，队列也满了之后，线程池最多可以扩容到 50 个线程。

### 3.3 queueCapacity

```java
executor.setQueueCapacity(200);
```

任务队列容量是 200。

线程池处理任务的大致顺序：

```text
1. 先用核心线程处理任务，最多 10 个线程。
2. 核心线程都忙了，任务进入队列，最多排队 200 个。
3. 队列也满了，创建更多线程，最多到 50 个。
4. 50 个线程也满了，触发拒绝策略。
```

### 3.4 keepAliveSeconds

```java
executor.setKeepAliveSeconds(30);
```

非核心线程空闲超过 30 秒后会被回收。

例如高峰期扩容到 30 个线程，之后任务减少，多出来的临时线程空闲 30 秒后会被销毁。

### 3.5 threadNamePrefix

```java
executor.setThreadNamePrefix("NoteExecutor-");
```

线程名会类似：

```text
NoteExecutor-1
NoteExecutor-2
```

日志里看到：

```text
[NoteExecutor-1]
```

就说明这条日志来自这个线程池。

当前名称叫 `NoteExecutor` 有点泛。因为它现在主要跑 Canal 桥接任务，后续可以考虑改成：

```text
CanalBridgeExecutor-
```

### 3.6 CallerRunsPolicy

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```

这是拒绝策略。

当线程池满了、队列也满了，又有新任务提交时，`CallerRunsPolicy` 不会直接丢弃任务，而是让提交任务的线程自己执行这个任务。

优点：

- 不轻易丢任务。
- 能自然降低提交速度。

风险：

- 如果提交任务的是 Web 请求线程，接口可能会变慢。
- 如果提交任务的是启动线程，启动流程可能被拖慢。

当前项目里主要是 `CanalKafkaBridge` 提交一个长期后台任务，所以暂时不会频繁触发拒绝策略。

### 3.7 waitForTasksToCompleteOnShutdown

```java
executor.setWaitForTasksToCompleteOnShutdown(true);
```

应用关闭时，线程池不会立刻杀掉正在执行的任务，而是等待它们执行完成。

### 3.8 awaitTerminationSeconds

```java
executor.setAwaitTerminationSeconds(60);
```

应用关闭时，最多等待 60 秒。

超过 60 秒还没有结束，就继续关闭流程。

## 4. 当前线程池设计提醒

当前配置：

```text
corePoolSize = 10
maxPoolSize = 50
queueCapacity = 200
```

但目前它主要只服务一个长期任务：

```text
CanalKafkaBridge 主循环
```

所以这个线程池有点偏“大而通用”。

如果后续它还会承载很多异步任务，可以保留。

如果只给 Canal 桥接用，更合理的是单独创建一个小线程池：

```text
corePoolSize = 1
maxPoolSize = 1
queueCapacity = 0 或很小
threadNamePrefix = CanalBridgeExecutor-
```

原因是 Canal 单连接消费本来就是一个长循环，不需要 10 个核心线程常驻。

## 5. Canal 是什么

Canal 是阿里开源的 MySQL binlog 订阅工具。

可以把它理解成：

```text
MySQL 变化监听器
```

MySQL 每次执行：

```sql
INSERT
UPDATE
DELETE
```

都会把变化写入 binlog。

Canal 会伪装成 MySQL 从库，订阅这些 binlog。

在本项目中，Canal 只监听：

```text
zhiguang.outbox
```

配置在：

```text
docker/canal/conf/example/instance.properties
```

```properties
canal.instance.filter.regex = zhiguang\\.outbox
```

## 6. Kafka 是什么

Kafka 是消息队列，也可以理解成高性能消息中转站。

它负责：

```text
接收消息
保存消息
按 topic 分类
让多个消费者独立消费
```

Kafka 中的 topic 可以理解成消息分类。

本项目里有两个主要 topic：

```text
counter-events
canal-outbox
```

| Topic | 作用 |
| --- | --- |
| `counter-events` | 点赞、收藏等计数事件 |
| `canal-outbox` | Canal 从 outbox 表转发出来的业务事件 |

## 7. Outbox 是什么

Outbox 是一张业务事件表。

文件：

```text
db/schema.sql
```

表：

```sql
CREATE TABLE IF NOT EXISTS outbox (
    id BIGINT UNSIGNED NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT UNSIGNED NULL,
    type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_outbox_agg (aggregate_type, aggregate_id),
    KEY ix_outbox_ct (created_at)
)
```

可以把 outbox 理解成：

```text
数据库里的事件草稿箱
```

业务操作成功时，不是直接发 Kafka，而是在同一个数据库事务里写：

```text
业务主表
outbox 表
```

这样可以保证：

```text
业务数据写成功，事件也一定写成功
业务数据回滚，事件也不会凭空出现
```

## 8. 为什么不直接在业务代码里发 Kafka

如果在业务代码里直接发 Kafka，会有一致性问题。

情况一：

```text
数据库写成功
Kafka 发送失败
```

结果：

```text
主数据已经变了，但后续消费者收不到事件
```

情况二：

```text
Kafka 发送成功
数据库事务回滚
```

结果：

```text
消费者以为业务成功了，但数据库里其实没有这条业务数据
```

Outbox + Canal 的好处是：

```text
事件先作为数据库数据写入 outbox
Canal 再从 MySQL binlog 中可靠监听这个事件
最后转发到 Kafka
```

这样主业务数据和事件记录能保持一致。

## 9. CanalKafkaBridge 做什么

文件：

```text
src/main/java/com/tongji/relation/outbox/CanalKafkaBridge.java
```

它是 Canal 到 Kafka 的桥。

主要步骤：

```text
1. 连接 Canal Server
2. 订阅 destination：example
3. 使用 filter 订阅 zhiguang.outbox
4. 从 Canal 拉取 binlog 事件
5. 只处理 ROWDATA 类型事件
6. 只处理 INSERT / UPDATE
7. 提取 outbox 表中的 payload 字段
8. 包装成 JSON
9. 发送到 Kafka topic：canal-outbox
10. ack Canal 位点
```

核心代码：

```java
Message message = connector.getWithoutAck(batchSize);
```

意思是从 Canal 拉取一批消息，但先不确认。

处理成功后：

```java
connector.ack(batchId);
```

意思是告诉 Canal：

```text
这一批我处理完成了，下次不用再给我
```

如果中间失败，没有 ack，就有机会重新消费。

这叫：

```text
至少一次处理语义
```

## 10. 至少一次语义

至少一次语义是消息系统里常见的可靠性策略。

含义：

```text
消息至少会被处理一次
```

它避免消息丢失，但可能带来重复处理。

例如：

```text
处理消息成功
但是 ack 前应用崩了
重启后 Canal 又把这批消息发过来
```

所以消费者最好具备幂等性。

幂等性意思是：

```text
同一条消息处理一次和处理多次，最终结果一样
```

## 11. 一个关注用户的例子

假设用户 100 关注用户 200。

同步主流程：

```text
接口收到请求
  ↓
写 following 表
  ↓
写 outbox 表：FOLLOW_CREATED
  ↓
接口返回成功
```

异步流程：

```text
MySQL binlog 记录 outbox 新增
  ↓
Canal 监听到新增
  ↓
CanalKafkaBridge 把 payload 发到 Kafka
  ↓
Kafka topic canal-outbox 收到消息
  ↓
消费者读取消息
  ↓
更新 follower 表 / 缓存 / 计数
```

这样接口不用等所有后续动作完成。

## 12. 一个知文发布的例子

发布知文可能需要：

```text
写 know_posts
写 outbox
更新 Feed 缓存
更新搜索索引
预构建 RAG 索引
通知粉丝
```

这些全部同步做会让接口很慢。

更合理的是：

```text
发布接口只保证核心数据落库
后续动作通过事件异步触发
```

## 13. Canal 配置

Canal Server 主配置：

```text
docker/canal/conf/canal.properties
```

当前本地开发配置中，Canal Server 客户端认证关闭：

```properties
canal.user =
canal.passwd =
```

注意：这不是 Canal 连接 MySQL 的账号密码。

Canal 连接 MySQL 的配置在：

```text
docker/canal/conf/example/instance.properties
```

```properties
canal.instance.master.address = mysql:3306
canal.instance.dbUsername = canal
canal.instance.dbPassword = Canal@123456
canal.instance.defaultDatabaseName = zhiguang
canal.instance.filter.regex = zhiguang\\.outbox
```

这里的：

```text
canal / Canal@123456
```

是 MySQL 复制账号，用来读取 binlog。

## 14. Spring Boot 中的 Canal 配置

`application.yml` 中：

```yaml
canal:
  enabled: true
  host: localhost
  port: 11111
  destination: example
  username: ${CANAL_USERNAME:}
  password: ${CANAL_PASSWORD:}
  filter: zhiguang\.outbox
  batchSize: 1000
  intervalMs: 1000
```

含义：

| 配置 | 作用 |
| --- | --- |
| `enabled` | 是否启用 Canal 桥接 |
| `host` | Canal Server 地址 |
| `port` | Canal Server 端口 |
| `destination` | Canal 实例名 |
| `username/password` | Spring Boot 连接 Canal Server 的认证信息 |
| `filter` | 订阅过滤表达式 |
| `batchSize` | 每次最多拉取多少条 Canal 消息 |
| `intervalMs` | 空轮询时休眠多久 |

本地开发中，Canal Server 认证关闭，所以：

```yaml
username: ""
password: ""
```

## 15. 常见问题：illegal hex-string

如果 Canal Server 主配置里写了：

```properties
canal.passwd = Canal@123456
```

可能会报：

```text
illegal hex-string: Canal@123456
```

原因是 Canal Server 会把 `canal.passwd` 当作加密后的 hex 字符串解析。

本地开发最简单的修复方式是关闭 Canal Server 客户端认证：

```properties
canal.user =
canal.passwd =
```

同时 Spring Boot 连接 Canal 时也不要传用户名密码：

```yaml
canal:
  username: ${CANAL_USERNAME:}
  password: ${CANAL_PASSWORD:}
```

注意不要把 Canal Server 的客户端认证密码，和 Canal 连接 MySQL 的复制账号密码混淆。

## 16. 初学者总结

可以先记住：

```text
MySQL 是事实来源
Outbox 是事件记录表
Canal 监听 MySQL 的 outbox 变化
Kafka 保存并分发这些事件
消费者从 Kafka 读取事件做后续处理
线程池负责让 Canal 桥接任务在后台运行
```

完整链路：

```text
业务操作
  ↓
写业务表 + outbox 表
  ↓
MySQL binlog
  ↓
Canal
  ↓
CanalKafkaBridge
  ↓
Kafka
  ↓
Consumer
  ↓
缓存 / 计数 / 搜索 / 关系表等后续更新
```

这个设计的核心价值：

```text
保证主业务数据和事件记录一致，同时把后续复杂操作异步化
```
