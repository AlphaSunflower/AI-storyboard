# AI Agent 对话模块实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 AI Agent 对话建立数据模型（conversations / agent_messages / agent_assets 三表）+ 后端 API（会话/消息/资产/上传）+ Dify chat-messages 代理，并改造 DifyAgentController 消灭临时 scene 孤儿数据、支持 PicUrl 图生图/图生视频。

**架构：** 新增独立 Agent 对话模块（`/api/agent/**`，JWT 鉴权）；`AgentChatService` 后端代理 Dify `/v1/chat-messages`（blocking 模式）；`DifyAgentController`（`/api/ai/dify/**`，X-Dify-Key 鉴权）无 sceneId 时改写入 agent_assets（不再创建临时 scene）；`ImageGenerationService`/`VideoGenerationService` 支持 sceneId 为空（跳过 scene 读写）。

**技术栈：** Spring Boot 4 / MyBatis-Plus / PostgreSQL（TEXT UUID 主键、OffsetDateTime 时间戳）/ JDK HttpClient / Lombok

**设计文档：** `docs/superpowers/specs/2026-08-03-agent-conversation-design.md`

**项目根目录：** `E:\Desktop\AI-storyboard`（后端模块 `AIStoryboardBackend`）

---

## 文件结构

**创建（后端）：**
- `AIStoryboardBackend/src/main/resources/db/migration/V2__agent_conversation.sql`
- `AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentConversation.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentMessage.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentAsset.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentConversationMapper.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentMessageMapper.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentAssetMapper.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentCreateConversationRequest.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentSendMessageRequest.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java`

**修改（后端）：**
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java`（加 `difyBaseUrl`）
- `AIStoryboardBackend/src/main/resources/application.yml`（加 `dify-base-url`）
- `AIStoryboardBackend/src/main/java/com/storyboard/service/FileStorageService.java`（加 `saveUploadedImage`）
- `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateImageRequest.java`（加 `conversationId` + `picUrl`）
- `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateVideoRequest.java`（加 `conversationId` + `picUrl`）
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java`（sceneId 可空）
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java`（sceneId 可空 + 双通道反查）
- `AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java`（写 agent_assets + picUrl）

**测试策略：** 本项目无单元测试基础设施，验证 = `mvn compile -q` 编译通过 + 最后手工 curl 冒烟验证。

---

## 任务 1：V2 migration SQL + 3 实体 + 3 Mapper

**文件：**
- 创建：`AIStoryboardBackend/src/main/resources/db/migration/V2__agent_conversation.sql`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentConversation.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentMessage.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentAsset.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentConversationMapper.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentMessageMapper.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentAssetMapper.java`

- [ ] **步骤 1：写 V2 migration SQL**

`AIStoryboardBackend/src/main/resources/db/migration/V2__agent_conversation.sql`：

```sql
-- V2__agent_conversation.sql
-- AI Agent 对话模块：会话 / 消息 / 生成资产（与分镜 scenes 无关）

CREATE TABLE IF NOT EXISTS conversations (
    id                   TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id              TEXT NOT NULL,
    project_id           TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title                TEXT NOT NULL DEFAULT '新对话',
    dify_conversation_id TEXT,
    status               TEXT NOT NULL DEFAULT 'active',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_project ON conversations(user_id, project_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_messages (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role             TEXT NOT NULL,
    content          TEXT NOT NULL,
    dify_message_id  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_messages_conv ON agent_messages(conversation_id, created_at);

CREATE TABLE IF NOT EXISTS agent_assets (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT REFERENCES conversations(id) ON DELETE CASCADE,
    type             TEXT NOT NULL,
    url              TEXT NOT NULL,
    prompt           TEXT,
    model            TEXT,
    status           TEXT NOT NULL DEFAULT 'queued',
    task_id          TEXT,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_assets_conv ON agent_assets(conversation_id, type);
```

- [ ] **步骤 2：写 3 个实体**

`entity/AgentConversation.java`：

```java
package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "conversations", schema = "public")
public class AgentConversation {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String projectId;
    private String title;
    private String difyConversationId;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
```

`entity/AgentMessage.java`：

```java
package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_messages", schema = "public")
public class AgentMessage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String role;
    private String content;
    private String difyMessageId;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
```

`entity/AgentAsset.java`：

```java
package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_assets", schema = "public")
public class AgentAsset {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String type;    // image | video | reference
    private String url;     // /api/files/images/xxx.png 或 /api/files/videos/xxx.mp4
    private String prompt;
    private String model;
    private String status;  // queued | generating | completed | failed
    private String taskId;  // 视频异步任务 ID
    private String error;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
```

- [ ] **步骤 3：写 3 个 Mapper**

`mapper/AgentConversationMapper.java`：

```java
package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
}
```

`mapper/AgentMessageMapper.java`（extends BaseMapper\<AgentMessage>）、`mapper/AgentAssetMapper.java`（extends BaseMapper\<AgentAsset>）——同样模式，分别 import 对应实体。

- [ ] **步骤 4：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
预期：BUILD SUCCESS（无输出 = 成功）

- [ ] **步骤 5：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/resources/db/migration/V2__agent_conversation.sql \
        AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentConversation.java \
        AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentMessage.java \
        AIStoryboardBackend/src/main/java/com/storyboard/entity/AgentAsset.java \
        AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentConversationMapper.java \
        AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentMessageMapper.java \
        AIStoryboardBackend/src/main/java/com/storyboard/mapper/AgentAssetMapper.java
git commit -m "feat: agent 对话三表（conversations/agent_messages/agent_assets）+ 实体与 Mapper"
```

---

## 任务 2：配置扩展（difyBaseUrl）+ FileStorageService.saveUploadedImage

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java`
- 修改：`AIStoryboardBackend/src/main/resources/application.yml`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/FileStorageService.java`

- [ ] **步骤 1：AiConfigProperties 加 difyBaseUrl**

在 `baseUrlVision` 字段下方（第 43 行附近）加字段：

```java
    /** Dify 自托管基础地址（对话代理 /v1/chat-messages 用） */
    private String difyBaseUrl;
```

在 `getBaseUrlVision`/`setBaseUrlVision`（第 138 行附近）下方加 getter/setter：

```java
    public String getDifyBaseUrl() { return difyBaseUrl; }
    public void setDifyBaseUrl(String s) { this.difyBaseUrl = s; }
```

- [ ] **步骤 2：application.yml 加 dify-base-url**

在 `dify-api-key` 行（第 54 行）下方：

```yaml
    # Dify 自托管基础地址（默认本机 Docker 部署）
    dify-base-url: ${DIFY_BASE_URL:http://localhost}
```

- [ ] **步骤 3：FileStorageService 加 saveUploadedImage**

在 `saveImageFromBase64` 方法（第 70 行）之后插入：

```java
    /**
     * 保存用户上传的图片文件（Agent 对话参考图）。
     * @return local relative path like /api/files/images/xxx.png
     */
    public String saveUploadedImage(org.springframework.web.multipart.MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new RuntimeException("上传文件为空");
            }
            String original = file.getOriginalFilename();
            String extension = "png";
            if (original != null && original.contains(".")) {
                extension = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
                if (extension.length() > 5) extension = "png"; // 防御异常扩展名
            }
            String filename = UUID.randomUUID().toString() + "." + extension;
            Path target = IMAGES_DIR.resolve(filename);
            Files.write(target, file.getBytes());
            log.info("Saved uploaded image: {}", target);
            return "/api/files/images/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("保存上传图片失败: " + e.getMessage(), e);
        }
    }
```

- [ ] **步骤 4：编译验证**（同任务 1 步骤 4 命令，预期 BUILD SUCCESS）

- [ ] **步骤 5：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java \
        AIStoryboardBackend/src/main/resources/application.yml \
        AIStoryboardBackend/src/main/java/com/storyboard/service/FileStorageService.java
git commit -m "feat: AiConfigProperties 增加 difyBaseUrl，FileStorageService 支持上传图片保存"
```

---

## 任务 3：Dify DTO 增加 conversationId + picUrl

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateImageRequest.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateVideoRequest.java`

- [ ] **步骤 1：DifyGenerateImageRequest 加字段**

`dto/request/DifyGenerateImageRequest.java` 完整替换为：

```java
package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 图片生成请求
 *
 * @param conversationId      Agent 会话 ID（sceneId 为空时资产归属该会话；为空则未归属）
 * @param picUrl              用户上传的参考图 URL（图生图源图，优先于 generatedImageUrl）
 * @param generatedImageUrl   完善图片时传入的已有图片 URL（仅 edit 模式使用，作为源图）
 */
public record DifyGenerateImageRequest(
    String projectId,
    String sceneId,
    String prompt,
    String model,
    String size,
    String quality,
    String mode,
    String generatedImageUrl,
    List<String> referenceImageUrls,
    String conversationId,
    String picUrl
) {}
```

- [ ] **步骤 2：DifyGenerateVideoRequest 加字段**

`dto/request/DifyGenerateVideoRequest.java` 完整替换为：

```java
package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 视频生成请求（后端代理 Laozhang 异步轮询）
 *
 * @param conversationId  Agent 会话 ID（sceneId 为空时资产归属该会话；为空则未归属）
 * @param picUrl          用户上传的参考图 URL（图生视频源图）
 */
public record DifyGenerateVideoRequest(
    String projectId,
    String sceneId,
    String prompt,
    String model,
    String resolution,
    String size,
    String aspectRatio,
    String duration,
    String negativePrompt,
    List<String> referenceImageUrls,
    String conversationId,
    String picUrl
) {}
```

- [ ] **步骤 3：编译验证**（预期 BUILD SUCCESS）

- [ ] **步骤 4：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateImageRequest.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateVideoRequest.java
git commit -m "feat: Dify 生成请求 DTO 增加 conversationId 与 picUrl 字段"
```

---

## 任务 4：ImageGenerationService 支持 sceneId 为空

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java:59-111`

**目标：** sceneId 为 null 时不读写 scene 表，只返回本地文件 URL（供 agent_assets 落库）。

- [ ] **步骤 1：改 generateImage 方法体**

将 `generateImage` 方法（第 59-111 行）完整替换为：

```java
    public String generateImage(String sceneId, String prompt, String model,
                                 String size, String quality, String aspectRatio,
                                 List<String> referenceImages,
                                 String mode, String generatedImageUrl) {
        // sceneId 可为空：为空时不读写 scene 表（agent_assets 模式）
        Scene scene = sceneId != null ? sceneMapper.selectById(sceneId) : null;
        if (sceneId != null && scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("图片生成 prompt 不能为空（Dify 变量可能未正确设置）");
        }

        String effectiveModel = model != null ? model : config.getDefaultImageModel();

        if (scene != null) {
            scene.setImageStatus("generating");
            sceneMapper.updateById(scene);
        }

        try {
            String result;
            String localPath;
            boolean hasReferenceImages = referenceImages != null && !referenceImages.isEmpty();

            // 有参考图或显式 edit 模式 → /v1/images/edits multipart 接口
            if (hasReferenceImages || "edit".equals(mode)) {
                result = callImageEdit(effectiveModel, prompt, referenceImages, generatedImageUrl);
                localPath = fileStorageService.saveImageFromBase64(result);

            // Gemini 原生接口
            } else if (config.getGeminiImageModelSet().contains(effectiveModel)) {
                result = callGeminiImage(prompt, aspectRatio, referenceImages);
                localPath = fileStorageService.saveImageFromBase64(result);

            // 纯文生图：/v1/images/generations JSON 接口
            } else {
                result = callOpenAIImage(effectiveModel, prompt, size, quality, aspectRatio);
                if (result.startsWith("http://") || result.startsWith("https://")) {
                    localPath = fileStorageService.saveImage(result);
                } else {
                    localPath = fileStorageService.saveImageFromBase64(result);
                }
            }

            if (scene != null) {
                scene.setImageUrl(localPath);
                scene.setImageStatus("completed");
                sceneMapper.updateById(scene);
            }
            return localPath;
        } catch (Exception e) {
            if (scene != null) {
                scene.setImageStatus("failed");
                sceneMapper.updateById(scene);
            }
            throw new RuntimeException("AI 图片生成失败: " + e.getMessage(), e);
        }
    }
```

注意：原代码在第 83/89/100 行直接 `scene.setImageUrl(localPath)`，新代码用局部变量 `localPath` 统一在最后写回 scene（scene 非空时）。

- [ ] **步骤 2：编译验证**（预期 BUILD SUCCESS；Scene 仍被引用，import 不变）

- [ ] **步骤 3：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java
git commit -m "refactor: ImageGenerationService 支持 sceneId 为空（agent_assets 模式不读写 scene）"
```

---

## 任务 5：VideoGenerationService 支持 sceneId 为空 + 双通道反查

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java`
- 新增注入：`AgentAssetMapper`

**目标：** sceneId 为 null 时跳过 scene 读写；`pollVideoTask` 先按 taskId 查 scene，查不到再查 agent_assets。

- [ ] **步骤 1：注入 AgentAssetMapper**

构造函数（第 41-46 行）替换为：

```java
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;
    private final FileStorageService fileStorageService;

    public VideoGenerationService(AiConfigProperties config, SceneMapper sceneMapper,
                                   AgentAssetMapper agentAssetMapper,
                                   FileStorageService fileStorageService) {
        this.config = config;
        this.sceneMapper = sceneMapper;
        this.agentAssetMapper = agentAssetMapper;
        this.fileStorageService = fileStorageService;
    }
```

import 行加：

```java
import com.storyboard.entity.AgentAsset;
import com.storyboard.mapper.AgentAssetMapper;
```

- [ ] **步骤 2：createVideoTask 支持 sceneId 为空**

第 55-56 行替换：

```java
        Scene scene = sceneId != null ? sceneMapper.selectById(sceneId) : null;
        if (sceneId != null && scene == null) throw new RuntimeException("分镜不存在: " + sceneId);
```

第 131-133 行（任务创建成功后）替换：

```java
            if (scene != null) {
                scene.setVideoTaskId(taskId);
                scene.setVideoStatus("generating");
                sceneMapper.updateById(scene);
            }
```

第 137-139 行（catch 中）替换：

```java
        } catch (Exception e) {
            if (scene != null) {
                scene.setVideoStatus("failed");
                sceneMapper.updateById(scene);
            }
            throw new RuntimeException("AI 视频生成失败: " + e.getMessage(), e);
        }
```

- [ ] **步骤 3：pollVideoTask 双通道反查**

completed 分支（第 169-176 行）替换：

```java
                var scenes = sceneMapper.selectList(
                    new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, taskId));
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoUrl(localPath);
                    scene.setVideoStatus("completed");
                    sceneMapper.updateById(scene);
                } else {
                    var assets = agentAssetMapper.selectList(
                        new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getTaskId, taskId));
                    if (!assets.isEmpty()) {
                        AgentAsset asset = assets.get(0);
                        asset.setUrl(localPath);
                        asset.setStatus("completed");
                        asset.setError(null);
                        agentAssetMapper.updateById(asset);
                    }
                }
```

failed 分支（第 179-185 行）替换：

```java
                var scenes = sceneMapper.selectList(
                    new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, taskId));
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoStatus("failed");
                    sceneMapper.updateById(scene);
                } else {
                    var assets = agentAssetMapper.selectList(
                        new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getTaskId, taskId));
                    if (!assets.isEmpty()) {
                        AgentAsset asset = assets.get(0);
                        asset.setStatus("failed");
                        asset.setError(result.get("error"));
                        agentAssetMapper.updateById(asset);
                    }
                }
```

- [ ] **步骤 4：编译验证**（预期 BUILD SUCCESS）

- [ ] **步骤 5：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java
git commit -m "refactor: VideoGenerationService 支持 sceneId 为空，pollVideoTask 双通道反查 scene/agent_assets"
```

---

## 任务 6：DifyAgentController 改造（写 agent_assets + picUrl 合并）

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java`
- 新增注入：`AgentAssetMapper`

- [ ] **步骤 1：注入 AgentAssetMapper + import**

构造函数（第 43-49 行）替换：

```java
    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;

    public DifyAgentController(ImageGenerationService imageService,
                                VideoGenerationService videoService,
                                SceneMapper sceneMapper,
                                AgentAssetMapper agentAssetMapper) {
        this.imageService = imageService;
        this.videoService = videoService;
        this.sceneMapper = sceneMapper;
        this.agentAssetMapper = agentAssetMapper;
    }
```

import 行加：

```java
import com.storyboard.entity.AgentAsset;
import com.storyboard.mapper.AgentAssetMapper;
```

移除不再需要的 import：`java.util.UUID`（临时 scene 逻辑删除后不再使用）。

- [ ] **步骤 2：新增私有方法 writeAgentImageAsset**

在 `toBase64DataUrls` 方法之前插入：

```java
    /**
     * 将生成结果写入 agent_assets（sceneId 为空时调用）。
     * conversationId 为空则创建未归属资产（conversation_id = NULL）。
     */
    private AgentAsset writeAgentImageAsset(String conversationId, String prompt,
                                             String model, String imageUrl) {
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(sanitize(conversationId));
        asset.setType("image");
        asset.setUrl(imageUrl);
        asset.setPrompt(sanitize(prompt));
        asset.setModel(sanitize(model));
        asset.setStatus("completed");
        agentAssetMapper.insert(asset);
        log.info("Agent 图片资产已落库: assetId={}, conversationId={}", asset.getId(), asset.getConversationId());
        return asset;
    }
```

- [ ] **步骤 3：改造 generateImage（JSON 版）**

第 89-121 行替换为：

```java
    @PostMapping(value = "/generate-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, String>> generateImage(
            @RequestBody DifyGenerateImageRequest request) {
        // sceneId 非空 → 写真实分镜；为空 → 写 agent_assets（不再创建临时 scene）
        String effectiveSceneId = (request.sceneId() != null && !request.sceneId().isBlank())
                ? request.sceneId() : null;
        if (effectiveSceneId != null) {
            log.info("Dify Agent 使用真实分镜 sceneId={} 生成图片, mode={}", effectiveSceneId, request.mode());
        }

        // 确定生图模式：显式传 "edit" 走图改图，否则走图生图
        String mode = "edit".equals(request.mode()) ? "edit" : null;

        // picUrl（用户上传图）优先于 generatedImageUrl（完善已有图）
        String effectiveGeneratedImageUrl = (request.picUrl() != null && !request.picUrl().isBlank())
                ? request.picUrl() : sanitize(request.generatedImageUrl());

        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            sanitize(request.prompt()), sanitize(request.model()),
            sanitize(request.size()), sanitize(request.quality()), null,
            request.referenceImageUrls(),
            mode,
            effectiveGeneratedImageUrl
        );

        if (effectiveSceneId == null) {
            AgentAsset asset = writeAgentImageAsset(
                request.conversationId(), request.prompt(), request.model(), imageUrl);
            return ApiResponse.ok(Map.of(
                "imageUrl", BACKEND_BASE_URL + imageUrl,
                "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1),
                "assetId", asset.getId()
            ));
        }
        return ApiResponse.ok(Map.of(
            "imageUrl", BACKEND_BASE_URL + imageUrl,
            "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        ));
    }
```

- [ ] **步骤 4：改造 generateImageMultipart**

第 215-258 行替换为：

```java
    @PostMapping(value = "/generate-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> generateImageMultipart(
            @RequestParam String projectId,
            @RequestParam String prompt,
            @RequestParam(required = false) String sceneId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String quality,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String generatedImageUrl,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String picUrl,
            @RequestPart(required = false) List<MultipartFile> images) {

        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        log.info("Dify Agent multipart 生成图片 sceneId={}, mode={}, files={}",
                effectiveSceneId, mode, images != null ? images.size() : 0);

        // 文件 → base64 data URL 列表
        List<String> referenceImageUrls = toBase64DataUrls(images);

        String effectiveMode = "edit".equals(mode) ? "edit" : null;

        String effectiveGeneratedImageUrl = (picUrl != null && !picUrl.isBlank())
                ? picUrl : sanitize(generatedImageUrl);

        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            sanitize(prompt), sanitize(model),
            sanitize(size), sanitize(quality), null,
            referenceImageUrls.isEmpty() ? null : referenceImageUrls,
            effectiveMode,
            effectiveGeneratedImageUrl
        );

        if (effectiveSceneId == null) {
            AgentAsset asset = writeAgentImageAsset(conversationId, prompt, model, imageUrl);
            return ApiResponse.ok(Map.of(
                "imageUrl", BACKEND_BASE_URL + imageUrl,
                "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1),
                "assetId", asset.getId()
            ));
        }
        return ApiResponse.ok(Map.of(
            "imageUrl", BACKEND_BASE_URL + imageUrl,
            "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        ));
    }
```

- [ ] **步骤 5：改造 generateVideo**

第 128-175 行替换为：

```java
    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(
            @RequestBody DifyGenerateVideoRequest request) {
        log.info("Dify Agent 创建视频任务: projectId={}", request.projectId());

        // duration 是 String 类型（Dify 变量引用可能是未解析的字符串）
        Integer duration = null;
        if (request.duration() != null && !request.duration().isBlank()) {
            try {
                duration = Integer.parseInt(request.duration());
            } catch (NumberFormatException e) {
                log.warn("Dify Agent 视频 duration 值非法({}), 将使用 service 默认值", request.duration());
            }
        }
        if (duration == null || duration <= 0) {
            log.info("Dify Agent 视频 duration 未设置或无效, 将使用 service 默认值");
        }

        // sceneId 非空 → 写真实分镜；为空 → 写 agent_assets
        String effectiveSceneId = (request.sceneId() != null && !request.sceneId().isBlank())
                ? request.sceneId() : null;
        if (effectiveSceneId != null) {
            log.info("Dify Agent 使用真实分镜 sceneId={} 生成视频", effectiveSceneId);
        }

        String effectiveGeneratedImageUrl = (request.picUrl() != null && !request.picUrl().isBlank())
                ? request.picUrl() : null;

        // 创建视频任务，立即返回 taskId（不阻塞等待）
        String taskId = videoService.createVideoTask(
            effectiveSceneId,
            sanitize(request.prompt()), sanitize(request.model()),
            sanitize(request.resolution()), sanitize(request.size()), sanitize(request.aspectRatio()),
            duration, sanitize(request.negativePrompt()), null,
            request.referenceImageUrls(), effectiveGeneratedImageUrl
        );

        log.info("Dify Agent 视频任务已创建: taskId={}, sceneId={}", taskId, effectiveSceneId);

        if (effectiveSceneId == null) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(sanitize(request.conversationId()));
            asset.setType("video");
            asset.setPrompt(sanitize(request.prompt()));
            asset.setModel(sanitize(request.model()));
            asset.setStatus("queued");
            asset.setTaskId(taskId);
            agentAssetMapper.insert(asset);
            log.info("Agent 视频资产已落库: assetId={}, taskId={}", asset.getId(), taskId);
            return ApiResponse.ok(Map.of(
                "taskId", taskId,
                "sceneId", effectiveSceneId != null ? effectiveSceneId : asset.getId(),
                "assetId", asset.getId(),
                "status", "queued"
            ));
        }
        return ApiResponse.ok(Map.of(
            "taskId", taskId,
            "sceneId", effectiveSceneId,
            "status", "queued"
        ));
    }
```

注意：无 sceneId 时返回的 `sceneId` 字段填入 assetId，保持 Dify 工作流"sceneId 字段可引用"的兼容（assetId 就是该资产的 ID）。

- [ ] **步骤 6：编译验证**（预期 BUILD SUCCESS；若 `UUID` import 报 unused 警告不影响编译）

- [ ] **步骤 7：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java
git commit -m "feat: DifyAgentController 无 sceneId 改写 agent_assets，支持 picUrl 图生图/图生视频"
```

---

## 任务 7：AgentChatService + AgentConversationController（会话/消息/资产）

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentCreateConversationRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentSendMessageRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java`

- [ ] **步骤 1：创建 2 个 DTO**

`dto/request/AgentCreateConversationRequest.java`：

```java
package com.storyboard.dto.request;

/** 创建 Agent 对话会话请求（userId 由 JWT 提供，不入 DTO） */
public record AgentCreateConversationRequest(
    String projectId,
    String title
) {}
```

`dto/request/AgentSendMessageRequest.java`：

```java
package com.storyboard.dto.request;

/** 发送 Agent 对话消息请求 */
public record AgentSendMessageRequest(
    String content
) {}
```

- [ ] **步骤 2：创建 AgentChatService**

`service/agent/AgentChatService.java`：

```java
package com.storyboard.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.mapper.AgentMessageMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.service.ai.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话服务 —— 代理 Dify /v1/chat-messages（blocking 模式）。
 * 负责：会话校验、消息落库、Dify 调用、conversation_id 回填。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final ProjectMapper projectMapper;
    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public AgentChatService(AgentConversationMapper conversationMapper,
                            AgentMessageMapper messageMapper,
                            ProjectMapper projectMapper,
                            AiConfigProperties config) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.projectMapper = projectMapper;
        this.config = config;
    }

    /** 创建会话：校验项目归属，返回会话 */
    public AgentConversation createConversation(String userId, String projectId, String title) {
        var project = projectMapper.selectById(projectId);
        if (project == null) throw new RuntimeException("项目不存在: " + projectId);
        if (!userId.equals(project.getUserId())) throw new RuntimeException("无权为该项目创建对话");

        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setProjectId(projectId);
        conversation.setTitle(title != null && !title.isBlank() ? title : "新对话");
        conversation.setStatus("active");
        conversationMapper.insert(conversation);
        return conversation;
    }

    /** 校验会话归属，返回会话 */
    public AgentConversation getOwnedConversation(String userId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) throw new RuntimeException("会话不存在: " + conversationId);
        if (!userId.equals(conversation.getUserId())) throw new RuntimeException("无权访问该会话");
        return conversation;
    }

    /** 会话消息列表（created_at 正序） */
    public List<AgentMessage> listMessages(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getConversationId, conversationId)
            .orderByAsc(AgentMessage::getCreatedAt));
    }

    /**
     * 发送消息：落库 user 消息 → 调 Dify chat-messages → 落库 assistant 消息。
     * 返回 assistant 消息；失败时 user 消息保留，抛异常。
     */
    @Transactional
    public AgentMessage sendMessage(String userId, String conversationId, String content) {
        if (content == null || content.isBlank()) {
            throw new RuntimeException("消息内容不能为空");
        }
        AgentConversation conversation = getOwnedConversation(userId, conversationId);

        // 1. 保存 user 消息
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        messageMapper.insert(userMessage);

        // 2. 调 Dify chat-messages
        Map<String, Object> result = callDifyChat(conversation, content, userId);

        // 3. 回填 Dify conversation_id（首次对话后才有）
        String difyConversationId = (String) result.get("conversationId");
        if (difyConversationId != null && !difyConversationId.isBlank()
                && !difyConversationId.equals(conversation.getDifyConversationId())) {
            conversation.setDifyConversationId(difyConversationId);
            conversationMapper.updateById(conversation);
        }

        // 4. 保存 assistant 消息
        AgentMessage assistantMessage = new AgentMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent((String) result.getOrDefault("answer", ""));
        assistantMessage.setDifyMessageId((String) result.get("messageId"));
        messageMapper.insert(assistantMessage);
        return assistantMessage;
    }

    /** 调用 Dify /v1/chat-messages（blocking 模式） */
    private Map<String, Object> callDifyChat(AgentConversation conversation,
                                              String query, String userId) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("inputs", Map.of(
                "project_id", conversation.getProjectId(),
                "project_name", ""
            ));
            body.put("query", query);
            body.put("response_mode", "blocking");
            body.put("user", userId);
            if (conversation.getDifyConversationId() != null
                    && !conversation.getDifyConversationId().isBlank()) {
                body.put("conversation_id", conversation.getDifyConversationId());
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/chat-messages"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Dify API 返回 " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = objectMapper.readTree(resp.body());
            Map<String, Object> result = new HashMap<>();
            result.put("answer", root.path("answer").asText(""));
            result.put("conversationId", root.path("conversation_id").asText(""));
            result.put("messageId", root.path("message_id").asText(""));
            return result;
        } catch (Exception e) {
            log.error("Dify chat-messages 调用失败: conversationId={}, error={}",
                    conversation.getId(), e.getMessage());
            throw new RuntimeException("Dify 对话失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **步骤 3：创建 AgentConversationController**

`controller/AgentConversationController.java`：

```java
package com.storyboard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.AgentCreateConversationRequest;
import com.storyboard.dto.request.AgentSendMessageRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.service.agent.AgentChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Agent 对话模块 —— JWT 鉴权（/api/agent/** 不在 SecurityConfig 白名单）。
 * 会话 / 消息 / 资产 / 图片上传。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentConversationController {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationController.class);

    private final AgentChatService chatService;
    private final AgentConversationMapper conversationMapper;
    private final AgentAssetMapper assetMapper;

    public AgentConversationController(AgentChatService chatService,
                                       AgentConversationMapper conversationMapper,
                                       AgentAssetMapper assetMapper) {
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.assetMapper = assetMapper;
    }

    /** 创建会话 */
    @PostMapping("/conversations")
    public ApiResponse<AgentConversation> createConversation(
            Authentication auth, @RequestBody AgentCreateConversationRequest request) {
        return ApiResponse.ok(chatService.createConversation(auth.getName(), request.projectId(), request.title()));
    }

    /** 当前用户的项目会话列表（updated_at 倒序） */
    @GetMapping("/conversations")
    public ApiResponse<List<AgentConversation>> listConversations(
            Authentication auth, @RequestParam String projectId) {
        List<AgentConversation> list = conversationMapper.selectList(
            new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getUserId, auth.getName())
                .eq(AgentConversation::getProjectId, projectId)
                .orderByDesc(AgentConversation::getUpdatedAt));
        return ApiResponse.ok(list);
    }

    /** 会话详情（含消息列表） */
    @GetMapping("/conversations/{id}")
    public ApiResponse<Map<String, Object>> getConversation(
            Authentication auth, @PathVariable String id) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        List<AgentMessage> messages = chatService.listMessages(id);
        return ApiResponse.ok(Map.of("conversation", conversation, "messages", messages));
    }

    /** 删除会话（级联删消息） */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(Authentication auth, @PathVariable String id) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        conversationMapper.deleteById(conversation.getId());
        return ApiResponse.ok("删除成功", null);
    }

    /** 消息列表 */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<AgentMessage>> listMessages(
            Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(chatService.listMessages(id));
    }

    /** 发送消息（代理 Dify） */
    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<AgentMessage> sendMessage(
            Authentication auth, @PathVariable String id,
            @RequestBody AgentSendMessageRequest request) {
        return ApiResponse.ok(chatService.sendMessage(auth.getName(), id, request.content()));
    }

    /** 会话生成资产列表 */
    @GetMapping("/conversations/{id}/assets")
    public ApiResponse<List<AgentAsset>> listAssets(Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        List<AgentAsset> assets = assetMapper.selectList(
            new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, id)
                .orderByDesc(AgentAsset::getCreatedAt));
        return ApiResponse.ok(assets);
    }
}
```

- [ ] **步骤 4：编译验证**（预期 BUILD SUCCESS；`ProjectMapper` 需已在 com.storyboard.mapper 包）

确认 ProjectMapper 存在：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/ProjectMapper.java`（已存在，extends BaseMapper\<Project>）。

- [ ] **步骤 5：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentCreateConversationRequest.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentSendMessageRequest.java \
        AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java \
        AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java
git commit -m "feat: Agent 对话模块（会话/消息/资产端点 + Dify chat-messages 代理）"
```

---

## 任务 8：图片上传端点（POST /api/agent/upload）

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java`

- [ ] **步骤 1：Controller 注入 FileStorageService + 加 upload 端点**

构造函数改为：

```java
    private final AgentChatService chatService;
    private final AgentConversationMapper conversationMapper;
    private final AgentAssetMapper assetMapper;
    private final com.storyboard.service.FileStorageService fileStorageService;

    public AgentConversationController(AgentChatService chatService,
                                       AgentConversationMapper conversationMapper,
                                       AgentAssetMapper assetMapper,
                                       com.storyboard.service.FileStorageService fileStorageService) {
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.assetMapper = assetMapper;
        this.fileStorageService = fileStorageService;
    }
```

在类末尾（`listAssets` 方法之后、类闭合大括号之前）加 upload 端点：

```java
    /** 上传图片（参考图）：存 uploads/images/ → 返回 URL → 落库 agent_assets(type=reference) */
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> uploadImage(
            Authentication auth,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String conversationId) {
        // 校验会话归属（传了 conversationId 时）
        if (conversationId != null && !conversationId.isBlank()) {
            chatService.getOwnedConversation(auth.getName(), conversationId);
        }
        // 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new RuntimeException("仅支持上传图片文件");
        }
        String url = fileStorageService.saveUploadedImage(file);

        // 落库 agent_assets（type=reference）
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(conversationId != null && !conversationId.isBlank() ? conversationId : null);
        asset.setType("reference");
        asset.setUrl(url);
        asset.setStatus("completed");
        assetMapper.insert(asset);
        log.info("Agent 参考图已落库: assetId={}, url={}", asset.getId(), url);

        return ApiResponse.ok(Map.of(
            "url", url,
            "assetId", asset.getId()
        ));
    }
```

- [ ] **步骤 2：编译验证**（预期 BUILD SUCCESS）

- [ ] **步骤 3：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java
git commit -m "feat: Agent 图片上传端点（存 uploads + 落库 reference 资产）"
```

---

## 任务 9：全量验证 + 冒烟测试

**文件：** 无新增

- [ ] **步骤 1：后端全量编译**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 2：确认 V2 migration 可执行（可选，需本地 PostgreSQL）**

```bash
psql -h localhost -U postgres -d newworkflow -f AIStoryboardBackend/src/main/resources/db/migration/V2__agent_conversation.sql
# 预期：CREATE TABLE ×3 + CREATE INDEX ×3，无报错
```

若 psql 不可用，跳过（Spring Boot 启动时 Flyway 会自动执行 V2；后端启动日志应显示 `Migrating schema "public" to version 2`）。

- [ ] **步骤 3：冒烟测试（可选，需后端 + Dify 均运行）**

```bash
# 1) 登录拿 token（替换账号密码）
curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"account":"xxx","password":"xxx"}'
# → 取 data.accessToken

# 2) 创建会话（替换 projectId / token）
curl -s -X POST http://localhost:8082/api/agent/conversations \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"projectId":"<projectId>"}'
# → data.id 即 conversationId

# 3) 发送消息
curl -s -X POST http://localhost:8082/api/agent/conversations/<conversationId>/messages \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"content":"你好"}'
# → data.role == "assistant"，data.content 非空

# 4) 上传图片
curl -s -X POST http://localhost:8082/api/agent/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/image.png" -F "conversationId=<conversationId>"
# → data.url 形如 /api/files/images/xxx.png

# 5) 查询资产列表
curl -s http://localhost:8082/api/agent/conversations/<conversationId>/assets \
  -H "Authorization: Bearer <token>"
# → 至少 1 条 reference 资产
```

- [ ] **步骤 4：确认无敏感值泄露 + 硬编码检查**

```bash
cd "E:/Desktop/AI-storyboard"
grep -rn "localhost:8082\|http://" AIStoryboardClient/src --include='*.ts' --include='*.tsx' | grep -v "localhost:5173" || echo "OK: 前端无新增硬编码"
```

预期：本次改动全部在后端，前端无变化。

- [ ] **步骤 5：最终 Commit（如有未提交改动）**

```bash
cd "E:/Desktop/AI-storyboard"
git status --short
git add -A && git commit -m "chore: agent 对话模块验证通过" 2>/dev/null || echo "无未提交改动"
```

---

## 自检记录

**1. 规格覆盖度：**
- conversations / agent_messages / agent_assets 三表 → 任务 1 ✓
- AgentConversationController 7 端点 + upload → 任务 7/8 ✓
- AgentChatService Dify chat-messages 代理 + conversation_id 回填 → 任务 7 ✓
- DifyAgentController 删临时 scene、写 agent_assets → 任务 6 ✓
- DTO conversationId + picUrl → 任务 3 ✓
- ImageGenerationService sceneId 可空 → 任务 4 ✓
- VideoGenerationService sceneId 可空 + 双通道反查 → 任务 5 ✓
- difyBaseUrl 配置 + saveUploadedImage → 任务 2 ✓
- JWT 鉴权（/api/agent/** 不在白名单）→ 任务 7（SecurityConfig 无需改动，anyRequest().authenticated() 已覆盖）✓

**2. 占位符扫描：** 无 TODO/待定；所有代码块完整可编译。

**3. 类型一致性：**
- `AgentAsset` 字段：id/conversationId/type/url/prompt/model/status/taskId/error/createdAt — 任务 1 定义，任务 5/6/8 使用一致
- `AgentConversation`：id/userId/projectId/title/difyConversationId/status/createdAt/updatedAt — 任务 1 定义，任务 7 使用一致
- `AgentMessage`：id/conversationId/role/content/difyMessageId/createdAt — 任务 1 定义，任务 7 使用一致
- `chatService.sendMessage` 签名 (userId, conversationId, content) — 任务 7 定义与调用一致
- `fileStorageService.saveUploadedImage(MultipartFile)` — 任务 2 定义，任务 8 调用一致
- Dify 响应字段：answer / conversation_id / message_id — 任务 7 解析与 Dify API 一致
