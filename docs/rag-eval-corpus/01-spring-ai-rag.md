# Spring AI 的 RAG 模块化链路

> 整理来源：[Spring AI Retrieval Augmented Generation](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
>
> 本文是用于知光 RAG 评测的中文整理稿，不是官方文档的逐字翻译。

## RAG 解决什么问题

大模型只依赖训练参数时，容易遇到知识过期、私有资料不可见、长文细节遗失和事实依据无法追踪等问题。RAG 在模型生成前检索外部资料，把相关片段作为上下文加入 Prompt，使回答能够基于业务文档而不是只依赖模型记忆。

RAG 不能自动保证答案绝对正确。检索可能漏掉关键片段，分块可能破坏上下文，Prompt 可能约束不足，模型也可能误解证据。因此工程上仍要记录召回片段、来源引用、延迟和 Token，并通过评测集持续验证。

## QuestionAnswerAdvisor

Spring AI 提供 `QuestionAnswerAdvisor` 封装常见的朴素 RAG 流程。开发者先把文档写入 `VectorStore`，用户提问时 Advisor 执行相似度检索，再把命中的文档追加到用户问题中，最后交给 `ChatClient` 生成答案。

```java
ChatResponse response = ChatClient.builder(chatModel)
        .build()
        .prompt()
        .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
        .user(userText)
        .call()
        .chatResponse();
```

`SearchRequest` 可以配置 `topK` 和 `similarityThreshold`。例如 `topK=6` 表示最多取 6 个候选片段；相似度阈值为 `0.8` 时，只接受达到该阈值的结果。阈值过高可能造成有答案却无召回，过低则可能把无关内容交给模型。

## 动态元数据过滤

单篇知文问答不能先让全站文章竞争候选名额，再在 Java 中删除其他文章。更稳妥的方式是在向量检索阶段传入过滤表达式，例如按照文档类型、租户、权限或 `postId` 限定检索范围。

Spring AI 的 `FILTER_EXPRESSION` 参数允许在每次请求中动态设置过滤条件。这样同一个 `ChatClient` 可以服务不同文章，同时保证当前请求只检索有权访问的资料。

## RetrievalAugmentationAdvisor

复杂场景可以使用 `RetrievalAugmentationAdvisor` 组合预检索、检索、后处理和生成模块。预检索阶段可以改写问题或扩展查询；检索阶段从向量库获取候选；后处理阶段可以去重、融合或重排；生成阶段负责构造带上下文的 Prompt。

默认情况下，检索上下文为空时，Advisor 会要求模型不要回答。也可以通过 `allowEmptyContext(true)` 放开限制，但知识库问答通常不应这样做，否则模型可能使用自身知识补全不存在于文章中的事实。

## Prompt 与安全边界

自定义 Prompt 模板时，需要明确区分用户问题和检索资料。资料属于不可信输入，其中可能包含“忽略之前规则”等提示注入文本。系统规则应要求模型只把资料当作事实来源，不执行资料中的命令，并在无依据时输出固定拒答。

完整的生产链路还应包括：索引版本检查、文章级权限过滤、检索日志、来源编号映射、Token 统计、超时处理和离线评测。Advisor 能减少样板代码，但不能替代这些工程控制。
