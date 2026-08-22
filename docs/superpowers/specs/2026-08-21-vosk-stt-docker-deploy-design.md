# Vosk 语音转文字（Moon 智能体语音输入）+ Docker 统一部署

日期：2026-08-21
状态：待用户审查

## 1. 背景与目标

给 Moon 智能体对话加语音输入：用户点击麦克风开始录音、再点结束，录音转写为文字填入输入框，再走现有对话链路。

- 场景：Moon 智能体对话语音输入（非音视频素材转写）
- 引擎：Vosk（离线、Apache 2.0、Kaldi 系），中文全量模型 `vosk-model-cn-0.22`（约 1.3GB）
- 部署：Docker 容器，统一纳入一个部署管理目录（与 Nacos 同管）
- 交互形态：**形态 2** —— 点击开始 → 点击结束 → 上传识别 → 文本填入输入框

## 2. 现状调查结论

- 项目内无任何 docker-compose / Dockerfile / 部署脚本
- Nacos 为手动 `docker run` 起的 `nacos-standalone`（nacos/nacos-server:v3.1.0，standalone，8848/9848/8850，**无数据卷挂载**，数据在容器内）
- 三个服务（AIStoryboardBackend / ApiGateway / MoonAgent）均只使用 Nacos **服务发现**（`spring.cloud.nacos.discovery`），**无配置中心**依赖 → 旧容器无手工配置需保留，可安全停旧建新
- 用户建议升级 Nacos 镜像至 3.2.3（Docker Hub 该标签存在；服务发现场景小版本升级无兼容风险）

## 3. 架构总览

```
浏览器（Moon 智能体输入区）
   │  1. 点击开始 → getUserMedia 采集 → 2. 点击结束
   │  3. AudioContext 重采样 16kHz mono → 组 WAV
   │  4. POST /api/agent/stt (multipart wav)
   ▼
Spring Boot 后端（AIStoryboardBackend :8082）
   │  VoskSttService → java.net.http.WebSocket（JDK 自带，零新依赖）
   ▼
vosk-server 容器（:2700，Docker 内网）
   └─ vosk-model-cn-0.22（挂载 /opt/vosk-model）
```

## 4. 部署层：deploy/docker/

```
deploy/docker/
├── docker-compose.yml        # nacos + vosk 两个服务
├── .env.example              # NACOS_ADDR / VOSK_PORT / MODEL_DIR 等
├── README.md                 # 用法、模型下载说明
├── .gitignore                # data/ 模型目录不入库
├── data/vosk/models/
│   └── vosk-model-cn-0.22/   # setup.sh 下载解压（不入库）
└── scripts/
    ├── setup.sh              # 首次：下载中文全量模型 zip 并解压
    ├── up.sh                 # compose up -d + 健康检查
    ├── down.sh               # compose down（保留数据卷）
    └── status.sh             # compose ps + 端口探测
```

### docker-compose.yml 要点

```yaml
services:
  nacos:
    image: nacos/nacos-server:3.2.3          # 用户建议升级
    container_name: nacos-standalone
    environment:
      MODE: standalone
      NACOS_AUTH_ENABLE: "false"             # 与现状一致（内网开发环境）
      TIME_ZONE: Asia/Shanghai
    ports:
      - "8848:8848"
      - "9848:9848"                          # gRPC
      - "8850:8080"                          # 控制台端口（与现状一致）
    volumes:
      - nacos-data:/home/nacos/data          # 新增持久卷（现状无卷，重建即丢）
    restart: unless-stopped

  vosk:
    image: alphacephei/vosk-server:latest    # 官方镜像，内置 websocket 服务器
    container_name: vosk-stt
    environment:
      VOSK_MODEL_PATH: /opt/vosk-model
    ports:
      - "${VOSK_PORT:-2700}:2700"            # websocket
    volumes:
      - ./data/vosk/models:/opt/vosk-model   # 挂载中文全量模型
    mem_limit: 2g                            # 全量中文模型加载约 1.5-2GB
    restart: unless-stopped

volumes:
  nacos-data:
```

注意：vosk-server 官方镜像入口即 websocket 服务（端口 2700），无需自写 Dockerfile；模型目录挂载 + `VOSK_MODEL_PATH` 环境变量即可切换模型。

### setup.sh 要点

```bash
# 下载 vosk-model-cn-0.22（约 1.3GB）
# 源：https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip
# 解压到 data/vosk/models/，校验目录含 conf/ 与 am/ 后提示完成
```

### Nacos 接管（幂等）

1. 旧容器 `nacos-standalone`（v3.1.0，无卷）已由用户手动停止删除
2. `docker compose up -d nacos` 直接接管（3.2.3 + 持久卷 nacos-data）
3. 脚本保留幂等检查：`docker ps -a` 发现旧容器才 stop/rm，不存在则跳过
4. 服务自动重新注册（服务发现场景，重启后 8848 恢复即无感）

## 5. 后端：POST /api/agent/stt

- **端点**：`POST /api/agent/stt`，multipart 文件字段 `file`（WAV），JWT 鉴权（`/api/agent/**` 非白名单，自动覆盖）
- **返回**：`ApiResponse<SttResponse>`，`SttResponse { text: string }`
- **分层**（遵循项目规范）：
  - `controller/AgentSttController`：收参、限长校验、调 Service、封装响应（薄层）
  - `ai/agent/AgentSttService`（接口）+ `ai/agent/impl/AgentSttServiceImpl`（实现）：WebSocket 转发 + 解析 + 超时
- **转发细节**：JDK `java.net.http.WebSocket` 连接 `ws://vosk:2700`（Docker 内网），发送 WAV 的 PCM 数据（16kHz mono 16bit），收 `{"result":{"text":"..."}}` 解析返回
- **校验**：文件大小上限（如 10MB，约 5 分钟语音）、空文件拒绝
- **错误处理**（遵循"上游报错不直接展示"偏好）：
  - vosk 容器不可达 / 超时 → 友好文案「语音识别服务暂不可用，请稍后重试」
  - 识别结果为空 → 「未识别到语音内容，请重试」
- **配置**：`vosk.ws-url`（默认 `ws://localhost:2700`）、`vosk.timeout`（默认 30s）、`vosk.max-file-size`（默认 10MB）——独立命名空间，不碰 DB_URL 占位符污染坑

## 6. 前端：Moon 智能体输入区麦克风按钮

- 位置：`AgentChatPanel` 输入区发送按钮旁，inline SVG 麦克风图标（禁用 emoji，stroke=currentColor）
- 交互（形态 2）：
  - 空闲态：麦克风图标 → 点击开始录音（按钮变红/显示录音时长）
  - 录音态：点击结束 → 停止采集 → 处理 → 上传 → 文本填入输入框 → 恢复空闲态
- 采集链：`getUserMedia` → `AudioContext` 采集 PCM → `OfflineAudioContext` 重采样到 16kHz → 组 WAV（44 字节头 + PCM）→ FormData 上传
- 纯 Web Audio API，零 npm 新依赖
- 权限处理：`navigator.mediaDevices` 不可用 / 用户拒绝 → 中文提示（不崩溃）
- 录音上限：60s（超时自动停止，防无限占用麦克风）
- 识别中：按钮禁用，结果回填后恢复

## 7. 测试

- 后端：单测 `AgentSttServiceImpl`（mock WebSocket 收发：构造 vosk 返回 JSON，断言解析；超时/异常 → 友好错误）——沿用项目 mvn ad-hoc JUnit 配方
- 部署：`up.sh` 后 `curl -X POST` 传测试 wav（setup.sh 顺带生成 1s 静音测试 wav）验证链路
- 前端：手动验证（录音 → 识别 → 回填）

## 8. 明确不做（YAGNI）

- 不做流式边录边出字（交互形态 3）——用户已选形态 2
- 不做视频/音频素材转写（场景 b）
- 不接 Nacos 配置中心——现状无此需求
- 不写 vosk 自定义 Dockerfile——官方镜像够用
- 不做多模型热切换——模型目录固定挂载，换模型改 .env + 重启即可

## 9. 交付物清单

| 项 | 文件 |
|----|------|
| 部署 | `deploy/docker/docker-compose.yml`、`.env.example`、`README.md`、`.gitignore`、`scripts/{setup,up,down,status}.sh` |
| 后端 | `AgentSttController.java`、`AgentSttService.java`、`AgentSttServiceImpl.java`、`SttResponse`（record）、`application-*.yml` 加 `vosk.*` |
| 前端 | `AgentChatPanel` 麦克风按钮 + 录音 hook（`useVoiceInput`） |
| 文档 | `CLAUDE.md` 补部署目录与 stt 端点说明 |
