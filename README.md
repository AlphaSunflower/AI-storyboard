# AI-storyboard · Moon 云端系统（冻结仓）

AI 分镜 / 分镜创作 Web 系统：项目、分镜、画布、智能体会话、云端沙箱产物都在云端；
账号与模型接入统一由 LLM 网关承担。

> **仓库状态：冻结维护。** 只接受 P0 缺陷修复与安全相关改动，不再演进新功能。
> 主线开发在 [`moon-desktop`](https://github.com/AlphaSunflower/moon-desktop)（本地优先的桌面客户端），
> 本仓库继续服务 Web 站点，并作为客户端的参考实现。决策背景见
> [`docs/2026-10-22-desktop-local-first-plan.md`](docs/2026-10-22-desktop-local-first-plan.md)。

## 模块

| 模块 | 职责 | 端口 / 技术栈 |
|---|---|---|
| `AIStoryboardClient` | Web 前端（编辑器、会话、资产中心） | React 19 + Vite，开发 5173，生产由 nginx 托管 |
| `AIStoryboardBackend` | 身份与业务服务：登录 / 账号 / 项目 / 分镜 | 8082，Spring Boot 4 + Java 25 |
| `MoonAgent` | 智能体内核：AgentScope 运行时、SSE 回合、HITL、沙箱工具 | 8084 |
| `XiaoYueCanvas` | 画布后端：画布执行 / 批次、MCP bridge、资产同步 | 9090（`/api/canvas`） |
| `XiaoYueCanvasAgent` | 画布前端 | React + Vite，开发 3000 |
| `AILLMGateway` | 模型网关：chat / image / video 路由、渠道与密钥下沉 | 8083 |
| `ApiGateway` | 统一入口：JWT 校验、限流、按服务名转发 | 8080（Spring Cloud Gateway） |
| `MoonSandbox` | 沙箱执行服务：会话工作区、文件读写、脚本执行 | 与 sandbox-runner 容器配合 |
| `asset-service` / `asset-client` | 资产库服务与共享 HTTP 契约 | 8085 |
| `agent-core` | 智能体通用模型、存储、checkpoint 与生成服务 | 公共库 |
| `CommonCore` | 公共返回体 / 异常 / 工具 | 公共库 |
| `AStoryboardMobile` | 移动端（Expo / React Native） | 不参与 `deploy/stack` |
| `AStoryboardAndroid` | 移动端（Kotlin + Compose） | 不参与 `deploy/stack` |

根 `pom.xml` 是 Maven reactor，包含上表中的 Java 模块。

## 本地开发

前置：JDK 25、Maven 3.9+、Node.js 22、PostgreSQL 17、Redis、MinIO。

```bash
# 后端：按模块编译 / 测试（-am 会带上依赖模块）
mvn -pl MoonAgent -am test
mvn -pl AIStoryboardBackend -am test

# 前端
cd AIStoryboardClient
npm ci
npm run dev      # 开发态 5173
npm run build    # 产物
```

配置一律走 Spring Profile（`local` / `prod`），**不读取 `.env`**：
`application.yml` 放无密钥的共享配置，`application-local.yml`、`application-prod.yml`
由部署机各自维护，不入库。`*.yml.example` 是占位模板。

## 部署

| 方式 | 目录 | 说明 |
|---|---|---|
| 单 compose 一体化栈 | `deploy/stack/` | 一条 `docker-compose.yml` 拉起 Postgres / Redis / MinIO / 沙箱 / 6 个 Java 服务 / nginx，见其 README |
| 离线一体化包 | `deploy/allinone/` | 打包镜像与数据，可整包搬运 |
| 分服务镜像 | `deploy/docker/` | 各服务独立 Dockerfile（含沙箱镜像） |

## 文档

- `CLAUDE.md`：工程约定、现状与已知坑（改代码前先读）
- `PRODUCT.md`：产品定位与平台约束
- `docs/`：方案、设计、审计、架构图、截图
  - `docs/superpowers/specs/`：设计文档；`docs/superpowers/plans/`：实施计划
  - `docs/ai-models-and-providers.md`：模型与 Provider 配置清单（密钥已脱敏）
  - `docs/diagrams/`：架构图；`docs/audit/`：拓扑与审计报告；`docs/screenshots/`：界面截图
- CI：`.github/workflows/ci.yml`（后端矩阵 `mvn test` + 前端类型检查与构建）

## 安全

- 仓库内不放真实密钥、口令、内部渠道 ID；示例配置只保留占位符。
- 发现密钥泄露，先在厂商侧吊销 / 轮换，再清理仓库与历史。
