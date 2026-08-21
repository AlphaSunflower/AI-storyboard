# deploy/docker — 基础设施 Docker 部署

统一管理本项目依赖的基础设施服务：

| 服务 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| nacos | nacos/nacos-server:3.2.3 | 8848/9848/8850 | 服务发现（standalone，内网开发） |
| vosk | alphacephei/vosk-server:latest | 2700 | 离线语音识别（中文全量模型） |

## 首次部署

1. `./scripts/setup.sh` —— 下载 vosk-model-cn-0.22（约 1.3GB）
2. `./scripts/up.sh` —— 启动全部服务（自动接管旧 nacos 容器）

## 日常

- `./scripts/status.sh` —— 查看状态
- `./scripts/down.sh` —— 停止（保留数据卷与模型）
- 换模型：替换 `data/vosk/models/` 下模型目录，重启 vosk 容器即可

## 注意

- nacos 数据在命名卷 `nacos-data`，`docker compose down -v` 会清空（不要加 -v）
- vosk 后端地址：`ws://localhost:2700`（MoonAgent 通过 `vosk.ws-url` 配置）
