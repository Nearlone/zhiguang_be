# 知光项目前后端联调 API 文档

本目录用于 Apifox 导入和本地联调。

## 文件

- `zhiguang-openapi.yaml`：OpenAPI 3.0.3 文档，可直接导入 Apifox。

## Apifox 导入方式

1. 打开 Apifox。
2. 新建项目或进入已有项目。
3. 选择「导入」。
4. 选择「OpenAPI / Swagger」。
5. 选择本目录下的 `zhiguang-openapi.yaml`。
6. 导入后，将环境服务地址设为：

```text
http://localhost:8080
```

## 建议配置的环境变量

```text
accessToken=
refreshToken=
userId=100
targetUserId=101
postId=1950000000000000000
```

登录或注册成功后，把响应里的：

- `token.accessToken` 填入 `accessToken`
- `token.refreshToken` 填入 `refreshToken`
- `user.id` 填入 `userId`

鉴权接口请求头使用：

```text
Authorization: Bearer {{accessToken}}
```

认证白名单接口不要带 `Authorization` 请求头，包括：

- `POST /api/v1/auth/send-code`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/token/refresh`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/password/reset`

如果这些接口返回 401，优先检查 Apifox 是否在项目、分组、接口或环境层面继承了 Bearer Token。清空 `Authorization` 请求头或将接口鉴权方式改成「无需认证」即可。

## 本地验证码获取

发送验证码后，本地开发环境通常会在后端日志打印验证码。

也可以从 Redis 读取，例如注册手机号 `13800000001`：

```bash
docker exec zhiguang-redis redis-cli HGET auth:code:REGISTER:13800000001 code
```

登录验证码：

```bash
docker exec zhiguang-redis redis-cli HGET auth:code:LOGIN:13800000001 code
```

重置密码验证码：

```bash
docker exec zhiguang-redis redis-cli HGET auth:code:RESET_PASSWORD:13800000001 code
```

## 推荐联调顺序

1. `GET /actuator/health`：确认后端启动。
2. `POST /api/v1/auth/send-code`：发送注册验证码。
3. `POST /api/v1/auth/register`：注册并拿到 token。
4. `GET /api/v1/auth/me`：验证 Bearer Token 是否生效。
5. `PATCH /api/v1/profile`：更新个人资料。
6. 再注册一个用户 B，记录 `targetUserId`。
7. `POST /api/v1/relation/follow`：测试关注。
8. `GET /api/v1/relation/status`：测试关系状态。
9. `POST /api/v1/knowposts/drafts`：创建草稿，记录 `postId`。
10. `POST /api/v1/storage/presign`：获取正文或图片直传 URL。
11. `POST /api/v1/knowposts/{id}/content/confirm`：确认正文上传。
12. `PATCH /api/v1/knowposts/{id}`：补全标题、标签、简介、封面。
13. `POST /api/v1/knowposts/{id}/publish`：发布。
14. `GET /api/v1/knowposts/feed`：检查首页 Feed。
15. `POST /api/v1/action/like`、`POST /api/v1/action/fav`：测试点赞收藏。
16. `GET /api/v1/counter/{etype}/{eid}`：检查计数。
17. `GET /api/v1/search`、`GET /api/v1/search/suggest`：测试搜索。
18. `POST /api/v1/knowposts/description/suggest`：测试 AI 简介。
19. `GET /api/v1/knowposts/{id}/qa/stream`：测试 RAG 流式问答。

## 注意

- `GET /api/v1/knowposts/feed`、`GET /api/v1/knowposts/detail/{id}`、`GET /api/v1/knowposts/{id}/qa/stream` 是公开接口。
- `GET /api/v1/search` 和 `GET /api/v1/search/suggest` 当前源码安全配置下需要登录。
- OSS、AI、RAG 相关接口依赖外部配置。如果没有真实 OSS、DeepSeek、DashScope key，相关接口可能只能走到参数校验或本地占位错误。
