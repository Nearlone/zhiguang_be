# 知光 RAG P0 闭环报告

日期：2026-07-17  
评测范围：20 篇知文、75 道问题  
固定参数：`topK=5`、`maxTokens=1024`、`similarityThreshold=0.50`

## 1. 结论

P0 的检索、拒答、引用、限流、结构化 SSE、日志指标和自动评测链路已完成工程闭环。

- 20 篇知文共 305 个向量，MySQL 索引状态和 Elasticsearch 数量一致。
- 最终 75 道题在外部服务失败样本重试后，业务行为通过率为 `75/75`。
- 55 道可回答题均正常回答并携带当前知文来源引用。
- 20 道文档外问题均在生成前拒答，不调用 DeepSeek。
- 跨文章引用和伪造引用均为 0。
- 旧文本 SSE 保持兼容，新增 `/qa/stream/v2` 提供 `meta/delta/done/error`。
- 匿名与登录用户具备独立速率配额，并限制同一主体的 SSE 并发连接。

## 2. 阈值校准

16 道代表题由 10 道正例和 6 道拒答题组成。

| 阈值 | 有效样本结果 | 正例引用 | 拒答 | 结论 |
| --- | ---: | ---: | ---: | --- |
| 0.45 | 重试后 16/16 | 10/10 | 6/6 | 可用，但没有额外收益 |
| 0.50 | 16/16 | 10/10 | 6/6 | 最终选择 |
| 0.55 | 16/16 | 10/10 | 6/6 | 效果相同，误拒风险更高 |

`0.50` 与 `0.55` 在当前小样本上的行为相同，因此保留较低的 `0.50`，为同义改写和边界问题保留召回空间。0.45 首轮的 3 道异常来自 DashScope 短时无响应，单独重试后全部通过，不属于阈值差异。

## 3. 最终 75 题

首次执行：

| 指标 | 结果 |
| --- | ---: |
| 首次请求成功 | 71/75 |
| 外部服务暂时不可用 | 4/75 |
| 首次行为通过 | 71/75 |
| 总耗时 P50 | 2.958 s |
| 总耗时 P95 | 20.047 s |
| 最大客户端耗时 | 22.184 s |

4 道失败连续出现在同一篇 MVCC 知文，错误均为 Embedding 服务暂时不可用。未修改代码，只重试失败题后：

| 指标 | 重试合并后的最终结果 |
| --- | ---: |
| 业务行为通过 | 75/75 |
| 可回答题正常回答 | 55/55 |
| 可回答题携带引用 | 55/55 |
| 文档外问题拒答 | 20/20 |
| 文档外问题 DeepSeek 调用 | 0/20 |
| 跨文章引用 | 0 |

首次可用率和重试后的业务正确率分开报告，避免把供应商波动误算成检索质量问题。

## 4. 与原 15 篇基线对比

最终 75 题中的前 45 道与第一轮基线使用相同知文和问题，可直接比较。

| 指标 | 第一轮基线 | P0 最终 | 变化 |
| --- | ---: | ---: | ---: |
| HTTP/SSE 成功 | 44/45 | 45/45 | +1 |
| 可回答题正常回答 | 29/30 | 30/30 | +1 |
| 文档外问题拒答 | 14/15 | 15/15 | +1 |
| 可回答题带引用 | 29/30 | 30/30 | +1 |
| 跨文章引用 | 0 | 0 | 持平 |
| 文档外问题 Total Token | 17,222 | 0 | -100% |
| 总耗时 P95 | 9.906 s | 4.048 s | -59.1% |
| 最大客户端耗时 | 180.003 s | 22.184 s | -87.7% |

外部模型延迟仍有波动，P95 结论只对同一 45 题串行样本成立，不能直接推导线上 SLA。

## 5. 回答完整性修复

最终回归前发现 `P4-Q3` 只回答了 `LinkedBlockingQueue`，没有完整覆盖三个并列实体。Elasticsearch 中 `#6/#7/#8` 三个切片均存在，根因是整句 BM25 被“风险、OOM”等公共词主导。

修复方式：

1. 从问题中提取 CamelCase、缩写、数字和连接符技术标识符。
2. 多实体问题为每个技术标识符执行一次受限 BM25 查询。
3. 每个实体先保留一个候选，再由整句 BM25 补满。
4. 继续通过 RRF 与向量结果融合，最终严格限制为 `topK`。

修复后 `P4-Q3` 的最终上下文包含 `#6/#8/#7`，回答完整说明三种队列：

- `ArrayBlockingQueue` 有界、内存可控。
- `LinkedBlockingQueue` 近似无界时可能积压并 OOM。
- `SynchronousQueue` 不存储任务，没有空闲线程时可能继续创建线程。

6 道技术术语回归题结果为 `6/6`，均有引用且无跨文章来源。

## 6. 稳定性与安全

### 限流

默认配置：

```text
匿名用户：10 次/分钟
登录用户：30 次/分钟
同一主体：最多 2 个并发 SSE
并发许可租期：3 分钟
```

限流 Key 使用用户 ID 或客户端 IP 的 SHA-256 摘要，不保存明文 IP。默认只信任 TCP 远端地址，不直接信任客户端提交的 `X-Forwarded-For`。Redis 故障时成本型接口受控返回 503。

真实验证使用临时 `1 次/分钟` 配置：第一次请求返回 200，第二次返回 429，并包含 `Retry-After: 60` 和 `RAG_RATE_LIMITED`。

### 结构化 SSE

新增兼容接口：

```text
GET /api/v1/knowposts/{id}/qa/stream/v2
```

正常顺序为 `meta -> delta* -> done`，异常顺序为 `meta -> delta* -> error`。

真实空召回冒烟结果为 `meta -> delta(固定拒答) -> done`，响应头和 `meta` 均携带同一个 Request ID。前端已改用 `fetch + ReadableStream`，支持 Authorization、429 文案、结构化错误和 `AbortController` 取消。

## 7. 可观测性

日志新增或补齐：

- `requestId` 与 MDC。
- `vectorHitCount`、`keywordHitCount`、`finalHitCount`。
- `maxVectorScore`、`minAcceptedScore`、`avgAcceptedScore`。
- `indexMs`、`retrievalMs`、`firstTokenMs`、`totalMs`。
- 服务商 Token usage 和本地估算降级。
- `principalType`、错误阶段、异常类型和取消状态。

Micrometer 指标：

- `rag.requests`
- `rag.retrieval.empty`
- `rag.model.calls`
- `rag.errors`
- `rag.rate.limited`
- `rag.retrieval.duration`
- `rag.first.token.duration`
- `rag.total.duration`
- `rag.provider.tokens`

指标标签只使用有限状态值，不包含 requestId、postId 和用户 ID。

## 8. 验证记录

- Java 21 `mvn clean test`：61 个测试全部通过。
- Spring Boot `local` Profile：启动成功。
- 前端 `npm run build`：TypeScript 与 Vite 构建成功。
- v2 SSE：真实空召回冒烟通过。
- Redis 速率限制：真实 429 冒烟通过。
- 最终 20 篇、75 题：失败样本重试后 75/75。

原始结果：

- `results/p0-final-20-posts-threshold-0.50-2026-07-17.jsonl`
- `results/p0-final-20-posts-threshold-0.50-retry-2026-07-17.jsonl`
- `results/technical-term-regression-0.50-2026-07-17.jsonl`

## 9. 复现命令

完整离线评测需要关闭正常用户限流：

```bash
RAG_SIMILARITY_THRESHOLD=0.5 \
RAG_RATE_LIMIT_ENABLED=false \
mvn spring-boot:run -Dspring-boot.run.profiles=local

scripts/rag-eval.sh \
  --threshold 0.50 \
  --output docs/rag-eval-corpus/results/p0-final.jsonl
```

正常开发和生产环境不要关闭 `RAG_RATE_LIMIT_ENABLED`。

## 10. 面试表达

> 我为知光的单篇知文 RAG 建立了 20 篇文章、75 道问题的固定评测集。基线暴露了 topK 越界、文档外问题仍调用模型、Embedding 长尾、重建后立即不可见和多实体问题证据不完整等问题。我在检索阶段按 postId 隔离当前文章，用向量门槛控制空召回，通过 BM25、技术实体补召回和 RRF 融合提高完整性；生成阶段增加结构化来源、引用校验和 Prompt 注入防护；工程侧增加 Redis 速率/并发限流、结构化 SSE、Request ID、Micrometer 指标与 Token 统计。最终原 45 题可比集达到 45/45，文档外问题生成 Token 降为 0，20 篇 75 题在外部服务失败样本重试后全部通过。

尚未对扩展后的 55 道可回答题逐题重新执行完整 6 分制人工评分；当前结论基于自动行为、引用校验和重点失败题人工复核，后续可将人工评分作为 P1 持续评测项。
