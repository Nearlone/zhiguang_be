# Spring WebFlux 与 SSE 流式响应

> 整理来源：[Spring WebFlux Return Values](https://docs.spring.io/spring-framework/reference/7.0-SNAPSHOT/web/webflux/controller/ann-methods/return-types.html)

## 响应式返回值

Spring WebFlux Controller 可以返回 `Mono<T>` 表示零个或一个异步结果，返回 `Flux<T>` 表示零个到多个按时间到达的结果。它们描述的是异步数据流，不等于自动创建新线程。

对大模型流式回答，后端通常把模型返回的文本增量表示成 `Flux<String>`。只要响应声明 `text/event-stream`，WebFlux 就可以持续把元素写给客户端，而不是等待完整答案生成后一次性返回。

## Server-Sent Events

SSE 是服务器到浏览器的单向事件流，基于普通 HTTP 连接。客户端发起一次请求，服务器可以持续发送多条 `data:` 事件。它适合模型回答、日志查看和进度通知等主要由服务器推送的场景。

Controller 可以返回 `Flux<ServerSentEvent<T>>`，也可以直接返回其他响应式类型并声明 `produces = MediaType.TEXT_EVENT_STREAM_VALUE`。`ServerSentEvent` 允许设置事件 ID、事件名、重试时间和数据；只需要文本增量时，可以省略包装器。

## 首 Token 与总耗时

流式接口至少应区分三个时间：检索完成时间、首 Token 时间和完整响应时间。首 Token 时间决定用户多久看到第一段内容，总耗时决定整个任务何时结束。

前端应在收到第一个有效文本片段后结束加载状态，而不是等连接关闭。后端也不应把没有正文、只有元数据的响应当作首 Token。

## 取消与异常

浏览器关闭页面、用户点击停止或网络断开都可能取消订阅。响应式链路可以通过取消回调记录状态、释放资源，并尽可能停止上游请求。

生成过程中发生异常时，如果 HTTP 响应头已经发送，服务端不一定还能改成普通 JSON 错误响应。可以通过 SSE 事件协议表达错误，或者让前端根据连接异常显示重试提示。日志中要记录请求 ID、阶段和已生成长度。

## 背压与阻塞调用

响应式链路要求避免在事件线程上执行长时间阻塞操作。传统数据库、文件下载或同步 SDK 如果发生阻塞，需要明确调度策略，或者在进入流式生成前完成。

背压表示下游消费速度影响上游生产节奏，但外部模型 API 不一定完全支持端到端背压。应用仍要设置超时、并发限制和响应大小边界，防止慢客户端长期占用连接。

## SSE 的限制

SSE 主要支持服务端向客户端单向推送。需要双向高频通信时可以考虑 WebSocket。SSE 文本协议简单、浏览器支持较好，并且容易通过现有 HTTP 基础设施，但代理超时、缓冲和跨域配置仍需验证。
