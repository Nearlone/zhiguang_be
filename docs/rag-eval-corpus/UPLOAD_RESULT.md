# RAG 扩展语料上传结果

上传时间：2026-07-16

本批 15 篇 Markdown 已通过知光正式接口完成草稿创建、OSS 直传、正文确认、元数据更新和发布，
随后触发单篇 RAG 索引重建。数据库中的切片数与 Elasticsearch 实际文档数逐篇一致。

| 文件 | 知文 ID | 发布状态 | RAG 状态 | 向量数 |
| --- | --- | --- | --- | ---: |
| `01-spring-ai-rag.md` | `336079253844135936` | `PUBLISHED` | `READY` | 6 |
| `02-elastic-hybrid-search.md` | `336079782083170304` | `PUBLISHED` | `READY` | 6 |
| `03-rrf.md` | `336079836294549504` | `PUBLISHED` | `READY` | 6 |
| `04-elasticsearch-dense-vector.md` | `336079887662190592` | `PUBLISHED` | `READY` | 6 |
| `05-dashscope-embedding.md` | `336079913587183616` | `PUBLISHED` | `READY` | 6 |
| `06-deepseek-chat-api.md` | `336079932906147840` | `PUBLISHED` | `READY` | 6 |
| `07-spring-security-jwt.md` | `336079968226381824` | `PUBLISHED` | `READY` | 7 |
| `08-webflux-sse.md` | `336079992557539328` | `PUBLISHED` | `READY` | 7 |
| `09-oss-presigned-upload.md` | `336080029685518336` | `PUBLISHED` | `READY` | 6 |
| `10-kafka-delivery-semantics.md` | `336080061428011008` | `PUBLISHED` | `READY` | 6 |
| `11-transactional-outbox.md` | `336080100665724928` | `PUBLISHED` | `READY` | 7 |
| `12-canal-to-kafka.md` | `336080452995649536` | `PUBLISHED` | `READY` | 7 |
| `13-docker-compose-startup.md` | `336080482955563008` | `PUBLISHED` | `READY` | 7 |
| `14-mybatis-dynamic-sql.md` | `336080528698642432` | `PUBLISHED` | `READY` | 7 |
| `15-spring-cache-annotations.md` | `336080581618176000` | `PUBLISHED` | `READY` | 7 |

汇总：15 篇知文，97 条向量，全部为 `published/public/READY`。

## 校验口径

- MySQL：核对 `status`、`visible`、`rag_index_status` 和 `rag_index_chunk_count`。
- Elasticsearch：按 `metadata.postId.keyword` 聚合实际向量文档数。
- 接口：重建接口仅在返回 `READY` 后将该篇记录为完成。
