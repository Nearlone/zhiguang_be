# Docker Compose 启动与关闭顺序

> 整理来源：[Docker Compose Startup Order](https://docs.docker.com/compose/how-tos/startup-order/)

## depends_on 能保证什么

Docker Compose 根据 `depends_on`、`links`、`volumes_from` 和共享网络命名空间等关系决定服务创建和删除顺序。依赖服务通常先创建，依赖它们的应用后创建；关闭时顺序相反。

普通 `depends_on` 只表示容器已经启动，不表示容器里的服务已经可以处理请求。MySQL 进程启动后还需要恢复数据，Elasticsearch 启动后还要完成集群初始化，业务应用过早连接可能立即失败。

## 三种 condition

`depends_on` 可以配合条件描述等待要求：

- `service_started`：依赖容器已经启动；
- `service_healthy`：依赖容器的健康检查已经通过；
- `service_completed_successfully`：一次性依赖任务成功执行并退出。

数据库、Redis 和 Elasticsearch 通常适合健康检查；数据库迁移任务可以使用 `service_completed_successfully`；只要求进程已存在的简单依赖可以使用 `service_started`。

## healthcheck

健康检查要验证服务是否真正可用，而不只是端口是否打开。例如数据库可以执行轻量查询，HTTP 服务可以访问专用健康接口。检查命令还需要配置 `interval`、`timeout`、`retries` 和 `start_period`。

`start_period` 给慢启动服务一个准备窗口，避免初始化期间的暂时失败过早计入重试次数。检查过于频繁会增加服务压力，检查过慢则会延长故障发现时间。

## restart 语义

依赖配置中的 `restart: true` 可以在显式更新或重启依赖服务时，让上层服务也重新启动并建立连接。它与容器自身的故障重启策略不是同一个概念。

应用仍应实现连接重试。Compose 启动顺序只能降低初始化竞争，无法避免数据库运行中重启、短暂网络故障或连接池失效。

## 示例

```yaml
services:
  app:
    depends_on:
      mysql:
        condition: service_healthy
        restart: true
  mysql:
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
```

该配置表示 Compose 等待 MySQL 健康后再创建应用，但应用代码仍需为后续连接中断设置超时和退避重试。

## 生产检查

多组件 AI 应用至少要检查 MySQL、Redis、Kafka、Elasticsearch 和对象存储相关配置。启动成功日志不等于整条 RAG 链路可用，还应进行数据库查询、向量检索和模型 API 的冒烟验证。
