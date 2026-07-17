# 知光 RAG 扩展评测语料

本目录保存 15 篇面向单篇知文问答的原创整理稿，并将此前人工上传的 5 篇本地学习笔记纳入统一评测。
20 篇知文用于验证 Markdown 分块、BM25 精确召回、向量语义召回、RRF 融合、来源引用和空召回拒答。

## 使用约定

- 发布标题使用文档中的一级标题，不带文件名前面的序号。
- 上传时使用 `text/markdown`，并记录内容的 SHA-256、ETag 和文件大小。
- 发布后等待 `rag_index_status=READY`，再加入评测集。
- 每篇建议准备 5 个可回答问题和 1 个文档外问题。
- 当前 RAG 只索引公开且已发布的知文，因此本批语料在本地评测账号中设为公开；正文采用原创整理并保留官方来源链接。
- 实际上传和向量核验结果见 [`UPLOAD_RESULT.md`](./UPLOAD_RESULT.md)。
- 第一轮 45 道基线题及评分规则见 [`EVALUATION_QUESTIONS.md`](./EVALUATION_QUESTIONS.md)。
- 2026-07-16 首轮实测结果见 [`BASELINE_REPORT_2026-07-16.md`](./BASELINE_REPORT_2026-07-16.md)。
- 结构化题库位于 [`cases`](./cases) 目录，共 75 题：扩展 15 篇 45 题，本地 5 篇 30 题。
- 阈值校准先执行 [`THRESHOLD_PILOT_CASES.txt`](./THRESHOLD_PILOT_CASES.txt) 中的 16 道代表题。
- P0 最终结论见 [`P0_CLOSURE_REPORT_2026-07-17.md`](./P0_CLOSURE_REPORT_2026-07-17.md)。

## 自动化执行

评测脚本只调用当前运行中的后端并记录结果，不会自动修改配置或业务代码。切换阈值后应重启后端，
再用相同参数执行下一轮，避免多个变量同时变化。

```bash
# 先校验 20 篇、75 题是否能被完整读取
scripts/rag-eval.sh --dry-run --threshold 0.50

# 执行 16 道阈值校准题
scripts/rag-eval.sh \
  --case-ids docs/rag-eval-corpus/THRESHOLD_PILOT_CASES.txt \
  --threshold 0.50

# 执行 75 道完整回归
scripts/rag-eval.sh --threshold 0.50
```

最终完整评测应单独启动关闭用户限流的本地实例；正常前端使用时保持限流开启：

```bash
RAG_SIMILARITY_THRESHOLD=0.5 \
RAG_RATE_LIMIT_ENABLED=false \
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## 文档清单

| 文件 | 主题 |
| --- | --- |
| `01-spring-ai-rag.md` | Spring AI RAG 模块化链路 |
| `02-elastic-hybrid-search.md` | 关键词与向量混合检索 |
| `03-rrf.md` | 倒数排名融合算法 |
| `04-elasticsearch-dense-vector.md` | Elasticsearch 稠密向量字段 |
| `05-dashscope-embedding.md` | DashScope 文本向量化模型 |
| `06-deepseek-chat-api.md` | DeepSeek 对话补全与流式输出 |
| `07-spring-security-jwt.md` | Spring Security JWT 资源服务器 |
| `08-webflux-sse.md` | WebFlux 与 SSE 流式响应 |
| `09-oss-presigned-upload.md` | OSS 服务端签名直传 |
| `10-kafka-delivery-semantics.md` | Kafka 投递语义与可靠性 |
| `11-transactional-outbox.md` | Transactional Outbox 模式 |
| `12-canal-to-kafka.md` | Canal 到 Kafka 的变更链路 |
| `13-docker-compose-startup.md` | Docker Compose 启动顺序 |
| `14-mybatis-dynamic-sql.md` | MyBatis 动态 SQL |
| `15-spring-cache-annotations.md` | Spring 声明式缓存注解 |
