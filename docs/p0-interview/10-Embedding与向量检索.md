# 10 Embedding 与向量检索

> 目标：能讲清楚文本如何变成向量、向量检索为什么能做语义搜索、相似度怎么算、topK/阈值/metadata filter 如何影响 RAG 效果。

## 1. 最短面试回答

```text
Embedding 是把文本转换成高维向量的模型能力。语义相近的文本，在向量空间中距离更近。RAG 中会把文档 chunk 先做 Embedding 存入向量库，用户提问时也做 Embedding，然后用相似度检索找出最相关的 topK 片段。

项目里通过 Spring AI 的 VectorStore 抽象连接 Elasticsearch 向量库。索引时 vectorStore.add(docs)，查询时 vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(fetchK).build())。
```

## 2. 项目源码锚点

- `src/main/java/com/tongji/llm/rag/RagIndexService.java`
  - `vectorStore.add(docs)`。
- `src/main/java/com/tongji/llm/rag/RagQueryService.java`
  - `vectorStore.similaritySearch(...)`。
- `src/main/java/com/tongji/config/ElasticsearchConfig.java`
  - ES 客户端。
- `src/main/java/com/tongji/config/EsProperties.java`
  - ES/RAG 索引名。
- `pom.xml`
  - `spring-ai-starter-vector-store-elasticsearch`。

## 3. Embedding 是什么

Embedding 可以理解为：

```text
把文本、图片、代码等输入转换成一串数字向量。
```

例子：

```text
"Java 如何学习 Spring Boot?"
-> [0.012, -0.231, 0.847, ...]
```

这些数字不是人工定义的，而是模型学出来的语义表示。

核心直觉：

```text
语义相似的文本，向量更接近。
```

例如：

- “如何学习 Spring Boot”
- “Spring Boot 入门路线”

它们字面不完全相同，但语义接近，向量距离也应该接近。

## 4. Embedding 模型和大语言模型区别

| 类型 | 输入 | 输出 | 用途 |
|---|---|---|---|
| Embedding 模型 | 文本 | 向量 | 检索、聚类、相似度 |
| Chat 模型 | 文本/消息 | 文本 | 对话、生成、推理 |

面试回答：

```text
Embedding 模型主要用于把文本变成向量，便于相似度计算；Chat 模型主要用于生成自然语言答案。RAG 中两者通常配合使用：Embedding 负责找资料，Chat 模型负责基于资料回答。
```

## 5. 为什么不能只用关键词搜索

关键词搜索擅长：

- 精确词匹配。
- 人名、编号、代码符号。
- 标题关键词。

但它不擅长：

- 同义词。
- 口语化问题。
- 语义相关但字面不同。

向量检索擅长：

- 语义相似。
- 问法不同但意思相同。
- 跨语言或弱关键词匹配。

最佳实践：

```text
关键词检索 + 向量检索混合使用。
```

## 6. 向量库是什么

向量库负责：

- 存储文本和向量。
- 建立向量索引。
- 执行相似度检索。
- 保存 metadata。
- 返回 topK 相似文档。

常见向量库：

- Elasticsearch vector store。
- Milvus。
- Pinecone。
- Weaviate。
- Qdrant。
- pgvector。
- Redis Vector。

项目使用 Spring AI `VectorStore` 抽象，底层是 Elasticsearch。

## 7. 向量检索流程

索引阶段：

```text
文档 chunk -> Embedding 模型 -> 向量 -> 向量库
```

查询阶段：

```text
用户问题 -> Embedding 模型 -> 查询向量 -> 相似度检索 -> topK chunks
```

项目代码隐藏了 embedding 细节：

```java
vectorStore.add(docs);
vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(fetchK).build());
```

Spring AI 的 VectorStore 会负责调用配置好的 embedding 能力并与向量库交互。

## 8. 相似度

常见相似度：

- Cosine Similarity：余弦相似度。
- Dot Product：点积。
- Euclidean Distance：欧氏距离。

第一阶段重点掌握余弦相似度：

```text
看两个向量方向是否接近，而不是长度是否接近。
```

直觉：

- 夹角越小，语义越接近。
- 余弦相似度越高，越相似。

面试回答：

```text
向量检索会把问题和文档片段都表示成向量，然后计算相似度，比如余弦相似度。相似度越高，说明语义越接近，就越可能作为上下文提供给模型。
```

## 9. topK

topK 表示返回最相似的 K 个结果。

项目默认：

```java
@RequestParam(value = "topK", defaultValue = "5") int topK
```

内部宽召回：

```java
int fetchK = Math.max(topK * 3, 20);
```

### 9.1 topK 太小

问题：

- 可能漏掉答案所在片段。
- 上下文不足。

### 9.2 topK 太大

问题：

- 引入噪声。
- Prompt 变长。
- 成本增加。
- 模型可能被无关内容干扰。

面试回答：

```text
topK 是召回片段数量。太小会漏召回，太大引入噪声并增加 token 成本。项目默认 topK=5，并宽召回后按 postId 过滤。
```

## 10. 相似度阈值

除了 topK，还可以设置 threshold。

作用：

- 过滤低相似度结果。
- 避免强行拿不相关片段回答。

如果没有阈值：

```text
即使问题和文档完全无关，向量库也会返回 topK 个“相对最像”的结果。
```

RAG 中常见策略：

```text
如果最高相似度低于阈值，直接回答“根据当前资料无法确定”。
```

项目当前可优化：

- 给 `SearchRequest` 增加 similarity threshold。
- 或检索后检查分数。

## 11. metadata filter

metadata filter 用于业务过滤。

项目当前做法：

```java
Object pid = d.getMetadata().get("postId");
if (pid != null && postId.equals(String.valueOf(pid))) {
    out.add(txt);
}
```

这叫 Java 侧过滤。

更优方式：

```text
在向量库查询时直接 filter: postId == 当前 postId
```

好处：

- 减少无关召回。
- 降低网络和内存开销。
- 提高 topK 质量。

面试回答：

```text
metadata filter 是向量检索中的业务过滤，比如只检索当前 postId 的 chunk。项目目前先宽召回再按 postId 过滤，后续可以把过滤条件下推到 VectorStore，效果和性能会更好。
```

## 12. 向量索引和 ANN

真实向量库中，数据量大时不可能每次全量计算所有向量距离。

所以会使用 ANN：

```text
Approximate Nearest Neighbor，近似最近邻搜索。
```

特点：

- 牺牲一点精确度。
- 换取大幅检索性能。

常见索引：

- HNSW。
- IVF。
- PQ。

第一阶段面试不必深讲算法细节，只要知道：

```text
向量库通过专门索引结构加速相似度检索，大规模场景通常是近似最近邻。
```

## 13. 影响向量检索质量的因素

### 13.1 Embedding 模型

不同模型：

- 语言能力不同。
- 维度不同。
- 中英文效果不同。
- 价格不同。

### 13.2 chunk 切分

切分太粗或太碎都会影响召回。

### 13.3 查询表达

用户问题太短、太口语、含指代词，会影响检索。

解决：

- query rewrite。
- 多轮对话压缩。
- multi-query。

### 13.4 检索参数

- topK。
- threshold。
- metadata filter。
- rerank。

### 13.5 数据质量

- 文档是否重复。
- 是否有噪声。
- 是否过期。
- 是否分权限。

## 14. 向量检索 vs 全文检索

| 维度 | 向量检索 | 全文检索 |
|---|---|---|
| 匹配方式 | 语义相似 | 关键词/倒排索引 |
| 擅长 | 同义表达、语义问答 | 精确词、编号、术语 |
| 弱点 | 可能召回语义相似但事实不对 | 问法不同可能搜不到 |
| RAG 用法 | 找语义相关片段 | 补充精确匹配 |

面试回答：

```text
向量检索适合语义相似，全文检索适合关键词精确匹配。实际 RAG 可以做 hybrid search，先混合召回，再 rerank，提高召回质量。
```

## 15. 项目可优化点

1. 检索时下推 `postId` metadata filter。
2. 增加 similarity threshold，低于阈值拒答。
3. 返回 chunkId 和 position，支持引用来源。
4. 增加 rerank。
5. 支持 hybrid search。
6. 记录每次召回的 chunkId 和分数，用于排查效果。

## 16. 高频面试题

### Q1：Embedding 是什么？

答：

```text
Embedding 是把文本转换成高维向量的模型能力，语义相近的文本在向量空间中距离更近。RAG 用它来做语义检索。
```

### Q2：Embedding 模型和 Chat 模型区别？

答：

```text
Embedding 模型输出向量，主要用于检索、聚类、相似度；Chat 模型输出文本，主要用于对话和生成。RAG 中 Embedding 找资料，Chat 模型基于资料回答。
```

### Q3：向量检索为什么能搜到语义相似内容？

答：

```text
因为 embedding 模型把语义信息编码到向量空间中，语义接近的文本向量距离更近。查询时计算问题向量和文档向量的相似度，返回最接近的 topK。
```

### Q4：常见相似度算法有哪些？

答：

```text
常见有余弦相似度、点积、欧氏距离。文本语义检索中经常使用余弦相似度，它关注向量方向是否接近。
```

### Q5：topK 怎么选？

答：

```text
topK 太小容易漏召回，太大引入噪声并增加 token 成本。通常从 3 到 8 调试，结合回答质量和成本决定。项目默认 topK=5。
```

### Q6：为什么需要 similarity threshold？

答：

```text
如果没有阈值，即使问题和知识库无关，向量库也会返回相对最像的片段。阈值可以过滤低相关结果，避免模型基于无关上下文强答。
```

### Q7：metadata 在向量库里有什么用？

答：

```text
metadata 用于业务过滤、权限控制、版本管理和引用展示。项目 chunk metadata 保存 postId、chunkId、position、contentSha256、title 等。
```

### Q8：向量检索和关键词检索区别？

答：

```text
向量检索按语义相似召回，适合问法不同但意思相同的场景；关键词检索按词匹配，适合编号、术语、精确词。实际可以 hybrid search 结合两者。
```

### Q9：项目怎么使用 VectorStore？

答：

```text
索引阶段 RagIndexService 把 chunk 封装成 Document 后调用 vectorStore.add；查询阶段 RagQueryService 调 similaritySearch，根据用户问题召回相关片段，再按 postId 过滤。
```

### Q10：向量检索效果差怎么办？

答：

```text
检查 embedding 模型、chunk 切分、topK、阈值、metadata filter、query rewrite、rerank 和数据质量。先看召回片段是否正确，再调整生成 Prompt。
```

## 17. 项目讲法

```text
项目通过 Spring AI VectorStore 抽象接入 Elasticsearch 向量库。索引时，RagIndexService 把每个知文 chunk 封装成 Document，并在 metadata 中保存 postId、chunkId、position、title、contentSha256 等信息，然后调用 vectorStore.add 写入。

用户提问时，RagQueryService 调用 similaritySearch，用问题语义召回相关 chunk。由于当前 RAG 是围绕单篇知文问答，所以会按 metadata.postId 过滤当前知文，只保留 topK 个片段作为 Prompt 上下文。
```

## 18. 自测清单

- Embedding 是什么？
- Embedding 和 Chat 模型区别？
- 向量检索完整流程？
- 相似度怎么算？
- topK 是什么？
- threshold 为什么重要？
- metadata filter 有什么作用？
- 向量检索和关键词检索区别？
- ANN 是什么？
- 影响检索质量的因素有哪些？
- 项目哪里写入 VectorStore？
- 项目哪里 similaritySearch？
- 项目向量检索有哪些可优化点？

