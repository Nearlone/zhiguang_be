# 03 MyBatis 与 MySQL

> 目标：能讲清楚 MyBatis 如何把 Java 方法映射到 SQL，MySQL 索引和事务为什么重要，以及项目中如何通过唯一索引解决并发注册冲突。

## 1. 最短面试回答

```text
项目使用 MyBatis 做数据访问，Mapper 接口定义方法，XML 中写 SQL，通过 resultMap 完成字段和 Java 对象属性映射。

MySQL 方面，我重点掌握索引、唯一索引、事务、隔离级别和 MVCC。在注册链路中，业务层会先查账号是否存在，但并发下两个请求可能同时通过校验，所以数据库 users 表对 phone/email/zg_id 建了唯一索引，作为最终一致性兜底。如果插入时发生 DuplicateKeyException，就转换成账号已存在的业务错误。
```

## 2. 项目源码锚点

- `src/main/java/com/tongji/user/mapper/UserMapper.java`
  - Mapper 接口。
- `src/main/resources/mapper/UserMapper.xml`
  - SQL 映射。
- `db/schema.sql`
  - 表结构、主键、唯一索引、普通索引。
- `src/main/java/com/tongji/user/service/impl/UserServiceImpl.java`
  - 事务和 User 创建逻辑。
- `src/main/java/com/tongji/auth/service/AuthService.java`
  - 注册时捕获 `DuplicateKeyException`。

## 3. MyBatis 是什么

MyBatis 是半自动 ORM 框架。

它不像 JPA/Hibernate 那样强封装 SQL，而是让开发者自己写 SQL，同时帮你做：

- 参数绑定。
- SQL 执行。
- 结果集映射。
- Mapper 接口代理。
- 动态 SQL。

适合：

- 复杂 SQL 多。
- 需要精细控制查询。
- 国内 Java 后端常见业务系统。

## 4. Mapper 接口和 XML

项目接口：

```java
@Mapper
public interface UserMapper {
    User findByPhone(@Param("phone") String phone);
    boolean existsByPhone(@Param("phone") String phone);
    void insert(User user);
}
```

对应 XML：

```xml
<select id="findByPhone" parameterType="string" resultMap="UserResultMap">
    SELECT *
    FROM users
    WHERE phone = #{phone}
    LIMIT 1
</select>
```

关键点：

- `namespace` 要对应 Mapper 接口全限定名。
- `id` 要对应接口方法名。
- `#{phone}` 是安全参数绑定。
- `resultMap` 负责数据库列到 Java 属性的映射。

## 5. `#{}` 和 `${}` 区别

这是 MyBatis 高频题。

### 5.1 `#{}`

预编译参数，占位符。

```sql
WHERE phone = #{phone}
```

会变成：

```sql
WHERE phone = ?
```

优点：

- 防 SQL 注入。
- 类型处理更安全。
- 可复用执行计划。

### 5.2 `${}`

字符串拼接。

```sql
ORDER BY ${sortField}
```

会直接拼到 SQL 中。

风险：

- SQL 注入。
- 参数不可控时非常危险。

### 5.3 面试回答

```text
#{} 是预编译参数绑定，会使用占位符，能防 SQL 注入；${} 是字符串替换，直接拼 SQL，通常只在表名、字段名、排序字段这类无法用占位符的位置使用，而且必须做白名单校验。项目里用户输入值都应该使用 #{}。
```

## 6. resultMap

数据库字段常用下划线：

```text
password_hash
created_at
```

Java 属性常用驼峰：

```text
passwordHash
createdAt
```

项目 XML：

```xml
<resultMap id="UserResultMap" type="com.tongji.user.domain.User">
    <id column="id" property="id"/>
    <result column="password_hash" property="passwordHash"/>
    <result column="created_at" property="createdAt"/>
</resultMap>
```

`resultMap` 的价值：

- 明确字段映射。
- 可处理复杂对象关系。
- 避免列名和属性名不一致导致赋值失败。

## 7. 动态 SQL

项目中 `updateProfile`：

```xml
<update id="updateProfile" parameterType="com.tongji.user.domain.User">
    UPDATE users
    <set>
        <if test="nickname != null">nickname = #{nickname},</if>
        <if test="bio != null">bio = #{bio},</if>
        updated_at = NOW()
    </set>
    WHERE id = #{id}
</update>
```

含义：

- 只更新非 null 字段。
- `<set>` 会自动处理多余逗号。
- 适合 PATCH 更新。

常用动态标签：

- `<if>`
- `<choose>/<when>/<otherwise>`
- `<foreach>`
- `<where>`
- `<set>`

## 8. MySQL 表结构

项目 users 表：

```sql
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    password_hash VARCHAR(128) NULL,
    nickname VARCHAR(64) NOT NULL,
    ...
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_phone (phone),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_zg_id (zg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

面试重点：

- `id` 是主键。
- `phone/email/zg_id` 有唯一索引。
- `password_hash` 只存哈希，不存明文。
- `utf8mb4` 支持 emoji 和完整 Unicode。
- InnoDB 支持事务、行锁、MVCC。

## 9. 索引是什么

索引是帮助数据库快速查找数据的数据结构。

如果没有索引：

```sql
SELECT * FROM users WHERE phone = '138...';
```

数据库可能全表扫描。

有索引后：

```sql
UNIQUE KEY uk_users_phone (phone)
```

数据库可以通过索引快速定位。

## 10. InnoDB 主键索引和二级索引

InnoDB 中：

- 主键索引是聚簇索引。
- 数据行和主键索引存放在一起。
- 二级索引叶子节点保存的是主键值。

查询二级索引时：

```sql
SELECT * FROM users WHERE phone = ?;
```

大致流程：

```text
查 phone 二级索引 -> 找到主键 id -> 回到主键索引查整行
```

这叫回表。

面试回答：

```text
InnoDB 的主键索引是聚簇索引，叶子节点存整行数据；普通二级索引叶子节点存主键值。如果查询字段不能被二级索引覆盖，就需要根据主键再回表查整行。
```

## 11. 为什么常说 B+ 树

MySQL InnoDB 索引通常用 B+ 树。

适合数据库的原因：

- 树高低，减少磁盘 IO。
- 非叶子节点主要存 key，能放更多索引项。
- 叶子节点有序，适合范围查询。
- 稳定支持等值查询和范围查询。

面试回答：

```text
B+ 树树高较低，能减少磁盘 IO；叶子节点有序并通过链表连接，适合范围查询；非叶子节点只存索引键，能提高扇出。因此数据库索引常用 B+ 树。
```

## 12. 联合索引和最左前缀

项目例子：

```sql
KEY ix_login_logs_user_created_at (user_id, created_at)
```

联合索引顺序很重要。

对于 `(user_id, created_at)`：

能较好利用：

```sql
WHERE user_id = ?
WHERE user_id = ? AND created_at > ?
```

不能直接充分利用前缀：

```sql
WHERE created_at > ?
```

面试回答：

```text
联合索引遵循最左前缀原则。比如索引是 (user_id, created_at)，查询条件从 user_id 开始才能有效利用；如果跳过 user_id 只查 created_at，通常不能充分使用这个联合索引。
```

## 13. 索引失效场景

常见：

- 对索引列使用函数：

```sql
WHERE DATE(created_at) = '2026-07-02'
```

- 左模糊：

```sql
WHERE nickname LIKE '%abc'
```

- 隐式类型转换：

```sql
WHERE phone = 13800138000
```

但 phone 是 varchar。

- 联合索引不满足最左前缀。
- `OR` 两边有未索引列。
- 低选择性字段单独建索引收益低。

## 14. 唯一索引和并发注册

这是你已经提出过的关键问题。

注册链路中，业务层会查：

```java
if (identifierExists(request.identifierType(), identifier)) {
    throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
}
```

但并发下可能出现：

```text
请求 A 查：不存在
请求 B 查：不存在
请求 A insert 成功
请求 B insert 触发唯一索引冲突
```

所以业务层检查只能提升用户体验，不能保证并发正确性。最终必须依赖数据库唯一索引：

```sql
UNIQUE KEY uk_users_phone (phone)
UNIQUE KEY uk_users_email (email)
```

项目已补充：

```java
try {
    userService.createUser(user);
} catch (DuplicateKeyException ex) {
    throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
}
```

面试回答：

```text
注册时我会先在业务层查账号是否存在，这样能提前返回友好错误。但这不是并发安全的，因为两个请求可能同时查到不存在。真正保证唯一性的是数据库唯一索引。如果并发插入冲突，MySQL 会拒绝第二次插入，项目捕获 DuplicateKeyException 并转换成账号已存在的业务异常。
```

## 15. 事务 ACID

事务四大特性：

- Atomicity 原子性：要么都成功，要么都失败。
- Consistency 一致性：事务前后数据满足约束。
- Isolation 隔离性：并发事务互不干扰到一定程度。
- Durability 持久性：提交后数据持久保存。

面试回答：

```text
事务保证一组数据库操作作为一个整体执行。比如创建用户、写相关初始化数据，如果其中一步失败，应该整体回滚，避免出现半成功状态。
```

## 16. 事务隔离级别

常见隔离级别：

| 隔离级别 | 可能问题 |
|---|---|
| Read Uncommitted | 脏读、不可重复读、幻读 |
| Read Committed | 不可重复读、幻读 |
| Repeatable Read | 一般避免脏读、不可重复读，MySQL InnoDB 默认 |
| Serializable | 串行化，隔离最强，性能较低 |

概念：

- 脏读：读到别人未提交的数据。
- 不可重复读：同一事务两次读同一行结果不同。
- 幻读：同一事务两次范围查询，出现新增或消失的行。

## 17. MVCC

MVCC：多版本并发控制。

核心思想：

```text
读不阻塞写，写不阻塞普通快照读。
```

InnoDB 会通过版本链和 Read View 让事务看到符合自己视图的数据版本。

面试回答：

```text
MVCC 是 MySQL InnoDB 实现高并发事务隔离的重要机制。它通过保存数据的多个版本，让普通一致性读可以读取快照，不必阻塞正在写的事务，从而提升并发性能。
```

## 18. `@Transactional` 和数据库事务

项目 Service 方法使用：

```java
@Transactional
public void createUser(User user) {
    ...
}
```

Spring 通过 AOP 开启事务，底层仍然是数据库连接上的事务。

注意：

- 事务边界一般放在 Service 层。
- 多个 Mapper 操作要在同一个事务中。
- 异常被吞掉可能不会回滚。
- 默认回滚 RuntimeException。

## 19. 慢 SQL 排查

常见步骤：

1. 看慢查询日志。
2. 用 `EXPLAIN` 看执行计划。
3. 检查是否走索引。
4. 检查扫描行数。
5. 检查是否回表、排序、临时表。
6. 优化索引或 SQL。
7. 必要时分页、分表、缓存、异步化。

`EXPLAIN` 关注：

- `type`
- `key`
- `rows`
- `Extra`

第一阶段回答不必太深，但要知道：

```text
type 越接近 const/ref/range 越好，ALL 通常代表全表扫描。
```

## 20. 高频面试题

### Q1：MyBatis 的 `#{}` 和 `${}` 区别？

答：

```text
#{} 是预编译参数绑定，安全防注入；${} 是字符串替换，直接拼 SQL，有注入风险。用户输入值必须用 #{}，字段名或表名等无法预编译的位置如必须使用 ${}，也要白名单校验。
```

### Q2：MyBatis 为什么是半自动 ORM？

答：

```text
因为它不像 Hibernate 那样自动生成大量 SQL，而是让开发者自己写 SQL，同时负责参数绑定、结果映射和 Mapper 代理。它兼顾了 SQL 可控性和对象映射便利性。
```

### Q3：什么是索引？为什么能加速查询？

答：

```text
索引是数据库维护的数据结构，能帮助数据库快速定位数据，避免全表扫描。InnoDB 常用 B+ 树索引，树高低、叶子有序，适合等值和范围查询。
```

### Q4：唯一索引和普通索引区别？

答：

```text
普通索引用于加速查询，允许重复值；唯一索引既能加速查询，又能保证列值唯一。项目 users 表对 phone/email 建唯一索引，用来防止并发注册重复账号。
```

### Q5：什么是回表？

答：

```text
InnoDB 二级索引叶子节点存的是主键值。如果通过二级索引找到主键后，还需要去主键索引查询整行数据，这个过程叫回表。
```

### Q6：什么是覆盖索引？

答：

```text
如果查询需要的字段都能从索引中拿到，不需要回表，就叫覆盖索引。它能减少一次主键索引查询，提升性能。
```

### Q7：联合索引最左前缀是什么？

答：

```text
联合索引按定义顺序组织，查询条件要从最左列开始才能充分利用。比如 (user_id, created_at)，用 user_id 或 user_id + created_at 能用，单独 created_at 通常不能充分利用。
```

### Q8：事务四大特性？

答：

```text
ACID：原子性、一致性、隔离性、持久性。原子性保证一组操作要么都成功要么都失败；隔离性解决并发事务互相影响；持久性保证提交后数据落盘。
```

### Q9：MySQL 默认隔离级别？

答：

```text
MySQL InnoDB 默认是 Repeatable Read。它通过 MVCC 支持一致性读，并结合锁机制处理当前读和写冲突。
```

### Q10：注册时为什么不能只靠业务层查重？

答：

```text
业务层查重存在并发窗口，两个请求可能同时查到不存在，然后同时插入。必须用数据库唯一索引作为最终约束，插入冲突时捕获 DuplicateKeyException 返回账号已存在。
```

## 21. 项目讲法

```text
项目用户数据使用 MyBatis 访问 MySQL。UserMapper 定义 findByPhone、existsByEmail、insert 等方法，XML 里写具体 SQL，并通过 resultMap 把 password_hash、created_at 这类下划线字段映射成 Java 的 passwordHash、createdAt。

注册链路里，我理解业务层查重只能做提前校验，真正防并发重复的是 users 表上的唯一索引。为了避免数据库 DuplicateKeyException 变成 500，我在 AuthService.register 中捕获重复键异常并转成 IDENTIFIER_EXISTS 业务错误。
```

## 22. 自测清单

- Mapper 接口和 XML 如何对应？
- `#{}` 和 `${}` 区别？
- resultMap 用来解决什么问题？
- 动态 SQL 常见标签有哪些？
- InnoDB 主键索引和二级索引区别？
- 什么是回表？
- 什么是覆盖索引？
- B+ 树为什么适合数据库索引？
- 联合索引最左前缀是什么？
- 哪些情况会导致索引失效？
- 事务 ACID 是什么？
- 隔离级别有哪些？
- MVCC 是什么？
- 为什么唯一索引能解决并发注册问题？

