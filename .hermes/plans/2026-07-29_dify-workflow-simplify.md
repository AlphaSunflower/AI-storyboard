# Dify 工作流精简方案

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将 Dify 工作流从 45 节点精简到 ~12 节点，把编排逻辑迁移到后端。

**Architecture:** 后端新增 `/api/ai/dify/generate-all` 批处理端点，一次性完成"解析需求 → 生成分镜 → 生成图片 → 生成视频"全流程。Dify 只负责意图路由 + 调用批处理 + 展示结果。

**Tech Stack:** Spring Boot, MyBatis-Plus, PostgreSQL, Dify v1.16+

---

## 现状分析

| 节点类型 | 数量 | 可否消除 | 原因 |
|----------|------|----------|------|
| assigner | 12 | ✓ 全部 | step 状态机变量切换 → 移到后端 |
| answer | 12 | ✓ 大部 | 每步都回话一句话 → 改为后端返回进度 |
| if-else | 8 | ✓ 大部 | 意图路由保留 1-2 个，其余移到后端 |
| llm | 7 | ✓ 部分 | 解析/设计逻辑移到后端 Java 代码 |
| http-request | 3 | ✓ | 合并为 1 个 batch 调用 |
| tool | 2 | 保留 | 外部工具 |
| start | 1 | 保留 | 入口 |

**目标：** 45 节点 → ~12 节点

---

## Task 1: 后端新增 `GenerateAllRequest` DTO

**Objective:** 定义批处理请求数据结构

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateAllRequest.java`

```java
package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 批量生成请求 — 一次请求完成全部 AI 生成流程。
 * step 可指定从哪个阶段开始，支持断点续跑。
 */
public record DifyGenerateAllRequest(
    String projectId,           // 项目 ID（必填）
    String scriptPrompt,        // 分镜方案描述（用户自然语言）
    String imageModel,          // 图片模型，默认 gpt-image-2
    String imageSize,           // 图片尺寸，默认 1024x1024
    String imageQuality,        // 图片质量
    String imageAspectRatio,    // 宽高比
    String videoModel,          // 视频模型，默认 veo-3.1-fast
    String videoAspectRatio,    // 视频宽高比
    Integer videoDuration,      // 视频时长（秒）
    int startStep,              // 起始步骤：1=分镜 3=图片 5=视频
    int endStep                 // 终止步骤
) {
    public DifyGenerateAllRequest {
        if (startStep < 1) startStep = 1;
        if (endStep < 1) endStep = 6;
    }
}
```

---

## Task 2: 后端新增 `GenerateAllService`

**Objective:** 实现全流程编排逻辑，替代 Dify 的状态机

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/GenerateAllService.java`

```java
@Service
public class GenerateAllService {

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    
    /**
     * 执行完整生成流程。
     * 返回 Map 包含每一步的结果和进度。
     */
    @Transactional
    public Map<String, Object> generateAll(DifyGenerateAllRequest req) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // Step 1-2: 分镜方案 → LLM 生成 JSON → 写入 DB
        if (req.startStep() <= 2 && req.endStep() >= 2) {
            List<Scene> scenes = generateScenes(req);
            result.put("scenes", scenes.size());
            result.put("sceneIds", scenes.stream().map(Scene::getId).toList());
        }
        
        // Step 3-5: 生图（逐个分镜）
        if (req.startStep() <= 5 && req.endStep() >= 5) {
            List<String> imageUrls = new ArrayList<>();
            for (Scene scene : getScenesForStep(req)) {
                if (req.startStep() <= 3) {
                    // 可插入 LLM 优化 imagePrompt 的逻辑
                }
                String url = imageService.generateImage(
                    scene.getId(), scene.getImagePrompt(), 
                    req.imageModel(), req.imageSize(), ...);
                imageUrls.add(url);
            }
            result.put("imageCount", imageUrls.size());
        }
        
        // Step 6: 生视频（逐个分镜）
        if (req.startStep() <= 6 && req.endStep() >= 6) {
            List<String> videoUrls = new ArrayList<>();
            for (Scene scene : getScenesForStep(req)) {
                // 创建任务 + 轮询
                String taskId = videoService.createVideoTask(...);
                Map<String, String> vr = pollVideo(taskId);
                videoUrls.add(vr.get("videoUrl"));
            }
            result.put("videoCount", videoUrls.size());
        }
        
        result.put("status", "completed");
        return result;
    }
}
```

---

## Task 3: 后端新增 Controller 端点

**Objective:** 暴露 POST `/api/ai/dify/generate-all`

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java`

新增方法：

```java
@PostMapping("/generate-all")
public ApiResponse<Map<String, Object>> generateAll(
        @RequestBody DifyGenerateAllRequest request) {
    log.info("Dify Agent 批量生成: projectId={}, steps=[{}-{}]",
        request.projectId(), request.startStep(), request.endStep());
    Map<String, Object> result = generateAllService.generateAll(request);
    return ApiResponse.ok(result);
}
```

---

## Task 4: Dify 工作流精简

**Objective:** 删除状态机节点，替换为简洁的意图路由 + 单次 HTTP 调用

**精简后的 Dify 工作流（~12 节点）：**

```
start
  ↓
[LLM 意图识别]  ← 识别用户是想"设计分镜"/"直接生图"/"全部生成"
  ↓
[if-else 路由]
  ├─ 查询/修改 → [answer: 当前有 X 个分镜，Y 张图...]
  ├─ 设计分镜 → [LLM 优化 prompt] → [HTTP: generate-all startStep=1 endStep=2]
  ├─ 生成图片 → [LLM 确认方案] → [HTTP: generate-all startStep=3 endStep=5]
  ├─ 生成视频 → [LLM 确认方案] → [HTTP: generate-all startStep=4 endStep=6]
  └─ 全部生成 → [HTTP: generate-all startStep=1 endStep=6]
                    ↓
              [answer: 展示结果]
```

**节点数对比：**

| 功能 | 原方案 | 精简后 |
|------|--------|--------|
| 意图识别 | 1 if-else + 1 assigner | 1 llm |
| 路由分发 | 8 if-else | 1 if-else (5 分支) |
| 状态回答 | 12 answer | 1 answer（复用） |
| 状态变量 | 12 assigner | 0（移到后端） |
| AI 调用 | 7 llm + 3 http | 2 llm + 1 http |
| **合计** | **45** | **~12** |

---

## Task 5: 验证

**Objective:** 确认后端编译通过 + Dify 工作流可导入

**Steps:**

1. 编译后端：
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

2. 测试 generate-all 端点（后端需运行）：
```bash
curl -X POST http://localhost:8082/api/ai/dify/generate-all \
  -H "Content-Type: application/json" \
  -H "X-Dify-Key: test-key" \
  -d '{"projectId":"test","scriptPrompt":"一只猫在散步","startStep":1,"endStep":2}'
```

3. 在 Dify 中导入精简后的工作流 YML → 跑一次端到端测试

---

## 风险和权衡

| 风险 | 缓解 |
|------|------|
| 后端调用耗时长（图片+视频可能 10 分钟+） | startStep/endStep 支持分段执行；超时设 600s |
| LLM 优化 prompt 逻辑移到后端需要调 Laozhang | 复用现有 AiConfigProperties |
| Dify 失去中间步骤的可视化反馈 | result 返回进度信息，Dify 展示 |
| 分镜设计仍需 LLM 参与（需要和用户对话） | 保留 Dify 的 LLM 节点做 prompt 优化/确认 |
