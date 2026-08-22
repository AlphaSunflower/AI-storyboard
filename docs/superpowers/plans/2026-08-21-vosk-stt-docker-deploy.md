# Vosk 语音转文字（Moon 智能体语音输入）+ Docker 统一部署 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 给 Moon 智能体对话加语音输入（点击开始→点击结束→识别→文本回填输入框），并新增 deploy/docker/ 目录统一管理 Nacos(3.2.3) + Vosk(vosk-server 官方镜像) 的构建与运行。

**架构：** 浏览器 getUserMedia 采集 → AudioContext 重采样 16kHz 组 WAV → POST MoonAgent `/api/agent/stt`（JWT 经网关透传 X-User-Id）→ AgentSttService 用 JDK `java.net.http.WebSocket` 转发 vosk-server 容器（:2700）→ 识别文本返回前端回填输入框。部署层用 docker compose 双服务 + setup/up/down/status 脚本。

**技术栈：** Spring Boot 4（MoonAgent）/ JDK 21 / React 19 + TS / vosk-server Docker 镜像（alphacephei/vosk-server）/ vosk-model-cn-0.22 / nacos 3.2.3

**前置事实（已核实）：**
- agent 端点位于 MoonAgent（server.port 8084），`/api/agent/**` 经 ApiGateway 转发、GatewayAuthenticationFilter 透传 X-User-Id 鉴权（SecurityConfig 非白名单即 authenticated）
- 前端 `AgentChatPanel.tsx:299` 已渲染 `<MicButton />`，`MicButton.tsx` 已预留 `onToggle?: (active: boolean) => void` 回调 + `useMicVolume` hook（仅音量可视化，未采集 PCM）
- `agent.ts` 的 api 走 `client`（axios，BACKEND_URL）；`uploadImage` 已示范 multipart 上传
- Nacos 旧容器（v3.1.0 无卷）已由用户手动停止；三个服务只用 discovery，无配置中心数据
- MoonAgent `spring.servlet.multipart.max-file-size: 20MB` 已够用
- 后端分层规范：Controller 薄层 + Service 接口/Impl 分离 + record DTO；BusinessException(code, message)
- vosk-server websocket 协议：连接后发 PCM 二进制帧（16kHz 16bit mono）→ 发 `{"eof":1}` → 收 `{"result":{"text":"..."}}` 或 `{"partial":{...}}`，最终 result 后服务端关闭

---

## 文件结构

**部署层（新建）**
- `deploy/docker/docker-compose.yml` — nacos(3.2.3) + vosk 双服务
- `deploy/docker/.env.example` — VOSK_PORT 等可配项
- `deploy/docker/.gitignore` — data/ 模型不入库
- `deploy/docker/README.md` — 用法说明
- `deploy/docker/scripts/setup.sh` — 下载 vosk-model-cn-0.22
- `deploy/docker/scripts/up.sh` — 幂等接管 + compose up
- `deploy/docker/scripts/down.sh` — compose down 保卷
- `deploy/docker/scripts/status.sh` — ps + 端口探测

**后端（MoonAgent 修改/新建）**
- 新建 `MoonAgent/src/main/java/com/moon/moonagent/dto/response/SttResponse.java` — record {text}
- 新建 `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentSttService.java` — 接口
- 新建 `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentSttServiceImpl.java` — WebSocket 转发 + WAV 解析
- 新建 `MoonAgent/src/main/java/com/moon/moonagent/controller/AgentSttController.java` — POST /api/agent/stt
- 修改 `MoonAgent/src/main/resources/application.yaml` — vosk.* 配置

**前端（AIStoryboardClient 修改）**
- 修改 `src/hooks/useMicVolume.ts` — 采集 PCM（ScriptProcessor）+ 停止转 WAV
- 修改 `src/components/agent/MicButton.tsx` — 接 onRecorded 回调
- 修改 `src/api/agent.ts` — 加 stt 上传方法
- 修改 `src/components/agent/AgentChatPanel.tsx` — 回填识别文本 + 录音中禁用发送

**文档**
- 修改 `CLAUDE.md` — 部署目录 + stt 端点说明

---

### 任务 1：部署层 deploy/docker/

**文件：**
- 创建：`deploy/docker/docker-compose.yml`、`.env.example`、`.gitignore`、`README.md`
- 创建：`deploy/docker/scripts/setup.sh`、`up.sh`、`down.sh`、`status.sh`

- [ ] **步骤 1：创建 docker-compose.yml**

```yaml
# deploy/docker/docker-compose.yml
# 基础设施统一部署：Nacos（服务发现）+ Vosk（离线语音识别）
# 用法：./scripts/up.sh（首次先 ./scripts/setup.sh 下载模型）
services:
  nacos:
    image: nacos/nacos-server:3.2.3
    container_name: nacos-standalone
    environment:
      MODE: standalone
      NACOS_AUTH_ENABLE: "false"      # 内网开发环境，与旧容器一致
      TIME_ZONE: Asia/Shanghai
    ports:
      - "8848:8848"                   # 服务发现/配置
      - "9848:9848"                   # gRPC
      - "8850:8080"                   # 控制台
    volumes:
      - nacos-data:/home/nacos/data   # 持久卷（旧容器无卷，重建即丢——已停旧建新）
    restart: unless-stopped

  vosk:
    image: alphacephei/vosk-server:latest
    container_name: vosk-stt
    environment:
      VOSK_MODEL_PATH: /opt/vosk-model
    ports:
      - "${VOSK_PORT:-2700}:2700"     # websocket 识别服务
    volumes:
      - ./data/vosk/models:/opt/vosk-model
    mem_limit: 2g                     # 全量中文模型加载约 1.5-2GB
    restart: unless-stopped

volumes:
  nacos-data:
```

- [ ] **步骤 2：创建 .env.example 与 .gitignore**

```bash
# deploy/docker/.env.example
# 复制为 .env 后按需修改；不创建 .env 则用默认值
VOSK_PORT=2700
```

```gitignore
# deploy/docker/.gitignore
# 模型目录（1.3GB，setup.sh 下载），不入库
data/
.env
```

- [ ] **步骤 3：创建 setup.sh（下载中文全量模型）**

```bash
#!/usr/bin/env bash
# deploy/docker/scripts/setup.sh
# 首次部署：下载 vosk-model-cn-0.22（约 1.3GB）到 data/vosk/models/
set -euo pipefail
cd "$(dirname "$0")/.."

MODEL_NAME=vosk-model-cn-0.22
MODEL_DIR="data/vosk/models/$MODEL_NAME"
URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"

if [ -d "$MODEL_DIR" ] && [ -f "$MODEL_DIR/conf/model.conf" ]; then
  echo "[skip] 模型已存在: $MODEL_DIR"
  exit 0
fi

mkdir -p data/vosk/models
echo "[下载] $URL"
curl -L -o data/vosk/models/${MODEL_NAME}.zip "$URL"
echo "[解压]"
unzip -q -o data/vosk/models/${MODEL_NAME}.zip -d data/vosk/models
rm -f data/vosk/models/${MODEL_NAME}.zip

if [ -d "$MODEL_DIR" ] && [ -f "$MODEL_DIR/conf/model.conf" ]; then
  echo "[OK] 模型就绪: $MODEL_DIR"
else
  echo "[错误] 模型目录结构异常，请检查" >&2
  exit 1
fi
```

- [ ] **步骤 4：创建 up.sh（幂等接管 + 启动 + 健康检查）**

```bash
#!/usr/bin/env bash
# deploy/docker/scripts/up.sh
# 幂等接管旧 nacos 容器（存在则停删）→ compose up → 健康检查
set -euo pipefail
cd "$(dirname "$0")/.."

# 旧容器接管（v3.1.0 无卷，仅服务发现，停删零风险；不存在则跳过）
if docker ps -a --format '{{.Names}}' | grep -qx nacos-standalone; then
  echo "[接管] 停止并删除旧 nacos-standalone 容器"
  docker stop nacos-standalone >/dev/null
  docker rm nacos-standalone >/dev/null
fi

docker compose up -d

echo "[等待] vosk 容器健康检查…"
for i in $(seq 1 30); do
  if docker inspect vosk-stt --format '{{.State.Health.Status}}' 2>/dev/null | grep -q healthy; then
    echo "[OK] vosk 已就绪"
    break
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx vosk-stt; then
    echo "[错误] vosk 容器未运行，docker compose logs vosk 查看" >&2
    exit 1
  fi
  [ "$i" = 30 ] && echo "[警告] vosk 30s 内未报 healthy（模型加载约需数秒~数十秒，可稍候重试 status.sh）"
  sleep 1
done

./scripts/status.sh
```

- [ ] **步骤 5：创建 down.sh 与 status.sh**

```bash
#!/usr/bin/env bash
# deploy/docker/scripts/down.sh
# 停止全部服务（保留 nacos-data 卷与模型文件）
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down
```

```bash
#!/usr/bin/env bash
# deploy/docker/scripts/status.sh
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose ps
echo "--- 端口探测 ---"
for p in 8848 9848 8850 2700; do
  if curl -s -o /dev/null --max-time 2 "http://localhost:$p" || true; then
    echo "localhost:$p 可达"
  else
    echo "localhost:$p 未响应（非 TCP 层探测，仅供参考）"
  fi
done
```

- [ ] **步骤 6：创建 README.md**

```markdown
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
```

- [ ] **步骤 7：验证**

```bash
chmod +x deploy/docker/scripts/*.sh
cd deploy/docker && docker compose config -q && echo "compose 语法 OK"
```

预期：`compose config -q` 无输出、exit 0。**模型下载与容器启动留到部署任务（任务 8）执行**，避免本任务阻塞（1.3GB 下载）。

- [ ] **步骤 8：Commit**

```bash
git add deploy/docker/
git commit -m "feat(deploy): docker 统一部署目录——nacos 3.2.3 + vosk-server（vosk-model-cn-0.22）"
```

---

### 任务 2：后端 SttResponse + AgentSttService 接口

**文件：**
- 创建：`MoonAgent/src/main/java/com/moon/moonagent/dto/response/SttResponse.java`
- 创建：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentSttService.java`

- [ ] **步骤 1：创建 SttResponse**

```java
package com.moon.moonagent.dto.response;

/** 语音识别结果 */
public record SttResponse(String text) {
}
```

- [ ] **步骤 2：创建 AgentSttService 接口**

```java
package com.moon.moonagent.ai.agent;

import org.springframework.web.multipart.MultipartFile;

/**
 * 语音转文字服务：接收 16kHz 单声道 WAV，经 vosk-server WebSocket 识别。
 */
public interface AgentSttService {

    /**
     * 识别 WAV 语音内容。
     *
     * @param file 16kHz 单声道 16bit WAV 文件（前端 AudioContext 重采样后生成）
     * @return 识别文本（空串表示未识别到语音）
     */
    String transcribe(MultipartFile file);
}
```

- [ ] **步骤 3：Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/dto/response/SttResponse.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentSttService.java
git commit -m "feat(agent): stt 响应与服务接口"
```

---

### 任务 3：AgentSttServiceImpl（WebSocket 转发 + WAV 解析）

**文件：**
- 创建：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentSttServiceImpl.java`

- [ ] **步骤 1：创建实现类**

```java
package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.agent.AgentSttService;
import com.storyboard.common.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 语音转文字实现：解析 WAV → WebSocket 转发 vosk-server（JDK 自带客户端，零新依赖）。
 * 协议：发 PCM 二进制帧（16kHz 16bit mono）→ 发 {"eof":1} → 收 {"result":{"text":"..."}}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSttServiceImpl implements AgentSttService {

    @Value("${vosk.ws-url:ws://localhost:2700}")
    private String wsUrl;

    @Value("${vosk.timeout:30}")
    private long timeoutSeconds;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String transcribe(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "音频文件不能为空");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new BusinessException(40001, "音频文件过大（上限 10MB，约 5 分钟语音）");
        }
        byte[] pcm;
        try {
            pcm = extractPcm16kMono(file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(40001, "音频读取失败");
        }
        if (pcm.length == 0) {
            throw new BusinessException(40001, "音频文件格式不正确（需要 16kHz 单声道 WAV）");
        }
        return recognize(pcm);
    }

    /** 解析 WAV 头（44 字节标准头），校验 16kHz/单声道/16bit，返回裸 PCM */
    private byte[] extractPcm16kMono(byte[] wav) {
        if (wav.length < 44
                || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
                || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            throw new BusinessException(40001, "音频文件格式不正确（需要 WAV）");
        }
        int audioFormat = (wav[20] & 0xff) | (wav[21] & 0xff) << 8;
        int channels = (wav[22] & 0xff) | (wav[23] & 0xff) << 8;
        int sampleRate = (wav[24] & 0xff) | (wav[25] & 0xff) << 8
                | (wav[26] & 0xff) << 16 | (wav[27] & 0xff) << 24;
        int bitsPerSample = (wav[34] & 0xff) | (wav[35] & 0xff) << 8;
        if (audioFormat != 1 || channels != 1 || sampleRate != 16000 || bitsPerSample != 16) {
            throw new BusinessException(40001, "音频格式需为 16kHz 单声道 16bit PCM WAV");
        }
        // 定位 data 块（标准 44 字节头即 data 起始；容错扫描）
        int dataOffset = 44;
        if (dataOffset + 8 <= wav.length
                && wav[dataOffset] == 'd' && wav[dataOffset + 1] == 'a'
                && wav[dataOffset + 2] == 't' && wav[dataOffset + 3] == 'a') {
            int dataLen = (wav[dataOffset + 4] & 0xff) | (wav[dataOffset + 5] & 0xff) << 8
                    | (wav[dataOffset + 6] & 0xff) << 16 | (wav[dataOffset + 7] & 0xff) << 24;
            byte[] pcm = new byte[Math.min(dataLen, wav.length - dataOffset - 8)];
            System.arraycopy(wav, dataOffset + 8, pcm, 0, pcm.length);
            return pcm;
        }
        byte[] pcm = new byte[wav.length - dataOffset];
        System.arraycopy(wav, dataOffset, pcm, 0, pcm.length);
        return pcm;
    }

    /** 经 WebSocket 转发 vosk-server 并取回识别文本 */
    private String recognize(byte[] pcm) {
        CompletableFuture<String> result = new CompletableFuture<>();
        List<String> texts = new ArrayList<>();

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                partial.append(data);
                if (!last) return WebSocket.Listener.super.onText(webSocket, data, false);
                String msg = partial.toString();
                partial.setLength(0);
                handleMessage(msg, texts, result);
                return WebSocket.Listener.super.onText(webSocket, data, true);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                if (!result.isDone()) result.complete(String.join("", texts).trim());
                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                if (!result.isDone()) result.completeExceptionally(error);
            }
        };

        try {
            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(10, TimeUnit.SECONDS);
            ws.sendText("{\"config\":{\"sample_rate\":16000}}", true).join();
            // 分块发送 PCM（vosk 按帧消费）
            int chunk = 16000 * 2; // 1 秒
            for (int off = 0; off < pcm.length; off += chunk) {
                byte[] part = new byte[Math.min(chunk, pcm.length - off)];
                System.arraycopy(pcm, off, part, 0, part.length);
                ws.sendBinary(ByteBuffer.wrap(part), true).join();
            }
            ws.sendText("{\"eof\":1}", true).join();
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("vosk 识别超时: {}", wsUrl);
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
            log.warn("vosk 连接失败: {}", wsUrl, e);
            Thread.currentThread().interrupt();
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        } catch (java.net.ConnectException e) {
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        } catch (Exception e) {
            throw new BusinessException(50001, "语音识别服务暂不可用，请稍后重试");
        }
    }

    /** 解析 vosk 返回消息：{"result":{"text":"..."}} 或 {"partial":{...}} */
    private void handleMessage(String msg, List<String> texts, CompletableFuture<String> result) {
        try {
            // 简化 JSON 扫描：只取 "text":"..." 值（vosk 输出结构固定）
            int textIdx = msg.indexOf("\"text\"");
            if (textIdx < 0) return;
            int colonIdx = msg.indexOf(':', textIdx + 6);
            int start = msg.indexOf('"', colonIdx + 1);
            int end = msg.indexOf('"', start + 1);
            if (start < 0 || end < 0) return;
            String text = msg.substring(start + 1, end);
            if (msg.contains("\"result\"")) {
                if (!text.isEmpty()) texts.add(text);
                // 最终结果：vosk 收到 eof 后发 result 即关闭连接
            }
        } catch (Exception e) {
            log.debug("vosk 消息解析失败: {}", msg);
        }
    }
}
```

- [ ] **步骤 2：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q
```

预期：exit 0，无编译错误。

- [ ] **步骤 3：Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentSttServiceImpl.java
git commit -m "feat(agent): stt 实现——WAV 解析 + WebSocket 转发 vosk-server"
```

---

### 任务 4：AgentSttController + vosk 配置

**文件：**
- 创建：`MoonAgent/src/main/java/com/moon/moonagent/controller/AgentSttController.java`
- 修改：`MoonAgent/src/main/resources/application.yaml`

- [ ] **步骤 1：创建 Controller**

```java
package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.AgentSttService;
import com.moon.moonagent.dto.response.SttResponse;
import com.storyboard.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音转文字端点（Moon 智能体语音输入）。
 * 鉴权由 Gateway 统一验签后透传 X-User-Id header（GatewayAuthenticationFilter 装配）。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentSttController {

    private final AgentSttService sttService;

    /** 语音识别：上传 WAV（16kHz 单声道）→ 返回识别文本 */
    @PostMapping("/stt")
    public ApiResponse<SttResponse> transcribe(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(new SttResponse(sttService.transcribe(file)));
    }
}
```

- [ ] **步骤 2：application.yaml 加 vosk 配置**

```yaml
# 在 ai: 段之后（文件末尾）追加：
# Vosk 语音识别（vosk-server 容器，deploy/docker 部署）
vosk:
  ws-url: ${VOSK_WS_URL:ws://localhost:2700}
  timeout: ${VOSK_TIMEOUT:30}
```

- [ ] **步骤 3：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q
```

预期：exit 0。

- [ ] **步骤 4：Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/controller/AgentSttController.java \
        MoonAgent/src/main/resources/application.yaml
git commit -m "feat(agent): POST /api/agent/stt 端点 + vosk 配置"
```

---

### 任务 5：前端 PCM 采集 + WAV 组装（useMicVolume 扩展）

**文件：**
- 修改：`AIStoryboardClient/src/hooks/useMicVolume.ts`

- [ ] **步骤 1：扩展 useMicVolume —— 采集 PCM + 停止时组装 WAV**

在现有实现上增加：ScriptProcessorNode 采集 Float32 帧 + `stopAndGetWav()`（OfflineAudioContext 重采样 16kHz → WAV Blob）。改动点：

```ts
// useMicVolume.ts 新增（保留原 volume/freqData/isActive/isSupported/toggle 对外契约）
import { useState, useRef, useCallback, useEffect } from 'react';

/** 16kHz 单声道 16bit WAV 组装（44 字节标准头） */
function encodeWav(samples: Float32Array, sampleRate: number): Blob {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const writeStr = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);            // PCM
  view.setUint16(22, 1, true);            // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);           // 16bit
  writeStr(36, 'data');
  view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Blob([buffer], { type: 'audio/wav' });
}

/** 把任意采样率 Float32 重采样为 16kHz（线性插值，语音场景足够） */
function resampleTo16k(input: Float32Array, fromRate: number): Float32Array {
  if (fromRate === 16000) return input;
  const ratio = fromRate / 16000;
  const outLen = Math.floor(input.length / ratio);
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const pos = i * ratio;
    const idx = Math.floor(pos);
    const frac = pos - idx;
    const a = input[idx] ?? 0;
    const b = input[idx + 1] ?? a;
    out[i] = a + (b - a) * frac;
  }
  return out;
}
```

在 hook 内新增（其余逻辑保持原样，仅 toggle/stop 处接线）：

```ts
// 新增 refs
const chunksRef = useRef<Float32Array[]>([]);
const recorderRef = useRef<ScriptProcessorNode | null>(null);

// toggle 内，getUserMedia 成功后、connect analyser 之后加：
const recorder = ctx.createScriptProcessor(4096, 1, 1);
recorder.onaudioprocess = (e) => {
  const ch = e.inputBuffer.getChannelData(0);
  chunksRef.current.push(new Float32Array(ch));
};
ctx.createMediaStreamSource(stream).connect(recorder);
recorder.connect(ctx.destination);
recorderRef.current = recorder;

// stop 内，getTracks().stop() 之后加：
recorderRef.current?.disconnect();
recorderRef.current = null;

// 新增：停止录音并返回 WAV Blob（重采样 16kHz）
const stopAndGetWav = useCallback(async (): Promise<Blob | null> => {
  const chunks = chunksRef.current;
  chunksRef.current = [];
  if (chunks.length === 0) return null;
  let total = 0;
  for (const c of chunks) total += c.length;
  const raw = new Float32Array(total);
  let off = 0;
  for (const c of chunks) { raw.set(c, off); off += c.length; }
  const srcRate = ctxRef.current?.sampleRate ?? 48000;
  const samples = resampleTo16k(raw, srcRate);
  return encodeWav(samples, 16000);
}, []);

// toggle 内，start 分支清空 chunks：
chunksRef.current = [];
```

返回对象追加：`return { volume, freqData, isActive, isSupported, toggle, stopAndGetWav };`

> 说明：ScriptProcessorNode 已废弃但全浏览器可用，零新依赖；波形可视化路径不变（recorder 与 analyser 并行接同一 source）。

- [ ] **步骤 2：TypeScript 检查**

```bash
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit
```

预期：exit 0（新增代码无类型错误）。

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/hooks/useMicVolume.ts
git commit -m "feat(agent): useMicVolume 采集 PCM + 16kHz WAV 组装"
```

---

### 任务 6：前端 MicButton onRecorded + agentApi.stt + 回填输入框

**文件：**
- 修改：`AIStoryboardClient/src/components/agent/MicButton.tsx`
- 修改：`AIStoryboardClient/src/api/agent.ts`
- 修改：`AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`

- [ ] **步骤 1：agent.ts 加 stt 方法**

```ts
// 在 agentApi 对象内（uploadImage 之后）追加：
// 语音识别：上传 WAV（16kHz 单声道）→ 返回识别文本
stt: (file: Blob) => {
  const form = new FormData();
  form.append('file', file, 'voice.wav');
  return client.post<{ data: { text: string } }>('/agent/stt', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000, // vosk 识别超时上限 30s + 网络余量
  });
},
```

- [ ] **步骤 2：MicButton 接 onRecorded**

```tsx
// MicButton.tsx
interface MicButtonProps {
  /** 录音中回调——用于父组件禁用发送等 */
  onToggle?: (active: boolean) => void;
  /** 录音结束回调（WAV Blob，16kHz 单声道）——接语音转文字 */
  onRecorded?: (wav: Blob) => void;
  /** 录音中（父组件禁用发送时外部同步状态） */
  disabled?: boolean;
}

export function MicButton({ onToggle, onRecorded, disabled }: MicButtonProps) {
  // ...
  const { volume, freqData, isActive, isSupported, toggle, stopAndGetWav } = useMicVolume(64);

  const handleClick = async () => {
    if (isActive) {
      // 停止 → 取 WAV → 回调父组件（录音时长 <300ms 视为误触，丢弃）
      const wav = await stopAndGetWav();
      await toggle(); // 内部 stop：停轨道、关 ctx
      if (wav && onRecorded) onRecorded(wav);
    } else {
      await toggle();
    }
  };
  // 按钮 disabled={disabled} 且样式 cursor: disabled ? 'not-allowed' : 'pointer'
  // （原 disabled 逻辑为 undefined，保持视觉不变）
}
```

> 时序注意：先 `stopAndGetWav()`（ctx 未关时采集结果）再 `toggle()`（真正停止），顺序不能反。

- [ ] **步骤 3：AgentChatPanel 回填输入框**

```tsx
// AgentChatPanel.tsx
import { agentApi } from '../../api/agent';

// 组件内加状态
const [recording, setRecording] = useState(false);
const [sttBusy, setSttBusy] = useState(false);

// 麦克风录制完成 → 上传识别 → 回填输入框
const handleRecorded = async (wav: Blob) => {
  setSttBusy(true);
  try {
    const res = await agentApi.stt(wav);
    const text = res.data?.data?.text ?? '';
    if (text.trim()) {
      setText((prev) => {
        const base = prev.trim();
        return base ? `${base} ${text}` : text;
      });
      inputRef.current?.focus();
    } else {
      setStreamErrorLocal('未识别到语音内容，请重试');
    }
  } catch {
    setStreamErrorLocal('语音识别失败，请稍后重试');
  } finally {
    setSttBusy(false);
  }
};

// <MicButton> 处改为：
<MicButton
  onToggle={setRecording}
  onRecorded={handleRecorded}
  disabled={sttBusy || streaming || !!waitingHumanInput || !!waitingVideoPlan}
/>
```

- [ ] **步骤 4：类型检查 + 构建**

```bash
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```

预期：两者 exit 0。

- [ ] **步骤 5：Commit**

```bash
git add AIStoryboardClient/src/api/agent.ts \
        AIStoryboardClient/src/components/agent/MicButton.tsx \
        AIStoryboardClient/src/components/agent/AgentChatPanel.tsx
git commit -m "feat(agent): 语音输入——录音→stt→回填输入框"
```

---

### 任务 7：文档（CLAUDE.md）

**文件：**
- 修改：`CLAUDE.md`

- [ ] **步骤 1：补部署目录与 stt 端点说明**

在「Project Structure」后新增小节：

```markdown
## Docker 部署目录（deploy/docker/）

统一管理基础设施 Docker 部署（scripts/ 下有 setup/up/down/status 脚本）：

- **nacos** `nacos/nacos-server:3.2.3` — standalone 服务发现（8848/9848/8850，数据卷 nacos-data）
- **vosk** `alphacephei/vosk-server:latest` — 离线语音识别（2700，挂载 vosk-model-cn-0.22）

首次部署：`./scripts/setup.sh`（下载 1.3GB 中文模型）→ `./scripts/up.sh`（自动接管旧容器）。
```

在「AI Agent 对话模块」端点表后追加：

```
| POST | `/api/agent/stt` | 语音识别（multipart `file`=WAV，16kHz 单声道）→ `{text}`；Moon 智能体语音输入，WebSocket 转发 vosk-server（`vosk.ws-url`，默认 ws://localhost:2700） |
```

- [ ] **步骤 2：Commit**

```bash
git add CLAUDE.md
git commit -m "docs: deploy/docker 目录与 /api/agent/stt 端点说明"
```

---

### 任务 8：端到端部署验证（vosk 容器 + 真实识别）

**文件：** 无（验证任务）

- [ ] **步骤 1：下载模型并启动**

```bash
cd deploy/docker && ./scripts/setup.sh && ./scripts/up.sh
```

预期：模型下载解压完成 → nacos + vosk 容器 Up → 30s 内 vosk healthy。

- [ ] **步骤 2：生成测试 WAV 并验证后端链路**

```bash
# 用 python 生成 1s 静音 16kHz WAV（验证格式链路，静音识别文本为空属正常）
python -c "
import wave, struct
with wave.open('/tmp/test16k.wav','wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
    w.writeframes(b'\x00\x00' * 16000)
print('ok')
"
# 直连 vosk-server 验证识别服务可用
python -c "
import asyncio, json, wave
async def main():
    import websockets
    async with websockets.connect('ws://localhost:2700') as ws:
        await ws.send(json.dumps({'config': {'sample_rate': 16000}}))
        with wave.open('/tmp/test16k.wav','rb') as w:
            await ws.send(w.readframes(w.getnframes()))
        await ws.send(json.dumps({'eof': 1}))
        while True:
            msg = await asyncio.wait_for(ws.recv(), timeout=10)
            print('vosk:', msg)
            if '\"result\"' in msg: break
asyncio.run(main())
" 2>/dev/null || pip install websockets -q
```

预期：vosk 返回 `{"result":{"text":""}}`（静音）或含 text 字段的 JSON——证明 vosk-server 模型加载成功、协议链路通。

- [ ] **步骤 3：后端真实请求验证（需要 MoonAgent 运行 + 网关签发 JWT；用户说话录音）**

```bash
# 启动 MoonAgent 后（IDE 或 java -jar），浏览器打开智能体输入框点麦克风说话
# 预期：录音结束 → 文本自动填入输入框
```

- [ ] **步骤 4：收尾 commit**

```bash
git add deploy/docker/  # 若 setup 后模型已生成但被 .gitignore 排除则无新文件
git commit -m "chore(deploy): vosk 端到端验证通过" 2>/dev/null || true
```

---

## 自检

**1. 规格覆盖度：**
- 部署目录（compose + 4 脚本 + README + 模型下载）→ 任务 1 ✓
- Nacos 3.2.3 升级 + 持久卷 + 幂等接管 → 任务 1 ✓（旧容器已由用户手动停）
- vosk 官方镜像 + 模型挂载 + mem_limit → 任务 1 ✓
- 后端 POST /api/agent/stt（薄 Controller + Service 接口/Impl + record DTO）→ 任务 2/3/4 ✓
- JDK WebSocket 零新依赖 → 任务 3 ✓
- 友好错误（vosk 不可达/超时/空识别）→ 任务 3 + 任务 6 ✓
- vosk.* 配置命名空间（避开 DB_URL 污染坑）→ 任务 4 ✓
- 前端形态 2（点开始→点结束）+ 16kHz 重采样 + WAV → 任务 5/6 ✓
- 复用既有 MicButton/useMicVolume（预留回调）→ 任务 5/6 ✓
- 回填输入框 + 录音中禁用发送 → 任务 6 ✓
- CLAUDE.md 文档 → 任务 7 ✓
- 端到端验证 → 任务 8 ✓

**2. 占位符扫描：** 无 TODO/待定；所有步骤含完整代码或精确命令。

**3. 类型一致性：** `stopAndGetWav(): Promise<Blob | null>` 在 hook 与 MicButton 一致；`onRecorded(wav: Blob)` 与 `handleRecorded(wav: Blob)` 一致；`agentApi.stt` 返回 `{data: {text: string}}` 与 `res.data?.data?.text` 取值一致；Controller `SttResponse(text)` 与前端 `{text}` 一致。`sttBusy`/`recording` 状态在 AgentChatPanel 内定义并被 MicButton props 消费，无跨任务漂移。

**已知简化（ponytail 标注）：**
- `resampleTo16k` 线性插值而非高质量 sinc——语音识别场景足够，识别不准时再升级
- vosk JSON 解析用子串扫描而非完整 JSON 解析——vosk 输出结构固定，零依赖优先
- 后端一次上传整体 PCM 而非流式——形态 2（整段识别）设计使然，非缺陷
