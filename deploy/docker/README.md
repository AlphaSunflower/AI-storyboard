# deploy/docker — 基础设施 Docker 部署

nacos 与 vosk 拆分为**两个独立 compose 项目**（子目录），可单独启停、单独拷到其他机器部署。

| 服务 | 目录 | 镜像 | 端口 | 说明 |
|------|------|------|------|------|
| nacos | `nacos/` | nacos/nacos-server:v3.2.3 | 8848/9848/8850 | 服务发现（standalone，内网开发） |
| vosk | `vosk/` | alphacep/kaldi-vosk-server:latest | 2700 | 离线语音识别（中文全量模型，官方 vosk-server 镜像已下架改用 Kaldi 版） |

## 首次部署

1. `./scripts/setup.sh` —— 下载 vosk-model-cn-0.22（约 1.3GB）到 `vosk/data/models/`
2. `./scripts/up.sh` —— 启动全部服务（自动接管旧 nacos 容器）

## 日常

脚本均接受服务参数，可单独操作：

```bash
./scripts/up.sh [nacos|vosk|all]      # 启动（默认 all）
./scripts/down.sh [nacos|vosk|all]    # 停止（保留数据卷与模型）
./scripts/status.sh [nacos|vosk|all]  # 查看状态
```

也可直接进子目录用原生 compose：

```bash
cd nacos && docker compose up -d      # 只启 nacos
cd vosk && docker compose up -d       # 只启 vosk
```

- 换模型：替换 `vosk/data/models/` 下模型目录，重启 vosk 容器即可
- vosk 端口/绑定 IP 配置：`vosk/.env`（参考 `vosk/.env.example`，`VOSK_PORT`、`VOSK_BIND_IP`）

## 注意

- nacos 数据在命名卷 `nacos-data`，`docker compose down -v` 会清空（不要加 -v）
- vosk 后端地址：`ws://localhost:2700`（MoonAgent 通过 `vosk.ws-url` 配置）
- vosk 模型目录 `vosk/data/` 已被 `.gitignore` 忽略（约 2GB，不入库）
