# Spring 声明式缓存注解

> 整理来源：[Spring Declarative Annotation-based Caching](https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html)

## 缓存抽象

Spring Cache 通过统一注解把缓存行为添加到方法调用，不要求业务代码直接依赖某个缓存产品。实际存储可以是 Caffeine、Redis 或其他 `CacheManager` 实现。

缓存抽象主要描述何时读、写和删除缓存。TTL、最大容量和淘汰策略通常由具体缓存实现配置，不是 `@Cacheable` 注解自动提供的能力。

## @Cacheable

`@Cacheable` 先根据缓存名和 key 查询缓存。命中时直接返回缓存值并跳过方法调用；未命中时执行方法，并把结果写入缓存。

它适合读多写少、相同参数重复查询的场景。Key 必须稳定且包含影响结果的所有参数，否则不同请求可能错误共享结果。空值是否缓存、异常是否缓存也要明确设计。

`sync=true` 可以让同一个实例内相同 key 的并发未命中只执行一次加载，但是否支持取决于缓存实现，并且不能自动解决多实例之间的缓存击穿。

## @CachePut

`@CachePut` 总是执行方法，再把返回值放入缓存。它适合业务更新成功后同步刷新缓存，而不是为了跳过方法执行。

同一个方法同时使用 `@Cacheable` 和 `@CachePut` 通常会产生冲突：前者可能跳过方法，后者要求方法必须执行。除非条件能够明确保证两者互斥，否则不建议组合。

## @CacheEvict

`@CacheEvict` 用于删除缓存。默认在方法成功执行后删除；设置 `beforeInvocation=true` 时，会在方法执行前删除，即使业务方法随后失败，缓存也已经被清理。

`allEntries=true` 会清空整个缓存区域，适合批量数据整体失效，但影响范围大。指定 `allEntries` 时，单个 key 不再有实际意义。

数据库更新场景通常选择方法成功后删除相关缓存，使下一次读取重新加载最新数据。如果删除失败，还需要重试、消息补偿或短 TTL，不能只依赖一次远程调用。

## @Caching 与 @CacheConfig

`@Caching` 可以在同一方法上组合多个缓存操作，例如同时删除详情缓存和列表缓存。`@CacheConfig` 在类级别共享缓存名、KeyGenerator 或 CacheManager 等配置，减少重复声明。

组合操作不自动具备跨 Redis、数据库和本地缓存的事务原子性。多级缓存还要考虑删除顺序、消息通知和短暂不一致窗口。

## AOP 边界

声明式缓存通常通过 Spring AOP 代理生效。同一个类内部直接调用自己的缓存方法可能绕过代理，使注解不执行。私有方法、非 Spring 管理对象和错误的代理方式也可能造成相同行为。

测试时不能只验证方法返回值，还应验证首次调用执行底层查询、再次调用命中缓存、更新后缓存失效，以及异常分支是否符合预期。
