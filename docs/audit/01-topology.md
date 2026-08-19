# 系统拓扑与接口清单（审计/E2E 共用事实源）

> 生成于 2026-08-19，分支 feature/moon-agent-microservice（微服务迁移后现状）。

## 模块拓扑

```
                    ┌─────────────┐   HTTPS/HTTP    ┌──────────────────┐
 浏览器(5173 Vite)──┤  ApiGateway  ├───────────────►│  AILLMGateway    │  :8083  LLM 网关
 (dev proxy→8080)   │  :8080       │  /v1/**,/admin  │  (OpenAI 兼容)    │  key_hash 比对 / admin JWT
                    │  Spring Cloud│                └──────────────────┘
                    │  Gateway     │
                    └──┬───────┬───┘
        /api/agent/**   │       │  /api/auth|projects|scenes|files|assets|ai|user/**
        (lb://moon-agent│       │  (lb://ai-storyboard)
        ┌───────────────▼──┐  ┌─▼──────────────────┐
        │ MoonAgent :8084  │  │ AIStoryboardBackend │ :8082  主后端
        │ 智能体微服务      │  │ (JWT/X-User-Id)      │
        │ X-User-Id 信任   │  │ /api/internal/** ←───┼── X-Internal-Token 共享密钥
        └────────┬─────────┘  └────────────────────┘
                 │ 内部调用(带 X-Internal-Token)
                 └───────────────────────────────────► /api/internal/**（仅 MoonAgent 可达）
```

- 服务发现：Nacos `:8848`（ApiGateway `lb://` 路由依赖；NACOS_ADDR 可配）
- 鉴权链：登录 → 主后端签发 JWT（HS256，issuer/typ/role/status 四重校验）→ 前端带 Bearer → **ApiGateway 统一验签** → 改写为 `X-User-Id/X-User-Role/X-User-Status` 头 → 下游服务信任该头（MoonAgent 完全信任；主后端优先信任、无头时回退 JWT 直签）
- 数据库：`newworkflow`（主业务 + agent 会话三表）、`llm_gateway`（通道/路由/model_params/api_key 等）
- `/api/internal/**`：ApiGateway 层 403 屏蔽外部访问，仅内部服务经 `X-Internal-Token` 头调用

## 端口表

| 端口 | 服务 | 鉴权 |
|---|---|---|
| 8080 | ApiGateway | JWT 统一验签；白名单 `/api/auth/` `/api/files/` `/actuator/` |
| 8082 | AIStoryboardBackend | JWT（白名单 auth/files/internal/actuator-health） |
| 8083 | AILLMGateway | `/v1/**` Bearer key_hash；`/admin/**` admin JWT（/admin/login 放行） |
| 8084 | MoonAgent | X-User-Id 头信任（须经网关） |
| 5173 | AIStoryboardClient | —（dev proxy → 8080） |

## 端点清单（E2E 矩阵基准）

### AIStoryboardBackend（42 个映射）

`/api/auth`: POST login / register / refresh / unlogin（无鉴权）
`/api/projects`: GET list / POST create / GET draft / GET {id} / PUT {id} / DELETE {id}
`/api`: POST /projects/{projectId}/scenes；PUT /scenes/{id}；DELETE /scenes/{id}；GET /scenes/{id}/references；POST /scenes/{id}/references（multipart type+purpose+file）；DELETE /scenes/references/{referenceId}
`/api/assets`: POST /assets / GET /assets?projectId&type / PUT /assets/{id} / DELETE /assets/{id} / POST /assets/{id}/images / DELETE /assets/{id}/images/{imageId} / PUT /scenes/{id}/assets / GET /scenes/{id}/assets
`/api/files`: POST /upload；GET /images/{filename}；GET /videos/{filename}；GET /audios/{filename}（无鉴权）
`/api/user`: GET /profile / PUT /profile / PUT /password / GET /stats
`/api/ai`: POST /generate-script /generate-image /generate-video；GET /task/{taskId} /models
`/api/internal`（X-Internal-Token，网关屏蔽外部）: GET /projects/{id}；GET /projects/{id}/scenes；POST /scenes/batch；DELETE /scenes/project/{projectId}；GET /scenes/{sceneId}/assets；POST /scenes/{sceneId}/assets；GET /scenes/{sceneId}；PUT /scenes/{sceneId}；PATCH /scenes/project/{projectId}/params；GET /projects/{projectId}/assets；GET /scenes/by-video-task/{videoTaskId}

### MoonAgent（17 个映射，均需鉴权）

POST /conversations；GET /conversations?projectId=；GET /conversations/{id}；DELETE /conversations/{id}；DELETE /conversations/{id}/messages；GET /conversations/{id}/messages；POST /conversations/{id}/messages；GET /conversations/{id}/assets?page&size；POST /upload（multipart file+conversationId?）；POST /prompt/optimize；POST /conversations/{id}/confirm-done；POST /conversations/{id}/messages/stream（SSE）；POST /conversations/{id}/form/submit（SSE）；POST /conversations/{id}/video/plan/generate（SSE）；GET /tasks/{taskId}；PATCH /conversations/{id}；DELETE /assets/{id}

### AILLMGateway（27 个映射）

`/v1`（StaticApiKeyFilter）: POST /chat/completions；POST /images/generations；POST /images/edits；GET /models；POST /videos；GET /videos/{taskId}
`/admin`（AdminJwtFilter，/admin/login 放行）: POST /login；GET /stats/overview；GET /channels/{id}/models；POST /channels/{id}/test；POST /routes/{id}/test；PUT /model-params；GET /model-params/{modelName}
`/admin/channels`: POST / GET / PUT/{id} / DELETE/{id}
`/admin/routes`: POST / GET / PUT/{id} / DELETE/{id}
`/admin/api-keys`: POST / GET / PUT/{id} / DELETE/{id}
`/admin/users`: GET / POST / PUT/{id} / DELETE/{id}
`/admin/config`: GET / PUT
`/admin/call-logs`: GET
`/admin-ui`（静态页，不经 AdminJwtFilter）: GET /admin-ui

### ApiGateway

路由表见 `ApiGateway/src/main/resources/application.yaml`：`/api/agent/**`→moon-agent；`/api/auth|projects|scenes|files|assets|ai|user/**`→ai-storyboard；`/actuator/**`→本地。
