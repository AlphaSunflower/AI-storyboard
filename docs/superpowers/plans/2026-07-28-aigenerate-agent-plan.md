# AiGenerateAgent 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现 AiGenerateAgent Dify 智能体（修复+扩展现有 step 状态机），并新增后端 API Key 认证和视频代理端点。

**架构：** Dify advanced-chat Agent 负责意图识别→方案设计→用户确认的编排层；Spring Boot 后端新增 `/api/ai/dify/*` 端点（带 X-Dify-Key 认证），代理 Laozhang 图片/视频 API 调用。

**技术栈：** Dify v1.16.1 (difyctl), Spring Boot 4 + JDK 21, JDK HttpClient, PostgreSQL

---

## 文件结构

### 后端 — 新建

| 文件 | 职责 |
|------|------|
| `security/DifyApiKeyFilter.java` | X-Dify-Key header 认证过滤器 |
| `dto/request/DifyGenerateImageRequest.java` | Dify 生图请求 DTO |
| `dto/request/DifyGenerateVideoRequest.java` | Dify 生视频请求 DTO |
| `dto/request/DifyGenerateScriptRequest.java` | Dify 分镜脚本请求 DTO |
| `controller/DifyAgentController.java` | `/api/ai/dify/*` 端点 |

### 后端 — 修改

| 文件 | 修改内容 |
|------|---------|
| `config/SecurityConfig.java` | 放行 `/api/ai/dify/**`，注入 DifyApiKeyFilter |
| `config/AiConfigProperties.java` | 新增 `difyApiKey` 配置项 |
| `application.yml` | 新增 `ai.dify.api-key` 配置 |
| `application-local.yml` | （.gitignore 内）新增 dify api-key 真实值 |

### Dify Agent — 修改

| 文件 | 职责 |
|------|------|
| `AIStoryboardDify/ai-generate-agent.dsl.yaml` | 重新生成的完整工作流 DSL |

### Dify Agent — 环境变量

| 变量 | 值 |
|------|-----|
| `DIFY_KEY` | 与后端 `ai.dify.api-key` 相同的密钥 |
| `LAOZHANG_KEY` | Laozhang API Key |
| `BACKEND_URL` | `http://host.docker.internal:8082` |

---

### 任务 1：后端 — DifyApiKeyFilter

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/security/DifyApiKeyFilter.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/config/SecurityConfig.java:49-51`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/config/AiConfigProperties.java`
- 修改：`AIStoryboardBackend/src/main/resources/application.yml`

- [ ] **步骤 1：创建 DifyApiKeyFilter**

```java
package com.storyboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Dify Agent API Key 认证过滤器
 * 检查 X-Dify-Key header 是否匹配配置的密钥
 */
public class DifyApiKeyFilter extends OncePerRequestFilter {

    private final String expectedKey;

    public DifyApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String difyKey = request.getHeader("X-Dify-Key");
        if (difyKey == null || !difyKey.equals(expectedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":40101,\"message\":\"Dify API Key 无效\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅拦截 /api/ai/dify/ 路径
        String path = request.getServletPath();
        return !path.startsWith("/api/ai/dify/");
    }
}
```

- [ ] **步骤 2：修改 SecurityConfig — 放行路径 + 注入过滤器**

在 `SecurityConfig.java` 中：

```java
// 修改 authorizeHttpRequests，新增放行路径
.requestMatchers("/api/ai/dify/**").permitAll()

// 新增 DifyApiKeyFilter Bean（注入前需要从 AiConfigProperties 读取 key）
// 在 filterChain 中 .addFilterBefore(difyApiKeyFilter(), UsernamePasswordAuthenticationFilter.class)
```

完整修改后的 `SecurityConfig.java`:

```java
package com.storyboard.config;

import com.storyboard.security.DifyApiKeyFilter;
import com.storyboard.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final AiConfigProperties aiConfig;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, AiConfigProperties aiConfig) {
        this.jwtFilter = jwtFilter;
        this.aiConfig = aiConfig;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public DifyApiKeyFilter difyApiKeyFilter() {
        return new DifyApiKeyFilter(aiConfig.getDifyApiKey());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/files/**").permitAll()
                .requestMatchers("/api/ai/dify/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(401);
                    response.getWriter().write("{\"code\":40101,\"message\":\"未授权，请先登录\"}");
                })
            )
            .addFilterBefore(difyApiKeyFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

**注意**：DifyApiKeyFilter 放在 JwtAuthenticationFilter 之前，通过 `shouldNotFilter` 只拦截 `/api/ai/dify/**`，其他路径跳过。

- [ ] **步骤 3：AiConfigProperties 新增 difyApiKey**

```java
// 在 AiConfigProperties.java 中新增
private String difyApiKey;

public String getDifyApiKey() {
    return difyApiKey;
}

public void setDifyApiKey(String difyApiKey) {
    this.difyApiKey = difyApiKey;
}
```

- [ ] **步骤 4：application.yml 新增配置**

```yaml
# application.yml 中新增
ai:
  dify:
    api-key: ${AI_DIFY_API_KEY:}
```

- [ ] **步骤 5：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

预期：编译通过，无错误。

- [ ] **步骤 6：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/security/DifyApiKeyFilter.java \
        AIStoryboardBackend/src/main/java/com/storyboard/config/SecurityConfig.java \
        AIStoryboardBackend/src/main/java/com/storyboard/config/AiConfigProperties.java \
        AIStoryboardBackend/src/main/resources/application.yml
git commit -m "feat: add DifyApiKeyFilter for Agent API authentication"
```

---

### 任务 2：后端 — Dify 端点 DTO

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateScriptRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateImageRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateVideoRequest.java`

- [ ] **步骤 1：创建三个 DTO records**

```java
// DifyGenerateScriptRequest.java
package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 分镜脚本生成请求
 */
public record DifyGenerateScriptRequest(
    String projectId,
    List<SceneItem> scenes,
    String aspectRatio
) {
    public record SceneItem(
        int sceneNumber,
        String scriptContent,
        String imagePrompt,
        String videoPrompt,
        String negativePrompt,
        String cameraMovement,
        String shotType,
        String soundDesign
    ) {}
}
```

```java
// DifyGenerateImageRequest.java
package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 图片生成请求
 */
public record DifyGenerateImageRequest(
    String projectId,
    String prompt,
    String model,
    String size,
    String quality,
    List<String> referenceImageUrls
) {}
```

```java
// DifyGenerateVideoRequest.java
package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 视频生成请求（后端代理 Laozhang 异步轮询）
 */
public record DifyGenerateVideoRequest(
    String projectId,
    String prompt,
    String model,
    String resolution,
    String size,
    String aspectRatio,
    int duration,
    String negativePrompt,
    List<String> referenceImageUrls
) {}
```

- [ ] **步骤 2：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

预期：编译通过。

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateScriptRequest.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateImageRequest.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/request/DifyGenerateVideoRequest.java
git commit -m "feat: add Dify Agent request DTOs"
```

---

### 任务 3：后端 — DifyAgentController

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java`

- [ ] **步骤 1：创建 DifyAgentController**

```java
package com.storyboard.controller;

import com.storyboard.dto.request.DifyGenerateImageRequest;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import com.storyboard.dto.request.DifyGenerateVideoRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Dify Agent 专用 API 端点
 * 认证方式：X-Dify-Key header（由 DifyApiKeyFilter 校验）
 */
@RestController
@RequestMapping("/api/ai/dify")
public class DifyAgentController {

    private static final Logger log = LoggerFactory.getLogger(DifyAgentController.class);

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;

    public DifyAgentController(ImageGenerationService imageService,
                                VideoGenerationService videoService,
                                SceneMapper sceneMapper) {
        this.imageService = imageService;
        this.videoService = videoService;
        this.sceneMapper = sceneMapper;
    }

    /**
     * Dify Agent 分镜脚本写入
     * 接收 Agent 生成的 JSON，批量创建 Scene 记录
     */
    @PostMapping("/generate-script")
    public ApiResponse<Map<String, Object>> generateScript(
            @RequestBody DifyGenerateScriptRequest request) {
        int count = 0;
        for (var item : request.scenes()) {
            Scene scene = new Scene();
            scene.setProjectId(request.projectId());
            scene.setSceneNumber(item.sceneNumber());
            scene.setScriptContent(item.scriptContent());
            scene.setImagePrompt(item.imagePrompt());
            scene.setVideoPrompt(item.videoPrompt());
            scene.setNegativePrompt(item.negativePrompt());
            scene.setCameraMovement(item.cameraMovement());
            scene.setShotType(item.shotType());
            scene.setSoundDesign(item.soundDesign());
            sceneMapper.insert(scene);
            count++;
        }
        log.info("Dify Agent 写入 {} 个分镜到项目 {}", count, request.projectId());
        return ApiResponse.ok(Map.of("projectId", request.projectId(), "sceneCount", count));
    }

    /**
     * Dify Agent 图片生成（代理 Laozhang API）
     * 生成后下载到本地，返回访问 URL
     */
    @PostMapping("/generate-image")
    public ApiResponse<Map<String, String>> generateImage(
            @RequestBody DifyGenerateImageRequest request) {
        // 为 Dify 调用创建临时 scene（projectId 关联）
        String tempSceneId = UUID.randomUUID().toString();
        String imageUrl = imageService.generateImage(
            tempSceneId, request.prompt(), request.model(),
            request.size(), request.quality(), null,
            request.referenceImageUrls(),
            null, null  // mode=null → generations 接口
        );
        return ApiResponse.ok(Map.of("imageUrl", imageUrl));
    }

    /**
     * Dify Agent 视频生成（代理 Laozhang API + 轮询 + 下载）
     * 同步等待视频生成完成，Dify 调用方只需一次 HTTP 请求
     */
    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(
            @RequestBody DifyGenerateVideoRequest request) {
        // 创建视频任务
        String taskId = videoService.createVideoTask(
            UUID.randomUUID().toString(),  // 临时 sceneId
            request.prompt(), request.model(),
            request.resolution(), request.size(), request.aspectRatio(),
            request.duration(), request.negativePrompt(), null,
            request.referenceImageUrls(), null
        );

        // 轮询等待完成（VideoGenerationService.pollVideoTask 内部处理轮询）
        Map<String, String> result = videoService.pollVideoTask(taskId);
        if (!"completed".equals(result.get("status"))) {
            return ApiResponse.error(500, "视频生成失败: " + result.getOrDefault("error", "未知错误"));
        }

        String videoUrl = result.get("videoUrl");
        return ApiResponse.ok(Map.of("videoUrl", videoUrl != null ? videoUrl : "", "taskId", taskId));
    }
}
```

**注意**：视频代理端点中，`pollVideoTask` 方法会轮询等待直到完成或超时。需要确认 `VideoGenerationService.pollVideoTask` 的实现是否支持阻塞等待。如果当前实现只查询一次状态，需要修改为带轮询逻辑的版本。

- [ ] **步骤 2：检查 VideoGenerationService.pollVideoTask 实现**

```bash
# 查看现有轮询逻辑
grep -n "pollVideoTask" AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java
```

根据检查结果，如果 pollVideoTask 不做轮询，需要修改：

```java
// 在 VideoGenerationService 中修改 pollVideoTask 方法
public Map<String, String> pollVideoTask(String taskId) {
    int maxAttempts = 60; // 5 分钟 / 5 秒
    long intervalMs = 5_000;

    for (int i = 0; i < maxAttempts; i++) {
        Map<String, String> result = fetchTaskStatus(taskId);
        String status = result.get("status");
        if ("completed".equals(status) || "failed".equals(status)) {
            // 完成后下载视频
            if ("completed".equals(status)) {
                String localPath = downloadVideoContent(config.getBaseUrl(), taskId);
                if (localPath != null) {
                    result.put("videoUrl", "/api/files/videos/" + 
                        localPath.substring(localPath.lastIndexOf('/') + 1));
                }
            }
            return result;
        }
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    return Map.of("taskId", taskId, "status", "timeout", "error", "轮询超时");
}
```

- [ ] **步骤 3：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

预期：编译通过。

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java
git commit -m "feat: add DifyAgentController with script/image/video proxy endpoints"
```

---

### 任务 4：Dify — 重建 Agent 工作流 DSL

**文件：**
- 修改：`AIStoryboardDify/ai-generate-agent.dsl.yaml`（通过 `difyctl export` 获取当前版本后修改）
- 最终通过：`difyctl import studio-app` 导入

- [ ] **步骤 1：导出当前 DSL 作为基线**

```bash
difyctl export studio-app c7cd4e0d-bc58-4a62-8322-293440759497 \
  --output "E:/Desktop/AI-storyboard/AIStoryboardDify/ai-generate-agent-v2.dsl.yaml"
```

- [ ] **步骤 2：编写新 DSL**

基于设计文档，修改 DSL YAML：

**2a. conversation_variables 更新**

```yaml
conversation_variables:
  - name: step
    type: integer
    default: -1
    description: |
      -1: 初始/意图识别
      1: 分镜方案设计（循环完善）
      2: 分镜JSON生成+写入后端
      3: 图片方案设计（循环完善）
      4: 视频方案设计（循环完善）
      5: 调用后端生图API
      6: 调用后端生视频API
  - name: historytalk
    type: array[string]
    default: []
    description: 最近20轮对话历史
  - name: currentProjectId
    type: string
    default: ""
    description: 当前操作的项目ID
```

**2b. 节点清单（需创建/修改的节点）**

保留并修改现有节点：
- start (1785116655424) — 保持不变
- step-router (1785201739907) — 扩展为 7 分支 (-1/1/2/3/4/5/6)
- intent-llm (1785139955672) — 保留，微调 prompt
- intent-router (1785141564962) — 扩展为 4 分支 + set step

新增节点：
- storyboard-design-llm — 分镜方案 LLM
- storyboard-confirm — 分镜确认分支
- storyboard-json-llm — 分镜 JSON 生成
- format-adapter-code — Code: 格式适配
- post-script-http — HTTP: POST 分镜脚本
- image-design-llm — 图片方案 LLM
- image-confirm — 图片确认分支
- post-image-http — HTTP: POST 生图
- video-design-llm — 视频方案 LLM
- video-confirm — 视频确认分支
- post-video-http — HTTP: POST 生视频
- trim-history-code — Code: 裁剪历史
- reset-assigner — 重置 step=-1 (多处复用)

**2c. HTTP 节点配置示例 (post-script-http)**

```yaml
data:
  type: http-request
  title: POST分镜脚本
  method: post
  url: "http://host.docker.internal:8082/api/ai/dify/generate-script"
  headers: "X-Dify-Key: {{DIFY_KEY}}\nContent-Type: application/json"
  body:
    type: json
    data:
      projectId: "{{#conversation.currentProjectId#}}"
      scenes: "{{#storyboard-json-llm.structured_output.items#}}"
      aspectRatio: "16:9"
```

**2d. Code 节点示例 (format-adapter-code)**

```javascript
// 将 LLM 输出的 JSON 适配为后端 DifyGenerateScriptRequest 格式
const llmOutput = {{#storyboard-json-llm.structured_output#}};

// LLM 输出已是正确格式，直接透传
const scenes = llmOutput.items.map(item => ({
    sceneNumber: item.sceneNumber,
    scriptContent: item.scriptContent || "",
    imagePrompt: item.imagePrompt || "",
    videoPrompt: item.videoPrompt || "",
    negativePrompt: item.negativePrompt || "",
    cameraMovement: item.cameraMovement || "",
    shotType: item.shotType || "",
    soundDesign: item.soundDesign || ""
}));

return { scenes: scenes };
```

**2e. 所有 LLM node prompts 按设计文档编写**

参见 `docs/superpowers/specs/2026-07-28-aigenerate-agent-design.md` 中的 Prompt 设计部分。

- [ ] **步骤 3：导入新 DSL**

```bash
# 先删除旧 app（或使用 --app-id 覆盖）
difyctl import studio-app \
  --from-file "E:/Desktop/AI-storyboard/AIStoryboardDify/ai-generate-agent-v2.dsl.yaml" \
  --app-id c7cd4e0d-bc58-4a62-8322-293440759497
```

- [ ] **步骤 4：确认导入成功 + 测试运行**

```bash
difyctl describe app c7cd4e0d-bc58-4a62-8322-293440759497

# 测试意图识别
difyctl run app c7cd4e0d-bc58-4a62-8322-293440759497 "你好" -o json

# 测试分镜流程
difyctl run app c7cd4e0d-bc58-4a62-8322-293440759497 "我想做一个武侠短片的分镜" -o json
```

- [ ] **步骤 5：设置环境变量**

在 Dify 工作空间环境变量中配置：
```
DIFY_KEY=<与后端 ai.dify.api-key 相同的值>
LAOZHANG_KEY=<Laozhang API Key>
BACKEND_URL=http://host.docker.internal:8082
```

- [ ] **步骤 6：Commit**

```bash
git add AIStoryboardDify/ai-generate-agent-v2.dsl.yaml
git commit -m "feat: rebuild AiGenerateAgent workflow with unified step state machine"
```

---

### 任务 5：端到端集成验证

- [ ] **步骤 1：启动后端**

```bash
# IDEA 中启动 Spring Boot (application-local profile)
# 或命令行：
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" spring-boot:run -Dspring-boot.run.profiles=local
```

- [ ] **步骤 2：验证 Dify Agent 端点**

```bash
# 测试认证（无 key 应返回 401）
curl -X POST http://localhost:8082/api/ai/dify/generate-script \
  -H "Content-Type: application/json" \
  -d '{"projectId":"test","scenes":[]}'

# 测试认证（有 key 应返回 200）
curl -X POST http://localhost:8082/api/ai/dify/generate-script \
  -H "Content-Type: application/json" \
  -H "X-Dify-Key: <your-key>" \
  -d '{"projectId":"test","scenes":[{"sceneNumber":1,"scriptContent":"test","imagePrompt":"test","videoPrompt":"test","negativePrompt":"","cameraMovement":"","shotType":"中景","soundDesign":""}]}'
```

预期：
- 无 key → 401 `{"code":40101,"message":"Dify API Key 无效"}`
- 有正确 key → 200，返回 sceneCount=1

- [ ] **步骤 3：验证 Dify Agent 对话流程**

```bash
# 逐轮测试完整对话流程
difyctl run app c7cd4e0d-bc58-4a62-8322-293440759497 "我想做一个3分钟武侠短片的分镜" --stream
```

预期：流式输出意图识别→分镜方案→等待确认→JSON生成→写回后端。

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "test: end-to-end integration verification for AiGenerateAgent"
```
