# Spring Security 配置笔记

本文档整理 `auth/config/SecurityConfig.java` 相关安全配置，帮助理解本项目的接口鉴权、JWT 校验、跨域配置和登录态处理方式。

涉及核心文件：

- `src/main/java/com/tongji/auth/config/SecurityConfig.java`
- `src/main/java/com/tongji/auth/config/AuthConfiguration.java`
- `src/main/java/com/tongji/auth/config/AuthProperties.java`
- `src/main/java/com/tongji/auth/token/JwtService.java`
- `src/main/java/com/tongji/auth/api/AuthController.java`

## 1. SecurityConfig 的定位

`SecurityConfig` 是整个后端 HTTP 安全入口。

它负责决定：

- 哪些接口可以匿名访问。
- 哪些接口必须登录。
- JWT 怎么接入 Spring Security。
- 是否使用 Session。
- 是否开启 CORS。
- 是否关闭 CSRF。

可以把它理解成后端 API 的“门卫”：

```text
请求进入后端
  ↓
Spring Security 过滤链
  ↓
判断路径是否公开
  ↓
公开接口：直接放行
  ↓
非公开接口：检查 Authorization: Bearer token
  ↓
token 有效：放行到 Controller
  ↓
token 无效或缺失：返回 401
```

## 2. 类上的注解

### 2.1 @Configuration

```java
@Configuration
```

表示这是一个 Spring 配置类，里面通过 `@Bean` 创建的对象会交给 Spring 容器管理。

### 2.2 @EnableWebSecurity

```java
@EnableWebSecurity
```

启用 Spring Security 的 Web 安全能力。

没有它，Spring Security 不会按照当前类中配置的规则保护接口。

### 2.3 @EnableMethodSecurity

```java
@EnableMethodSecurity
```

启用方法级权限控制。

例如以后可以在 Service 或 Controller 方法上使用：

```java
@PreAuthorize("hasRole('ADMIN')")
```

当前项目主要还是 URL 级权限控制，暂时没有大量使用方法级权限注解。

## 3. SecurityFilterChain

核心配置在：

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception
```

这个 Bean 会构建一条 Spring Security 过滤链。

所有 HTTP 请求进入 Controller 前，都会先经过这条过滤链。

## 4. 关闭 CSRF

配置：

```java
.csrf(AbstractHttpConfigurer::disable)
```

CSRF 是 Cross-Site Request Forgery，跨站请求伪造。

它主要防的是传统 Cookie + Session 网站中的攻击场景。

本项目是纯 API 后端，认证方式是：

```text
Authorization: Bearer JWT
```

而不是浏览器自动携带的 Cookie。

所以关闭 CSRF 是合理的。

注意：如果未来改成 Cookie 登录，或者把 refresh token 放进 Cookie，就需要重新评估 CSRF 防护。

## 5. 开启 CORS

配置：

```java
.cors(Customizer.withDefaults())
```

这表示启用跨域配置。

实际配置来自：

```java
@Bean
public CorsConfigurationSource corsConfigurationSource()
```

当前 CORS 配置：

```java
configuration.setAllowedOrigins(List.of("*"));
configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
configuration.setAllowCredentials(false);
```

含义：

| 配置 | 含义 |
| --- | --- |
| `AllowedOrigins("*")` | 允许任意来源访问 |
| `AllowedMethods` | 允许 GET、POST、PUT、DELETE、OPTIONS |
| `AllowedHeaders` | 允许 Authorization、Content-Type、X-Requested-With |
| `AllowCredentials(false)` | 不允许跨域携带 Cookie 等凭证 |

开发阶段允许任意来源比较方便。

生产环境建议改成前端域名白名单，例如：

```java
configuration.setAllowedOrigins(List.of(
    "https://www.zhiguang.example",
    "https://app.zhiguang.example"
));
```

## 6. 项目里存在额外 CORS 配置

项目里还有：

```text
src/main/java/com/tongji/profile/config/CorsConfig.java
```

它定义了一个 `CorsFilter`，只作用于：

```text
/api/v1/profile/**
```

这和 `SecurityConfig` 里的全局 CORS 有一定重复。

当前不一定会立刻出问题，但长期看建议统一到一处，减少后续排查跨域问题的复杂度。

## 7. 无状态 Session

配置：

```java
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

含义：后端不创建 Session，也不依赖 Session 保存登录状态。

每次请求都必须自己带 JWT：

```http
Authorization: Bearer <accessToken>
```

后端只看当前请求里的 token 是否有效。

这就是无状态认证。

优点：

- 服务端不用保存登录会话。
- 多实例部署更简单。
- 横向扩展方便。

缺点：

- Access Token 签发后，在过期前默认一直有效。
- 如果要主动踢人下线，需要额外设计黑名单或缩短 access token 有效期。

本项目使用短 access token + refresh token 白名单来折中。

## 8. 放行接口

权限规则从这里开始：

```java
.authorizeHttpRequests(auth -> auth
```

### 8.1 健康检查接口

```java
.requestMatchers("/actuator/health", "/actuator/info").permitAll()
```

允许匿名访问：

```text
/actuator/health
/actuator/info
```

用途：

- 本地调试。
- Docker 健康检查。
- 监控系统探活。

### 8.2 公开 Feed

```java
.requestMatchers("/api/v1/knowposts/feed").permitAll()
```

允许匿名访问首页 Feed。

这意味着未登录用户也可以浏览公开内容。

### 8.3 知文详情

```java
.requestMatchers(HttpMethod.GET, "/api/v1/knowposts/detail/*").permitAll()
```

允许匿名访问知文详情接口。

注意：这里不是所有详情都无条件公开。

Security 层只是允许请求进入 Controller。

真正判断能不能看，是业务层做的：

```text
已发布 + public：可以看
非公开内容：只有作者本人可以看
删除内容：不可看
```

也就是说，SecurityConfig 负责“能不能进门”，Service 负责“进门后能不能看这篇内容”。

### 8.4 RAG 问答流接口

```java
.requestMatchers(HttpMethod.GET, "/api/v1/knowposts/*/qa/stream").permitAll()
```

允许匿名访问知文详情页的 RAG 问答流式接口。

这个接口可能会调用大模型，有成本风险。

后续如果要上线，建议考虑：

- 改成必须登录。
- 增加限流。
- 增加调用次数限制。
- 对匿名用户只开放较小额度。

### 8.5 认证相关接口

```java
.requestMatchers(
    "/api/v1/auth/send-code",
    "/api/v1/auth/register",
    "/api/v1/auth/login",
    "/api/v1/auth/token/refresh",
    "/api/v1/auth/logout",
    "/api/v1/auth/password/reset"
).permitAll()
```

这些接口允许匿名访问。

原因很简单：用户在登录前还没有 token，如果这些接口也要求登录，就无法注册和登录。

接口含义：

| 接口 | 作用 |
| --- | --- |
| `/api/v1/auth/send-code` | 发送验证码 |
| `/api/v1/auth/register` | 注册 |
| `/api/v1/auth/login` | 登录 |
| `/api/v1/auth/token/refresh` | 使用 refresh token 刷新令牌 |
| `/api/v1/auth/logout` | 登出，撤销 refresh token |
| `/api/v1/auth/password/reset` | 重置密码 |

其中 `logout` 被放行，是因为它通过请求体里的 `refreshToken` 撤销刷新令牌，不依赖当前 access token。

## 9. 其他接口都必须登录

配置：

```java
.anyRequest().authenticated()
```

这句话表示：前面没有被 `permitAll()` 放行的接口，全部需要认证。

例如：

```text
/api/v1/auth/me
/api/v1/profile/**
/api/v1/relation/**
/api/v1/action/**
/api/v1/storage/**
大部分 knowpost 写操作
```

请求这些接口时必须带：

```http
Authorization: Bearer <accessToken>
```

否则 Spring Security 会返回 401。

## 10. JWT 资源服务器

配置：

```java
.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
```

这表示当前服务作为 OAuth2 Resource Server。

它会自动做这些事情：

1. 从请求头读取 `Authorization: Bearer xxx`。
2. 解析 JWT。
3. 校验 JWT 签名。
4. 校验 JWT 是否过期。
5. 校验通过后，把 JWT 放进 Spring Security 上下文。
6. Controller 可以通过 `@AuthenticationPrincipal Jwt jwt` 拿到当前 token。

例如：

```java
public AuthUserResponse me(@AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    return authService.me(userId);
}
```

## 11. JWT 编码和解码配置

JWT 编码和解码不在 `SecurityConfig` 中，而是在：

```text
AuthConfiguration.java
```

### 11.1 JwtEncoder

```java
@Bean
public JwtEncoder jwtEncoder()
```

作用：签发 JWT。

它会读取：

```yaml
auth:
  jwt:
    private-key: classpath:keys/private.pem
    public-key: classpath:keys/public.pem
```

使用 RSA 私钥签名。

### 11.2 JwtDecoder

```java
@Bean
public JwtDecoder jwtDecoder()
```

作用：校验 JWT。

它只读取公钥：

```yaml
auth:
  jwt:
    public-key: classpath:keys/public.pem
```

服务端收到 token 后，用公钥验证它是不是由对应私钥签发。

## 12. JWT 内容

JWT 的签发在：

```text
JwtService.java
```

登录或注册成功后，会签发一对 token：

```text
accessToken
refreshToken
```

配置：

```yaml
auth:
  jwt:
    issuer: zhiguang
    access-token-ttl: PT15M
    refresh-token-ttl: P7D
```

含义：

| 配置 | 含义 |
| --- | --- |
| `issuer` | 签发者 |
| `PT15M` | access token 有效期 15 分钟 |
| `P7D` | refresh token 有效期 7 天 |

Access Token 中包含：

```text
iss: zhiguang
sub: 用户 ID
jti: token ID
token_type: access
uid: 用户 ID
nickname: 用户昵称
```

Refresh Token 中包含：

```text
iss: zhiguang
sub: 用户 ID
jti: refresh token ID
token_type: refresh
uid: 用户 ID
```

`jti` 是 token ID。

在 refresh token 中，`jti` 用来做 Redis 白名单记录和撤销。

## 13. Access Token 与 Refresh Token

本项目使用双令牌机制：

```text
Access Token：短期令牌，用来访问接口
Refresh Token：长期令牌，用来换新的 Access Token
```

当前有效期：

```text
Access Token: 15 分钟
Refresh Token: 7 天
```

为什么要这样设计：

- Access Token 时间短，即使泄露，风险时间窗口较小。
- Refresh Token 时间长，但会存入 Redis 白名单，可以主动撤销。
- 用户不用频繁重新登录。

典型流程：

```text
登录成功
  ↓
返回 accessToken + refreshToken
  ↓
普通接口使用 accessToken
  ↓
accessToken 过期
  ↓
使用 refreshToken 请求 /api/v1/auth/token/refresh
  ↓
服务端校验 refreshToken 白名单
  ↓
签发新的 accessToken + refreshToken
  ↓
撤销旧 refreshToken
```

## 14. PasswordEncoder

`AuthConfiguration` 中还配置了：

```java
@Bean
public PasswordEncoder passwordEncoder()
```

它使用：

```java
new BCryptPasswordEncoder(properties.getPassword().getBcryptStrength())
```

配置：

```yaml
auth:
  password:
    bcrypt-strength: 12
```

意思是密码不会明文存数据库，而是存 BCrypt 哈希。

`bcrypt-strength: 12` 表示加密成本因子。值越大越慢，也越抗暴力破解。

## 15. 当前安全策略总结

当前策略可以总结为：

```text
认证方式：JWT Bearer Token
服务端会话：无状态，不使用 Session
密码存储：BCrypt
JWT 签名：RSA 私钥签发，公钥验证
匿名可访问：登录、注册、验证码、公开 Feed、公开详情、健康检查
其他接口：必须登录
```

## 16. 需要注意的点

### 16.1 CORS 生产环境需要收紧

当前：

```java
configuration.setAllowedOrigins(List.of("*"));
```

生产环境建议改成前端域名白名单。

### 16.2 CORS 配置存在重复

当前有：

```text
SecurityConfig.corsConfigurationSource()
Profile 模块 CorsConfig.corsFilter()
```

建议后续统一到一个全局配置中。

### 16.3 RAG 流式问答匿名开放有成本风险

当前：

```java
GET /api/v1/knowposts/*/qa/stream
```

允许匿名访问。

如果这个接口会调用大模型，建议后续加登录要求或限流。

### 16.4 logout 放行是当前设计选择

`logout` 目前通过请求体里的 refresh token 撤销白名单记录。

所以它不依赖 access token，被配置为匿名可访问。

这个设计可以工作，但客户端要注意保护 refresh token。

### 16.5 暂无角色权限

当前没有明显的角色/权限体系。

所有登录用户基本处于同一权限层级。

后续如果需要管理员、审核员、普通用户等角色，可以引入：

```java
@PreAuthorize
```

或者在 JWT 中加入角色声明。

## 17. 初学者总结

可以先记住这几句话：

- `SecurityConfig` 决定接口是否需要登录。
- `permitAll()` 表示匿名可访问。
- `authenticated()` 表示必须带合法 JWT。
- JWT 放在请求头 `Authorization: Bearer xxx` 中。
- 后端不使用 Session，每个请求都靠 JWT 证明身份。
- Access Token 用来访问接口，Refresh Token 用来换新令牌。
- JWT 用 RSA 私钥签发，公钥验证。
- CORS 控制前端网页能不能跨域访问后端。
