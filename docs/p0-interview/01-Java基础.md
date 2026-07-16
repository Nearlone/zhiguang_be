# 01 Java 基础

> 目标：达到 Java 后端实习一面可用水平。你不需要第一阶段掌握 JVM 所有细节，但必须能讲清楚面向对象、集合、异常、泛型、常用类、线程基础，以及这些东西如何支撑 Spring Boot 项目。

## 1. 最短面试回答

面试官问“你的 Java 基础怎么样”，可以这样答：

```text
我主要使用 Java 做 Spring Boot 后端开发。熟悉面向对象、集合框架、异常处理、泛型、Stream 常用操作，也了解线程、线程池、synchronized、volatile 的基础原理。

在项目里，Controller/Service/Mapper 分层主要依赖 Java 的接口、类和依赖注入；认证模块里用 Optional 表示查询结果可能为空，用 record 定义 DTO，用 Builder 构建 User，用异常体系统一返回业务错误。
```

## 2. 项目源码锚点

本项目里能体现 Java 基础的地方：

- `src/main/java/com/tongji/auth/service/AuthService.java`
  - `Optional<User>`：表示用户可能不存在。
  - `switch` 表达式：根据手机号/邮箱走不同逻辑。
  - `BusinessException`：业务异常。
  - `Duration`、`Instant`：时间和 TTL。
  - `UUID`：生成 tokenId 和默认昵称。
- `src/main/java/com/tongji/auth/api/dto/*.java`
  - 大量 `record` DTO。
- `src/main/java/com/tongji/user/domain/User.java`
  - 领域对象。
- `src/main/java/com/tongji/common/exception/BusinessException.java`
  - 自定义异常。
- `src/main/java/com/tongji/common/web/GlobalExceptionHandler.java`
  - 全局异常处理。

## 3. Java 程序的基本结构

Java 是强类型、面向对象语言。

最基本的结构：

```java
package com.tongji.demo;

public class UserService {
    public String hello(String name) {
        return "hello " + name;
    }
}
```

核心概念：

- `package`：包名，用来组织类。
- `class`：类，描述对象的结构和行为。
- `field`：成员变量，表示对象状态。
- `method`：方法，表示对象行为。
- `public/private/protected`：访问控制。
- `static`：属于类本身，不属于某个对象。
- `final`：不可变引用、不可继承类、不可重写方法，具体语义看位置。

## 4. 面向对象三大特性

### 4.1 封装

封装是把数据和操作数据的方法放在一个类里，并隐藏内部细节。

项目例子：

```java
public class AuthService {
    private final UserService userService;
    private final JwtService jwtService;

    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        ...
    }
}
```

外部只需要调用 `login`，不需要知道里面如何查用户、校验密码、签发 JWT、写 Redis。

面试回答：

```text
封装是隐藏对象内部实现，只暴露必要方法。好处是降低调用方复杂度，也方便后续修改内部实现而不影响外部接口。比如 AuthService 对外暴露 login/register，内部封装了验证码、密码、JWT、Redis 白名单等细节。
```

### 4.2 继承

继承是子类复用父类属性和方法。

Java 是单继承：一个类只能继承一个父类，但可以实现多个接口。

实际开发中，不要滥用继承。后端项目更多使用接口 + 组合。

面试回答：

```text
继承用于表达 is-a 关系，比如 RuntimeException 是 Exception 的一种。但业务代码里我会谨慎使用继承，更多通过接口和组合扩展能力，因为继承层级太深会导致耦合变高。
```

### 4.3 多态

多态是同一个接口引用，在运行时指向不同实现。

项目例子：

```java
private final RefreshTokenStore refreshTokenStore;
```

`AuthService` 依赖的是接口 `RefreshTokenStore`，实际注入的是 `RedisRefreshTokenStore`。

好处：

- 业务层不关心具体存储。
- 以后可以换成数据库实现、内存实现、混合实现。
- 单元测试时可以 mock。

面试回答：

```text
多态是父类或接口引用指向子类实现，调用时根据实际对象执行对应方法。在项目里 AuthService 依赖 RefreshTokenStore 接口，实际实现是 RedisRefreshTokenStore，这样认证业务不用绑定具体 Redis 实现。
```

## 5. 接口和抽象类

### 5.1 接口

接口定义能力规范。

```java
public interface RefreshTokenStore {
    void storeToken(long userId, String tokenId, Duration ttl);
    boolean isTokenValid(long userId, String tokenId);
    void revokeToken(long userId, String tokenId);
    void revokeAll(long userId);
}
```

适合：

- 定义能力。
- 多实现。
- 解耦业务和基础设施。
- Spring 依赖注入。

### 5.2 抽象类

抽象类可以包含字段、构造方法、普通方法、抽象方法。

适合：

- 多个子类有共同状态或共同模板逻辑。
- 表达更强的继承关系。

### 5.3 高频区别

```text
接口更偏能力规范，类可以实现多个接口；抽象类更偏共性复用，类只能继承一个抽象类。
```

## 6. String、StringBuilder、StringBuffer

### 6.1 String

`String` 不可变。

```java
String a = "hello";
a = a + " world";
```

这里不是修改原字符串，而是创建新字符串。

为什么不可变：

- 安全：字符串经常用于路径、SQL、URL、类名。
- 可缓存 hash。
- 字符串常量池可以复用。
- 线程安全。

### 6.2 StringBuilder

可变字符串，非线程安全，单线程拼接性能好。

项目例子：

`VerificationService.generateNumericCode` 用 `StringBuilder` 拼验证码。

```java
StringBuilder builder = new StringBuilder(length);
for (int i = 0; i < length; i++) {
    builder.append(RANDOM.nextInt(10));
}
```

### 6.3 StringBuffer

可变字符串，方法加了同步，线程安全，但性能比 `StringBuilder` 差。

面试回答：

```text
String 不可变，适合保存文本值；StringBuilder 可变、非线程安全，适合单线程大量拼接；StringBuffer 可变且线程安全，但现代项目里较少用。
```

## 7. Java 集合框架

### 7.1 Collection 与 Map

集合主要分两大类：

```text
Collection
  - List: 有序，可重复
  - Set: 不重复
  - Queue: 队列

Map
  - key-value 映射
```

### 7.2 ArrayList

底层是动态数组。

特点：

- 查询快：按下标 O(1)。
- 尾部追加均摊 O(1)。
- 中间插入/删除慢：需要移动元素 O(n)。
- 非线程安全。

常见追问：

```text
ArrayList 扩容机制是什么？
```

回答：

```text
ArrayList 底层数组容量不够时会创建更大的数组，把旧元素复制过去。扩容会有复制成本，所以如果能预估大小，可以在构造时指定初始容量，减少扩容次数。
```

### 7.3 LinkedList

底层是双向链表。

特点：

- 按下标查找慢：O(n)。
- 已知节点时插入/删除快。
- 实际业务中不一定比 ArrayList 常用，因为链表节点额外对象开销大，缓存局部性差。

### 7.4 HashMap

HashMap 是面试高频。

核心结构：

```text
数组 + 链表 + 红黑树
```

基本流程：

1. 对 key 求 hash。
2. 根据 hash 定位数组下标。
3. 如果位置为空，直接放入。
4. 如果位置已有元素，比较 key 是否相等。
5. 如果 hash 冲突，挂到链表或红黑树。

为什么需要重写 `equals` 和 `hashCode`：

- `hashCode` 决定大概放在哪个桶。
- `equals` 决定两个 key 是否真的相等。
- 两个对象 `equals` 相等时，`hashCode` 必须相等。

面试回答：

```text
HashMap 底层是数组加链表加红黑树。put 时先根据 key 的 hash 定位桶，如果桶里有元素，再用 equals 判断 key 是否相同。冲突过多时链表会树化，提高极端情况下的查询性能。HashMap 非线程安全，并发场景一般用 ConcurrentHashMap。
```

### 7.5 HashMap 常见坑

- 可变对象不要轻易当 key。
- 自定义对象当 key 必须正确实现 `equals/hashCode`。
- 遍历时不能直接结构性修改，否则可能 `ConcurrentModificationException`。
- 多线程下不要使用普通 `HashMap` 承担共享写。

### 7.6 ConcurrentHashMap

第一阶段掌握到：

```text
ConcurrentHashMap 是线程安全的哈希表，适合高并发读写。它不是简单给整个 Map 加一把大锁，而是通过更细粒度的并发控制提升性能。
```

不要第一阶段死磕源码细节，但要知道：

- key/value 不能为 null。
- 复合操作仍需要注意原子性，比如先 get 再 put 不是整体原子。
- 可用 `computeIfAbsent` 做原子初始化。

## 8. 泛型

泛型让类型在编译期检查。

```java
List<User> users = new ArrayList<>();
```

好处：

- 减少强制类型转换。
- 编译期发现类型错误。
- 提高代码可读性。

常见概念：

```java
List<? extends Number> // 上界，能读 Number，写入受限
List<? super Integer>  // 下界，能写 Integer，读取类型较宽
```

面试回答：

```text
泛型本质是把类型参数化，让编译器在编译期做类型检查。Java 泛型主要通过类型擦除实现，运行时大多拿不到具体泛型参数，所以不能直接 new T()，也不能用基本类型作为泛型参数。
```

## 9. Optional

`Optional<T>` 表示值可能存在，也可能不存在。

项目例子：

```java
Optional<User> userOptional = findUserByIdentifier(request.identifierType(), identifier);
if (userOptional.isEmpty()) {
    throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
}
```

好处：

- 提醒调用方处理空值。
- 比直接返回 null 更明确。

注意：

- 不要滥用在实体字段中。
- 方法返回值可以用。
- 性能敏感路径不要过度包装。

## 10. record

Java `record` 适合定义不可变数据载体。

项目 DTO 可能类似：

```java
public record TokenResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {}
```

特点：

- 自动生成构造器。
- 自动生成访问方法。
- 自动生成 `equals/hashCode/toString`。
- 字段默认 `private final`。

适合：

- 请求 DTO。
- 响应 DTO。
- 简单值对象。

不适合：

- 复杂可变领域模型。
- ORM 实体有时不适合用 record。

## 11. 异常体系

Java 异常分两大类：

```text
Throwable
  - Error
  - Exception
      - Checked Exception
      - RuntimeException
```

### 11.1 Checked Exception

编译器要求处理。

例如：

- `IOException`
- `SQLException`

### 11.2 RuntimeException

运行时异常，编译器不强制处理。

例如：

- `NullPointerException`
- `IllegalArgumentException`
- `BusinessException`

项目中业务异常：

```java
throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
```

再由 `GlobalExceptionHandler` 统一转换成 HTTP 响应。

面试回答：

```text
项目里我会把可预期的业务错误封装成 BusinessException，比如验证码错误、账号不存在、密码错误，然后用全局异常处理器统一返回错误码。这样 Controller 不需要到处 try-catch，也能保证前端拿到统一响应格式。
```

## 12. 时间 API

项目使用：

- `Instant`：时间点，适合 token 签发、过期。
- `Duration`：时间长度，适合 TTL。
- `LocalDate`：日期，不含时区。

例子：

```java
Instant issuedAt = Instant.now();
Instant accessExpiresAt = issuedAt.plus(Duration.ofMinutes(15));
Duration ttl = Duration.between(Instant.now(), tokenPair.refreshTokenExpiresAt());
```

面试重点：

```text
不要用字符串到处表示时间。token 过期、Redis TTL 这类逻辑应该用 Instant/Duration 表达，会更清晰。
```

## 13. Java 并发基础

### 13.1 进程与线程

- 进程：资源分配单位。
- 线程：CPU 调度单位。
- 一个进程可以有多个线程。
- 线程共享进程内存，因此会有线程安全问题。

### 13.2 线程安全

多个线程同时访问共享可变数据，如果没有正确同步，结果可能不符合预期。

例子：

```java
count++;
```

这不是原子操作，包含读、加、写。

### 13.3 synchronized

`synchronized` 用于互斥访问，也能保证可见性。

面试回答：

```text
synchronized 可以保证同一时刻只有一个线程进入临界区，并且进入和退出锁时会建立内存可见性关系。缺点是如果锁粒度过大，会影响并发性能。
```

### 13.4 volatile

`volatile` 保证可见性和一定的有序性，但不保证复合操作原子性。

```java
private volatile boolean running = true;
```

适合：

- 状态标记。
- 配置开关。
- 单写多读场景。

不适合：

- `count++` 这种复合更新。

### 13.5 线程池

为什么需要线程池：

- 避免频繁创建销毁线程。
- 控制并发数量。
- 管理任务队列。
- 统一异常和拒绝策略。

核心参数：

- `corePoolSize`：核心线程数。
- `maximumPoolSize`：最大线程数。
- `keepAliveTime`：非核心线程空闲存活时间。
- `workQueue`：任务队列。
- `threadFactory`：线程创建工厂。
- `rejectedExecutionHandler`：拒绝策略。

面试回答：

```text
线程池用于复用线程和控制并发，避免请求量上来时无限创建线程拖垮系统。配置时要结合任务类型，如果是 IO 密集型，线程数可以相对多些；如果是 CPU 密集型，线程数通常接近 CPU 核数。
```

## 14. Stream API

Stream 适合集合转换、过滤、聚合。

```java
List<Long> ids = users.stream()
        .map(User::getId)
        .toList();
```

常用操作：

- `map`：映射。
- `filter`：过滤。
- `sorted`：排序。
- `distinct`：去重。
- `collect/toList`：收集。
- `anyMatch/allMatch`：匹配。

注意：

- 不要为了炫技写很长的 Stream。
- 有副作用的逻辑不适合塞进 Stream。
- 数据量很大时注意性能和可读性。

## 15. Lombok

项目中用了 Lombok：

- `@RequiredArgsConstructor`
- `@Builder`
- `@Getter/@Setter`

优点：

- 减少样板代码。
- 构造器注入更简洁。
- Builder 构造复杂对象更清楚。

缺点：

- 编译期生成代码，IDE 插件或编译配置不对会报错。
- 新人读代码时需要知道注解背后生成了什么。

面试回答：

```text
Lombok 是编译期代码生成工具，可以减少 getter、setter、构造器、builder 这些样板代码。项目里 AuthService 用 @RequiredArgsConstructor 配合 final 字段做构造器注入。
```

## 16. 高频面试题

### Q1：Java 是值传递还是引用传递？

答：

```text
Java 只有值传递。对于基本类型，传的是值本身；对于对象引用，传的是引用的副本。方法里可以通过引用副本修改对象内部状态，但不能让外部引用指向一个新对象。
```

### Q2：`==` 和 `equals` 区别？

答：

```text
== 对基本类型比较值，对引用类型比较地址。equals 是方法，默认也是比较地址，但很多类会重写，比如 String 比较字符内容。自定义对象如果要作为 HashMap key，一般要同时重写 equals 和 hashCode。
```

### Q3：HashMap 为什么线程不安全？

答：

```text
HashMap 没有同步控制，多线程同时 put 可能出现数据覆盖、结构不一致、扩容期间异常等问题。并发场景应该使用 ConcurrentHashMap 或加外部同步。
```

### Q4：ArrayList 和 LinkedList 怎么选？

答：

```text
大多数业务场景优先 ArrayList，因为查询快、内存连续、缓存友好。LinkedList 理论上适合频繁插入删除，但只有在已知节点位置时才明显，否则按下标查找仍是 O(n)，实际使用反而不一定更快。
```

### Q5：Exception 和 RuntimeException 区别？

答：

```text
Exception 包含受检异常和运行时异常。受检异常编译器要求处理，RuntimeException 不强制处理，通常表示编程错误或业务运行时错误。项目里的 BusinessException 属于运行时异常，用全局异常处理器统一返回。
```

### Q6：final 有什么用？

答：

```text
final 修饰变量表示引用不可重新赋值，修饰方法表示不能被重写，修饰类表示不能被继承。注意 final 对引用变量只限制引用本身，不代表对象内部状态不可变。
```

### Q7：接口和抽象类区别？

答：

```text
接口偏能力规范，一个类可以实现多个接口；抽象类偏共性复用，一个类只能继承一个抽象类。项目中像 RefreshTokenStore 更适合接口，因为它定义的是刷新令牌存储能力，具体可以由 Redis 或数据库实现。
```

### Q8：什么是泛型擦除？

答：

```text
Java 泛型主要在编译期做类型检查，编译后很多泛型信息会被擦除成原始类型或边界类型。因此运行时不能直接 new T，也不能用 instanceof List<String> 这类判断。
```

### Q9：volatile 能保证原子性吗？

答：

```text
volatile 主要保证可见性和一定有序性，不保证复合操作原子性。比如 count++ 包含读、加、写，即使用 volatile 修饰，多线程下也可能丢失更新。
```

### Q10：为什么推荐构造器注入？

答：

```text
构造器注入可以让依赖在对象创建时就完整赋值，字段可以声明为 final，更利于不可变和测试；也能避免循环依赖被隐藏。项目里很多 Service 使用 Lombok 的 @RequiredArgsConstructor 实现构造器注入。
```

## 17. 项目讲法

可以这样把 Java 基础落到项目上：

```text
项目里我大量使用 Java 的接口和多态来解耦业务。例如 AuthService 依赖 RefreshTokenStore 接口，实际实现是 RedisRefreshTokenStore。这样认证业务只关心“保存、校验、撤销刷新令牌”这些能力，不绑定具体 Redis 代码。

另外，登录和注册方法里使用 Optional 表示用户可能不存在，用 BusinessException 表达业务错误，再由全局异常处理器统一返回。JWT 过期时间和 Redis TTL 用 Instant、Duration 表示，避免用字符串或 long 到处传。
```

## 18. 自测清单

你应该能回答：

- 面向对象三大特性是什么？
- 接口和抽象类怎么选？
- HashMap 底层结构是什么？
- 为什么 `equals` 和 `hashCode` 要一起重写？
- ArrayList 和 LinkedList 区别？
- String 为什么不可变？
- StringBuilder 和 StringBuffer 区别？
- Java 是值传递还是引用传递？
- Checked Exception 和 RuntimeException 区别？
- Optional 适合用在哪里？
- record 适合做什么？
- synchronized 和 volatile 区别？
- 线程池为什么重要？
- 项目里哪里体现了多态、异常、Optional、Duration？

