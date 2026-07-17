# MyBatis 动态 SQL

> 整理来源：[MyBatis Dynamic SQL](https://mybatis.org/mybatis-3/dynamic-sql.html)

## 动态 SQL 解决的问题

业务查询经常包含可选条件。手工拼接字符串容易遗漏空格、产生多余逗号，并增加 SQL 注入风险。MyBatis 在 Mapper XML 中提供动态标签，根据参数安全地生成最终 SQL。

常用标签包括 `if`、`choose`、`trim`、`where`、`set` 和 `foreach`。条件判断由 OGNL 表达式完成，值参数仍应使用 `#{}` 绑定。

## if

`if` 在条件成立时加入一段 SQL：

```xml
<if test="title != null and title != ''">
  AND title LIKE CONCAT('%', #{title}, '%')
</if>
```

每个条件相互独立，多个条件可以同时出现。可选字段更新时也可以使用 `if`，但要避免所有字段都为空时仍执行无意义 UPDATE。

## choose

`choose` 类似 Java 的 `switch` 或 if-else-if，只选择第一个满足的 `when`，都不满足时执行 `otherwise`。它适合互斥策略，例如优先按 ID 查询，否则按标题查询。

```xml
<choose>
  <when test="id != null">AND id = #{id}</when>
  <when test="title != null">AND title = #{title}</when>
  <otherwise>AND status = 'published'</otherwise>
</choose>
```

## where 与 trim

`where` 只有在内部产生条件时才输出 `WHERE`，并能移除开头多余的 `AND` 或 `OR`。这样可以避免生成 `WHERE AND ...` 或只有 `WHERE` 的非法 SQL。

`trim` 是更通用的处理器，可以配置 `prefix`、`suffix`、`prefixOverrides` 和 `suffixOverrides`。`where` 可以理解为针对查询条件预设好参数的 trim。

## set

`set` 用于动态 UPDATE。它只在内部存在字段时生成 `SET`，并去掉末尾多余逗号：

```xml
<set>
  <if test="title != null">title = #{title},</if>
  <if test="visible != null">visible = #{visible},</if>
  update_time = NOW()
</set>
```

保留一个始终更新的 `update_time`，可以避免所有可选字段为空时生成空 SET，但业务层仍应校验请求是否具有实际修改内容。

## foreach 与参数安全

`foreach` 用于构造 IN 列表或批量写入，可以指定集合、元素名、分隔符和开闭符号。空集合需要单独处理，否则可能生成 `IN ()`。

`#{value}` 使用预编译参数，适合普通值；`${value}` 直接把文本拼进 SQL，只应用于经过严格白名单验证的列名或排序方向。用户输入不能直接进入 `${}`。

动态 SQL 提高表达能力，但最终 SQL 仍应通过 Mapper 测试覆盖空参数、单条件、多条件、空集合和边界数量。
