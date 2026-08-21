# Moon Agent 微服务拆分计划

## 整体拓扑

```
Nacos (8848) ◄── 四个服务注册
    │
    ├── API Gateway (8080) ── JWT 统一验签 + 路由/CORS
    │       │
    │       ├── /api/agent/**     → Agent 8084
    │       ├── /api/auth/**      → 主后端 8082
    │       ├── /api/projects/**  → 主后端 8082
    │       ├── /api/files/**     → 主后端 8082
    │       └── /api/internal/**  → 403 拒绝
    │
    ├── 主后端 (8082) ── 项目/分镜/资产/文件/登录
    │
    ├── Agent (8084) ── Moon 智能体编排/对话/生图/视频
    │
    └── LLM Gateway (8083) ── 模型代理（独立，不走 API Gateway）
```

## JWT 方案

- Gateway 唯一验签点，验通过后写 X-User-Id/X-User-Role/X-User-Status header
- 后端服务只读 header 拿 userId，不再持有 JWT secret
- 三个业务服务共享 jwt.access-secret（Nacos 配置下发）

## 服务间调用

- Agent → 主后端 /api/internal/**：直连，X-Internal-Token 鉴权
- 内部 API：6 个端点（project/scene/asset CRUD）
- Gateway 不路由 /api/internal/**，前端不可达

## Nacos 配置分层

- shared: jwt.access-secret, jwt.issuer, internal.secret
- ai-storyboard: server.port=8082, datasource
- moon-agent: server.port=8084, datasource, spring.ai.openai.*, storyboard.internal-url

## Agent 拥有 vs 回调

| Agent 拥有（独立 DB）       | 回调主后端（HTTP）     |
|---------------------------|----------------------|
| conversations/msgs/assets/checkpoints | Scene 增删查 |
| 编排器 + 6 IntentHandler   | Project 归属校验     |
| 意图/标题/优化/回答（LLM 网关）| Asset 列表/关联      |
| 生图/视频（LLM 网关）       | —                    |
| 自有 uploads/             | —                    |

## 实施顺序

1. ✅ Nacos Server 启动（Docker）
2. API Gateway 模块（Spring Cloud Gateway + JWT 验签 + 路由 + Nacos）
3. 主后端改造（接 Nacos + 内部 API 6 端点 + Header Filter）
4. Agent 骨架（pom + Nacos + DB + Header Filter）
5. 搬 Agent 代码（entity/mapper/service/handler + AI 服务复制）
6. 前端改 base URL（统一走 Gateway 8080）
7. 联调测试

## 分支策略

- 分支名：`feature/moon-agent-microservice`
- 基于 master 创建
