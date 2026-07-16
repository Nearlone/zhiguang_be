# 缓存配置与热点探测笔记

本文档整理 `com.tongji.cache.config` 相关代码，以及 Feed/知文详情中使用的缓存机制。

涉及核心文件：

- `src/main/java/com/tongji/cache/config/CacheConfig.java`
- `src/main/java/com/tongji/cache/config/CacheProperties.java`
- `src/main/java/com/tongji/cache/hotkey/HotKeyDetector.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`

## 1. 缓存整体分层

本项目中的缓存大致可以理解为三层：

```text
第一层：Caffeine，本机 JVM 内存缓存，最快
第二层：Redis，独立缓存服务，多应用实例可共享
第三层：MySQL，最终数据来源，最慢但最可靠
```

查询 Feed 或知文详情时，代码会尽量按下面的顺序读取：

```text
Caffeine -> Redis -> MySQL
```

如果前面的缓存命中，就不用访问后面的存储。这样可以减少 Redis 和数据库压力。

## 2. CacheProperties 的作用

`CacheProperties` 是一个配置绑定类：

```java
@Component
@ConfigurationProperties(prefix = "cache")
public class CacheProperties
```

它会读取 `application.yml` 中以 `cache` 开头的配置：

```yaml
cache:
  l2:
    public-cfg:
      ttl-seconds: 15
      max-size: 1000
    mine-cfg:
      ttl-seconds: 10
      max-size: 1000
  hotkey:
    window-seconds: 60
    segment-seconds: 10
    level-low: 50
    level-medium: 200
    level-high: 500
    extend-low-seconds: 20
    extend-medium-seconds: 60
    extend-high-seconds: 120
```

这里分成两大类：

| 配置块 | 作用 |
| --- | --- |
| `cache.l2` | 配置本地 Caffeine 缓存 |
| `cache.hotkey` | 配置热点 key 探测和 TTL 延长策略 |

注意：这里的 `l2` 在代码注释里叫“本地二级缓存”，实际创建的是 JVM 进程内的 Caffeine 缓存，不是 Redis。

## 3. CacheConfig 创建的缓存 Bean

`CacheConfig` 创建了 3 个 Caffeine 缓存：

```java
feedPublicCache
feedMineCache
knowPostDetailCache
```

### 3.1 feedPublicCache

用途：缓存公开首页 Feed，也就是广场/推荐流分页结果。

```java
@Bean("feedPublicCache")
public Cache<String, FeedPageResponse> feedPublicCache(CacheProperties props)
```

配置：

```yaml
cache.l2.public-cfg.ttl-seconds: 15
cache.l2.public-cfg.max-size: 1000
```

含义：

- `ttl-seconds: 15`：写入本地缓存后，最多保留 15 秒。
- `max-size: 1000`：最多缓存 1000 个页面结果。

使用位置：

```text
KnowPostFeedServiceImpl.getPublicFeed()
```

### 3.2 feedMineCache

用途：缓存当前用户自己的发布列表。

```java
@Bean("feedMineCache")
public Cache<String, FeedPageResponse> feedMineCache(CacheProperties props)
```

配置：

```yaml
cache.l2.mine-cfg.ttl-seconds: 10
cache.l2.mine-cfg.max-size: 1000
```

含义：

- `ttl-seconds: 10`：个人列表缓存时间更短，因为它更容易因为用户操作而变化。
- `max-size: 1000`：最多缓存 1000 个个人列表页面。

缓存 key 形态：

```text
feed:mine:{userId}:{size}:{page}
```

由于 key 中包含 `userId`，所以不会把 A 用户的数据缓存给 B 用户。

### 3.3 knowPostDetailCache

用途：缓存知文详情页。

```java
@Bean("knowPostDetailCache")
public Cache<String, KnowPostDetailResponse> knowPostDetailCache(CacheProperties props)
```

默认配置在 Java 类中：

```java
private int ttlSeconds = 30;
private long maxSize = 5000;
```

当前 `application.yml` 没有显式写 `detail-cfg`，所以会使用 Java 默认值。

建议补充：

```yaml
cache:
  l2:
    detail-cfg:
      ttl-seconds: 30
      max-size: 5000
```

详情缓存 key 形态：

```text
knowpost:detail:{id}:v{version}
```

`v{version}` 用于缓存版本控制。如果详情页结构变化，可以修改版本号，让旧缓存自然失效。

## 4. TTL 与 maxSize

### 4.1 TTL

TTL 是 Time To Live，表示缓存存活时间。

例如：

```yaml
ttl-seconds: 15
```

表示数据写入缓存后，15 秒后自动过期。

Caffeine 中使用的是：

```java
expireAfterWrite(Duration.ofSeconds(...))
```

意思是：从写入那一刻开始计时，到期后过期。

### 4.2 maxSize

`max-size` 表示最多允许缓存多少个条目。

例如：

```yaml
max-size: 1000
```

表示这个 Caffeine 缓存最多保留 1000 个 key-value。

超过上限后，Caffeine 会根据自己的淘汰策略移除一部分缓存。通常会优先淘汰低频、较旧的数据。

## 5. Feed 的缓存流程

公开 Feed 的读取流程大致是：

```text
1. 先查本地 Caffeine：feedPublicCache
2. 本地没命中，再查 Redis 片段缓存
3. Redis 片段缓存也没命中，再查 MySQL
4. 查到数据后，写回 Redis 和 Caffeine
```

Redis 中公开 Feed 没有只存一个大 JSON，而是拆成片段：

```text
feed:public:ids:{size}:{hourSlot}:{page}
feed:public:ids:{size}:{hourSlot}:{page}:hasMore
feed:item:{id}
feed:public:index:{id}:{hourSlot}
```

含义：

| Redis key | 作用 |
| --- | --- |
| `feed:public:ids:{size}:{hourSlot}:{page}` | 当前页包含哪些知文 ID |
| `feed:public:ids:{size}:{hourSlot}:{page}:hasMore` | 当前页是否还有下一页 |
| `feed:item:{id}` | 单个知文在 Feed 中展示所需的基础信息 |
| `feed:public:index:{id}:{hourSlot}` | 反向索引：某篇知文出现在哪些 Feed 页中 |

这样拆的好处是：

- 单个知文信息可以被多个页面复用。
- 某篇知文变化时，可以通过反向索引找到受影响的页面。
- 页面缓存不会因为用户维度的 liked/faved 状态被污染。

## 6. 热点 key 探测

热点 key 探测由 `HotKeyDetector` 实现。

它的作用是：统计某个 key 最近一段时间被访问了多少次。如果访问很频繁，就认为它是热点，并延长相关 Redis 缓存的 TTL。

当前配置：

```yaml
cache:
  hotkey:
    window-seconds: 60
    segment-seconds: 10
    level-low: 50
    level-medium: 200
    level-high: 500
    extend-low-seconds: 20
    extend-medium-seconds: 60
    extend-high-seconds: 120
```

### 6.1 60 秒窗口

`window-seconds: 60` 表示只关心最近 60 秒内的访问次数。

例如一篇知文在最近 60 秒被访问了 230 次，它就会被认为是中等热点。

### 6.2 拆成 6 个小段

`segment-seconds: 10` 表示每个小段 10 秒。

窗口长度是 60 秒，所以会拆成：

```text
60 / 10 = 6 个小段
```

可以想象成 6 个桶：

```text
[桶0][桶1][桶2][桶3][桶4][桶5]
```

每个桶负责记录一个 10 秒时间片内的访问次数。

假设当前活跃的是 `桶0`：

```text
[桶0*][桶1][桶2][桶3][桶4][桶5]
```

星号表示当前正在写入访问次数的桶。

每访问一次某个 key，就会执行：

```java
hotKey.record(key)
```

然后当前桶计数 +1。

例如 10 秒内访问了 8 次：

```text
[8*][0][0][0][0][0]
```

### 6.3 每 10 秒轮转一次

代码中有一个定时任务：

```java
@Scheduled(fixedRateString = "${cache.hotkey.segment-seconds:10}000")
public void rotate()
```

配置是 10 秒，所以每 10 秒执行一次 `rotate()`。

轮转时会做两件事：

1. 当前桶指针移动到下一个桶。
2. 把新的当前桶清零。

例如最开始：

```text
[8*][0][0][0][0][0]
```

10 秒后轮转到桶1：

```text
[8][0*][0][0][0][0]
```

如果接下来 10 秒又访问 15 次：

```text
[8][15*][0][0][0][0]
```

再过 10 秒轮转到桶2：

```text
[8][15][0*][0][0][0]
```

如果这 10 秒访问 30 次：

```text
[8][15][30*][0][0][0]
```

此时最近窗口内总热度是所有桶求和：

```text
8 + 15 + 30 = 53
```

达到 `level-low: 50`，所以进入低热等级。

### 6.4 为什么叫滑动窗口

继续过时间：

```text
[8][15][30][20*][0][0]
```

再过：

```text
[8][15][30][20][5*][0]
```

再过：

```text
[8][15][30][20][5][10*]
```

此时 6 个桶都填过数据，总热度：

```text
8 + 15 + 30 + 20 + 5 + 10 = 88
```

再过 10 秒，指针会回到桶0，并清零桶0：

```text
[0*][15][30][20][5][10]
```

这一步代表：最早那段 10 秒的数据已经滑出最近 60 秒窗口，不再参与统计。

所以它叫滑动窗口：时间不断往前走，旧时间片自动被清掉，新时间片继续记录。

### 6.5 热度怎么计算

热度计算代码：

```java
public int heat(String key) {
    int[] arr = counters.get(key);
    int sum = 0;
    for (int v : arr) {
        sum += v;
    }
    return sum;
}
```

它就是把 6 个桶里的计数加起来。

例如：

```text
[0][15][30][20][5][10]
```

热度就是：

```text
0 + 15 + 30 + 20 + 5 + 10 = 80
```

### 6.6 热度等级

等级判断：

```java
if (h >= levelHigh) return HIGH;
if (h >= levelMedium) return MEDIUM;
if (h >= levelLow) return LOW;
return NONE;
```

当前配置：

```text
LOW:    >= 50
MEDIUM: >= 200
HIGH:   >= 500
```

所以：

| 最近 60 秒访问次数 | 热度等级 |
| --- | --- |
| 0 ~ 49 | NONE |
| 50 ~ 199 | LOW |
| 200 ~ 499 | MEDIUM |
| >= 500 | HIGH |

### 6.7 热点如何影响 TTL

热点等级会影响 Redis 缓存的 TTL。

配置：

```yaml
extend-low-seconds: 20
extend-medium-seconds: 60
extend-high-seconds: 120
```

如果基础 TTL 是 60 秒：

| 热度等级 | 最终 TTL |
| --- | --- |
| NONE | 60 秒 |
| LOW | 80 秒 |
| MEDIUM | 120 秒 |
| HIGH | 180 秒 |

代码中对应：

```java
public int ttlForPublic(int baseTtlSeconds, String key) {
    Level l = level(key);
    return baseTtlSeconds + extendSeconds(l);
}
```

### 6.8 它保护了哪些缓存

访问公开 Feed 时，代码会记录内容热点：

```text
knowpost:{id}
```

然后尝试延长：

```text
feed:item:{id}
```

访问详情页时，也会记录：

```text
knowpost:{id}
```

然后尝试延长：

```text
knowpost:detail:{id}:v{version}
feed:item:{id}
```

访问“我的发布”页面时，会记录页面 key：

```text
feed:mine:{userId}:{size}:{page}
```

然后尝试延长：

```text
feed:mine:{userId}:{size}:{page}
```

注意：热点探测主要延长 Redis 里的缓存 TTL。Caffeine 本地缓存的 TTL 在创建时已经固定，不会被热点探测动态修改。

## 7. 缓存击穿与 SingleFlight

代码中还用了 `singleFlight` 思想。

场景：

```text
某个热门页面缓存刚好过期，同时来了 1000 个请求
```

如果不加控制，这 1000 个请求都可能打到数据库。

SingleFlight 的做法是：

```text
同一个 key 同一时刻只允许一个请求回源数据库
其他请求等待，等第一个请求把缓存写好后，再从缓存读
```

在代码中可以看到：

```java
Object lock = singleFlight.computeIfAbsent(idsKey, k -> new Object());
synchronized (lock) {
    ...
}
```

这是一种防止缓存击穿的手段。

## 8. 空值缓存

详情页查询不存在或已删除内容时，会写入：

```text
"NULL"
```

并设置 30~60 秒过期时间。

作用是防止缓存穿透。

缓存穿透指的是：有人不断请求不存在的数据，如果每次都查数据库，就会造成无意义压力。

写入短 TTL 的空值缓存后，短时间内再查同一个不存在 ID，就可以直接返回，不再打数据库。

## 9. 随机抖动 Jitter

写 Redis 缓存时，代码会给 TTL 加一点随机值：

```java
int jitter = ThreadLocalRandom.current().nextInt(30);
```

例如基础 TTL 是 60 秒，实际可能是：

```text
60 ~ 89 秒
```

作用是防止大量 key 在同一秒一起过期。

如果大量缓存同时过期，就可能造成缓存雪崩。

## 10. 当前配置建议

当前 `application.yml` 没有显式配置 `detail-cfg`，虽然 Java 默认值会生效，但建议补齐，方便阅读：

```yaml
cache:
  l2:
    public-cfg:
      ttl-seconds: 15
      max-size: 1000
    mine-cfg:
      ttl-seconds: 10
      max-size: 1000
    detail-cfg:
      ttl-seconds: 30
      max-size: 5000
    hotkey:
      window-seconds: 60
      segment-seconds: 10
      level-low: 50
      level-medium: 200
      level-high: 500
      extend-low-seconds: 20
      extend-medium-seconds: 60
      extend-high-seconds: 120
```

注意，上面示例中 `hotkey` 应该和 `l2` 同级，正确完整写法如下：

```yaml
cache:
  l2:
    public-cfg:
      ttl-seconds: 15
      max-size: 1000
    mine-cfg:
      ttl-seconds: 10
      max-size: 1000
    detail-cfg:
      ttl-seconds: 30
      max-size: 5000
  hotkey:
    window-seconds: 60
    segment-seconds: 10
    level-low: 50
    level-medium: 200
    level-high: 500
    extend-low-seconds: 20
    extend-medium-seconds: 60
    extend-high-seconds: 120
```

## 11. 初学者总结

可以先记住这几句话：

- Caffeine 是本机内存缓存，速度最快，但只对当前 Java 进程有效。
- Redis 是共享缓存，多个应用实例都可以访问。
- MySQL 是最终数据来源。
- 热点探测不是保存数据，而是统计访问次数。
- 60 秒窗口拆成 6 个 10 秒桶，是为了让旧访问量自然过期。
- 访问越热，Redis 缓存 TTL 越长。
- SingleFlight 防止同一时刻大量请求一起打数据库。
- 空值缓存防止不存在的数据反复打数据库。
- Jitter 防止大量缓存同一时间过期。
