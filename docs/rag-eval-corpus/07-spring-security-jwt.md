# Spring Security JWT 资源服务器

> 整理来源：[Spring Security Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)

## Bearer Token 进入过滤链

客户端访问受保护接口时，在请求头携带 `Authorization: Bearer <token>`。Spring Security 的 Bearer Token 过滤器提取令牌，创建认证请求，再交给认证管理组件处理。

JWT 认证成功后，认证结果会放入 `SecurityContextHolder`，后续 Controller 可以通过 `@AuthenticationPrincipal Jwt` 读取主体和 claim。失败时应清理安全上下文并返回 401，而不是继续进入业务方法。

## JwtDecoder 的职责

`JwtDecoder` 负责解码、验证签名和校验令牌。使用非对称签名时，资源服务器保存公钥或从 JWK Set 地址获取公钥，不需要持有签发令牌的私钥。

常见校验包括：

- 签名是否来自可信密钥；
- `exp` 是否已过期；
- `nbf` 是否已经生效；
- `iss` 是否为预期签发者；
- `aud` 是否包含当前资源服务器。

只把 JWT Base64 解码出来并读取用户 ID 不等于完成认证，必须先验证签名和关键 claim。

## 权限转换

JWT 中的 `scope` 或 `scp` 可以转换成 Spring Security 权限，默认常带有 `SCOPE_` 前缀。例如 scope 为 `message:read`，授权判断时使用 `SCOPE_message:read`。

项目使用自定义角色或权限字段时，可以提供 `JwtAuthenticationConverter`。转换逻辑必须处理字段缺失、类型错误和意外值，不能让任意字符串自动获得管理员权限。

## 公钥配置方式

资源服务器可以通过 `issuer-uri` 自动发现认证服务元数据和 JWK Set，也可以直接配置 `jwk-set-uri`，还可以指定本地 RSA 公钥。直接配置 JWK Set 可以减少启动时对认证服务发现接口的依赖，但仍需校验 issuer，避免接受其他签发者的令牌。

公钥可以缓存，认证服务器轮换密钥时资源服务器需要及时刷新。网络超时、缓存时间和密钥轮换都应纳入可观测性。

## Access Token 与 Refresh Token

Access Token 用于访问业务接口，通常有效期较短。Refresh Token 用于换取新的令牌对，有效期更长，不应作为普通 Bearer Token 访问业务接口。

JWT 本身是无状态的，但强制登出和 Refresh Token 轮换需要服务端状态。常见做法是在 Redis 保存 Refresh Token 的 `jti` 白名单，刷新时撤销旧令牌并写入新令牌，登出时删除白名单记录。

## 安全边界

令牌不应写入 URL、普通日志或前端错误提示。HTTPS 用于防止传输过程中被窃取；短有效期降低 Access Token 泄露后的影响；权限检查仍要在服务端完成，不能只依赖前端隐藏按钮。
