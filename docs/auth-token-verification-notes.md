# 认证授权：JWT、认证服务与验证码系统笔记

本文档整理认证授权部分的核心代码逻辑。

涉及文件：

- `src/main/java/com/tongji/auth/token/JwtService.java`
- `src/main/java/com/tongji/auth/token/RefreshTokenStore.java`
- `src/main/java/com/tongji/auth/token/RedisRefreshTokenStore.java`
- `src/main/java/com/tongji/auth/token/TokenPair.java`
- `src/main/java/com/tongji/auth/service/AuthService.java`
- `src/main/java/com/tongji/auth/verification/VerificationService.java`
- `src/main/java/com/tongji/auth/verification/VerificationCodeStore.java`
- `src/main/java/com/tongji/auth/verification/RedisVerificationCodeStore.java`
- `src/main/java/com/tongji/auth/verification/LoggingCodeSender.java`
- `src/main/java/com/tongji/auth/config/AuthProperties.java`
- `src/main/resources/application.yml`

## 1. 整体关系

认证授权模块可以拆成三根主线：

```text
JwtService：负责生成和解析 JWT
AuthService：负责编排登录、注册、刷新、登出、重置密码等认证业务流程
VerificationService：负责验证码发送、限流、存储和校验
```

完整认证链路可以理解为：

```text
发送验证码
  ↓
注册 / 登录
  ↓
校验验证码或密码
  ↓
创建或查询用户
  ↓
签发 Access Token + Refresh Token
  ↓
Refresh Token 写入 Redis 白名单
  ↓
客户端用 Access Token 访问接口
```

## 2. 认证相关配置

配置位于 `application.yml`：

```yaml
auth:
  jwt:
    issuer: zhiguang
    key-id: zhiguang-key
    private-key: classpath:keys/private.pem
    public-key: classpath:keys/public.pem
    access-token-ttl: PT15M
    refresh-token-ttl: P7D
  verification:
    code-length: 6
    ttl: PT5M
    max-attempts: 5
    send-interval: PT60S
    daily-limit: 10
  password:
    bcrypt-strength: 12
    min-length: 8
```

含义：

| 配置 | 含义 |
| --- | --- |
| `issuer` | JWT 签发者 |
| `key-id` | JWK key id，用于标识密钥 |
| `private-key` | JWT 签发私钥 |
| `public-key` | JWT 校验公钥 |
| `access-token-ttl` | Access Token 有效期 |
| `refresh-token-ttl` | Refresh Token 有效期 |
| `code-length` | 验证码长度 |
| `ttl` | 验证码有效期 |
| `max-attempts` | 验证码最大尝试次数 |
| `send-interval` | 同一账号发送验证码间隔 |
| `daily-limit` | 同一账号每日发送上限 |
| `bcrypt-strength` | BCrypt 密码哈希强度 |
| `min-length` | 密码最小长度 |

Java Duration 格式说明：

```text
PT15M = 15 分钟
P7D = 7 天
PT5M = 5 分钟
PT60S = 60 秒
```

## 3. JwtService 的职责

文件：

```text
src/main/java/com/tongji/auth/token/JwtService.java
```

`JwtService` 负责：

```text
1. 签发 Access Token
2. 签发 Refresh Token
3. 解码 JWT
4. 从 JWT 中提取用户 ID
5. 从 JWT 中提取 token_type
6. 从 JWT 中提取 jti
```

核心方法：

```java
public TokenPair issueTokenPair(User user)
```

登录或注册成功后，会调用这个方法签发一对 token：

```text
Access Token
Refresh Token
```

## 4. Access Token

Access Token 是短期访问令牌。

用途：

```text
客户端访问普通业务接口时携带
```

请求头格式：

```http
Authorization: Bearer <accessToken>
```

Access Token 当前有效期：

```text
15 分钟
```

Access Token 中包含：

```text
iss: 签发者，zhiguang
iat: 签发时间
exp: 过期时间
sub: 用户 ID
jti: token 唯一 ID
token_type: access
uid: 用户 ID
nickname: 用户昵称
```

代码中构造方式：

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

## 5. Refresh Token

Refresh Token 是长期刷新令牌。

用途：

```text
Access Token 过期后，用 Refresh Token 换取新的令牌对
```

Refresh Token 当前有效期：

```text
7 天
```

Refresh Token 中包含：

```text
iss: 签发者，zhiguang
iat: 签发时间
exp: 过期时间
sub: 用户 ID
jti: refresh token ID
token_type: refresh
uid: 用户 ID
```

Refresh Token 不只依赖 JWT 本身合法，还必须存在于 Redis 白名单中。

## 6. TokenPair

文件：

```text
src/main/java/com/tongji/auth/token/TokenPair.java
```

定义：

```java
public record TokenPair(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String refreshTokenId
) {
}
```

它是一个令牌结果对象，包含：

| 字段 | 含义 |
| --- | --- |
| `accessToken` | 访问令牌 |
| `accessTokenExpiresAt` | 访问令牌过期时间 |
| `refreshToken` | 刷新令牌 |
| `refreshTokenExpiresAt` | 刷新令牌过期时间 |
| `refreshTokenId` | 刷新令牌 ID，也就是 JWT 的 `jti` |

## 7. Refresh Token 白名单

接口：

```text
src/main/java/com/tongji/auth/token/RefreshTokenStore.java
```

实现：

```text
src/main/java/com/tongji/auth/token/RedisRefreshTokenStore.java
```

Redis key 设计：

```text
auth:rt:{userId}:{tokenId}
```

值固定为：

```text
1
```

并设置 TTL。

示例：

```text
auth:rt:100:550e8400-e29b-41d4-a716-446655440000 = 1
```

作用：

- 校验 Refresh Token 是否仍然有效。
- 支持登出时撤销单个 Refresh Token。
- 支持重置密码后撤销该用户所有 Refresh Token。

核心方法：

```java
storeToken(userId, tokenId, ttl)
isTokenValid(userId, tokenId)
revokeToken(userId, tokenId)
revokeAll(userId)
```

## 8. 为什么需要 Refresh Token 白名单

JWT 本身是无状态的。

这意味着：

```text
只要 JWT 签名正确且没过期，服务端默认会接受
```

这对 Access Token 可以接受，因为它只有 15 分钟。

但 Refresh Token 有 7 天，如果完全无状态，就很难主动让它失效。

所以本项目给 Refresh Token 加了 Redis 白名单。

只有满足两点才能刷新：

```text
1. Refresh Token JWT 本身合法
2. Refresh Token 的 jti 仍在 Redis 白名单里
```

这样就能做到：

```text
登出时撤销
刷新时轮换
重置密码后全部下线
```

## 9. AuthService 的职责

文件：

```text
src/main/java/com/tongji/auth/service/AuthService.java
```

`AuthService` 是认证模块的业务编排层。

它依赖：

```java
private final UserService userService;
private final VerificationService verificationService;
private final PasswordEncoder passwordEncoder;
private final JwtService jwtService;
private final RefreshTokenStore refreshTokenStore;
private final LoginLogService loginLogService;
private final AuthProperties authProperties;
```

对应职责：

| 依赖 | 作用 |
| --- | --- |
| `UserService` | 查询、创建用户、更新密码 |
| `VerificationService` | 发送和校验验证码 |
| `PasswordEncoder` | 加密和校验密码 |
| `JwtService` | 签发和解码 token |
| `RefreshTokenStore` | 管理 refresh token 白名单 |
| `LoginLogService` | 记录登录审计 |
| `AuthProperties` | 读取认证配置 |

## 10. 发送验证码流程

方法：

```java
public SendCodeResponse sendCode(SendCodeRequest request)
```

流程：

```text
1. 校验手机号 / 邮箱格式
2. 标准化标识
   - 手机号 trim
   - 邮箱 trim + 小写
3. 判断账号是否存在
4. 根据验证码场景做业务校验
   - REGISTER：账号必须不存在
   - LOGIN：账号必须存在
   - RESET_PASSWORD：账号必须存在
5. 调用 VerificationService.sendCode()
6. 返回验证码过期时间
```

验证码场景：

```java
REGISTER,
LOGIN,
RESET_PASSWORD
```

## 11. 注册流程

方法：

```java
public AuthResponse register(RegisterRequest request, ClientInfo clientInfo)
```

流程：

```text
1. 检查是否同意协议
2. 校验手机号 / 邮箱格式
3. 标准化标识
4. 检查账号是否已经存在
5. 校验 REGISTER 场景验证码
6. 构造 User
7. 如果提交了密码，校验密码复杂度并 BCrypt 加密
8. 创建用户
9. 签发 Access Token + Refresh Token
10. 保存 Refresh Token 白名单
11. 记录注册审计日志
12. 返回用户信息和 token
```

核心代码：

```java
userService.createUser(user);
TokenPair tokenPair = jwtService.issueTokenPair(user);
storeRefreshToken(user.getId(), tokenPair);
loginLogService.record(...);
```

## 12. 登录流程

方法：

```java
public AuthResponse login(LoginRequest request, ClientInfo clientInfo)
```

支持两种登录方式：

```text
密码登录
验证码登录
```

流程：

```text
1. 校验手机号 / 邮箱格式
2. 标准化标识
3. 查询用户
4. 如果传了 password：走密码登录
   - 检查用户是否有 passwordHash
   - 使用 passwordEncoder.matches() 校验密码
5. 如果传了 code：走验证码登录
   - 校验 LOGIN 场景验证码
6. 签发新的 token pair
7. 保存 refresh token 白名单
8. 记录登录日志
9. 返回用户信息和 token
```

密码校验：

```java
passwordEncoder.matches(request.password(), user.getPasswordHash())
```

数据库中存的是 BCrypt 哈希，不是明文密码。

## 13. 刷新令牌流程

方法：

```java
public TokenResponse refresh(TokenRefreshRequest request)
```

流程：

```text
1. 解码 refreshToken
2. 检查 token_type 是否为 refresh
3. 提取 userId
4. 提取 tokenId，也就是 jti
5. 检查 Redis 白名单是否存在
6. 查询用户是否存在
7. 签发新的 token pair
8. 撤销旧 refresh token
9. 保存新 refresh token
10. 返回新的 token pair
```

关键代码：

```java
refreshTokenStore.revokeToken(userId, tokenId);
storeRefreshToken(userId, tokenPair);
```

这叫 Refresh Token Rotation，刷新令牌轮换。

含义：

```text
旧 refresh token 用过一次后立即失效
每次刷新都会得到新的 refresh token
```

## 14. 登出流程

方法：

```java
public void logout(String refreshToken)
```

流程：

```text
1. 尝试解码 refresh token
2. 解码失败则忽略
3. 检查 token_type 是否为 refresh
4. 提取 userId 和 tokenId
5. 从 Redis 白名单删除
```

注意：

```text
登出撤销的是 Refresh Token
Access Token 在剩余有效期内可能仍然有效
```

这是短 Access Token + 可撤销 Refresh Token 方案的常见取舍。

## 15. 重置密码流程

方法：

```java
public void resetPassword(PasswordResetRequest request)
```

流程：

```text
1. 校验手机号 / 邮箱格式
2. 校验新密码复杂度
3. 查询用户
4. 校验 RESET_PASSWORD 场景验证码
5. BCrypt 加密新密码
6. 更新用户密码
7. 撤销该用户所有 Refresh Token
```

关键代码：

```java
refreshTokenStore.revokeAll(user.getId());
```

作用：

```text
密码重置后，所有设备都需要重新登录
```

## 16. 密码策略

方法：

```java
private void validatePassword(String password)
```

当前规则：

```text
不能为空
trim 后长度至少 8
必须包含字母
必须包含数字
```

密码存储：

```java
passwordEncoder.encode(password)
```

密码校验：

```java
passwordEncoder.matches(rawPassword, passwordHash)
```

使用 BCrypt，不保存明文密码。

## 17. VerificationService 的职责

文件：

```text
src/main/java/com/tongji/auth/verification/VerificationService.java
```

它负责：

```text
1. 验证码发送间隔限制
2. 每日发送次数限制
3. 生成随机验证码
4. 保存验证码
5. 调用发送器
6. 校验验证码
7. 删除验证码
```

配置：

```yaml
auth:
  verification:
    code-length: 6
    ttl: PT5M
    max-attempts: 5
    send-interval: PT60S
    daily-limit: 10
```

含义：

```text
验证码长度：6 位
验证码有效期：5 分钟
最多尝试次数：5 次
同一标识发送间隔：60 秒
同一标识每日最多发送：10 次
```

## 18. 验证码发送间隔限制

方法：

```java
enforceSendInterval(scene, identifier, cfg.getSendInterval())
```

Redis key：

```text
auth:code:last:{scene}:{identifier}
```

示例：

```text
auth:code:last:LOGIN:test@example.com = 1
```

TTL：

```text
60 秒
```

如果 60 秒内再次发送，Redis 中还能查到这个 key，就抛出发送频率限制异常。

## 19. 每日发送次数限制

方法：

```java
enforceDailyLimit(scene, identifier, cfg.getDailyLimit())
```

Redis key：

```text
auth:code:count:{scene}:{identifier}:{yyyyMMdd}
```

示例：

```text
auth:code:count:LOGIN:test@example.com:20260601
```

每发送一次验证码，计数 +1：

```java
increment(key)
```

第一次创建时设置 1 天 TTL。

超过每日上限后抛出：

```text
VERIFICATION_DAILY_LIMIT
```

## 20. 验证码存储

接口：

```text
VerificationCodeStore
```

实现：

```text
RedisVerificationCodeStore
```

Redis key：

```text
auth:code:{scene}:{identifier}
```

示例：

```text
auth:code:LOGIN:test@example.com
```

使用 Redis Hash 保存：

```text
code: 验证码
maxAttempts: 最大尝试次数
attempts: 当前尝试次数
```

示例：

```text
code = 123456
maxAttempts = 5
attempts = 0
```

这个 key 的 TTL 是 5 分钟。

## 21. 验证码校验流程

方法：

```java
codeStore.verify(scene.name(), identifier, code)
```

流程：

```text
1. Redis 中找不到 key：NOT_FOUND
2. attempts >= maxAttempts：TOO_MANY_ATTEMPTS
3. code 匹配：删除验证码，返回 SUCCESS
4. code 不匹配：attempts + 1
5. 如果达到最大次数：返回 TOO_MANY_ATTEMPTS，并延长 30 分钟
6. 否则返回 MISMATCH
```

成功后删除验证码：

```java
redisTemplate.delete(key);
```

所以验证码只能成功使用一次。

## 22. 验证码状态

验证码校验结果状态：

```java
SUCCESS
NOT_FOUND
EXPIRED
MISMATCH
TOO_MANY_ATTEMPTS
```

含义：

| 状态 | 含义 |
| --- | --- |
| `SUCCESS` | 校验成功 |
| `NOT_FOUND` | Redis 中没有验证码，可能没发送或已过期 |
| `EXPIRED` | 验证码过期，当前实现中通常表现为 NOT_FOUND |
| `MISMATCH` | 验证码不匹配 |
| `TOO_MANY_ATTEMPTS` | 尝试次数过多 |

## 23. CodeSender

接口：

```text
CodeSender
```

当前实现：

```text
LoggingCodeSender
```

它不会真正发送短信或邮件，只会把验证码打印到日志：

```java
log.info("Send verification code scene={} identifier={} code={} expireMinutes={}", ...)
```

本地开发时，可以从控制台日志中看到验证码。

后续如果接入短信或邮件，只需要新增一个真正的 `CodeSender` 实现。

## 24. 三大流程总览

### 24.1 注册

```text
AuthService.register()
  ↓
VerificationService.verify(REGISTER)
  ↓
UserService.createUser()
  ↓
JwtService.issueTokenPair()
  ↓
RefreshTokenStore.storeToken()
```

### 24.2 登录

```text
AuthService.login()
  ↓
密码登录：PasswordEncoder.matches()
或
验证码登录：VerificationService.verify(LOGIN)
  ↓
JwtService.issueTokenPair()
  ↓
RefreshTokenStore.storeToken()
```

### 24.3 刷新

```text
AuthService.refresh()
  ↓
JwtService.decode(refreshToken)
  ↓
检查 token_type == refresh
  ↓
RefreshTokenStore.isTokenValid()
  ↓
JwtService.issueTokenPair()
  ↓
撤销旧 refresh token
  ↓
保存新 refresh token
```

### 24.4 重置密码

```text
AuthService.resetPassword()
  ↓
VerificationService.verify(RESET_PASSWORD)
  ↓
PasswordEncoder.encode()
  ↓
UserService.updatePassword()
  ↓
RefreshTokenStore.revokeAll()
```

## 25. 初学者总结

可以这样记：

```text
VerificationService 证明“你拥有这个手机号/邮箱”
AuthService 决定“你能不能登录、注册、刷新、重置密码”
JwtService 给你发“通行证”
RefreshTokenStore 决定“长期通行证是否还有效”
```

Access Token 像短期门票：

```text
15 分钟有效，用来访问接口
```

Refresh Token 像续票凭证：

```text
7 天有效，用来换新门票，但必须还在 Redis 白名单里
```

验证码像一次性证明：

```text
5 分钟有效，最多输错 5 次，成功后立即作废
```

## 26. 当前设计亮点

当前认证设计包含几个比较完整的安全点：

- 验证码发送间隔限制。
- 验证码每日发送上限。
- 验证码最大尝试次数限制。
- 验证码成功后立即失效。
- 密码使用 BCrypt 哈希保存。
- Access Token 有效期较短。
- Refresh Token 使用 Redis 白名单。
- Refresh Token 每次刷新后轮换。
- 登出可以撤销单个 Refresh Token。
- 重置密码后撤销该用户全部 Refresh Token。

## 27. 后续可优化点

可以考虑的优化：

1. Access Token 黑名单机制，用于强制立即下线。
2. 登录失败次数限制，防止密码暴力破解。
3. 真正接入短信或邮件发送器，替换 `LoggingCodeSender`。
4. `revokeAll` 当前使用 Redis `keys`，数据量大时可以考虑改成维护用户 token set 或使用 scan。
5. 验证码可考虑存 hash，而不是明文 code。
6. Refresh Token 轮换时可以加入复用检测，发现旧 token 被重复使用时撤销该用户全部会话。
