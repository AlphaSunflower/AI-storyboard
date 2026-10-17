# 开发环境（依赖在 Docker，代码在本机热更新）

两种模式，随时切换：

| 模式 | 起什么 | 命令 |
|---|---|---|
| **开发模式** | Docker 只跑依赖（Postgres/Redis/MinIO/沙箱），业务代码在本机改、本机跑 | `powershell -File scripts\dev-up.ps1` |
| 部署模式 | 整套 11 个容器（业务也在容器里） | `powershell -File scripts\up.ps1` |

退出开发模式：`scripts\dev-down.ps1`（停依赖）；`scripts\dev-down.ps1 -FullStack`（直接恢复整套部署）。

## 一、开发模式下的依赖端口

`dev-up.ps1` 会**停掉容器里的业务服务**（避免和本机跑的同名服务抢定时任务/双写），只保留：

| 依赖 | 地址 | 说明 |
|---|---|---|
| PostgreSQL | `localhost:5432`（本机原生库，开发默认） / `127.0.0.1:5433`（容器库，交付数据） | 见下方「数据库怎么选」 |
| Redis | `127.0.0.1:6379` | 缓存/限流/会话锁 |
| MinIO | `127.0.0.1:9000`（API）/ `9001`（控制台 `minioadmin` / `minioadmin123`） | 对象目录就是 `deploy/docker/minio/data` |
| MoonSandbox | `127.0.0.1:8090` | 密钥 `local-moon-sandbox-secret` |

没有 Nacos：服务发现默认关闭（`NACOS_DISCOVERY_ENABLED=false`），ApiGateway 走直连默认值
（`http://localhost:8082/8085/9090/8084`），本机起服务不用传任何参数。

也没有画布 Bridge 进程：画布命令信箱在 canvas 服务内（`/api/canvas/bridge/**`），
浏览器打开画布后按同源 `/api/canvas` 轮询领取命令，本机开发无需额外进程或端口。

## 二、本机启动业务服务

六个后端都是 Spring Boot，默认 `spring.profiles.active: local`，直接连上面这些依赖：

```powershell
mvn -pl ApiGateway          spring-boot:run   # :8080 统一网关（JWT 验签 → 透传 X-User-Id）
mvn -pl AIStoryboardBackend spring-boot:run   # :8082 主后端
mvn -pl AILLMGateway        spring-boot:run   # :8083 模型网关（/v1、/admin-ui）
mvn -pl MoonAgent           spring-boot:run   # :8084 智能体（SSE）
mvn -pl asset-service       spring-boot:run   # :8085 资产库
mvn -pl XiaoYueCanvas       spring-boot:run   # :9090 画布后端（/api/canvas）
```

用 IDE 直接跑主类也可以：`StoryboardApplication` / `LLMGatewayApplication` / `ApiGatewayApplication` /
`MoonAgentApplication` / `AssetApplication` / `XiaoYueCanvasApplication`。

前端（Vite 热更新）：

```powershell
cd AIStoryboardClient && npm run dev     # http://localhost:5173（/api 代理到 8080）
cd XiaoYueCanvasAgent  && npm run dev    # http://localhost:3000/canvas/
```

## 三、数据库怎么选（两种都行）

| 选哪个 | 连接 | 数据 | 用法 |
|---|---|---|---|
| 本机原生 PG18（**默认**） | `localhost:5432` | 开发库：146 个账号 / 322 会话（含各种 e2e 测试账号） | 各服务 `application-local.yml` 就是这个，什么都不用改 |
| 容器 PG | `localhost:5433` | 交付库：只有 `admin@qq.com` / `123456` + 250 会话 | 启动时加环境变量覆盖：<br>`$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/ai_storyboard'` |

两者账号都是 `postgres` / `123456`。

## 四、常见开关

- **Agent 用容器沙箱**（默认关）：`$env:AGENT_SANDBOX_ENABLED='true'; $env:MOON_SANDBOX_INTERNAL_SECRET='local-moon-sandbox-secret'`
- **画布执行命令**：由本机起着的 `XiaoYueCanvas` 提供信箱端点，浏览器打开画布即自动注册；
  没有独立 Bridge 进程、没有 8787 端口、没有桥接令牌需要对齐。
- **切到交付库做联调**：只覆盖 `SPRING_DATASOURCE_URL` 即可，Redis/MinIO 不用动。

## 五、注意

1. 开发模式期间**不要**再 `up.ps1`：容器里的同名服务会和本机进程抢同一批定时任务（视频队列、Agent 恢复协调器）。
2. 改代码不需要重建镜像；只有要出交付包才需要：
   `build-apps.ps1` → `build-base.ps1 -UseHostNetwork`（仅首次/依赖变更）→ `build-images.ps1` → `save-images.ps1` → `export-usb.ps1`。
3. 本机原生 PG 是开发库，容器 PG 是交付库；写数据前先确认自己连的是哪一个（`SPRING_DATASOURCE_URL`）。
