# deploy/allinone — 单 JVM 全栈 + 全量数据（2 核 2G 云服务器）

把原本 6 个容器 / 6 个 JVM 的 Java 服务（api-gateway、backend、llm-gateway、moon-agent、
asset-service、canvas）**合并进 1 个 JVM**，并带上本地全量业务数据，部署到
`http://122.51.12.200/`：

- 1 个 `allinone` 容器 = 6 个 Spring 上下文，各自独立类加载器（行为与 6 个独立容器一致）
- Postgres / Redis / MinIO / nginx 仍是独立容器（不是 JVM，没必要也不该塞进来）
- **全量数据**：157 个账号、168 个项目、376 个会话、2812 条消息、677 条 Agent 资产、
  2087 个 MinIO 对象文件（1.72GB），全部随包交付
- `admin@qq.com / 123456` 由 `771038325@qq.com` **原地改名**而来（id 不变，15 个项目、
  283 个会话全部保留）；其余 156 个账号一行不删
- 注册在入口 nginx 层关闭（后端 `/api/auth/register` 代码未改）
- Agent 沙箱（MoonSandbox）是**独立容器、默认开启**：保留非 root、只读根文件系统、
  出网白名单的隔离；不想开时 `WITH_SANDBOX=0 bash scripts/load-and-up.sh`

## 交付物

```
deploy/allinone/
├── docker-compose.yml        单 JVM 全栈（含 sandbox profile）
├── .env                      正式栈配置（PUBLIC_ORIGIN 已设为 http://122.51.12.200）
├── .env.test                 本机测试栈（端口全部错开）
├── allinone/Dockerfile       6 服务 → 1 JVM 的应用镜像
├── frontend/Dockerfile       nginx 入口镜像
├── nginx/default.conf        入口路由 + 注册关闭
├── config/*.json             每个服务的 spring.application.json（数据源/密钥按服务区分）
├── launcher/                 启动器源码（AllInOneLauncher + Unzip）
├── db/                       建库 + 全量还原 + admin 初始化 + 行数自检
│   ├── ai_storyboard.dump    业务库全量（public + asset + agentscope）
│   ├── llm_gateway.dump      模型网关全量（12 渠道 / 26 路由 / 10 key）
│   ├── data-manifest.env     行数清单（还原后逐表对比）
│   └── 03-init-admin.sh      只确保 admin 可登录，不删除任何数据
├── data/minio/               全量对象（1.72GB，2087 个文件，发布包内也会打成 tar）
├── offline/                  离线镜像包 + minio-data.tar
└── scripts/                  构建 / 起停 / 备份 / 验收脚本
```

## 与 deploy/stack、旧 allinone 包的差别

| 维度 | deploy/stack | 旧 allinone（lite-20261015） | 本包（cloud-20261021） |
|------|--------------|------------------------------|------------------------|
| Java 服务 | 6 容器 / 6 JVM | 1 容器 / 1 JVM | **1 容器 / 1 JVM** |
| 账号 | 全量 | 收敛成 2 个，删除其它账号数据 | **全量保留，admin 改名** |
| 会话/项目 | 全量 | 只有 admin 的 | **168 项目 / 376 会话全带** |
| MinIO 对象 | 全量 | 可选，默认空桶 | **2087 个对象文件全带** |
| 沙箱 | 独立容器 | 可选，默认关闭 | **独立容器，默认开启** |
| 入口 | `http://<IP>/` | 同 | 同 |
| 容器前缀 | 可配 | `ai-lite` | **`ai-cloud`（旧栈卷/数据不被覆盖）** |

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

外部：postgres:5432    redis:6379    minio:9000    sandbox-runner:8090（默认开）
入口：nginx:80 ──/api/**──> api-gateway:8080 ──> 各服务
```

启动顺序：llm-gateway → backend/asset-service/canvas/moon-agent → api-gateway（入口最后起）。

**共用环境变量、按服务区分数据源**：给每个服务传
`--spring.application.json=<config/<服务>.json 展开后的 JSON>`。这个属性源优先级高于
`application-prod.yml` 与环境变量，所以「llm-gateway 连 `llm_gateway` 库、其余连
`ai_storyboard` 库」「backend 用 MAIN key、Agent 侧用 AGENT key」能精确表达，不用改应用代码。

存活判定：web 服务的 `main` 在上下文刷新后就会返回，所以启动器在 main 返回后进入
**端口看门狗**（先等端口监听，再每 15 秒探一次）。端口连续 4 次失败即在新类加载器里重启
该服务；连续失败超过上限则 `halt(1)`，交给 compose 的 `restart: unless-stopped` 整体自愈。

## 数据完整性：这次是怎么保证的

1. **导出**：`pg_dump -Fc` 全量导出 `ai_storyboard`（87 张表：public 64 / asset 22 /
   agentscope 1）与 `llm_gateway`；MinIO 在 `docker pause minio-object` 的瞬间做整目录快照，
   保证对象一致。
2. **清单**：`db/data-manifest.env` 记录 11 张核心表的行数与 MinIO 文件数/字节数。
3. **还原**：`02-restore.sh` 还原后统计表数量，0 张表直接失败；`05-verify-data.sh` 逐表对比
   清单，`users=0` 视为致命错误（避免对外提供空站）。
4. **起栈前**：`load-and-up.sh` 硬校验两个 dump、`admin-scrypt.txt`、MinIO 对象数
   （<1500 个文件直接拒绝启动）；`data/minio` 为空时自动从 `offline/minio-data.tar` 解包。
5. **起栈后**：`smoke-test.sh` 用 HTTP + `psql` 双重校验 157/168/376/677 与 MinIO 对象数，
   不是只看“容器起来了”。
6. **不做夜间删除**：`.env` 默认 `AI_ARCHIVE_CLEANUP_ENABLED=false`、
   `AI_RETENTION_ENABLED=false`，关闭 MoonAgent 的归档会话清理与保留期清理，
   避免每天 03:40/04:20 删掉历史 turn events、checkpoint、画布运行等派生数据。
   需要恢复清理能力时再改成 `true`（先读 MoonAgent 的清理规则）。

## 目标机部署（122.51.12.200）

### 0. 备份并停掉旧栈（旧栈数据不删）

旧栈在 `/opt/ai-lite`，新包用独立前缀 `ai-cloud`，卷名/容器名都不同，起新栈不会覆盖旧数据。
先停旧栈释放 80 端口，并把旧库/旧对象备份一份：

```bash
mkdir -p /opt/backup
cd /opt/ai-lite
docker exec ai-lite-postgres pg_dump -U postgres -Fc ai_storyboard > /opt/backup/ai_storyboard-old.dump
docker exec ai-lite-postgres pg_dump -U postgres -Fc llm_gateway     > /opt/backup/llm_gateway-old.dump
docker compose --env-file .env down       # 先停容器（保留卷），释放 80 端口
tar -czf /opt/backup/ai-lite-minio-$(date +%F).tar.gz data/minio 2>/dev/null || true
```

> 旧库的 13 个项目、182 条资产都包含在本地全量导出里（本地是它的超集），
> 备份只是为了可回滚，不是合并来源。

### 1. 上传

```bash
# 源机（本机）
scp -r deploy/allinone root@122.51.12.200:/opt/ai-cloud
```

约 3.2GB（镜像 1.45GB + MinIO 1.73GB + 前端/配置/脚本）。也可以用 U 盘 / 宝塔上传，
保持目录结构不变。

### 2. 前置检查（2 核 2G 必看）

```bash
docker version && docker compose version
free -m          # 内存与 swap
df -h /opt       # 新包解包 + 数据卷 + 旧栈备份，建议 >=10GB 可用
```

没有 swap 或小于 1G 时先加 4G（否则高峰期会被 OOM Killer 杀掉）：

```bash
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab
free -m    # 确认 Swap 已经是 4096
```

不想加 swap 就把 `.env` 的 `JVM_XMX` 降到 `640m`（更省，但 Agent 长任务更容易 OOM 重启）。

### 3. 起栈

```bash
cd /opt/ai-cloud
bash scripts/load-and-up.sh
```

脚本会：校验全量数据 → `docker load` 4 个 tar → 解 MinIO 对象 → `docker compose up -d` →
等 `:80/healthz` 返回。首次启动 6 个 Spring 上下文在 2 核上约 1~3 分钟。

看日志（每行带服务名前缀 `[llm-gateway]` / `[backend]` …）：

```bash
docker compose --env-file .env logs -f allinone | grep -E 'Started|端口|failure'
```

### 4. 验收

```bash
bash scripts/smoke-test.sh
# 不花模型额度：
SKIP_MODEL_CALL=1 bash scripts/smoke-test.sh
```

验收覆盖：入口/画布站、注册 403、admin 登录、全量行数、MinIO 对象数、6 个端口、
画布 CORS 写操作、模型网关 `/v1/models`、真实模型调用、沙箱健康 + Python 执行、内存占用。

### 5. 访问

```
http://122.51.12.200/
admin@qq.com / 123456
```

其余 156 个历史账号密码保持原样；注册入口被 nginx 拦成 403。

安全组只放行 **80**；5433/6379/8083/9000/9001/8090 全部只绑 `127.0.0.1`。

## 回滚

```bash
cd /opt/ai-cloud && docker compose --env-file .env down
cd /opt/ai-lite  && docker compose --env-file .env up -d
```

旧栈的卷 `ai-lite-pgdata` / `ai-lite-uploads` 与 `data/minio` 都还在，新栈从未写过它们。

## 内存预算（实测）

| 容器 | mem_limit | 实测 RSS |
|------|-----------|----------|
| allinone（6 服务，`-Xmx768m -XX:MaxMetaspaceSize=640m`） | 1500m | **~1.15G**（堆稳定 ~240M，Metaspace ~430M） |
| postgres（shared_buffers=64MB, max_connections=60） | 384m | ~60M |
| minio（GOGC=50） | 384m | ~85M |
| redis（maxmemory 128mb） | 192m | ~8M |
| frontend（nginx） | 64m | ~28M |
| sandbox-runner + proxy（默认开） | 384m + 192m | ~350M |
| **合计** | — | **~1.7G** |

2G 物理内存 + 4G swap 是硬前提。调参入口全在 `.env`，不用重建镜像：
`JVM_XMX` / `JVM_METASPACE` / `JVM_CODE_CACHE` / `JVM_EXTRA_OPTS` / `ALLINONE_MEM_LIMIT` /
各容器 `*_MEM_LIMIT`。

## 本机重建（改代码 / 改数据后重新出包）

```powershell
# 1) Java + 前端
mvn -B "-DskipTests" package
cd AIStoryboardClient; npm run build; cd ..
cd XiaoYueCanvasAgent; npm run build; cd ..

# 2) 全量数据（Postgres + MinIO）
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\export-db.ps1
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\export-data.ps1

# 3) 暂存 + 构建镜像
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\build-apps.ps1
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\build-images.ps1
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\build-sandbox.ps1 -Tag cloud-20261021

# 4) 离线镜像 + 交付目录
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\save-images.ps1 -WithSandbox
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\export-package.ps1 -Target E:\Desktop\ai-cloud-deploy
```

> 本机 PowerShell 执行策略是 RemoteSigned 时会拒跑未签名脚本，加
> `-ExecutionPolicy Bypass`（仓库里 deploy/stack 脚本同理）。

本机自测（端口全部错开，不影响 dev 服务与 `deploy/stack`）：

```powershell
# 先把全量对象复制到测试目录（首次）
robocopy deploy\allinone\data\minio deploy\allinone\data-test\minio /E
# 清掉上一次测试栈的数据卷（只删 ai-cloud-test-*，不碰正式数据）
docker compose -f deploy\allinone\docker-compose.yml --env-file deploy\allinone\.env.test down -v
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\up.ps1 -EnvFile .env.test
powershell -ExecutionPolicy Bypass -File deploy\allinone\scripts\verify.ps1 -EnvFile .env.test -BaseUrl http://127.0.0.1:18080
```

## 排障

| 现象 | 原因 / 处理 |
|------|-------------|
| `load-and-up.sh` 报“MinIO 数据不完整” | `data/minio` 只有 `.keep` 或对象没传全。删掉空的 `data/minio` 后重跑；脚本会从 `offline/minio-data.tar` 解包 |
| 起栈后库里只有 1 个账号 / 项目数不对 | 用了非空数据卷（旧 `ai-cloud-pgdata` 已存在），Postgres 不会执行 `db/` 初始化。确认 `STACK_PREFIX` 是新的，或 `docker compose down -v` 后重来（会删该栈数据） |
| `load-and-up.sh` 报 `PUBLIC_ORIGIN` 是本机地址 | 改 `.env` 的 `PUBLIC_ORIGIN=http://122.51.12.200`；仅本机测试可 `ALLOW_LOCALHOST_ORIGIN=1` |
| 容器反复重启，日志有“超过上限，退出 JVM” | 某个服务连续失败 5 次。往上翻该服务第一条异常（通常是数据源/MinIO/Redis 地址或密钥） |
| `java.lang.Error: factory already defined` | Tomcat 的 `URLStreamHandlerFactory` 只能全局注册一次。启动器已对每个类加载器先 `disable()`；自行改启动器请保留 |
| 启动很慢 / health: starting | 6 个上下文抢 2 个核；`start-period` 给了 240s。用 `docker stats` 看 CPU |
| 502 Bad Gateway | allinone 还没起完（gateway 最后启动）。看 `docker logs … \| grep Started` |
| **画布保存/重命名 403，响应体 `Invalid CORS request`，日志里查不到** | `.env` 的 `PUBLIC_ORIGIN` 与实际访问地址不一致。改成 `http://122.51.12.200` 后 `docker compose --env-file .env up -d --no-build allinone frontend` |
| 历史图片 404 | `data/minio` 没带全或没挂上；先跑 `smoke-test.sh` 的 MinIO 对象项 |
| 注册接口 403 | 预期行为（nginx 拦截）。要放开就删 `nginx/default.conf` 的 `location = /api/auth/register` 并重建 frontend 镜像 |
| 端口被占用 | 旧栈还在跑。先 `cd /opt/ai-lite && docker compose down`；宿主机 shell 里遗留的 `REDIS_PORT/POSTGRES_PASSWORD` 等变量优先级高于 `.env`，手工 `docker compose up` 前先 `unset` |
| `db/*.sh` 报 `/bin/bash^M` | 在 Windows 上检出成了 CRLF。`scripts/normalize-lf.ps1` 会统一成 LF，`load-and-up.sh` 起栈前也会兜底 `sed -i 's/\r$//'` |
| 一个服务挂了会不会全挂 | 单服务异常就地重启（新类加载器）；JVM 级故障（OOM/超重启上限）才整体重启，数据卷不受影响 |

## 已知限制与取舍

- **为什么不合并成“一个 Spring 上下文”**：6 个服务里有同名 FQCN（`com.moon.moonagent.*`
  同时存在于 moon-agent 与 canvas）、6 份 `application.yml`，共享类路径会互相污染。
  隔离类加载器是“零代码改动 + 行为等价”的方案；代价是 Metaspace 仍是 6 份
  （实测 ~430M），省下的主要是 6 份 JVM 自身开销（JIT/GC/内部线程栈/JDK 基础设施）。
- **Tomcat `war:` / `classpath:` 协议处理器不再注册**：为了让 6 个内嵌 Tomcat 共存必须先
  `disable()` 掉全局注册；本栈 6 个服务都是展开目录启动的普通 jar 应用（不是 war），用不到。
- **ForkJoinPool.commonPool 的 TCCL**：JVM 全局线程池被多个应用共享时，TCCL 敏感的
  `ServiceLoader.load` 理论上可能取到另一个应用的类加载器。已用
  `-Djava.util.concurrent.ForkJoinPool.common.parallelism=4` 收敛规模；实测对话/分镜/
  生图/生视频链路正常。如遇 `ClassNotFound` 且堆栈在 commonPool 线程上，把该处改成显式
  executor 即可。
- **沙箱独立容器**：没有塞进同一个 JVM。它是模型生成 Python 的执行边界，独立容器才能保留
  非 root、只读根文件系统、CPU/内存/PIDs 限额与 Squid 域名白名单。默认开启，
  `WITH_SANDBOX=0` 可关。
- **联网检索关闭**（`AGENTSCOPE_WEB_SEARCH_ENABLED=false`）：本镜像不带 DeepSeek key。
  要恢复就给 moon-agent 配 `DEEPSEEK_API_KEY` 并把这个开关设回 `true`。
- **`deepseek-v4-flash` 渠道在转储里可能没有可用 key**：网关 `/v1/models` 能列出，实际调用会挂；
  默认模型（`mimo-v2.6-pro` / `doubao-seedream-*` / `MiniMax-H3`）实测可用。
  上线前建议在网关管理后台（`http://127.0.0.1:8083/admin-ui/`，需 SSH 隧道）核一遍渠道密钥。
- **旧栈数据不自动合并**：新包是本地 2026-10-21 的全量快照；旧栈 10-15 之后的增量数据
  不包含在内（已确认旧栈最新项目是 10-08，本地是超集）。如需合并，先保留
  `/opt/backup/*-old.dump`，再单独做数据迁移。
