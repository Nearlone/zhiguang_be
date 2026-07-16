# 知光后端 1Panel 专业部署与版本迭代手册

> 目标：把项目从“本地能跑”推进到“服务器可部署、配置不泄露、版本可追踪、发布可回滚、问题可排查”的工程化状态。本文按 1Panel + Docker Compose + Nginx/OpenResty 反向代理的方式设计。

## 1. 当前部署形态

推荐部署结构：

```text
用户浏览器
  -> 域名 HTTPS
  -> 1Panel 网站 / OpenResty 反向代理
  -> 127.0.0.1:18080
  -> zhiguang-prod-app
  -> Docker 内部网络
     -> mysql / redis / kafka / elasticsearch / canal
```

关键点：

- 宿主机公网只开放 `80/443/22`，不要暴露 MySQL、Redis、Kafka、Elasticsearch、Canal。
- Spring Boot 只绑定到宿主机 `127.0.0.1:18080`，由 1Panel 反向代理转发。
- 每个项目使用独立 Compose project、独立容器名、独立 volume、独立网络。
- 真实密钥只放服务器环境变量或 `deploy/secrets/`，不进 Git，不进 Docker 镜像。

## 2. 已完成的本地安全处理

本地已保存敏感配置备份：

```text
.local-secrets/application-local.yml.bak
.local-secrets/.env.bak
.local-secrets/keys/private.pem.bak
.local-secrets/keys/public.pem.bak
```

这些文件只用于你本机找回旧配置，已经被 `.gitignore` 和 `.dockerignore` 排除。

新增/调整的安全文件：

```text
.gitignore
.dockerignore
src/main/resources/application-local.example.yml
.env.prod.example
compose.prod.yaml
```

注意：

- `src/main/resources/application-local.yml` 是真实本地配置，不要提交。
- `.env`、`.env.local`、`.env.prod` 是真实环境变量，不要提交。
- `src/main/resources/keys/private.pem` 当前已经被 Git 跟踪，生产上线前建议生成新的生产 JWT 密钥，并逐步把私钥从仓库跟踪中移除。
- 如果真实 DeepSeek Key、OSS Key 曾经推到远程仓库，必须去对应平台轮换密钥。

## 3. 上线前准备清单

服务器建议：

```text
最低：2 核 4G，只适合演示
推荐：4 核 8G，适合 MySQL + Redis + Kafka + ES + Canal + App 同机部署
磁盘：40G 起步，建议 80G+
系统：Ubuntu 22.04 / Debian 12 / Rocky Linux 9
```

安全组开放：

```text
22    SSH，只允许自己的 IP 更好
80    HTTP，给 Let's Encrypt 申请证书
443   HTTPS，正式访问
```

不要开放：

```text
3306  MySQL
6379  Redis
9092  Kafka
9200  Elasticsearch
11111 Canal
18080 App 内部端口
```

如果 Elasticsearch 启动失败，先在服务器执行：

```bash
sysctl -w vm.max_map_count=262144
```

持久化写入：

```bash
echo "vm.max_map_count=262144" >> /etc/sysctl.conf
sysctl -p
```

## 4. 生产密钥准备

### 4.1 创建生产环境变量

复制示例文件：

```bash
cp .env.prod.example .env.prod
```

必须修改：

```text
APP_IMAGE
MYSQL_ROOT_PASSWORD
MYSQL_APP_PASSWORD
SPRING_DATASOURCE_PASSWORD
SPRING_DATA_REDIS_PASSWORD
DEEPSEEK_API_KEY
DASHSCOPE_API_KEY
OSS_ACCESS_KEY_ID
OSS_ACCESS_KEY_SECRET
OSS_BUCKET
OSS_PUBLIC_DOMAIN
```

要求：

- `MYSQL_APP_PASSWORD` 和 `SPRING_DATASOURCE_PASSWORD` 保持一致。
- 密码使用长随机字符串，不要用 `Root@123456`、`123456`、生日、项目名。
- OSS 建议使用 RAM 子账号，只授予当前 bucket 的最小权限。

### 4.2 生成生产 JWT 密钥

不要复用开发环境 `src/main/resources/keys/private.pem`。

在服务器项目目录执行：

```bash
mkdir -p deploy/secrets
openssl genrsa -out deploy/secrets/jwt-private.pem 2048
openssl rsa -in deploy/secrets/jwt-private.pem -pubout -out deploy/secrets/jwt-public.pem
chmod 600 deploy/secrets/jwt-private.pem
chmod 644 deploy/secrets/jwt-public.pem
```

对应环境变量：

```text
AUTH_JWT_PRIVATE_KEY=file:/run/secrets/jwt-private.pem
AUTH_JWT_PUBLIC_KEY=file:/run/secrets/jwt-public.pem
```

`compose.prod.yaml` 会把服务器上的密钥文件挂载到容器内：

```text
./deploy/secrets/jwt-private.pem -> /run/secrets/jwt-private.pem
./deploy/secrets/jwt-public.pem  -> /run/secrets/jwt-public.pem
```

## 5. 镜像构建与版本命名

推荐版本号：

```text
v0.1.0  第一版可部署
v0.2.0  新增功能或较大优化
v0.2.1  修复 bug
v1.0.0  稳定演示版本
```

镜像命名：

```text
ghcr.io/your-github-org/zhiguang-be:v0.1.0
ghcr.io/your-github-org/zhiguang-be:latest
```

本地临时构建：

```bash
docker build -t zhiguang-be:v0.1.0 .
```

更专业的方式：

```text
GitHub tag v0.1.0
  -> GitHub Actions 跑测试
  -> 构建 Docker 镜像
  -> 推送 GHCR / 阿里云 ACR
  -> 服务器拉取指定 tag
```

不要在生产环境只用 `latest`，正式部署时用固定 tag，方便回滚。

## 6. 1Panel 部署步骤

### 6.1 准备项目目录

建议目录：

```text
/opt/zhiguang/zhiguang_be
```

上传或拉取代码：

```bash
git clone https://github.com/G-Pegasus/zhiguang_be.git /opt/zhiguang/zhiguang_be
cd /opt/zhiguang/zhiguang_be
```

创建生产环境变量：

```bash
cp .env.prod.example .env.prod
```

编辑 `.env.prod`，填入真实值。

创建 JWT 密钥：

```bash
mkdir -p deploy/secrets
openssl genrsa -out deploy/secrets/jwt-private.pem 2048
openssl rsa -in deploy/secrets/jwt-private.pem -pubout -out deploy/secrets/jwt-public.pem
chmod 600 deploy/secrets/jwt-private.pem
chmod 644 deploy/secrets/jwt-public.pem
```

### 6.2 在 1Panel 创建 Compose 编排

路径：

```text
1Panel -> 容器 -> 编排 -> 创建编排
```

推荐配置：

```text
名称：zhiguang-prod
路径：/opt/zhiguang/zhiguang_be
Compose 文件：compose.prod.yaml
环境变量文件：.env.prod
```

如果 1Panel 页面不支持选择 `.env.prod`，可以把 `.env.prod` 的内容粘贴到“环境变量”区域，或在终端执行：

```bash
docker compose --env-file .env.prod -f compose.prod.yaml up -d
```

启动后检查：

```bash
docker compose --env-file .env.prod -f compose.prod.yaml ps
docker logs -f zhiguang-prod-app
```

### 6.3 配置 1Panel 反向代理

路径：

```text
1Panel -> 网站 -> 创建网站 -> 反向代理
```

示例：

```text
主域名：api.your-domain.com
代理地址：http://127.0.0.1:18080
开启 HTTPS：Let's Encrypt
强制 HTTPS：开启
```

建议 Nginx/OpenResty 配置：

```nginx
client_max_body_size 20m;

location / {
    proxy_pass http://127.0.0.1:18080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /api/v1/knowposts/ {
    proxy_pass http://127.0.0.1:18080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
}
```

如果 SSE 流式问答出现“攒一段才返回”，重点检查：

```text
proxy_buffering off;
```

## 7. 部署后验收

### 7.1 容器状态

```bash
docker compose --env-file .env.prod -f compose.prod.yaml ps
```

预期：

```text
mysql          healthy
redis          healthy
kafka          healthy
elasticsearch  healthy
canal          running
app            running
```

### 7.2 健康检查

服务器本机：

```bash
curl -i http://127.0.0.1:18080/actuator/health
```

公网域名：

```bash
curl -i https://api.your-domain.com/actuator/health
```

预期：

```json
{"status":"UP"}
```

### 7.3 核心链路冒烟测试

最小冒烟顺序：

```text
1. /actuator/health
2. 发送验证码
3. 注册 / 登录
4. 携带 Bearer token 创建草稿
5. 获取 OSS 预签名或确认内容
6. 更新知文元数据
7. 发布知文
8. 搜索接口能搜到发布内容
9. RAG 流式问答能返回 SSE
```

重点观察：

```bash
docker logs -f zhiguang-prod-app
docker logs -f zhiguang-prod-canal
docker logs -f zhiguang-prod-kafka
```

## 8. 数据备份策略

MySQL 是核心数据源，必须备份。

推荐：

```text
1Panel -> 数据库 -> MySQL -> 备份
每日一次，保留 7-14 天
上线前手动备份一次
每次版本发布前手动备份一次
```

命令行备份示例：

```bash
docker exec zhiguang-prod-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" zhiguang_prod > backup-zhiguang-prod.sql
```

Redis：

- 如果只做缓存，可以不作为强一致备份对象。
- 如果有计数、bitmap、token 白名单等业务状态，至少开启 AOF，本模板已经开启 `appendonly yes`。

Elasticsearch：

- 搜索索引可以由 MySQL 重建，优先保证 MySQL 备份。
- 如果数据量变大，再考虑 ES snapshot。

Kafka：

- 当前主要用于异步事件，不建议把 Kafka 当长期存储。
- 关键业务要靠 MySQL + Outbox + 重试/补偿保证。

## 9. 发布流程

推荐发布 SOP：

```text
1. 从 main 拉新分支：feature/xxx
2. 本地开发和测试
3. 提 PR，检查改动范围
4. 合并 main
5. 打 tag：v0.2.0
6. CI 构建镜像：zhiguang-be:v0.2.0
7. 服务器修改 .env.prod 的 APP_IMAGE
8. 1Panel 重新部署编排
9. 执行冒烟测试
10. 记录发布结果
```

命令示例：

```bash
git tag v0.2.0
git push origin v0.2.0
```

服务器更新：

```bash
docker compose --env-file .env.prod -f compose.prod.yaml pull app
docker compose --env-file .env.prod -f compose.prod.yaml up -d app
docker logs -f zhiguang-prod-app
```

## 10. 回滚流程

回滚原则：

```text
先回滚应用镜像，再判断是否需要回滚数据库。
```

如果只是代码 bug：

```text
APP_IMAGE 从 v0.2.0 改回 v0.1.0
重新部署 app
执行健康检查和冒烟测试
```

命令：

```bash
docker compose --env-file .env.prod -f compose.prod.yaml up -d app
```

如果涉及数据库结构变更：

- 发布前必须备份 MySQL。
- 数据库迁移脚本要区分 forward migration 和 rollback plan。
- 后续建议引入 Flyway，把 `db/schema.sql` 演进为版本化迁移：

```text
db/migration/V1__init.sql
db/migration/V2__add_outbox_index.sql
db/migration/V3__add_rag_task.sql
```

## 11. 线上排障清单

### 11.1 应用起不来

检查：

```bash
docker logs zhiguang-prod-app
```

常见原因：

```text
JWT 私钥路径不对
数据库密码不一致
Redis 设置了 requirepass 但应用没传密码
ES 内存不足或 vm.max_map_count 不够
AI/OSS 环境变量为空导致相关功能失败
```

### 11.2 数据库连不上

检查：

```bash
docker logs zhiguang-prod-mysql
docker exec -it zhiguang-prod-mysql mysql -uroot -p
```

确认：

```text
MYSQL_DATABASE 是否是 zhiguang_prod
SPRING_DATASOURCE_URL 是否连接 mysql:3306
SPRING_DATASOURCE_USERNAME/PASSWORD 是否匹配
```

### 11.3 Redis 连不上

检查：

```bash
docker exec -it zhiguang-prod-redis redis-cli -a "$SPRING_DATA_REDIS_PASSWORD" ping
```

预期：

```text
PONG
```

### 11.4 搜索不同步

检查链路：

```text
MySQL outbox 表
  -> Canal
  -> Kafka canal-outbox topic
  -> CanalOutboxConsumerSearch
  -> Elasticsearch
```

日志：

```bash
docker logs -f zhiguang-prod-canal
docker logs -f zhiguang-prod-kafka
docker logs -f zhiguang-prod-app
```

### 11.5 SSE 不流式

检查：

```text
Nginx 是否开启 proxy_buffering off
前端是否使用 EventSource / fetch stream
后端接口 Content-Type 是否为 text/event-stream
```

## 12. 后续专业化优化路线

第一阶段，先做到：

- 生产密钥不进 Git。
- Docker Compose 可一键部署。
- 1Panel HTTPS 反向代理可访问。
- MySQL 有备份。
- 应用能健康检查。
- 每次发布有固定镜像 tag。

第二阶段，再补：

- GitHub Actions CI：`mvn test` + Docker build。
- GitHub Actions Release：tag 后推送 GHCR/ACR。
- Flyway 数据库迁移。
- Actuator 暴露 Prometheus 指标。
- Grafana 监控 JVM、接口耗时、错误率、数据库连接池。
- 结构化日志和 traceId。
- Outbox 消费失败重试、死信队列、搜索索引重建脚本。

第三阶段，才考虑：

- 多机部署。
- 蓝绿发布。
- Kubernetes。
- 独立拆分 search-service / rag-service。

## 13. 面试回答模板

```text
我的项目部署不是简单把 jar 包丢到服务器，而是按工程化流程做的。后端用 Docker 多阶段构建镜像，生产环境用 Docker Compose 编排 Spring Boot、MySQL、Redis、Kafka、Elasticsearch 和 Canal。服务器上通过 1Panel 管理容器和 OpenResty 反向代理，只对外开放 HTTPS，数据库和中间件不暴露公网。

配置方面，我把真实密钥从 application-local.yml 中拆出来，放到服务器环境变量和 secrets 文件里，仓库只保留 example 文件。JWT 私钥不打进镜像，通过 volume 挂载并用环境变量指定路径。

版本迭代上，我计划按 tag 发布镜像，比如 v0.1.0、v0.2.0，每次发布前备份数据库，发布后做健康检查和核心链路冒烟测试。如果发布失败，可以把 APP_IMAGE 切回上一个 tag 快速回滚。
```
