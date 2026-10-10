# MoonAgent 共享 Docker 沙箱

该目录部署一个常驻的 `moon-sandbox-runner` 和一个域名白名单 `sandbox-egress-proxy`。runner 只连接 internal 网络，代理同时连接 internal 与 egress 网络；runner 不挂载 Docker socket，也不获得宿主机级执行权限。

## 部署结构

```text
MoonAgent
   │  internal HTTP
   ▼
sandbox-runner ── sandbox-internal（internal: true）
   │
   └── sandbox-egress-proxy ── sandbox-egress（可访问公网）
                                  │
                                  └── 仅 SANDBOX_ALLOWED_DOMAINS 中的域名
```

- `sandbox-runner`：Java 25 + Python 3.12，预装 `python-docx`、`matplotlib`、`pandas`、`openpyxl`、`Pillow`、Noto CJK 字体和常用证书/图形库。
- `sandbox-workspaces`：命名卷，挂载到 `/workspaces`。会话目录由 runner 内部按会话隔离，最终产物仍应上传 MinIO。
- `sandbox-egress-proxy`：Squid 代理，支持 HTTP 与 HTTPS CONNECT，只放行 `SANDBOX_ALLOWED_DOMAINS` 中的目标域名。
- runner 使用非 root 用户、只读根文件系统、`tmpfs /tmp`、CPU/内存/PIDs 限额、健康检查和 `unless-stopped` 重启策略。

## 首次准备

1. 从仓库根目录构建 runner 产物：

   ```bash
   mvn -pl MoonSandbox -am package -DskipTests
   ```

   预期产物：`MoonSandbox/target/moon-sandbox.jar`。

2. 复制环境变量文件并修改 secret 与白名单：

   ```bash
   cd deploy/docker/sandbox
   cp .env.example .env
   ```

   `SANDBOX_INTERNAL_SECRET` 不能使用样例值。`SANDBOX_ALLOWED_DOMAINS` 支持空格或逗号分隔，前导点表示允许子域名，例如：

   ```dotenv
   SANDBOX_ALLOWED_DOMAINS=.pypi.org .files.pythonhosted.org .pythonhosted.org
   ```

## 静态检查

不启动容器，只渲染并检查 Compose 配置：

```bash
cd deploy/docker/sandbox
./scripts/check-compose.sh
```

也可以直接执行：

```bash
docker compose --env-file .env.example config -q
docker compose --env-file .env.example config
```

静态检查会确认：runner 只接 `sandbox-internal`、代理同时接两个网络、internal 网络已隔离、runner 为非 root/只读根文件系统、`/workspaces` 使用命名卷，以及 runner 的代理和白名单环境变量已配置。

## 启动与停止

```bash
cd deploy/docker/sandbox
./scripts/up.sh
./scripts/status.sh
./scripts/down.sh
```

`up.sh` 会在启动前校验 `MoonSandbox/target/moon-sandbox.jar` 是否存在，并执行 `docker compose config -q`。它不会清理 `sandbox-workspaces` 命名卷；需要清空工作区时，明确执行：

```bash
docker compose --env-file .env down -v
```

不要用 `down -v` 代替日常停止，否则会一并删除工作区卷。

MoonAgent 侧启用沙箱时设置：

```dotenv
AGENT_SANDBOX_ENABLED=true
MOON_SANDBOX_BASE_URL=http://127.0.0.1:8090
MOON_SANDBOX_INTERNAL_SECRET=<与 SANDBOX_INTERNAL_SECRET 相同>
```

## 运行约束

- runner 默认只绑定 `127.0.0.1:8090`；跨主机连接时修改 `SANDBOX_BIND_IP`，并配合防火墙限制来源。
- runner 只能通过 `sandbox-egress-proxy` 访问公网；没有白名单的域名会被代理拒绝。
- 运行时 Python 依赖应安装到会话私有 `vendor/` 目录，不要污染其他会话。
- 当前 v1 是目录级会话隔离和会话任务锁，适合受信任登录用户，不作为强多租户安全边界使用。
- Docker Compose 只负责部署常驻 runner；会话锁、清理策略、文件工具和 MinIO 产物发布由 MoonSandbox/MoonAgent 实现。
