# deploy/stack — AI-storyboard 一体化部署栈（单 compose 文件）

一个 `docker-compose.yml` 拉起完整业务栈，数据（PostgreSQL / MinIO / Agent 沙箱）随目录一起迁移：

- 基础设施：Postgres 18、Redis 7、MinIO
- Agent 沙箱：`moon-sandbox-runner` + Squid 出网代理（常驻，不再是可选 profile）
- 6 个 Java 服务：api-gateway(8080)、backend(8082)、llm-gateway(8083)、moon-agent(8084)、asset-service(8085)、canvas(9090)
- 入口：nginx 主站 `/` + 画布站 `/canvas/` + `/api` 反代

**入口** `http://<IP>/` · **初始账号** `admin@qq.com` / `123456`（数据库已收敛为仅此一个账号）

栈内不含 Nacos：服务发现默认关闭（`NACOS_DISCOVERY_ENABLED=false`），ApiGateway 按服务名直连上游。
不含 vosk 与移动端。

## 交付包数据（干净部署）

| 数据 | 位置 | 当前内容 |
|------|------|----------|
| 业务库 `ai_storyboard` + 网关库 `llm_gateway` | `db/*.dump`（首启自动还原） | 业务库仅 1 个账号 `admin@qq.com`；项目 / 会话 / 消息 / 分镜 / 资产均为 **0**。网关库保留 12 渠道 / 26 路由 / 10 Key |
| MinIO 对象 | `data/minio/`（宿主目录挂载） | **空桶**（保留 `storyboard` 桶元数据），0 个对象 |
| Agent 沙箱工作区 | `data/sandbox-workspaces/`（首次播种进命名卷） | 空（用户数据已清） |
| Agent 技能包 | 沙箱镜像 `/opt/moon-skills/ppt-master` | **保留**，13,018 个文件；按会话按需物化 |
| 账号 | `db/sql/post-migrate.sql` | `admin@qq.com` / `123456`，role=admin，仅此一个 |

> 开发环境（宿主 PostgreSQL 5432、`deploy/docker/minio/data`、开发沙箱卷）**未参与清理**，保持原样。
> 这个包是「干净部署」：不带任何历史会话、消息或媒体；需要带数据时自行导入 dump / 对象目录。
>
> **沙箱工作区运行时在 Docker 命名卷 `${STACK_PREFIX}-sandbox-workspaces`**：起栈脚本首次从
> `data/sandbox-workspaces` 播种，之后运行时只写卷。`data/sandbox-workspaces` 是「播种源 + 备份」，
> 不是运行时真源；备份/出包前用 `scripts/export-sandbox.ps1` 把卷导回来。
> 这样做的原因：`ppt-master` 会一次物化 12k 个小文件，Windows Docker Desktop 的 bind mount
> 拷贝这种小文件要 10 分钟级，会让模型请求还没发出、前端一直停在「思考中」。

## 目录

```
deploy/stack/
├── docker-compose.yml          唯一的 compose 文件（12 个服务）
├── .env                        端口 / 密钥 / 内存上限（部署时改这里）
├── .env.example                配置示例
├── .env.verify                 本机验收栈（端口全部错开，可与开发环境并存）
├── data/
│   ├── minio/                  MinIO 对象（storyboard 桶）
│   └── sandbox-workspaces/     Agent 会话工作区（首次播种进命名卷，兼作备份）
├── db/                         建库、还原、账号收敛、模型种子 + 两份 dump
├── base/  service/             共享 Java 基座与应用运行时 Dockerfile
├── frontend/  nginx/           入口镜像与 nginx 路由
├── build/                      构建暂存（jar / dist，不入库）
├── offline/                    离线镜像包（docker load 用）
└── scripts/                    构建、启停、迁移、验收脚本
```

## 架构

```
                    ┌────────────── nginx frontend :80 ──────────────┐
浏览器 ── /、/canvas/、/api ──►│ 静态站 + /api 反代                           │
                    └───────────────┬────────────────────────────────┘
                                    ▼
                          api-gateway :8080
        ┌───────────────┬───────────┼────────────┬───────────────┐
        ▼               ▼           ▼            ▼               ▼
    backend:8082   asset:8085   canvas:9090   moon-agent:8084   llm-gateway:8083
        │               │           │            │               │
        └───────────────┴─────┬─────┴────────────┘               │
                              ▼                                  ▼
                   postgres:5432 / redis:6379 / minio:9000   （模型渠道）
                              ▲
                    moon-agent ──► sandbox-runner:8090 ──► squid 白名单出网
                                   └── data/sandbox-workspaces
```

- 容器名、网络名、数据卷名统一带 `STACK_PREFIX` 前缀，同一台机器可并存多套。
- 不使用固定 IP / 固定 subnet，避免与宿主已有网段冲突；服务间走 compose DNS。
- 每个容器都有可覆盖的 `*_MEM_LIMIT`，JVM 堆按 `MaxRAMPercentage=75` 跟随容器上限。

## 目标机部署（Linux，离线）

前置：Docker 24+ / compose v2，内存建议 **≥ 8G**（12 个容器；4G 机器按 `.env` 调小内存上限）。

```bash
# 1) 上传整个 deploy/stack（data/、offline/、db/*.dump、.env 都在里面）
scp -r deploy/stack root@<服务器IP>:/opt/ai-storyboard

# 2) 改配置
cd /opt/ai-storyboard
vi .env     # PUBLIC_ORIGIN 留空 = 自动用本机默认出口网卡的内网 IP；
            # 公网 IP / 域名 / 反代地址请显式写；80 被占用时改 FRONTEND_PORT

# 3) 一条命令：load 镜像 → 校验/解包迁移数据 → 起栈 → 等健康
bash scripts/load-and-up.sh

# 4) 验收（15 项：入口/登录/账号/项目/会话/消息/分镜/网关/MinIO/历史图片/沙箱/真实模型/SSE）
bash scripts/verify.sh
#    不想花钱跑模型：SKIP_MODEL_CALL=1 bash scripts/verify.sh
```

访问 `http://<服务器IP>/`，`admin@qq.com / 123456`。
安全组只需放行 `FRONTEND_PORT`（默认 80）；5433/6379/8083/9000/9001/8090 全部只绑 `127.0.0.1`。

`load-and-up.sh` 做的事：
1. `docker load` 导入 `offline/images-*.tar`（可用 `SKIP_LOAD=1` 跳过）；
2. 统一 `db/*.sh`、`db/sql/*.sql`、`scripts/*.sh` 为 LF（Windows 检出的 CRLF 会让 postgres 初始化静默跳过）；
3. 若 `data/minio`、`data/sandbox-workspaces` 为空且 `offline/*-data.tar` 存在则自动解包；Linux root 下把沙箱种子目录 `chown 10001:10001`；
4. 创建沙箱命名卷 `${STACK_PREFIX}-sandbox-workspaces`，空卷时从 `data/sandbox-workspaces` 播种（已有内容则跳过）；
5. `docker compose up -d --no-build`，最多等 600 秒直到全部 healthy。

## 本机构建与启动

```powershell
cd deploy\stack

# 一次性：暂存当前 jar / dist（-SkipFrontendBuild 复用已有 dist）
powershell -File scripts\build-apps.ps1 -SkipJava -SkipFrontendBuild

# 构建 7 个应用镜像（base 镜像已 retag 为 IMAGE_TAG）
powershell -File scripts\build-images.ps1

# 起栈 + 验收
powershell -File scripts\up.ps1
powershell -File scripts\verify.ps1
```

改代码后完整重建：

```powershell
powershell -File scripts\build-apps.ps1 -RebuildJava -RebuildFrontend   # 需要时
powershell -File scripts\build-images.ps1
```

本机验收栈（端口 18088/15433/16379/18083/19000/19001/18090，不影响开发环境）：

```powershell
powershell -File scripts\up.ps1     -EnvFile .env.verify
powershell -File scripts\verify.ps1 -EnvFile .env.verify
powershell -File scripts\down.ps1   -EnvFile .env.verify
```

> `up.ps1` 会把 env 文件的键值写入进程环境：宿主机 shell 里遗留的 `REDIS_PORT` / `POSTGRES_PORT`
> 等变量优先级高于 `--env-file`，不覆盖会把端口悄悄改回默认值（曾导致 `127.0.0.1:6379` 冲突）。

## 出交付包

```powershell
# 1) 导出离线镜像（12 个镜像，约 6GB）
powershell -File scripts\save-images.ps1
#    可选：把 data 也打成 tar（目标机 load-and-up.sh 会自动解包）
powershell -File scripts\save-images.ps1 -WithData

# 2) 汇总成一个可直接 scp 的目录（不含 build/；含 data/、offline/、db/）
powershell -File scripts\export-usb.ps1 -Target E:\Desktop\aistoryboard-deploy
```

## 日常运维

```bash
cd /opt/ai-storyboard
docker compose --env-file .env ps
docker compose --env-file .env logs -f backend
docker compose --env-file .env restart backend
docker compose --env-file .env down          # 停止，保留 pgdata/redis/uploads 与 ./data
```

**不要 `down -v`**，除非确认要删库重来（`./data` 宿主目录不受 `-v` 影响）。

备份：

```bash
docker exec ai-storyboard-postgres pg_dump -U postgres -Fc ai_storyboard > backup-ai_storyboard.dump
docker exec ai-storyboard-postgres pg_dump -U postgres -Fc llm_gateway  > backup-llm_gateway.dump
tar -cf backup-minio.tar -C data minio

# 沙箱运行时在命名卷：先导回 data/sandbox-workspaces，再打包
powershell -File scripts\export-sandbox.ps1
tar -cf backup-sandbox.tar -C data sandbox-workspaces
```

## 账号与网关

- 业务账号：`admin@qq.com / 123456`（`db/sql/post-migrate.sql` 收敛，仅此一个）
- 模型网关键：`.env` 的 `LLM_GATEWAY_API_KEY_MAIN` / `_AGENT`，必须与 `llm_gateway.gateway_api_key` 表一致
- 网关管理后台：`http://127.0.0.1:8083/admin-ui/`（SSH 隧道访问），admin 密码 = `GATEWAY_ADMIN_INIT_PASSWORD`
- 网页检索：唯一后端是 DeepSeek 原生搜索，`.env` 未配 key 时该工具不注册

## MCP 与 Skill

| MCP | 地址 | 说明 |
|-----|------|------|
| `llm-gateway-media` | `http://llm-gateway:8083/mcp` | `generate_image` / `generate_video` |
| `xiaoyue-canvas` | `http://canvas:9090/api/canvas/mcp` | `canvas_graph_*` + `canvas_execute_*`（浏览器命令信箱） |

`AGENTSCOPE_NACOS_ENABLED=false` 时 Skill 与 MCP 元数据走 classpath；moon-agent 启动日志应出现
`[MCP] 已注册远程工具: canvas=true, media=true`，工具数稳定 84。

## 验收项（scripts/verify.ps1 / verify.sh）

compose 12 服务 healthy、主站与画布站 200、`admin@qq.com` 登录（含 refresh cookie）、`/api/projects` 可用、
账号数=1 且为 `admin@qq.com/admin/enabled`、业务数据量如实展示（干净包 0/0/0/0）、网关 12/26/10、
MinIO 桶可读（干净包 0 对象）、历史图片（有对象时校验签名读取）、沙箱 Python 执行、
镜像内 `ppt-master` 技能包（13,018 文件）、沙箱卷可写、真实模型调用；
有项目时再跑 Agent SSE `turn_completed`，空库自动跳过。

2026-10-21 本机实测：**16/16 通过**（空库跳过 SSE）。

## 排障

| 现象 | 原因 / 处理 |
|------|-------------|
| postgres 一直 unhealthy，日志 `/bin/bash^M: bad interpreter` | `db/*.sh` 被检出成 CRLF。`up.ps1` / `load-and-up.sh` 会自动转 LF；手工执行时先 `sed -i 's/\r$//' db/*.sh db/sql/*.sql` |
| PPT/技能类对话一直「思考中」，模型调用日志里没有 `/v1/chat/completions` | 沙箱工作区被放在宿主 bind mount 上，`ppt-master` 物化 12k 个小文件极慢，模型请求尚未发出。确认 compose 里 `/workspaces` 挂的是 `sandbox-workspaces` 命名卷，并用 `docker volume ls` 看到 `${STACK_PREFIX}-sandbox-workspaces`；从旧版本升级时先 `up.ps1` 播种 |
| `Bind for 127.0.0.1:6379 failed: port is already allocated` | 宿主 shell 里遗留 `REDIS_PORT=6379` 覆盖了 `.env`。用 `scripts/up.ps1`（会覆盖进程环境），或先 `unset REDIS_PORT` |
| 画布保存/重命名/命令信箱 403，响应体 `Invalid CORS request` | `.env` 的 `PUBLIC_ORIGIN` 与实际访问地址不一致。留空时只会自动探测「本机默认出口网卡」的内网 IP；经域名、公网 IP、NAT/反代访问必须显式填写。改完 `up.ps1` / `load-and-up.sh` 重建 canvas（`CORS_ORIGINS` 注入到 Java 服务） |
| 首页 502 | api-gateway 还没起完（最后启动）。`docker compose logs -f api-gateway` 看 `Started` |
| 历史图片/视频 404 | `data/minio` 为空或未挂载。确认目录里有 `storyboard/`，或重新拷数据 |
| 每 60 秒 `画布资产同步项目库失败` | MinIO 中缺少历史对象（库里引用了但桶里没有），补 `data/minio` 即可 |
| 沙箱报 `Permission denied` | 宿主 `data/sandbox-workspaces` 属主不是 10001。`chown -R 10001:10001 data/sandbox-workspaces` |
| 容器被 OOM Kill | 8G 以下机器按 `.env` 调小 `MOON_AGENT_MEM_LIMIT` 等，或加 swap |
| 启动很慢 | 6 个 Spring 上下文串行启动，2 核机器 1~3 分钟属正常；healthcheck 给了 240s start_period |

## 重新迁移 / 出包（数据变了以后）

```powershell
cd deploy\stack\scripts

# 1) 重新导出实时数据库（本机原生 PG18）+ 生成 admin 密码哈希
powershell -File dump-db.ps1

# 2) 重新导出 MinIO 与沙箱数据到 data/
#    沙箱卷名以本机实际为准（docker volume ls）
docker run --rm -v aistoryboard_sandbox-workspaces:/w:ro -v "${PWD}\..\data\sandbox-workspaces:/out" alpine sh -c "cp -a /w/. /out/"
robocopy "..\..\docker\minio\data" "..\data\minio" /E /MT:16 /XD "..\..\docker\minio\data\.minio.sys\tmp"

# 3) 重打镜像 + 导出离线包
powershell -File build-apps.ps1 -SkipJava -SkipFrontendBuild
powershell -File build-images.ps1
powershell -File save-images.ps1
powershell -File export-usb.ps1 -Target E:\Desktop\aistoryboard-deploy
```

## 已知限制

- 不含 vosk 语音识别与 `AStoryboardMobile`；需要时复用 `deploy/docker/vosk`。
- 本包是干净部署：MinIO 为空桶、业务库无项目/会话/消息；历史图片/视频不会存在，属预期。
- 沙箱用户工作区为空；`ppt-master` 等技能包保留在镜像 `/opt/moon-skills`，首次引用时按需物化（约 3~5 秒）。
- 画布启动时会同步历史画布资产，若对象不存在会打印 404 警告，不影响功能。
- `deepseek-v4-flash` 渠道在库里有记录但可能没有可用上游 key；默认模型（`mimo-v2.6-pro` 等）实测可用。
- 与 `deploy/allinone` 的取舍：allinone 是「6 服务合并 1 JVM」的 2C2G 版本；本栈是 6 个独立 JVM，
  隔离更好但内存约 2.6G 起。两套都保留，按目标机规格选择。
