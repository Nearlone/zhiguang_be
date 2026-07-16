# 知光后端本地开发环境

这份配置面向全新的 macOS 开发机：本机安装 JDK/Maven/IDE，MySQL、Redis、Kafka、Elasticsearch、Canal 由 Docker Compose 管理。这样适合边开发边学习，应用可以在 IntelliJ IDEA 里调试，也可以按需放进 Docker 跑。

## 1. 安装基础工具

Apple Silicon Mac 当前是 `arm64`。先安装 Homebrew：

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

安装 Java 21 和 Maven：

```bash
brew install openjdk@21 maven
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version
mvn -version
```

安装 Docker Desktop：

```bash
brew install --cask docker
open -a Docker
```

首次启动 Docker Desktop 后，等待状态变成 running，再验证：

```bash
docker --version
docker compose version
```

## 2. 启动开发依赖

在项目根目录执行：

```bash
cp .env.example .env
docker compose up -d mysql redis kafka kafka-init elasticsearch canal
docker compose ps
```

默认端口：

| 服务 | 地址 |
| --- | --- |
| MySQL | `localhost:3306`，库名 `zhiguang`，root 密码 `Root@123456` |
| Redis | `localhost:6379` |
| Kafka | `localhost:9092` |
| Elasticsearch | `http://localhost:9200` |
| Canal | `localhost:11111`，destination `example` |

MySQL 首次启动会自动执行 `db/schema.sql`，并创建 Canal 复制账号 `canal / Canal@123456`。如果改过初始化 SQL，需要重建数据卷：

```bash
docker compose down -v
docker compose up -d mysql redis kafka kafka-init elasticsearch canal
```

## 3. 本机运行 Spring Boot

推荐学习和调试时用 IntelliJ IDEA 直接运行 `com.tongji.ZhiGuangApplication`，并设置 profile：

```text
SPRING_PROFILES_ACTIVE=local
```

命令行运行：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

健康检查：

```bash
curl http://localhost:8080/actuator/health
```

AI 和 OSS 的密钥默认是占位值。只有调用 AI 摘要、RAG、OSS 上传相关接口时才需要在 `.env` 或 IDE 环境变量里填写：

```bash
DEEPSEEK_API_KEY=你的DeepSeekKey
DASHSCOPE_API_KEY=你的阿里云百炼Key
OSS_ACCESS_KEY_ID=你的OSSAccessKeyId
OSS_ACCESS_KEY_SECRET=你的OSSAccessKeySecret
OSS_BUCKET=你的Bucket
```

## 4. 可选：后端也放进 Docker

不需要本机 Maven 时，可以用 Docker 构建并运行应用：

```bash
docker compose --profile app up -d --build
```

开发阶段更推荐本机运行应用，因为断点调试、热重启和阅读源码更直接。

## 5. 常用命令

```bash
docker compose logs -f mysql
docker compose logs -f kafka
docker compose logs -f canal
docker compose restart canal
docker compose down
docker compose down -v
```

`down -v` 会删除 MySQL、Redis、Kafka、Elasticsearch 的本地数据卷，适合重新初始化环境。
