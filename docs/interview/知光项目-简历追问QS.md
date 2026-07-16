# 知光项目 - 简历追问 QS

> 适用岗位：AI 应用工程师 / 大模型应用开发 / Java 后端实习  
> 使用方法：先读“高频总览”，再按 P0 顺序逐个背 2 分钟口述稿。每个问题都尽量结合知光项目回答，避免只背概念。

---

## 0. 简历项目表述

### 简历项目

**知光平台 - 知识社区与 AI 问答系统**  
角色：后端开发 / AI 应用开发

项目概述：面向知识内容发布与分享的社区平台，支持用户认证、知文发布、Feed 展示、点赞收藏、搜索、AI 摘要与单篇知文 RAG 问答。

主要工作：

- 梳理并联调知文发布链路：草稿创建、OSS 预签名直传、内容确认、元数据更新、发布、Feed 展示，解决本地联调中的 CORS、JWT 鉴权和 OSS 权限问题。
- 实现/梳理 AI 摘要链路：前端提交正文，后端通过 Spring AI ChatClient 调用 DeepSeek，使用 Prompt 约束生成 50 字以内摘要，并进行后处理、异常包装和日志记录。
- 梳理单篇知文 RAG 问答链路：内容分块、Embedding 向量化、Elasticsearch 向量检索、Prompt 拼接、DeepSeek 生成，并通过 SSE 返回流式回答。
- 理解认证与缓存设计：基于 JWT + OAuth2 Resource Server 完成接口鉴权，Refresh Token 与验证码使用 Redis TTL 管理，Feed/详情使用多级缓存提升读取性能。

---

## 1. 高频总览

### 1.1 面试官最可能从哪里问起？

1. 你这个项目的 AI 能力具体是什么？
2. AI 摘要是怎么实现的？
3. RAG 问答链路怎么走？
4. 向量库用的是什么？为什么用 Elasticsearch？
5. SSE 是什么？为什么用 SSE 返回大模型结果？
6. OSS 预签名上传为什么比公共写安全？
7. 为什么白名单接口带错 token 也会 401？
8. Refresh Token 为什么放 Redis？
9. Feed/详情缓存怎么设计？如何保证一致性？
10. 你在联调时遇到过什么实际问题，怎么排查的？

### 1.2 你要形成的总体回答

可以先用 30 秒讲清楚：

> 知光是一个知识社区项目，基础业务包括用户认证、内容发布、Feed、点赞收藏、搜索；AI 相关主要有两个能力：一个是发布时根据正文生成知识摘要，另一个是针对单篇文章做 RAG 问答。我的重点是梳理和联调完整链路，包括 OSS 预签名上传、JWT 鉴权、Redis TTL、Spring AI 调 DeepSeek、Elasticsearch 向量检索和 SSE 流式返回，并且解决过本地联调中的 CORS、token、OSS 权限和日志排查问题。

---

## 2. P0 - OSS 预签名直传链路

### Q1：知文发布链路整体怎么走？

**面试回答：**

知文发布不是一个接口完成的，而是拆成多个步骤：

1. 前端先调用后端创建草稿，后端生成帖子 ID。
2. 前端调用后端获取 OSS 预签名上传 URL。
3. 前端使用预签名 URL 直接 PUT 文件到 OSS。
4. 上传成功后，前端调用内容确认接口，把 objectKey、etag、size、sha256 回传后端。
5. 前端再调用元数据更新接口，提交标题、标签、图片 URL、可见性、摘要等。
6. 最后调用发布接口，将草稿状态从 draft 改成 published。
7. 发布后 Feed/详情/搜索/RAG 等模块基于已发布数据工作。

**项目结合：**

源码入口：

- `POST /api/v1/knowposts/drafts`
- `POST /api/v1/storage/presign`
- `POST /api/v1/knowposts/{id}/content/confirm`
- `PATCH /api/v1/knowposts/{id}`
- `POST /api/v1/knowposts/{id}/publish`

相关文件：

- `/Users/songjunliang/IdeaProjects/zhiguang_be/src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- `/Users/songjunliang/IdeaProjects/zhiguang_be/src/main/java/com/tongji/storage/api/StorageController.java`
- `/Users/songjunliang/IdeaProjects/zhiguang_fe/src/pages/CreatePage.tsx`

**继续追问：为什么不一个接口直接发布？**

因为正文和图片文件可能比较大，如果全部经过后端上传，会增加后端带宽和内存压力；拆成 OSS 直传可以让文件流量直接走对象存储，后端只负责鉴权、生成 objectKey、保存元数据。

---

### Q2：为什么用 OSS 预签名 URL，而不是把 Bucket 开成公共写？

**面试回答：**

公共写风险非常大，任何人拿到 Bucket 地址都能上传文件，可能导致垃圾文件、恶意文件、违规内容、存储费用和流量费用失控。

预签名 URL 的思路是：

1. Bucket 仍然保持私有或至少不开放公共写。
2. 后端使用自己的 AccessKey 生成一个短期有效的上传 URL。
3. 这个 URL 只允许上传到后端指定的 objectKey。
4. URL 过期后无法继续使用。

这样用户可以上传，但写权限仍然由后端控制。

**项目结合：**

项目中后端生成 objectKey：

```text
posts/{postId}/content.md
posts/{postId}/images/{date}/{random}.png
```

后端会校验 `postId` 是否属于当前登录用户，避免用户给别人的帖子上传文件。

**继续追问：预签名 URL 有什么缺点？**

- 过期时间内 URL 泄露后，别人也可能使用。
- 只能控制对象路径和方法，不能替代内容安全审核。
- 前端直传会遇到浏览器 CORS 问题。
- 上传成功后仍需后端确认对象是否存在，生产中最好做 HEAD Object 校验。

---

### Q3：OSS 直传为什么会遇到 CORS？

**面试回答：**

前端页面运行在 `http://localhost:5173`，但上传目标是 `https://bucket.oss-cn-xxx.aliyuncs.com`，这是跨域请求。

浏览器对跨域 PUT 请求会先发 OPTIONS 预检请求，如果 OSS Bucket 没配置允许该 Origin、Method、Header，浏览器会拦截真正的 PUT 请求。

**项目结合：**

我联调时遇到过：

```text
No 'Access-Control-Allow-Origin' header is present
```

解决方案是在 OSS Bucket CORS 中配置：

```text
AllowedOrigin: http://localhost:5173
AllowedMethod: GET, HEAD, PUT, POST
AllowedHeader: *
ExposeHeader: ETag
```

**继续追问：为什么要暴露 ETag？**

浏览器默认只能读取少数安全响应头。前端上传成功后需要读取 OSS 返回的 `ETag`，再回传给后端确认上传结果，所以需要在 CORS 中暴露 `ETag`。

---

### Q4：为什么上传成功后还要 content/confirm？

**面试回答：**

预签名 PUT 只代表文件上传到了 OSS，但数据库还不知道这个文件对应哪篇知文，也不知道 objectKey、etag、size、sha256 等信息。

`content/confirm` 的作用是把对象存储中的文件和业务数据绑定起来：

1. 记录 `contentObjectKey`
2. 记录 `contentUrl`
3. 记录 `contentEtag`
4. 记录 `contentSize`
5. 记录 `contentSha256`

这样后续详情展示、RAG 索引、内容读取才能找到对应文件。

**项目结合：**

后端方法：

```java
service.confirmContent(userId, id, request.objectKey(), request.etag(), request.size(), request.sha256());
```

**继续追问：生产中如何防止前端伪造 confirm？**

当前项目主要依赖 JWT 用户校验和 postId 归属校验。更安全的做法是：

- 后端对 OSS 做 HEAD Object，确认对象真实存在。
- 校验 Content-Length 是否和前端回传一致。
- 校验 Content-Type 是否符合白名单。
- objectKey 必须位于当前用户当前 postId 的目录下。
- 对图片做格式、大小、安全审核。

---

## 3. P0 - AI 摘要链路

### Q5：AI 摘要链路怎么走？

**面试回答：**

AI 摘要不是发布时自动后台生成，而是前端在创建页面点击 AI 摘要开关后主动触发：

1. 前端检查用户是否登录、正文是否为空。
2. 前端请求 `POST /api/v1/knowposts/description/suggest`。
3. 后端 Spring Security 校验 Bearer JWT。
4. Controller 接收正文。
5. Service 构造 system prompt 和 user prompt。
6. 通过 Spring AI ChatClient 调用 DeepSeek。
7. 后端对模型结果做后处理，限制 50 字以内。
8. 前端拿到 description 后回填到摘要输入框。
9. 用户发布时，摘要作为 `description` 随元数据一起保存。

**项目结合：**

前端：

- `/Users/songjunliang/IdeaProjects/zhiguang_fe/src/pages/CreatePage.tsx`
- `/Users/songjunliang/IdeaProjects/zhiguang_fe/src/services/knowpostService.ts`

后端：

- `/Users/songjunliang/IdeaProjects/zhiguang_be/src/main/java/com/tongji/knowpost/api/KnowPostAiController.java`
- `/Users/songjunliang/IdeaProjects/zhiguang_be/src/main/java/com/tongji/llm/service/impl/KnowPostDescriptionServiceImpl.java`

**继续追问：摘要接口为什么要登录？**

因为大模型调用有成本，如果匿名开放，容易被恶意刷接口；登录后可以做用户级限流、审计和成本控制。

---

### Q6：system prompt 和 user prompt 分别有什么作用？

**面试回答：**

system prompt 用来定义模型角色、任务边界和输出规范；user prompt 放用户输入的正文和具体请求。

在项目里：

```text
system：你是中文文案编辑，请生成简洁有吸引力且不超过 50 字的中文描述，不输出解释。
user：正文如下：... 请直接给出不超过 50 字的中文描述。
```

这样做的好处是把稳定规则放在 system，把动态正文放在 user，结构更清楚。

**继续追问：只靠 prompt 能保证 50 字以内吗？**

不能完全保证。大模型可能不严格遵守字数限制，所以后端还做了 `postProcess`，按 code point 截断到 50 字。也就是说：

```text
Prompt 是软约束
后端后处理是硬约束
```

---

### Q7：temperature 和 maxTokens 怎么理解？

**面试回答：**

`temperature` 控制输出随机性，越高越发散，越低越稳定。摘要场景既要自然一点，又不能太发散，所以项目里使用了 `0.8`。

`maxTokens` 控制最大生成长度，项目里摘要只需要 50 字以内，所以设置 `maxTokens=120` 足够，避免模型生成很长内容导致成本增加。

**项目结合：**

```java
DeepSeekChatOptions.builder()
    .model("deepseek-chat")
    .temperature(0.8)
    .maxTokens(120)
    .build()
```

**继续追问：怎么进一步控制成本？**

- 限制正文最大输入长度。
- 对同一篇文章摘要做缓存。
- 对用户做接口限流。
- 使用更便宜的模型生成摘要。
- 记录 token 用量和调用耗时。
- 前端防重复点击，后端幂等处理。

---

### Q8：大模型调用失败怎么处理？

**面试回答：**

项目里大模型调用被 try-catch 包住。如果调用失败，会抛业务异常：

```text
INTERNAL_ERROR
大模型调用失败: xxx
```

前端捕获错误后展示“生成失败”或后端返回的 message。

**项目结合：**

我后来给摘要服务加了日志，打印输入长度、输入预览、模型原始输出、后处理后的输出，以及失败时的异常信息，方便联调时判断是否真的调用到大模型。

**继续追问：生产中是否应该把异常原文返回给前端？**

不建议。生产中应该：

- 前端返回统一友好提示。
- 后端日志记录详细异常。
- 避免把 API Key、供应商错误详情、内部网络信息暴露给用户。
- 可增加降级策略，比如“摘要生成失败，请手动填写”。

---

## 4. P0 - RAG 问答链路

### Q9：RAG 和普通大模型问答有什么区别？

**面试回答：**

普通大模型问答主要依赖模型参数中的知识，可能存在知识过时、上下文缺失和幻觉问题。

RAG 是 Retrieval-Augmented Generation，即检索增强生成。它会先从外部知识库检索相关内容，再把检索结果作为上下文放进 Prompt，让模型基于检索到的材料回答。

流程是：

```text
用户问题 -> 向量检索 -> 召回相关片段 -> 拼接 Prompt -> 大模型生成答案
```

**项目结合：**

知光项目做的是“单篇知文 RAG 问答”，也就是用户在某篇文章详情页提问，系统从这篇文章的切片里召回相关内容，再让 DeepSeek 回答。

**继续追问：RAG 能完全消除幻觉吗？**

不能。RAG 只能降低幻觉。还需要：

- 检索结果质量足够好。
- Prompt 明确要求基于上下文回答。
- 没有依据时回答“不确定”。
- 对答案做引用来源或片段展示。
- 评估召回率和答案相关性。

---

### Q10：文档为什么要切分 chunk？

**面试回答：**

如果整篇文章直接做一个向量，粒度太粗，检索时很难定位到具体相关内容；如果切得太碎，语义又可能不完整。

切分 chunk 的目的是在“语义完整性”和“检索精度”之间平衡。

常见做法：

- 按标题、段落、Markdown 结构切分。
- 设置最大 chunk 长度。
- 设置 overlap，避免上下文被截断。

**项目结合：**

项目里会从知文 `contentUrl` 拉取 Markdown 内容，然后进行分块，再写入向量库。

**继续追问：chunk size 和 overlap 怎么选？**

经验上：

- chunk 太大：召回结果不精准，占用上下文多。
- chunk 太小：语义不完整，回答缺上下文。
- overlap 可以保留跨段信息，但会增加索引数量和成本。

面试中可以回答：先按业务经验设置，比如 800 字符、100 overlap，再通过实际问答效果调优。

---

### Q11：Embedding 是什么？为什么维度要一致？

**面试回答：**

Embedding 是把文本转换成高维向量，让语义相近的文本在向量空间中距离更近。

向量检索时，用户问题也会被转成向量，然后和文档 chunk 的向量做相似度计算。

维度必须一致，因为向量相似度计算要求两个向量长度相同。如果索引中是 1536 维，查询向量也必须是 1536 维，否则无法计算。

**项目结合：**

项目使用 DashScope 的 embedding 模型配置：

```yaml
model: text-embedding-v4
dimensions: 1536
```

向量库使用 Elasticsearch vector store。

**继续追问：为什么不用 MySQL 做向量检索？**

MySQL 更适合结构化数据和事务查询，不适合高维向量相似度检索。向量检索需要专门的索引和相似度搜索能力，因此使用 Elasticsearch、Milvus、pgvector 等更合适。

---

### Q12：topK / fetchK 是什么？

**面试回答：**

`topK` 表示最终取多少个最相关片段作为上下文。

`fetchK` 可以理解为先召回更多候选，再在应用层过滤或重排。例如项目里如果需要按 `postId` 过滤，就可能先取更多，再过滤出当前文章的片段。

**项目结合：**

单篇文章 RAG 最好在向量检索阶段就按 `postId` 做 metadata filter，否则全局召回可能被其他文章的相似内容占据，导致当前文章片段召回不足。

**继续追问：topK 越大越好吗？**

不是。topK 太小容易缺信息；topK 太大会带入噪声，占用上下文窗口，增加 token 成本，还可能干扰模型判断。

---

### Q13：为什么 RAG 首次提问可能慢？

**面试回答：**

首次提问可能会触发索引检查或重建：

1. 判断文章是否已经向量化。
2. 如果没有，读取 OSS 中的正文。
3. 对内容进行切分。
4. 调 embedding 模型生成向量。
5. 写入 Elasticsearch。
6. 再进行检索和生成。

这些步骤都在首次请求链路里时，首 token 延迟就会比较高。

**优化方向：**

- 发布时异步预索引。
- 用消息队列触发索引任务。
- 建立索引状态表。
- RAG 请求只做查询，不做重建。
- 对热门文章预热。

---

## 5. P0 - SSE 流式输出

### Q14：为什么大模型回答适合 SSE？

**面试回答：**

大模型长回答如果等完整结果生成后一次性返回，用户等待时间长，前端也容易超时。SSE 可以让后端边生成边返回，前端边接收边展示，用户体感更好。

SSE 特点：

- 基于 HTTP。
- 服务端单向推送。
- Content-Type 是 `text/event-stream`。
- 适合大模型流式文本、通知、进度条等场景。

**项目结合：**

项目 RAG 问答接口：

```text
GET /api/v1/knowposts/{id}/qa/stream
```

返回 `Flux<String>`，通过 SSE 流式输出模型内容。

---

### Q15：SSE 和 WebSocket 有什么区别？

**面试回答：**

SSE 是服务端到客户端的单向推送，基于 HTTP，前端使用 EventSource 或 fetch stream 就能接收，比较简单。

WebSocket 是全双工通信，适合实时聊天、协同编辑、游戏等客户端和服务端频繁双向通信的场景。

大模型文本生成通常是用户发起一次请求，服务端持续返回文本，因此 SSE 已经足够，复杂度比 WebSocket 更低。

**继续追问：SSE 有什么缺点？**

- 主要是单向通信。
- 浏览器连接数有限。
- 代理层可能有缓冲，需要配置。
- 错误处理和断线重连要额外设计。

---

## 6. P0 - JWT + Spring Security Resource Server

### Q16：JWT 的结构是什么？

**面试回答：**

JWT 由三部分组成：

```text
Header.Payload.Signature
```

- Header：算法、类型、key id。
- Payload：业务声明，比如 sub、uid、exp、jti、token_type。
- Signature：对前两部分签名，防止篡改。

JWT 本身不是加密，只是 Base64URL 编码，所以不要放敏感信息。

---

### Q17：Access Token 和 Refresh Token 有什么区别？

**面试回答：**

Access Token 用来访问业务接口，生命周期短，例如 15 分钟；Refresh Token 用来换取新的令牌对，生命周期长，例如 7 天。

这样设计是为了兼顾安全和体验：

- Access Token 泄露后影响时间较短。
- Refresh Token 可以让用户不用频繁登录。
- Refresh Token 放 Redis 白名单，可以支持服务端撤销。

**项目结合：**

项目里 Refresh Token 的 `jti` 会存入 Redis：

```text
auth:rt:{userId}:{tokenJti}
```

登出或重置密码时可以删除 Redis 中的白名单记录，让 Refresh Token 失效。

---

### Q18：OAuth2 Resource Server 如何自动校验 Bearer JWT？

**面试回答：**

Spring Security 配置了 Resource Server 后，请求进入过滤链时会检查 `Authorization: Bearer xxx`。

如果存在 Bearer Token，它会：

1. 解析 JWT。
2. 校验签名。
3. 校验 exp 等标准声明。
4. 构造 Authentication。
5. Controller 中可以通过 `@AuthenticationPrincipal Jwt` 拿到当前 JWT。

**项目结合：**

项目 SecurityConfig：

```java
.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
```

Controller 中：

```java
public AuthUserResponse me(@AuthenticationPrincipal Jwt jwt)
```

---

### Q19：为什么 permitAll 接口带错 token 也会 401？

**面试回答：**

`permitAll` 的意思是“不要求必须认证”，不是“忽略 Authorization 头”。

如果请求没有 Authorization，白名单接口可以直接访问。

但如果请求带了：

```text
Authorization: Bearer bad-token
```

Resource Server 会尝试解析这个 token，解析失败就返回 401，甚至还没进入 Controller。

**项目结合：**

我在 Apifox 测试发送验证码、注册、登录时遇到过白名单接口返回 401，原因就是 Apifox 继承了全局 Bearer Token，但 token 是空的或无效。

---

## 7. P1 - Redis TTL 与缓存

### Q20：验证码为什么适合放 Redis？

**面试回答：**

验证码天然是短期有效数据，不需要长期持久化，适合 Redis TTL。

优点：

- 自动过期。
- 读写快。
- 可以记录尝试次数。
- 可以做发送频率限制。
- 可以按场景隔离 key。

**项目结合：**

验证码 key 类似：

```text
auth:code:REGISTER:13800000000
```

TTL 大约 5 分钟。

---

### Q21：Refresh Token 为什么也适合放 Redis？

**面试回答：**

JWT 本身是无状态的，签发后服务端不保存也能校验。但 Refresh Token 通常需要支持服务端撤销，比如登出、重置密码、风控下线。

把 Refresh Token 的 jti 放 Redis 白名单，可以做到：

- 只有 Redis 中存在的 refresh token 才有效。
- 登出时删除对应 jti。
- 重置密码时删除用户全部 refresh token。
- Redis TTL 和 refresh token 过期时间一致。

---

### Q22：Feed/详情为什么要做多级缓存？

**面试回答：**

Feed 和详情是读多写少场景，热点内容可能被大量访问。如果每次都查数据库，会增加数据库压力。

多级缓存通常是：

```text
本地缓存 Caffeine -> Redis -> MySQL
```

优点：

- Caffeine 本地缓存速度最快。
- Redis 作为分布式缓存，多个实例共享。
- MySQL 作为最终数据源。

**继续追问：缓存一致性怎么处理？**

更新或发布内容后要删除缓存，项目中有缓存失效逻辑；复杂场景可以用延迟双删、消息队列、版本号、TTL 兜底。

---

## 8. P1 - 工程排查类问题

### Q23：你联调时遇到过哪些问题？

**面试回答：**

我主要遇到过几类问题：

1. 后端 8080 端口被旧进程占用，导致新服务启动失败。
2. `application-local.yml` 配了 OSS key 但没生效，原因是没有启用 `local` profile。
3. Apifox 白名单接口返回 401，原因是继承了无效 Authorization 头。
4. OSS 上传失败，原因是 Bucket CORS 没允许 `localhost:5173` 的 PUT 请求。
5. OSS 上传成功但图片不显示，原因是上传权限和读取权限是两回事，Bucket 或对象没有公共读。
6. AI 摘要没有后端日志，原因是摘要链路没有数据库 SQL，也没有业务 log，后来补了输入输出日志。

**继续追问：你如何定位问题？**

我的排查顺序一般是：

```text
看浏览器 Network -> 看后端日志 -> 用 Apifox/curl 复现 -> 查配置 -> 查源码链路 -> 缩小失败点
```

例如 OSS 问题，后端日志显示草稿创建和 presign 都成功，但浏览器报 CORS，因此可以判断失败点在浏览器直传 OSS，而不是后端接口。

---

### Q24：application-local.yml 为什么没生效？

**面试回答：**

Spring Boot 不会自动加载 `application-local.yml`，必须启用 `local` profile：

```text
--spring.profiles.active=local
```

或者设置环境变量：

```text
SPRING_PROFILES_ACTIVE=local
```

否则只会读 `application.yml`，里面 OSS key 默认是空，就会导致：

```text
Access key id should not be null or empty
```

---

## 9. P2 - AI 应用工程化追问

### Q25：如何防止用户刷 AI 摘要接口？

**面试回答：**

可以从几层做：

1. 登录鉴权，匿名用户不能调用。
2. 用户级限流，比如每分钟 N 次。
3. IP 级限流，防止脚本刷。
4. 输入长度限制，避免超长文本消耗 token。
5. 摘要结果缓存，同一篇文章不重复生成。
6. 记录调用日志和成本。
7. 对失败和超时做降级。

---

### Q26：如何处理 Prompt 注入？

**面试回答：**

Prompt 注入是用户在输入中写入“忽略以上规则”之类的指令，试图让模型越过系统约束。

处理方式：

- system prompt 明确边界。
- 将用户内容作为待处理数据，而不是指令。
- 对输入做长度和敏感内容检查。
- 对输出做安全过滤。
- RAG 场景中要求模型只基于上下文回答。
- 对高风险场景加入审核模型或规则。

---

### Q27：如何评估 RAG 效果？

**面试回答：**

RAG 评估可以分检索和生成两部分。

检索侧：

- 召回率：相关片段有没有被召回。
- 精准率：召回片段里有多少是真相关。
- topK 命中率。

生成侧：

- 答案是否基于上下文。
- 是否回答完整。
- 是否有幻觉。
- 是否引用了正确内容。

工程上可以构建一批测试问题和标准答案，定期评估召回和回答质量。

---

## 10. 7 天复习安排

### Day 1：OSS 直传与发布链路

目标：能画出草稿、预签名、上传、确认、元数据、发布全链路。

重点问题：

- 为什么用预签名 URL？
- CORS 为什么会失败？
- 上传成功为什么还要 confirm？
- objectKey 为什么后端生成？

### Day 2：AI 摘要链路

目标：能讲从前端点击到 DeepSeek 返回摘要，再到发布保存的完整链路。

重点问题：

- Prompt 怎么设计？
- ChatClient 怎么调用？
- temperature/maxTokens 是什么？
- 如何记录输入输出日志？
- 如何控制成本？

### Day 3：RAG 链路

目标：能讲清 chunk、embedding、向量检索、Prompt 拼接、SSE 输出。

重点问题：

- RAG 和普通问答区别？
- chunk size 怎么选？
- topK 怎么选？
- postId metadata filter 为什么重要？
- 首次问答为什么慢？

### Day 4：JWT 与 Spring Security

目标：能讲 Access/Refresh Token、JWT claims、Resource Server 自动鉴权。

重点问题：

- JWT 三段结构。
- exp/sub/jti/claim。
- Refresh Token 为什么放 Redis？
- permitAll 为什么也可能 401？

### Day 5：Redis 与缓存

目标：能讲验证码 TTL、Refresh Token 白名单、Feed/详情多级缓存。

重点问题：

- Redis TTL 适合什么场景？
- Caffeine + Redis + MySQL 怎么分层？
- 缓存一致性怎么处理？

### Day 6：SSE 与前后端联调

目标：能讲 SSE 和 WebSocket 区别，以及你如何排查项目问题。

重点问题：

- SSE 为什么适合大模型？
- 浏览器 Network 怎么看？
- Apifox 环境变量怎么用？
- CORS、401、端口占用怎么排？

### Day 7：压缩成口述稿

目标：每个模块准备 30 秒、2 分钟、5 分钟三个版本。

建议模板：

```text
这个模块解决什么问题？
链路怎么走？
我看过/改过哪些代码？
遇到过什么问题？
后续怎么优化？
```

---

## 11. 面试口述模板

### 11.1 30 秒项目介绍

> 知光是一个知识社区平台，基础能力包括认证、内容发布、Feed、点赞收藏和搜索；AI 能力主要包括发布时的知识摘要和单篇文章 RAG 问答。我重点梳理和联调了发布链路、OSS 预签名上传、AI 摘要、RAG 检索生成、JWT 鉴权和 Redis 缓存，并在本地联调中解决过 CORS、token、OSS 权限、profile 配置和日志排查问题。

### 11.2 2 分钟 AI 摘要介绍

> AI 摘要是在创建知文时触发的。前端先检查登录态和正文是否为空，然后调用 `/api/v1/knowposts/description/suggest`。这个接口不是白名单，所以会先经过 Spring Security Resource Server 校验 Bearer JWT。后端 Controller 接收正文后，调用摘要服务，服务里通过 Spring AI ChatClient 构造 system prompt 和 user prompt，调用 DeepSeek 的 `deepseek-chat` 模型，并设置 temperature 和 maxTokens。模型返回后，后端会做换行清理、标点清理和 50 字截断，最后返回给前端。前端把结果填入摘要输入框，用户发布时再作为 `description` 随元数据保存到数据库。

### 11.3 2 分钟 RAG 介绍

> 单篇知文 RAG 的思路是先把文章内容从 OSS 读取出来，按 Markdown 结构和长度切分为多个 chunk，再通过 embedding 模型转成向量，写入 Elasticsearch 向量索引。用户在详情页提问时，系统先确保文章已经被索引，然后把问题转成向量，在向量库中召回相关 chunk，再把这些上下文和用户问题拼成 Prompt 调用 DeepSeek，最后通过 SSE 流式返回答案。这样可以让模型基于当前文章内容回答，降低单纯依赖模型内部知识带来的幻觉。

### 11.4 2 分钟 OSS 直传介绍

> 发布知文时，文件没有直接传到后端，而是通过 OSS 预签名 URL 直传。前端先创建草稿拿到 postId，然后请求后端生成 presign URL。后端会从 JWT 解析当前用户，并校验 postId 是否属于该用户，再生成限定 objectKey、Method 和过期时间的 PUT URL。前端拿 URL 直接 PUT 到 OSS，成功后再调用 content/confirm 把 objectKey、etag、size、sha256 回传后端。这样后端不承担文件流量，同时 Bucket 不需要开放公共写，安全性更好。

