# 05 JWT 与 Spring Security

> 目标：能完整讲清楚本项目认证链路：注册/登录签发 JWT，access token 访问受保护接口，refresh token 通过 Redis 白名单续期，Spring Security Resource Server 自动校验 Bearer JWT。

## 1. 最短面试回答

```text
项目认证采用 Spring Security + OAuth2 Resource Server + JWT 双令牌 + Redis refresh token 白名单。

用户登录后服务端签发 access token 和 refresh token。access token 有效期短，用于访问业务接口；refresh token 有效期长，用于续期，但它的 jti 会存到 Redis 白名单里，登出或重置密码时可以删除白名单实现撤销。

业务接口通过 Spring Security 过滤链保护。白名单接口 permitAll，其余 authenticated。请求携带 Authorization: Bearer accessToken 后，Resource Server 会自动解析 JWT、验签、校验 exp，成功后 Controller 可以用 @AuthenticationPrincipal Jwt 获取当前用户。
```

## 2. 项目源码锚点

- `src/main/java/com/tongji/auth/config/SecurityConfig.java`
  - 过滤链、白名单、`authenticated()`、无状态 Session、Resource Server。
- `src/main/java/com/tongji/auth/config/AuthConfiguration.java`
  - `PasswordEncoder`、`JwtEncoder`、`JwtDecoder`。
- `src/main/java/com/tongji/auth/token/JwtService.java`
  - 签发 access/refresh token，写入 `sub/exp/jti/token_type/uid`。
- `src/main/java/com/tongji/auth/token/RedisRefreshTokenStore.java`
  - refresh token 白名单。
- `src/main/java/com/tongji/auth/service/AuthService.java`
  - 注册、登录、刷新、登出、重置密码链路。
- `src/main/java/com/tongji/auth/api/AuthController.java`
  - `/me` 使用 `@AuthenticationPrincipal Jwt`。

## 3. 认证和鉴权

认证 Authentication：

```text
你是谁？
```

例子：

- 登录。
- 手机验证码验证。
- 密码验证。
- JWT 验签后确认用户身份。

鉴权 Authorization：

```text
你有没有权限做这件事？
```

例子：

- 是否登录后才能点赞。
- 是否只能修改自己的资料。
- 管理员才能删除内容。

项目认证模块目前重点是“已登录用户鉴权”，更细粒度权限可后续扩展。

## 4. JWT 是什么

JWT：JSON Web Token。

它是一种紧凑的、URL 安全的 claims 表达格式，常放在 HTTP Authorization Header 中。

典型结构：

```text
header.payload.signature
```

三段都是 Base64URL 编码。

### 4.1 Header

描述 token 类型和签名算法。

例子：

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "zhiguang-key"
}
```

### 4.2 Payload

保存 claims，也就是声明。

项目 access token：

```java
JwtClaimsSet.builder()
    .issuer(properties.getJwt().getIssuer())
    .issuedAt(issuedAt)
    .expiresAt(expiresAt)
    .subject(String.valueOf(user.getId()))
    .id(tokenId)
    .claim("token_type", "access")
    .claim("uid", user.getId())
    .claim("nickname", user.getNickname())
    .build();
```

项目 refresh token：

```java
JwtClaimsSet.builder()
    .issuer(properties.getJwt().getIssuer())
    .issuedAt(issuedAt)
    .expiresAt(expiresAt)
    .subject(String.valueOf(user.getId()))
    .id(tokenId)
    .claim("token_type", "refresh")
    .claim("uid", user.getId())
    .build();
```

### 4.3 Signature

签名用于保证 token 未被篡改。

项目使用 RSA 非对称签名：

- 私钥签发。
- 公钥验签。

好处：

- 认证服务持有私钥。
- 其他资源服务只需要公钥即可验证。
- 更适合多服务场景。

## 5. 常见 claim

| claim | 含义 | 项目使用 |
|---|---|---|
| `iss` | issuer，签发者 | 配置里的 issuer |
| `sub` | subject，主体 | 用户 ID |
| `exp` | expiration time，过期时间 | access/refresh 都有 |
| `iat` | issued at，签发时间 | access/refresh 都有 |
| `jti` | JWT ID，令牌唯一 ID | refresh token 白名单 key |
| `uid` | 自定义用户 ID | 业务提取用户 |
| `token_type` | 自定义令牌类型 | access/refresh 区分 |
| `nickname` | 自定义昵称 | access token 中携带 |

面试重点：

```text
JWT 的 payload 只是 Base64URL 编码，不是加密，不能放密码、身份证、密钥等敏感信息。
```

## 6. access token 和 refresh token

### 6.1 access token

用途：

- 访问业务接口。

特点：

- 有效期短。
- 尽量无状态。
- 泄露后的风险窗口较短。

项目 README 中描述是 15 分钟访问令牌。

### 6.2 refresh token

用途：

- 换取新的 access token。

特点：

- 有效期长。
- 必须可撤销。
- 需要轮换。

项目 README 中描述是 7 天刷新令牌。

### 6.3 为什么不用一个长期 token

如果只用一个长期 access token：

- 泄露风险大。
- 服务端不容易撤销。
- 每次访问都长期有效。

双令牌方案：

```text
access token 短期无状态，提高性能。
refresh token 长期有状态，放 Redis 白名单，可撤销。
```

## 7. 项目签发链路

```mermaid
flowchart TD
    A["用户登录/注册成功"] --> B["AuthService.login/register"]
    B --> C["JwtService.issueTokenPair"]
    C --> D["生成 access token jti"]
    C --> E["生成 refresh token jti"]
    D --> F["JwtEncoder 用私钥签名 access"]
    E --> G["JwtEncoder 用私钥签名 refresh"]
    G --> H["AuthService.storeRefreshToken"]
    H --> I["Redis: auth:rt:{uid}:{jti} = 1 + TTL"]
    I --> J["返回 AuthResponse"]
```

核心代码：

```java
TokenPair tokenPair = jwtService.issueTokenPair(user);
storeRefreshToken(user.getId(), tokenPair);
```

## 8. Spring Security 是什么

Spring Security 是 Spring 生态的认证鉴权框架。

它的核心思想：

```text
请求先经过一组安全过滤器，再进入 Controller。
```

过滤器负责：

- 提取认证信息。
- 判断是否白名单。
- 校验 token。
- 构建认证上下文。
- 拒绝未认证请求。

## 9. SecurityFilterChain

项目配置：

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/api/v1/knowposts/feed").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/knowposts/detail/*").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/knowposts/*/qa/stream").permitAll()
            .requestMatchers(
                "/api/v1/auth/send-code",
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/token/refresh",
                "/api/v1/auth/logout",
                "/api/v1/auth/password/reset"
            ).permitAll()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
    return http.build();
}
```

逐行理解：

- `csrf(disable)`：关闭 CSRF。
- `cors`：允许跨域配置。
- `STATELESS`：不创建 Session。
- `permitAll()`：白名单。
- `authenticated()`：其他接口必须认证。
- `oauth2ResourceServer().jwt()`：启用 Bearer JWT 校验。

## 10. 白名单和 authenticated

### 10.1 permitAll

表示不需要登录。

项目白名单包括：

- 健康检查。
- 首页 Feed。
- 已发布知文详情。
- RAG 流式问答。
- 认证相关接口。

### 10.2 authenticated

表示必须通过认证。

例如：

- 发布知文。
- 修改资料。
- 点赞收藏。
- 获取当前用户信息。

### 10.3 面试回答

```text
SecurityConfig 中先声明白名单接口 permitAll，比如登录注册和公开内容；最后 anyRequest().authenticated() 表示其他接口都需要认证。这样默认是安全的，新增接口如果没有显式加入白名单，就会要求登录。
```

## 11. OAuth2 Resource Server 如何自动校验 Bearer JWT

前端请求：

```http
GET /api/v1/auth/me HTTP/1.1
Authorization: Bearer eyJhbGciOiJSUzI1NiJ9...
```

Spring Security 大致流程：

```mermaid
flowchart TD
    A["请求进入过滤链"] --> B["BearerTokenAuthenticationFilter"]
    B --> C["从 Authorization Header 提取 Bearer token"]
    C --> D["JwtDecoder.decode"]
    D --> E["使用 RSA 公钥验签"]
    E --> F["校验 exp 等标准声明"]
    F --> G["构造 JwtAuthenticationToken"]
    G --> H["放入 SecurityContext"]
    H --> I["进入 Controller"]
    I --> J["@AuthenticationPrincipal Jwt 注入当前 Jwt"]
```

项目配置 `JwtDecoder`：

```java
@Bean
public JwtDecoder jwtDecoder() {
    AuthProperties.Jwt jwtProps = properties.getJwt();
    RSAPublicKey publicKey = PemUtils.readPublicKey(jwtProps.getPublicKey());
    return NimbusJwtDecoder.withPublicKey(publicKey).build();
}
```

## 12. @AuthenticationPrincipal

项目 Controller：

```java
public AuthUserResponse me(@AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    return authService.me(userId);
}
```

含义：

- token 已经由过滤链校验成功。
- 当前认证主体是 `Jwt`。
- Controller 直接从 Jwt 中取 `uid`。

优势：

- 不信任前端传 userId。
- 用户身份来自服务端校验过的 token。
- 防止越权修改别人数据。

面试回答：

```text
@AuthenticationPrincipal 可以拿到当前认证主体。项目启用 Resource Server 后，Bearer JWT 校验成功会生成 JwtAuthenticationToken，所以 Controller 可以注入 Jwt，再从里面解析 uid，避免让前端传 userId。
```

## 13. BCrypt

项目配置：

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(properties.getPassword().getBcryptStrength());
}
```

登录校验：

```java
passwordEncoder.matches(request.password(), user.getPasswordHash())
```

### 13.1 为什么不能明文存密码

数据库泄露后，所有用户密码直接暴露。

### 13.2 为什么不推荐 MD5

MD5：

- 速度太快，容易被暴力破解。
- 相同密码 hash 相同。
- 彩虹表攻击成本低。
- 不是为密码存储设计的。

### 13.3 BCrypt 的优势

- 自带随机盐。
- 计算成本可调。
- 同一密码每次 hash 结果不同。
- 专门适合密码哈希。

面试回答：

```text
密码不能明文存，也不推荐 MD5。MD5 太快且容易被彩虹表攻击。项目使用 BCrypt，它自带随机盐，计算成本可调，同一密码每次生成的 hash 不同，登录时用 matches 校验。
```

## 14. CSRF 和 Session

### 14.1 CSRF 是什么

CSRF：跨站请求伪造。

典型场景：

1. 用户登录银行网站，浏览器保存 Cookie。
2. 用户访问恶意网站。
3. 恶意网站诱导浏览器向银行发起转账请求。
4. 浏览器自动带上银行 Cookie。
5. 如果银行只靠 Cookie 判断登录，就可能被攻击。

### 14.2 为什么前后端分离 JWT 项目通常关闭 CSRF

本项目：

```java
.csrf(AbstractHttpConfigurer::disable)
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

原因：

- 项目是前后端分离 API。
- 使用 Authorization Header 传 Bearer token。
- 浏览器不会像 Cookie 那样自动给任意跨站请求加 Authorization Header。
- 服务端不依赖 Session。

注意：

```text
如果把 JWT 放进 Cookie 且自动携带，仍然要考虑 CSRF。
```

面试回答：

```text
CSRF 主要利用浏览器自动携带 Cookie 的机制。项目使用 Authorization Header 携带 Bearer JWT，并且 SessionCreationPolicy 是 STATELESS，不依赖服务端 Session，所以通常关闭 CSRF。但如果把 token 放在 Cookie 中自动携带，就仍然需要 CSRF 防护。
```

## 15. Refresh 流程

```mermaid
flowchart TD
    A["前端 access token 过期"] --> B["调用 /api/v1/auth/token/refresh"]
    B --> C["提交 refreshToken"]
    C --> D["JwtService.decode 验签和 exp"]
    D --> E["检查 token_type == refresh"]
    E --> F["提取 uid 和 jti"]
    F --> G["RedisRefreshTokenStore.isTokenValid"]
    G --> H{"白名单存在?"}
    H -->|"否"| I["返回 refresh token invalid"]
    H -->|"是"| J["签发新 access/refresh"]
    J --> K["删除旧 refresh jti"]
    K --> L["保存新 refresh jti + TTL"]
    L --> M["返回新 token"]
```

为什么要轮换 refresh token：

- 旧 token 用过即废。
- 降低泄露后的可持续利用风险。
- 更容易发现异常重放。

## 16. 登出和重置密码

登出：

```java
refreshTokenStore.revokeToken(userId, tokenId);
```

重置密码：

```java
refreshTokenStore.revokeAll(user.getId());
```

含义：

- 登出当前设备：删除当前 refresh token。
- 重置密码：删除该用户所有 refresh token，让所有设备重新登录。

注意：

```text
access token 因为无状态，通常不能立即撤销，只能等短 TTL 过期。如果要求立即撤销 access token，也要引入黑名单或版本号机制。
```

## 17. 项目当前风险点

### 17.1 refresh token 也可能被 Resource Server 验签通过

项目现在 access token 和 refresh token 都是合法 JWT。

Resource Server 默认主要校验：

- 签名。
- 过期时间。
- JWT 格式。

如果没有额外校验 `token_type == access`，理论上 refresh token 也可能被拿去访问业务接口。

优化方案：

- 给 Resource Server 增加自定义 `OAuth2TokenValidator<Jwt>`。
- 或在鉴权转换器里拒绝非 access token。

面试话术：

```text
我注意到项目里 access 和 refresh 都是 JWT，如果 Resource Server 只校验签名和过期时间，还应增加 token_type=access 校验，避免 refresh token 被用于访问业务接口。
```

### 17.2 RAG 流式接口匿名开放有成本风险

`SecurityConfig` 中：

```java
.requestMatchers(HttpMethod.GET, "/api/v1/knowposts/*/qa/stream").permitAll()
```

风险：

- 被刷模型调用。
- token 成本不可控。
- 需要限流或登录控制。

优化：

- 登录后使用。
- IP 限流。
- 用户额度。
- 对公开内容允许低频匿名。

## 18. 高频面试题

### Q1：JWT 三段结构是什么？

答：

```text
JWT 通常由 header、payload、signature 三段组成。header 描述算法和类型，payload 存 claims，signature 对前两段签名，保证 token 没被篡改。
```

### Q2：JWT payload 是加密的吗？

答：

```text
不是。普通 JWT payload 只是 Base64URL 编码，任何人都能解码查看，所以不能放密码、密钥等敏感信息。签名只能保证完整性，不保证机密性。
```

### Q3：`exp`、`sub`、`jti` 分别是什么？

答：

```text
exp 是过期时间，sub 是 token 主体，通常放用户 ID，jti 是 token 唯一 ID。项目里 refresh token 的 jti 会存进 Redis 白名单，用于刷新和撤销。
```

### Q4：为什么要 access token + refresh token？

答：

```text
access token 有效期短，用于访问接口，保持无状态；refresh token 有效期长，用于续期，但存 Redis 白名单可撤销。这样兼顾性能和安全。
```

### Q5：Spring Security 过滤链做什么？

答：

```text
请求进入 Controller 前会先经过 Spring Security 过滤链。过滤链负责判断是否白名单、提取认证信息、校验 JWT、构建 SecurityContext，以及拒绝未认证请求。
```

### Q6：`permitAll()` 和 `authenticated()` 区别？

答：

```text
permitAll 表示接口允许匿名访问，authenticated 表示必须认证通过才能访问。项目里登录注册是 permitAll，其他业务接口默认 authenticated。
```

### Q7：Resource Server 如何校验 Bearer JWT？

答：

```text
开启 oauth2ResourceServer().jwt() 后，Spring Security 会从 Authorization Header 提取 Bearer token，调用 JwtDecoder 用公钥验签并校验过期时间，成功后构建认证对象放入 SecurityContext。
```

### Q8：`@AuthenticationPrincipal Jwt` 是怎么来的？

答：

```text
JWT 通过过滤链校验成功后，Spring Security 会把认证信息保存到 SecurityContext。Controller 使用 @AuthenticationPrincipal 就能拿到当前 principal，在本项目里就是 Jwt 对象。
```

### Q9：为什么密码不能用 MD5？

答：

```text
MD5 速度太快，不适合密码存储，容易被暴力破解和彩虹表攻击。BCrypt 自带随机盐，计算成本可调，更适合保存密码哈希。
```

### Q10：为什么 JWT 项目通常无状态？

答：

```text
因为服务端可以通过验签直接确认 token 是否可信，不需要把登录状态放在 Session 中。项目设置 SessionCreationPolicy.STATELESS，业务接口每次请求都通过 Bearer JWT 完成认证。
```

## 19. 项目讲法

```text
知光项目认证模块使用 JWT 双令牌。登录成功后 JwtService 使用私钥签发 access token 和 refresh token。access token 携带 uid、sub、exp、jti、token_type=access，用于访问业务接口；refresh token 携带 token_type=refresh，它的 jti 会写入 Redis 白名单。

受保护接口由 Spring Security 过滤链保护。SecurityConfig 中登录、注册、公开 Feed 等接口 permitAll，其余 anyRequest().authenticated。前端访问业务接口时带 Authorization: Bearer accessToken，Resource Server 自动用公钥验签并校验 exp，Controller 再通过 @AuthenticationPrincipal Jwt 获取用户身份。
```

## 20. 自测清单

- JWT 三段结构是什么？
- 签名解决什么问题？
- `exp/sub/jti/claim` 分别是什么？
- access token 和 refresh token 区别？
- refresh token 为什么要存 Redis？
- Spring Security 过滤链是什么？
- 白名单和 authenticated 怎么配置？
- Resource Server 如何自动校验 Bearer JWT？
- `@AuthenticationPrincipal` 从哪里来？
- BCrypt 为什么比 MD5 更适合密码？
- CSRF 是什么？
- 为什么本项目前后端分离 JWT 关闭 CSRF？
- 项目当前 token_type 校验有什么优化点？

