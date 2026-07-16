# Docker 本地环境排错记录

这份文档记录了一次全新 Mac 启动知光后端开发环境时遇到的问题和修复过程。目标是帮助初学者理解：Docker Compose 启动的不只是一个 Java 程序，而是一整套后端依赖服务。

## 1. 项目本地环境组成

本项目后端启动前，需要先启动这些基础服务：

| 服务 | 作用 |
| --- | --- |
| MySQL | 存储用户、知文、关注关系、Outbox 等业务数据 |
| Redis | 缓存、验证码、刷新令牌、计数等 |
| Kafka | 消息队列，用于异步事件 |
| Elasticsearch | 搜索和 RAG 检索 |
| Canal | 监听 MySQL binlog，把数据库变更转成事件 |

这些服务由项目根目录下的 `compose.yaml` 管理。开发阶段推荐：

- Docker 负责启动外部依赖：MySQL、Redis、Kafka、Elasticsearch、Canal。
- 本机负责启动 Spring Boot 应用，方便在 IDEA 里调试。

## 2. 问题一：Kafka 镜像拉取失败

### 现象

执行 Docker Compose 启动命令时报错：

```text
failed to resolve reference "docker.io/bitnami/kafka:3.7": not found
```

### 原因

原配置中使用了：

```yaml
image: bitnami/kafka:3.7
```

Docker 去 Docker Hub 拉取 `bitnami/kafka:3.7`，但这个镜像标签已经不可用。

Docker 镜像名后面的 `:3.7` 叫 tag。如果 tag 不存在，镜像就会拉取失败。

### 修复

把 Kafka 镜像改成官方 Kafka 镜像：

```yaml
image: apache/kafka:3.7.2
```

同时，官方 Kafka 镜像和 Bitnami Kafka 镜像的环境变量写法不同，所以 Kafka 的启动参数也一起调整为官方镜像支持的写法。

## 3. 问题二：Kafka 启动后立刻退出

### 现象

Kafka 镜像能拉取后，容器仍然启动失败。日志中有：

```text
Error while writing meta.properties file /tmp/kraft-combined-logs
```

### 原因

Kafka 需要往自己的数据目录写文件。原配置给 Kafka 挂载了一个 Docker volume：

```yaml
volumes:
  - kafka-data:/tmp/kraft-combined-logs
```

官方 Kafka 镜像默认不是 root 用户运行，和挂载目录的权限产生了冲突，导致 Kafka 无法写入数据目录。

### 修复

开发环境中 Kafka 消息和 topic 可以重建，不一定需要持久化。因此先去掉 Kafka 的持久化数据卷。

删除 Kafka 服务中的：

```yaml
volumes:
  - kafka-data:/tmp/kraft-combined-logs
```

同时删除底部 volumes 中的：

```yaml
kafka-data:
```

## 4. 问题三：MySQL 初始化 SQL 挂载失败

### 现象

MySQL 启动失败，报错大意是：

```text
error mounting ".../db/schema.sql" to "/docker-entrypoint-initdb.d/02-schema.sql"
read-only file system
```

### 原因

原配置先把整个初始化目录挂载进容器：

```yaml
- ./docker/mysql/init:/docker-entrypoint-initdb.d:ro
```

然后又把单个 SQL 文件挂载到同一个目录下面：

```yaml
- ./db/schema.sql:/docker-entrypoint-initdb.d/02-schema.sql:ro
```

这等于先把 `/docker-entrypoint-initdb.d` 整个目录变成只读，再尝试往这个只读目录里挂载一个文件。Docker 不允许这种嵌套只读挂载。

### 修复

改成逐个挂载 SQL 文件：

```yaml
volumes:
  - mysql-data:/var/lib/mysql
  - ./docker/mysql/init/01-canal-user.sql:/docker-entrypoint-initdb.d/01-canal-user.sql:ro
  - ./db/schema.sql:/docker-entrypoint-initdb.d/02-schema.sql:ro
```

MySQL 官方镜像会在第一次初始化数据库时，自动执行 `/docker-entrypoint-initdb.d/` 目录下的 `.sql` 文件。

这里文件名前面的数字用于控制执行顺序：

1. `01-canal-user.sql`：创建 Canal 复制账号。
2. `02-schema.sql`：创建项目业务表。

## 5. 问题四：Kafka topic 没有创建成功

### 现象

Kafka 启动后，检查 topic 列表是空的。`kafka-init` 容器退出码是 `1`，日志打印的是 Kafka 命令帮助页。

这说明 `kafka-topics.sh` 命令没有按预期接收到完整参数。

### 原因

原配置使用了多行 command：

```yaml
entrypoint: ["/bin/bash", "-c"]
command: >
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092
  --create --if-not-exists --topic counter-events --partitions 3 --replication-factor 1 &&
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092
  --create --if-not-exists --topic canal-outbox --partitions 3 --replication-factor 1
```

Compose 最终解析后，命令没有按预期完整传入 shell，导致 topic 初始化失败。

### 修复

改成显式 shell entrypoint，把完整初始化命令作为 shell 参数传入：

```yaml
kafka-init:
  image: apache/kafka:3.7.2
  container_name: zhiguang-kafka-init
  depends_on:
    kafka:
      condition: service_healthy
  entrypoint:
    - /bin/bash
    - -lc
    - /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic counter-events --partitions 3 --replication-factor 1 && /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic canal-outbox --partitions 3 --replication-factor 1
```

修复后，`kafka-init` 成功输出：

```text
Created topic counter-events.
Created topic canal-outbox.
```

这两个 topic 的作用是：

| Topic | 作用 |
| --- | --- |
| `counter-events` | 计数事件，例如点赞、收藏 |
| `canal-outbox` | Canal 从 MySQL outbox 表监听到的业务事件 |

## 6. 配置文件检查

启动 Spring Boot 应用前，重点检查了这些文件：

```text
src/main/resources/application.yml
```

```text
src/main/resources/application-local.yml
```

```text
db/schema.sql
```

```text
docker/mysql/init/01-canal-user.sql
```

```text
docker/canal/conf/example/instance.properties
```

### application.yml

`application.yml` 是主配置文件，里面包含完整的业务配置。

但当前主配置里还有一些占位值，例如：

```yaml
password: 写你自己的
api-key: 写你自己的
```

所以本地开发不能只用默认配置启动。

### application-local.yml

`application-local.yml` 是本地开发配置。它会覆盖主配置中的数据库、Redis、Kafka、Elasticsearch、Canal、AI、OSS 等连接信息。

例如本地数据库密码会被覆盖为：

```yaml
password: Root@123456
```

本地 Redis 地址：

```yaml
host: localhost
port: 6379
```

本地 Kafka 地址：

```yaml
bootstrap-servers: localhost:9092
```

因此，本地启动 Spring Boot 时必须使用 `local` profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Spring Boot 会先读取 `application.yml`，再读取 `application-local.yml`。后者会覆盖前者。

## 7. 数据库初始化结果

MySQL 配置：

| 配置项 | 值 |
| --- | --- |
| host | `localhost` |
| port | `3306` |
| database | `zhiguang` |
| username | `root` |
| password | `Root@123456` |

`db/schema.sql` 已经成功初始化，当前创建了这些表：

```text
users
login_logs
know_posts
outbox
following
follower
```

Canal 账号也已经通过 `docker/mysql/init/01-canal-user.sql` 创建：

```sql
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'Canal@123456';
GRANT SELECT, SHOW VIEW, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
```

Canal 监听配置：

```properties
canal.instance.master.address = mysql:3306
canal.instance.dbUsername = canal
canal.instance.dbPassword = Canal@123456
canal.instance.defaultDatabaseName = zhiguang
canal.instance.filter.regex = zhiguang\\.outbox
```

## 8. 最终依赖状态

最终 Docker 依赖服务状态：

```text
MySQL: healthy
Redis: healthy
Kafka: healthy
Elasticsearch: healthy
Canal: running
```

Redis 连通性检查：

```text
PONG
```

Elasticsearch 状态：

```text
green
```

Kafka topic：

```text
counter-events
canal-outbox
```

## 9. 当前正确启动顺序

进入项目根目录：

```bash
cd /Users/songjunliang/IdeaProjects/zhiguang_be
```

启动 Docker 依赖：

```bash
docker compose up -d mysql redis kafka kafka-init elasticsearch canal
```

查看容器状态：

```bash
docker compose ps
```

本机启动 Spring Boot：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

检查 Spring Boot 健康状态：

```bash
curl http://localhost:8080/actuator/health
```

## 10. 初学者理解版总结

可以先把整个本地环境理解成两层：

```text
Docker 层：MySQL + Redis + Kafka + Elasticsearch + Canal
```

```text
Java 应用层：Spring Boot 后端
```

Docker 层先启动成功，Java 应用层才能正常连接数据库、缓存、消息队列和搜索服务。

这次修复主要解决的是 Docker 层的问题：

1. Kafka 镜像标签不可用。
2. Kafka 数据目录权限冲突。
3. MySQL 初始化 SQL 挂载方式错误。
4. Kafka topic 初始化命令传参错误。

这些问题解决后，项目已经具备启动 Spring Boot 后端的基础条件。
