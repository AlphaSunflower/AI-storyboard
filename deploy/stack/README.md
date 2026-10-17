# deploy/stack — AI-storyboard 统一部署栈

一个 compose 项目拉起完整业务栈：Postgres / Redis / MinIO / MoonSandbox + 6 个 Java 服务
（api-gateway、backend、llm-gateway、moon-agent、asset-service、canvas）+ 前端入口（主站 `/` 与画布站 `/canvas/`）。

栈内不含 Nacos：服务发现默认关闭（`NACOS_DISCOVERY_ENABLED=false`），ApiGateway 按 `.env` 的
`BACKEND_URI` / `ASSET_SERVICE_URI` / `CANVAS_URI` / `MOON_AGENT_URI` 直连四个上游服务。

访问入口：`http://localhost/`，默认账号 `admin@qq.com` / `123456`。

## 目录

| 路径 | 说明 |
|------|------|
| `docker-compose.yml` | 统一栈定义（服务、端口、卷、网络） |
| `.env` / `.env.example` | 端口与全部密钥（`.env` 不入库） |
| `base/Dockerfile` | 共享 Java 基座（temurin 25 + curl/tzdata，可选 ffmpeg） |
| `service/Dockerfile` | 通用 JRE 运行时（JDK 25），参数化端口与 ffmpeg |
| `frontend/Dockerfile` | nginx 静态站 + `/api` 反代 |
| `nginx/default.conf` | 入口路由契约 |
| `db/` | 首启建库 + 还原转储 + 账号收敛脚本 |
| `scripts/` | 构建、预拉、启停、验收、离线打包脚本 |
| `build/` `offline/` | 构建暂存与离线镜像包（不入库） |

## 首次部署（本机）

```powershell
cd deploy\stack
powershell -File scripts\pull-base.ps1      # 预拉基础镜像，后续构建不再等下载
powershell -File scripts\dump-db.ps1        # 从本机原生 PG18 导出 ai_storyboard / llm_gateway + 生成 admin 密码哈希
powershell -File scripts\build-apps.ps1     # mvn package + 两个前端 npm build，暂存到 build/
powershell -File scripts\build-base.ps1 -UseHostNetwork   # 构建共享 Java 基座（国内镜像源 + host 网络最稳）
powershell -File scripts\build-images.ps1   # 打应用镜像
powershell -File scripts\up.ps1             # 接管旧 infra compose 项目并启动统一栈
powershell -File scripts\verify.ps1         # 端到端验收（含一次真实模型调用）
```

`up.ps1` 会先停掉 `deploy/docker/{nacos,vosk,redis,minio,sandbox}` 旧项目（保留数据卷与 MinIO 宿主数据；
老的 `nacos-standalone` 容器也会被一并清掉，正好释放它占的内存），再按同名容器与端口启动统一栈。
已属于本项目的容器/网络会被识别并保留，重复执行 `up.ps1` 不会重启它们。
**不要**对任何项目执行 `down -v`。

## 日常

```powershell
powershell -File scripts\down.ps1                 # 停止（保留数据卷）
docker compose --env-file .env ps                 # 查看状态
docker compose --env-file .env logs -f backend    # 看日志
```

## 模型网关（LLM Gateway）访问

网关监听 `0.0.0.0:8083`，已发布到宿主机 `127.0.0.1:8083`（只绑本机回环，需要局域网访问就改 compose 里这条 ports）。

| 用途 | 地址 | 鉴权 |
|------|------|------|
| OpenAI 兼容 API | `http://127.0.0.1:8083/v1/...`（chat/completions、images/generations、images/edits、models、videos） | `Authorization: Bearer <网关 Key>`（见 `llm_gateway.gateway_api_key` 表；业务侧用的是 `.env` 的 `LLM_GATEWAY_API_KEY_AGENT` / `_MAIN`） |
| 管理后台静态页 | `http://127.0.0.1:8083/admin-ui/` | 页面内登录 |
| 管理 API | `http://127.0.0.1:8083/admin/**`（channels / routes / api-keys / users / config / call-logs / stats） | `POST /admin/login` 取 JWT 后带 `Authorization: Bearer <adminToken>` |

管理后台账号沿用 `llm_gateway.admin_user` 表中的记录（`admin`、`e2eadmin` 等，均 role=admin）；
`admin` 的密码就是 `.env` 里的 `GATEWAY_ADMIN_INIT_PASSWORD`。`AdminInitRunner` 仅在 `admin_user` 为空时自举，
已有记录不会覆盖。

示例：

```powershell
curl.exe -s http://127.0.0.1:8083/v1/models -H "Authorization: Bearer <网关Key>"
curl.exe -s -X POST http://127.0.0.1:8083/admin/login -H "Content-Type: application/json" -d '{\"username\":\"admin\",\"password\":\"<密码>\"}'
```

## MCP（Streamable HTTP）

栈内有 2 条 MCP 链路，MoonAgent 作为 MCP 客户端连接：

| MCP server | 地址 | 鉴权 | 提供的工具 |
|---|---|---|---|
| `llm-gateway-media` | `http://llm-gateway:8083/mcp`（宿主机 `http://127.0.0.1:8083/mcp`） | `Authorization: Bearer <网关Key>` | `generate_image`、`generate_video` |
| `xiaoyue-canvas`（连接名 `canvas-server`） | `http://canvas:9090/api/canvas/mcp`（经入口是 `http://localhost/api/canvas/mcp`） | `X-Internal-Token: <INTERNAL_SECRET>`；经 nginx 入口时还需带业务 JWT（网关先验签） | `canvas_graph_*` 系列（读取/建节点/连线/导入素材/布局等）+ `canvas_execute_node` / `canvas_execute_graph` / `canvas_get_execution` / `canvas_cancel_execution` |

MoonAgent 侧用这些环境变量指向它们（`.env` 已配好）：

- `MORROW_CANVAS_SERVER_URL=http://canvas:9090` —— **必须指向 canvas 服务**，写成 `localhost:9090` 会连到容器自身；连不上时
  `CanvasMcpConnectionManager` 会每 2 秒重试并刷 `Registered tool 'canvas_create_run' …` 日志。
- `LLM_GATEWAY_BASE_URL=http://llm-gateway:8083` —— 网关 MCP 的地址（同主机同端口时客户端会自动补 `Bearer`）。

连上后 moon-agent 日志会出现：

```
[MCP] 已注册远程工具: canvas=true, media=true
```

外部客户端直连示例（初始化 + tools/list，需要 `Mcp-Session-Id` 回传）：

```powershell
curl.exe -s -D - -X POST http://127.0.0.1:8083/mcp -H "Authorization: Bearer <网关Key>" `
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" `
  -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"probe\",\"version\":\"0\"}}}'
```

注意：canvas/media 工具注册只依赖 canvas 服务与网关；`canvas_execute_*` 还需要浏览器打开目标画布才会真正执行。

### 画布命令信箱（在 canvas 服务内，无独立进程）

浏览器要执行的画布命令由 canvas 服务内的信箱转发，不再有独立的 `canvas-bridge` 容器、8787 端口或桥接令牌：

- 浏览器侧：画布页面按**同源**相对路径 `/api/canvas/bridge/{health,register,poll,result,unregister}` 轮询，
  身份走正常登录态（nginx → 网关验签 → 注入 `X-User-Id`），因此换机器访问画布页面无需重新构建前端。
- 服务端：4 个 `canvas_execute_*` / `canvas_get_execution` 工具挂在 `/api/canvas/mcp` 上，调用时投递命令并阻塞等待浏览器回传。
- 归属与时限：标签按「用户 + 项目 + 画布」登记；命令超时 11 分钟，浏览器心跳 30 秒，标签 15 分钟失活。
- 强制预览：删除类与执行类命令由服务端强制 dry-run，调用方无法用 `dryRun=false` 绕过。

启动后 moon-agent 工具数稳定为 **84**（80 + 4 个 execute 工具），日志里可见
`[MCP] 已注册远程工具: canvas=true, media=true`。

### Skill 与工具装载

`AGENTSCOPE_NACOS_ENABLED=false` 时按设计走 classpath：`runtime.skills=classpath`、`runtime.mcpMetadata.source=classpath`
（见 `GET /api/agent/conversations/{id}/runtime`）。容器实测注册 84 个工具，覆盖 6 个 skill
（`canvas-design` 17 个、`canvas-run` 21 个、`general` 15 个、`storyboard` 18 个、`image` 5 个、`video` 3 个）声明的
全部 `allowed-tools`——4 个 `canvas_execute_*` / `canvas_get_execution` 现在由 canvas 服务提供，
不再依赖"跑浏览器的机器上另起一个 Bridge"。

## 实现要点（排障时先看这里）

- **静态 IP**：应用网 `aistoryboard` 固定为 `172.28.0.0/24`，各服务分配固定地址（postgres .10 / redis .11 /
  .12 空位 / minio .13 / moon-agent .14 / backend .15 / llm-gateway .16 / asset-service .17 / canvas .18 /
  api-gateway .19 / frontend .20）。容器重建后地址不变，避免 JVM 连接池、nginx 上游缓存里的旧地址失效。
- **直连路由**：`NACOS_DISCOVERY_ENABLED=false` 时网关按 `BACKEND_URI` / `ASSET_SERVICE_URI` /
  `CANVAS_URI` / `MOON_AGENT_URI` 直连（compose 内是服务名，本机开发是 `localhost:<端口>`）。
  地址解析依赖 compose 内建 DNS；moon-agent 同时接入 `moon-sandbox-edge`，但直连走服务名
  `moon-agent`，不会落到沙箱网地址。
- **恢复服务发现**：`.env` 里 `NACOS_DISCOVERY_ENABLED=true`，四个 `*_URI` 改成 `lb://<服务名>`，
  再单独起 `deploy/docker/nacos`；此时 moon-agent 还需要 `SPRING_CLOUD_NACOS_DISCOVERY_IP`
  （= `MOON_AGENT_REGISTER_IP`），否则可能注册成沙箱网 IP。
- **健康检查**：应用镜像统一用 TCP 端口探活（`/dev/tcp`）。api-gateway 未暴露 actuator，canvas 的
  actuator 需要鉴权，用 HTTP 探活会误判。
- **Nacos 已不在栈内**：统一栈不构建、不启动 Nacos，`nacos-data` 卷已移除；旧的
  `deploy/docker/nacos` 仅作为需要恢复服务发现时的备用旧栈保留。
- **沙箱代理 CRLF**：`deploy/docker/sandbox/proxy/entrypoint.sh` 在 Windows 检出时是 CRLF，
  Dockerfile 内已 `sed -i 's/\r$//'` 修正，否则 `exec ... no such file or directory`。
- **基础镜像构建**：基座 Dockerfile 默认把 apt 源替换为 `mirrors.aliyun.com` 并加重试；
  国内网络下建议 `-UseHostNetwork` 构建（BuildKit 沙箱下载大包容易超时）。
- **`WITH_FFMPEG` 大小写**：`build-base.ps1` 传的是 PowerShell 布尔（`True/False`），基座 Dockerfile 现在做小写归一化后再比较；
  否则 moon-agent 镜像里不会有 `ffmpeg/ffprobe`（视频拼接会失败）。核对：
  `docker exec aistoryboard-moon-agent sh -c 'command -v ffmpeg; command -v ffprobe'`。
- **`$HOME` 与 Nacos 日志目录**：moon-agent 仍带 nacos-client（AgentScope 的 Skill/提示词资产扩展），
  其 logback 会往 `$HOME/logs/nacos` 写文件，镜像里保留 `/home/app/logs/nacos` 与 `HOME=/home/app`，
  否则启动会刷 `FileNotFoundException` 报错（不影响功能，但很吵）。
- **SPA 回退不要用 `$uri/`**：`AIStoryboardClient/dist` 里有 `login/`（放 `hero-bg.mp4`）等静态资源目录，
  `try_files $uri $uri/ /index.html` 会让 `/login/` 命中目录且无 index → 403。现在统一写成 `try_files $uri /index.html`。
- **画布子站的「执行服务」= 内嵌 Moon 对话**：`MoonChatWindow` 用 iframe 指向 `${VITE_MOON_CHAT_URL}/chat?embed=1`。
  该变量未配置时开发环境回退 `http://localhost:5173`、生产走同源相对路径（`/chat?embed=1`，由同一个 nginx 托管）。
  若构建时把 5173 写死，生产下面板会一直空白（iframe 指向未启动的 dev server）。
- **画布命令信箱**：浏览器端同源轮询 `/api/canvas/bridge/poll`，命令由 canvas 服务投递；没有独立进程、端口或令牌。
  画布面板自身的任务/状态同样走后端 `/api/canvas/**`，两者共用同一条 nginx → 网关联路。
- **改 nginx 配置后要重新暂存**：`frontend` 镜像用的是 `build/frontend/default.conf`（由 `build-apps.ps1` 从
  `nginx/default.conf` 拷贝）。只改源文件不重跑 `build-apps.ps1` 的话镜像里还是旧配置。

## 数据与账号

- `dump-db.ps1` 导出两个库到 `db/*.dump`，首启时由 postgres 容器的 `docker-entrypoint-initdb.d` 自动建库并还原。
- `db/sql/post-migrate.sql` 把 `771038325@qq.com` 改名为 `admin@qq.com`（保留 id 与全部项目/会话/分镜），
  密码重置为 `123456`，并清除其它测试账号及其数据（含 asset schema 的 library_* 归属数据）。
- `db/sql/seed-models.sql`（由 `04-seed-models.sh` 在还原后执行，幂等）补齐额外模型路由，
  当前包含 `doubao-seedream-5-0-flash-260915` → 豆包渠道（`type=image`，并写入 `model_params` 与 `channel.models`）。
  该模型是**单图模型**：方舟不接受 `sequential_image_generation`（连 `disabled` 都 400）、
  `sequential_image_generation_options` 和 `stream`，尺寸档位只有 1K/1.5K/2K，参考图最多 10 张，
  所以 `model_params` 收敛为 `n_max=1` + `sizes=1K,1.5K,2K` + `ref_images_max=10`；
  后端另有 `SeedreamImageProtocol` 按模型裁剪参数并对非流式响应走 JSON 分支（5.0 pro/flash 同理）。
  追加模型只要往这个文件里加一段同样的 `DO $$` 块即可；对运行中的栈也能重复执行：

  ```powershell
  docker cp db\sql\seed-models.sql aistoryboard-postgres:/tmp/seed-models.sql
  docker exec aistoryboard-postgres psql -v ON_ERROR_STOP=1 -U postgres -d llm_gateway -f /tmp/seed-models.sql
  ```

  注意：画布/网关在选到新模型时也会自动写 `model_route`，所以线上条数只增不减，验收断言按“不低于基线”判定。
- MinIO 直接挂载宿主目录 `deploy/docker/minio/data`，对象数据原样复用，不做筛选与清理。
- 还原失败时 `02-restore.sh` 会打印 `/tmp/restore-<db>.log` 末尾日志并不阻断启动（表数为 0 才报错）。

## 验收项（scripts/verify.ps1）

compose 健康状态、主站/画布站 200、`admin@qq.com` 登录（含 refresh cookie）、`/api/projects` 13 个项目、
账号收敛=1、会话 250 / 消息 / 分镜 83、网关渠道 12/路由 20/Key 10、MinIO 桶对象、历史图片经 `/api/files/**` 签名读取、
Agent 会话经网关可达 250 条、沙箱 Python 回声、真实模型调用（网关渠道密钥）、Agent SSE 流式对话 `turn_completed`。

## 迁移到另一台机器（U 盘 / scp）

```powershell
powershell -File scripts\save-images.ps1    # offline/images-app-<tag>.tar、images-base-<tag>.tar、manifest.txt、sha256.txt
```

目标机需要拷贝：整个 `deploy/stack/`（含 `db/*.dump`、`.env`、`offline/`）以及
`deploy/docker/minio/data/`（对象数据）。之后在目标机执行：

```bash
bash scripts/load-and-up.sh
```

目标机无需联网、无需 Maven/Node，`docker load` 后直接 `up`。
若以后要在目标机**从源码重建**，还需要带上 `eclipse-temurin:25-jre-noble`、`node:22-alpine`、`ubuntu:24.04`、
`ubuntu/squid` 这些构建期基础镜像（见 `offline/base-images.txt`）。

## 端口

| 端口 | 服务 |
|------|------|
| 80 | 前端入口（主站 + `/canvas/` + `/api`） |
| 127.0.0.1:9000 / 9001 | MinIO API / 控制台 |
| 127.0.0.1:6379 | Redis |
| 127.0.0.1:5433 | Postgres（避开本机原生 PG 的 5432） |
| 127.0.0.1:8090 | MoonSandbox runner |

6 个 Java 服务仅在 compose 网络内暴露（8080/8082/8083/8084/8085/9090）。

## 已知限制

- 本次不含 vosk 语音识别（前端麦克风入口不可用）与 `AStoryboardMobile`；需要时可复用 `deploy/docker/vosk`。
- 迁移时未筛选 MinIO 对象，已删除测试账号留下的孤儿对象仍在桶里（`orphan-scan-enabled` 保持 false，不会被自动清理）。
- 画布服务启动时会尝试同步历史画布资产，若对应对象在 MinIO 不存在会打印 404 警告（开发环境遗留），不影响功能。
