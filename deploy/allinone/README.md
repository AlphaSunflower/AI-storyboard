# deploy/allinone — 单 JVM 全栈部署（2 核 2G 公网测试）

把原本 6 个容器 / 6 个 JVM 的 Java 服务（api-gateway、backend、llm-gateway、moon-agent、
asset-service、canvas）**合并进 1 个 JVM**，只为 2 核 2G 的远程机器能跑起来：

- 1 个 `allinone` 容器 = 6 个 Spring 上下文，各自独立类加载器（行为与 6 个独立容器一致）
- Postgres / Redis / MinIO / nginx 仍是独立容器（不是 JVM，没必要也不该塞进来）
- **不含 Nacos、不含 vosk**（自托管 SearXNG 检索链路已从产品里移除，联网检索只有 DeepSeek 原生搜索）
- **注册在入口 nginx 层关闭**（后端 `/api/auth/register` 代码一行未改）
- 账号收敛为**恰好两个**：`admin@qq.com` + `test@qq.com`（都是 `123456`）
- Agent 沙箱（MoonSandbox）作为**可选 profile**，默认不启动，需要时一条命令打开

## 交付物

```
deploy/allinone/
├── docker-compose.yml        单 JVM 全栈（含可选 sandbox profile）
├── .env                      密钥 / 端口 / 内存预算（部署时按需改）
├── .env.test                 本机测试用（端口全部错开，不撞已有栈）
├── allinone/Dockerfile       6 服务 → 1 JVM 的应用镜像
├── frontend/Dockerfile       nginx 入口镜像
├── nginx/default.conf        入口路由 + 注册关闭规则
├── config/*.json             每个服务的 spring.application.json（数据源/密钥按服务区分）
├── launcher/                 启动器源码（AllInOneLauncher + Unzip）
├── db/                       建库 + 还原 + 模型种子 + 两账号收敛
├── offline/                  离线镜像包（docker load 用）
└── scripts/                  构建 / 启停 / 验收 / 导出脚本
```

## 与 deploy/stack 的差别

| 维度 | deploy/stack | deploy/allinone |
|------|--------------|-----------------|
| Java 服务 | 6 个容器 / 6 个 JVM | **1 个容器 / 1 个 JVM** |
| 容器总数 | 11 | **5**（+2 开沙箱时） |
| Nacos / vosk | 无 Nacos，无 vosk | 都没有 |
| 联网检索 | DeepSeek 原生搜索（需 key） | 关闭（无 key） |
| 注册 | 开放 | 入口 nginx 拦成 403（后端代码未改） |
| 账号 | 1 个（admin） | **恰好 2 个**（admin + test） |
| 实测内存 | ~2.6G | **~1.35G**（不开沙箱） |
| 入口 | `http://<IP>/` | `http://<IP>/` 同 |

## 架构：一个 JVM 里怎么装下 6 个服务

```
allinone 容器（1 个 JVM, PID 1）
├── URLClassLoader(llm-gateway)  → 8083   ← 每组 BOOT-INF/classes + BOOT-INF/lib 独立
├── URLClassLoader(backend)      → 8082      因此各自的 application.yml、依赖版本、
├── URLClassLoader(asset-service)→ 8085      静态状态互不干扰
├── URLClassLoader(canvas)       → 9090
├── URLClassLoader(moon-agent)   → 8084
└── URLClassLoader(api-gateway)  → 8080
        ↑ 父加载器只给到 platform loader

外部：postgres:5432    redis:6379    minio:9000    sandbox-runner:8090(可选)
入口：nginx:80 ──/api/**──> api-gateway:8080 ──> 各服务
```

启动顺序：llm-gateway → backend/asset-service/canvas/moon-agent → api-gateway（入口最后起）。

**共用环境变量、按服务区分数据源**的做法：给每个服务传
`--spring.application.json=<config/<服务>.json 展开后的 JSON>`。
这个属性源优先级高于 `application-prod.yml` 与环境变量，所以
「llm-gateway 连 `llm_gateway` 库、其余连 `ai_storyboard` 库」「backend 用 MAIN key、
Agent 侧用 AGENT key」能精确表达，而不用改任何应用代码。

存活判定：web 服务的 `main` 在上下文刷新后就会返回（返回 ≠ 结束），所以启动器在 main
返回后进入**端口看门狗**（先等端口监听，再每 15 秒探一次）。端口连续 4 次探测失败即判定
服务已死，在新类加载器里重启该服务；连续失败超过 5 次则 `halt(1)`，交给 compose
的 `restart: unless-stopped` 整体重启（自愈）。

## 内存预算（实测）

| 容器 | mem_limit | 实测 RSS |
|------|-----------|----------|
| allinone（6 服务，`-Xmx768m -XX:MaxMetaspaceSize=512m`） | 1500m | **~1.15G**（堆稳定 ~240M，Metaspace ~430M） |
| postgres（shared_buffers=64MB, max_connections=60） | 384m | ~60M |
| minio（GOGC=50） | 384m | ~85M |
| redis（maxmemory 128mb） | 192m | ~8M |
| frontend（nginx） | 64m | ~28M |
| **合计（不开沙箱）** | — | **~1.35G** |
| 沙箱（runner 384m + proxy 192m） | — | +~350M |

### swap 是 2G 机器的硬前提

物理 2G 减去系统与 docker（约 200-250M），剩余空间只够跑稳态；模型生成、Agent 长任务
这类峰值一定会顶到上限。**上线前先加 4G swap**：

```bash
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab
free -m    # 确认 Swap 已经是 4096
```

不想加 swap 就把 `.env` 里 `JVM_XMX` 降到 `640m`（更省，但 Agent 长任务更容易 OOM 重启）。

调参入口全在 `.env`，不用重建镜像：`JVM_XMX` / `JVM_METASPACE` / `JVM_CODE_CACHE` /
`JVM_EXTRA_OPTS` / `ALLINONE_MEM_LIMIT` / 各容器 `*_MEM_LIMIT`。

## 目标机部署

**前置**：Docker（含 compose v2）+ 上面那份 swap。

> **上传前必改两处 `.env`**（都是踩过的坑）：
> 1. `PUBLIC_ORIGIN=` **改成你实际访问这个站点的地址**（如 `http://122.51.12.200` 或 `https://your.domain`）。
>    浏览器对 `POST/PATCH/PUT` 会带 `Origin` 头，canvas 服务据此做 CORS 校验；这里不一致时，
>    画布的**保存 / 重命名 / 命令信箱注册会静默 403**（响应体 `Invalid CORS request`，日志里没有任何痕迹）。
>    `scripts/smoke-test.sh` 里有一项专门测它：写错时该项直接 FAIL 并提示改这里。
> 2. 确认已经加好 4G swap（见上一节）。

```bash
# 1) 上传整个 deploy/allinone 目录（含 offline/*.tar、db/*.dump、.env）
scp -r deploy/allinone root@<服务器IP>:/opt/ai-lite

# 2) 起栈（自动 docker load + 起容器，不需要联网/Maven/Node）
cd /opt/ai-lite
bash scripts/load-and-up.sh

# 3) 验收（16 项：入口/注册关闭/两个账号/6 个端口/画布写操作(带 Origin)/模型网关/真实模型调用）
bash scripts/smoke-test.sh

# 4) 访问
#    http://<服务器公网IP>/          admin@qq.com / 123456
#                                    test@qq.com  / 123456
```

安全组只需放行 **80**；5433/6379/8083/9000/9001/8090 全部只绑 `127.0.0.1`。

首次启动：6 个上下文串行起来，本机（32 核）约 20 秒；2 核机器预计 1~2 分钟。
看日志：

```bash
docker compose --env-file .env logs -f allinone | grep -E 'Started|端口|failure'
```

每行日志都带服务名前缀 `[llm-gateway]` / `[backend]` …，便于分辨来源
（llm-gateway 与 backend 用各自的 logback-spring.xml，前缀体现在线程名 `svc-<服务>` 上）。

### 账号与注册

- `admin@qq.com / 123456`（admin）、`test@qq.com / 123456`（member，显示名"测试用户"）
- 首次初始化数据卷时由 `db/05-two-accounts.sh` 收敛：存在就重置密码、不存在就新建，
  并删掉其它账号及其会话/项目（幂等，可重复执行）
- 改第二个账号：改 `.env` 的 `SECOND_ACCOUNT_EMAIL/NAME` 后**重建数据卷**才生效
  （`docker compose --env-file .env down -v && bash scripts/load-and-up.sh`），
  或在运行中的库里直接跑 `db/sql/two-accounts.sql`
- **注册关闭**：`nginx/default.conf` 里 `location = /api/auth/register` 返回
  `403 {"code":40301,"message":"本环境不开放注册，请使用已有账号登录"}`。
  后端 `AuthController#register` / `SecurityConfig` 的 `/api/auth/register` 白名单均保持原样。

### Agent 沙箱（可选，默认关闭）

沙箱给 Agent 提供 `write_file` / `run_python_script` / 产物文件卡能力。2G 机器上默认不开：

```bash
# 打开（需要 offline/images-sandbox-*.tar 已 load）
sed -i 's/^AGENT_SANDBOX_ENABLED=.*/AGENT_SANDBOX_ENABLED=true/' .env
WITH_SANDBOX=1 bash scripts/load-and-up.sh
bash scripts/smoke-test.sh      # 会多跑一项沙箱链路（健康 + 写文件 + python 执行）
```

沙箱仍是原设计：只接 internal 网络、出网走 Squid 白名单（`SANDBOX_ALLOWED_DOMAINS`）、
非 root、只读根文件系统、CPU/内存/PIDs 限额。

### MinIO 历史对象（可选，约 1.6G）

`data/minio/` 是宿主目录，空目录启动也能正常工作，只是库里引用的**历史**图片/视频会 404
（新生成的产物一切正常）。想带上历史对象：

```powershell
# 源机
powershell -File scripts\export-data.ps1        # -> offline/minio-data.tar
# 目标机（deploy/allinone 目录下）
tar -xf minio-data.tar -C .
```

## 本机重建（改代码后重新出包）

```powershell
cd deploy\allinone
powershell -ExecutionPolicy Bypass -File scripts\build-apps.ps1 -RebuildJava -RebuildFrontend
powershell -ExecutionPolicy Bypass -File scripts\build-images.ps1
powershell -ExecutionPolicy Bypass -File scripts\build-sandbox.ps1 -Tag lite    # 可选
powershell -ExecutionPolicy Bypass -File scripts\save-images.ps1 -WithSandbox
powershell -ExecutionPolicy Bypass -File scripts\export-package.ps1 -Target E:\Desktop\ai-lite-deploy
```

> 本机 PowerShell 执行策略是 RemoteSigned 时会拒跑未签名脚本，加
> `-ExecutionPolicy Bypass`（仓库里已有的 deploy/stack 脚本同理）。

本机自测（端口全部错开，不影响 `deploy/stack` 的容器）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\up.ps1 -EnvFile .env.test
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -EnvFile .env.test
# Linux 侧验收脚本本身也可以用 Git Bash 验证：
# ENV_FILE=.env.test bash scripts/smoke-test.sh
```

## 排障

| 现象 | 原因 / 处理 |
|------|-------------|
| 容器反复重启，日志有"超过上限，退出 JVM" | 某个服务连续失败 5 次。往上翻该服务第一条异常（通常是数据源/MinIO/Redis 地址或密钥） |
| `java.lang.Error: factory already defined` | Tomcat 的 `URLStreamHandlerFactory` 只能全局注册一次。启动器已对每个类加载器先调 `disable()`；若自行改动启动器请保留这段 |
| 启动很慢 / 一直 health: starting | 6 个上下文抢 2 个核；`start-period` 给了 240s。用 `docker stats` 看 CPU 是否被压满 |
| 502 Bad Gateway | allinone 还没起完（gateway 最后启动）。看 `docker logs … \| grep Started` |
| **画布保存/重命名/信箱注册 403，响应体 `Invalid CORS request`，日志里查不到** | `.env` 的 `PUBLIC_ORIGIN` 与实际访问地址不一致（默认是 `http://localhost`）。浏览器对 POST/PATCH/PUT 会带 `Origin` 头，canvas 的 CORS 白名单不含它就会被静默拒绝。改 `.env` 的 `PUBLIC_ORIGIN` 为实际地址后 `docker compose --env-file .env up -d --no-build allinone` |
| 画布素材 500 / 每 60 秒 `画布资产同步项目库失败` | MinIO 里没有历史对象（见"MinIO 历史对象"），补 `minio-data.tar` 即可 |
| 注册接口返回 403 | 预期行为（nginx 拦截）。要放开就删 `nginx/default.conf` 里的 `location = /api/auth/register` 并重建 frontend 镜像 |
| 历史图片 404 | `data/minio` 是空桶，历史对象没带过来（见上"MinIO 历史对象"） |
| 端口被占用 | 宿主机 shell 里遗留的 `REDIS_PORT/POSTGRES_PASSWORD` 等变量**优先级高于 `.env`**。`scripts/up.ps1` 已把 env 文件写进进程环境规避；手工 `docker compose up` 时先 `unset` |
| `db/*.sh` 报 `/bin/bash^M` | 在 Windows 上检出成了 CRLF。`scripts/normalize-lf.ps1` 会统一成 LF，`load-and-up.sh` 起栈前也会兜底 `sed -i 's/\r$//'` |
| 一个服务挂了会不会全挂 | 单服务异常会被就地重启（新类加载器）；JVM 级故障（OOM/超过重启上限）才整体重启，数据卷不受影响 |

## 已知限制与取舍

- **为什么不改代码合并成"一个 Spring 上下文"**：6 个服务里有同名 FQCN（`com.moon.moonagent.*`
  同时存在于 moon-agent 与 canvas）、6 份 `application.yml`，共享类路径会互相污染。
  隔离类加载器是"零代码改动 + 行为等价"的方案；代价是 Metaspace 仍是 6 份
  （实测 ~430M），省下的主要是 6 份 JVM 自身开销（JIT/GC/内部线程栈/JDK 基础设施）。
- **Tomcat `war:` / `classpath:` 协议处理器不再注册**：为了让 6 个内嵌 Tomcat 共存必须先
  `disable()` 掉全局注册；本栈 6 个服务都是展开目录启动的普通 jar 应用（不是 war），用不到
  （已验证静态资源、`/canvas/`、SPA fallback 全部正常）。
- **ForkJoinPool.commonPool 的 TCCL**：这是 JVM 全局线程池，多个应用共享它时，
  TCCL 敏感的操作（`ServiceLoader.load`/`Class.forName(via TCCL)`）理论上可能取到另一个
  应用的类加载器。已用 `-Djava.util.concurrent.ForkJoinPool.common.parallelism=4` 收敛规模；
  仓库里 `agent-core` 的 `supplyAsync(...)` 未指定 executor 的几处是潜在触发点。
  实测对话/分镜/生图/生视频链路正常；如遇 `ClassNotFound` 且堆栈在 commonPool 线程上，
  把该处改成显式 executor（几行改动）即可。
- **不含 vosk**：前端麦克风入口不可用（按你的要求）。需要时复用 `deploy/docker/vosk`。
- **联网检索关闭**（`AGENTSCOPE_WEB_SEARCH_ENABLED=false`）：本镜像不带 DeepSeek key
  （唯一后端已改为 DeepSeek 原生搜索，自托管 SearXNG 那条链路已移除）。
  要恢复就给 moon-agent 配 `DEEPSEEK_API_KEY`（或 `AGENTSCOPE_WEB_SEARCH_API_KEY`）并把这个开关设回 `true`。
- **`deepseek-v4-flash` 渠道在转储里没有可用 key**：网关 `/v1/models` 能列出，实际调用会挂；
  默认模型（`mimo-v2.6-pro` / `doubao-seedream-*` / `MiniMax-H3`）实测可用。
  上线前建议在网关管理后台（`http://127.0.0.1:8083/admin-ui/`，需 SSH 隧道）核一遍渠道密钥。
- 迁移的 MinIO 对象未做筛选，历史测试账号的孤儿对象仍在桶里（`orphan-scan-enabled` 保持 false）。
