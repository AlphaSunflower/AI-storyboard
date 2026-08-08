# 图改图（edits）接入 LLM 网关 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把 Backend 的图改图（/v1/images/edits multipart）调用从直连 Laozhang 切换为走 LLM 网关，实现所有生成接口全覆盖。

**架构：** 网关新增 `POST /v1/images/edits` 端点，接收原始 multipart 字节流，从字节流轻量解析 `model` part → 复用 model_route 渠道路由（与 GatewayRoutingService 相同语义：429/5xx 切渠道、4xx 透传、全失败 50301、落 call_log）→ UpstreamClient 新增 postMultipart 原样转发 Laozhang。Backend 侧 callImageEdit 只改 URI 和 Authorization 两处。

**技术栈：** Spring Boot 4.0.0 / JDK 21 / MyBatis-Plus / JDK HttpClient / jjwt

**设计文档：** docs/superpowers/specs/2026-08-08-image-edits-gateway-design.md

---

### 任务 1：UpstreamClient 新增 postMultipart

**文件：**
- 修改：`AILLMGateway/src/main/java/com/llmgateway/service/UpstreamClient.java`（在 postGemini 方法后新增）

- [ ] **步骤 1：新增 postMultipart 方法**

在 `UpstreamClient.java` 的 `postGemini` 方法（约 :46-54）之后插入：

```java
    /** POST multipart 到 openai_compatible 渠道（图改图 edits：原样透传 multipart 字节流） */
    public HttpResponse<String> postMultipart(String baseUrl, String path, String apiKey,
                                              String contentType, byte[] bodyBytes) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", contentType)          // 透传上游 Content-Type（含 boundary）
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();
        return sendWithRetry(request);
    }
```

- [ ] **步骤 2：编译验证**

运行：
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
```
预期：exit 0（无输出）

- [ ] **步骤 3：Commit**

```bash
cd "E:\Desktop\AI-storyboard"
git add AILLMGateway/src/main/java/com/llmgateway/service/UpstreamClient.java
git commit -m "feat: UpstreamClient 新增 postMultipart（图改图 multipart 原样透传）"
```

---

### 任务 2：ImageEditService（网关侧 edits 路由转发）

**文件：**
- 创建：`AILLMGateway/src/main/java/com/llmgateway/service/ImageEditService.java`
- 参考（镜像其渠道解析逻辑）：`AILLMGateway/src/main/java/com/llmgateway/service/GatewayRoutingService.java:67-110`

- [ ] **步骤 1：创建 ImageEditService**

完整文件内容：

```java
package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.CallLog;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * 图改图（edits）网关服务：接收 OpenAI 原生 multipart 字节流，
 * 从字节流解析 model 字段 → 复用 model_route 渠道路由 → 原样透传上游。
 *
 * 行为与 GatewayRoutingService 完全一致：
 *   429/5xx → 切下一渠道；4xx → 透传错误体；全渠道失败 → 50301；每次调用落 call_log
 */
@Service
public class ImageEditService {

    private static final Logger log = LoggerFactory.getLogger(ImageEditService.class);

    /** 上游 edits 端点路径（openai_compatible 渠道统一使用） */
    private static final String EDIT_PATH = "/v1/images/edits";

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final CallLogService callLogService;

    public ImageEditService(ModelRouteMapper routeMapper,
                            ChannelMapper channelMapper,
                            KeyService keyService,
                            UpstreamClient upstreamClient,
                            CallLogService callLogService) {
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.upstreamClient = upstreamClient;
        this.callLogService = callLogService;
    }

    /**
     * 转发图改图请求。
     *
     * @param multipartBody  原始 multipart 字节流（含 model/prompt 字段 + image 文件 part）
     * @param contentType    原 Content-Type（含 boundary）
     * @return 上游响应体（200 时含 data[0].b64_json；非 200 时为错误体）
     */
    public String edit(byte[] multipartBody, String contentType) {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        try {
            // 1. 从 multipart 字节流轻量解析 model 字段（name="model" part 的 body）
            model = parseModelField(multipartBody);
            if (model == null || model.isBlank()) throw new BusinessException(40001, "model 不能为空");

            // 2. 查该模型的所有路由（一个模型可指向多个渠道，按 priority 轮换）
            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getModelName, model));
            if (routes == null || routes.isEmpty()) {
                throw new BusinessException(40401, "no route for model: " + model);
            }

            // 3. 候选渠道（路由指向的 enabled 渠道，按 priority 升序）
            List<Channel> candidates = routes.stream()
                    .map(r -> channelMapper.selectById(r.getChannelId()))
                    .filter(c -> c != null && Boolean.TRUE.equals(c.getEnabled()))
                    .sorted(Comparator.comparingInt(c -> c.getPriority() == null ? 0 : c.getPriority()))
                    .toList();
            if (candidates.isEmpty()) {
                throw new BusinessException(50301, "no available channel for model: " + model);
            }

            // 4. 逐个渠道尝试（失败切下一个）
            for (Channel channel : candidates) {
                try {
                    channelId = channel.getId();
                    String apiKey = keyService.decrypt(channel.getApiKey());
                    HttpResponse<String> resp = upstreamClient.postMultipart(
                            channel.getBaseUrl(), EDIT_PATH, apiKey, contentType, multipartBody);
                    int status = resp.statusCode();
                    if (status >= 400) {
                        String error = upstreamClient.extractError(resp.body());
                        log.warn("渠道 {} 返回 {}: {}", channel.getName(), status, error);
                        // 429/5xx 尝试下一个渠道；其余 4xx 业务错误直接透传
                        if (status != 429 && status < 500) {
                            callLogService.log(model, channelId, "error",
                                    System.currentTimeMillis() - start, error, null, null);
                            return resp.body();
                        }
                        continue;
                    }
                    callLogService.log(model, channelId, "success",
                            System.currentTimeMillis() - start, null, null, null);
                    return resp.body();
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception e) {
                    log.warn("渠道 {} 调用异常: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(50301, "all channels failed for model: " + model);
        } catch (BusinessException be) {
            callLogService.log(model, channelId, "error",
                    System.currentTimeMillis() - start, be.getMessage(), null, null);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error",
                    System.currentTimeMillis() - start, e.getMessage(), null, null);
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /**
     * 从 multipart 字节流中提取 name="model" 字段的值。
     * 轻量字节级解析（不引入 multipart 解析库）：
     *   定位 name="model" → 其后跟随 \r\n\r\n → 读取直到下一个 \r\n
     */
    private String parseModelField(byte[] body) {
        String text = new String(body, StandardCharsets.ISO_8859_1);  // multipart 二进制安全，逐字节映射
        String marker = "name=\"model\"";
        int idx = text.indexOf(marker);
        if (idx < 0) return null;
        int headerEnd = text.indexOf("\r\n\r\n", idx);
        if (headerEnd < 0) return null;
        int valueStart = headerEnd + 4;
        int valueEnd = text.indexOf("\r\n", valueStart);
        if (valueEnd < 0) return null;
        return text.substring(valueStart, valueEnd).trim();
    }
}
```

- [ ] **步骤 2：编译验证**

运行：
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
```
预期：exit 0（无输出）

- [ ] **步骤 3：ad-hoc 断言验证 parseModelField**

写临时验证脚本 `C:\Users\38632\AppData\Local\Temp\HermesVerifyEditModel.java`（类名与文件名一致）：

```java
import com.llmgateway.service.ImageEditService;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class HermesVerifyEditModel {
    public static void main(String[] args) throws Exception {
        // 反射调用私有 parseModelField（无需实例化依赖——方法只用入参）
        Method m = ImageEditService.class.getDeclaredMethod("parseModelField", byte[].class);
        m.setAccessible(true);
        // 无法构造实例（构造器依赖 mapper），用 Unsafe 分配
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = unsafeCls.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        ImageEditService svc = (ImageEditService) unsafeCls.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, ImageEditService.class);

        int pass = 0, fail = 0;
        // 用例 1：标准 multipart（model 在中间）
        String mp1 = "--boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-image-2\r\n--boundary\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\nmake it red\r\n--boundary--\r\n";
        String r1 = (String) m.invoke(svc, mp1.getBytes(StandardCharsets.ISO_8859_1));
        System.out.println(("gpt-image-2".equals(r1) ? "PASS" : "FAIL") + " | 标准 multipart 解析: " + r1);
        if ("gpt-image-2".equals(r1)) pass++; else fail++;

        // 用例 2：model 在开头（image 文件 part 在后面）
        String mp2 = "--boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nmy-model\r\n--boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\nBINARYDATA\r\n--boundary--\r\n";
        String r2 = (String) m.invoke(svc, mp2.getBytes(StandardCharsets.ISO_8859_1));
        System.out.println(("my-model".equals(r2) ? "PASS" : "FAIL") + " | model 开头 + 文件 part: " + r2);
        if ("my-model".equals(r2)) pass++; else fail++;

        // 用例 3：无 model 字段 → null
        String mp3 = "--boundary\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\nno model here\r\n--boundary--\r\n";
        String r3 = (String) m.invoke(svc, mp3.getBytes(StandardCharsets.ISO_8859_1));
        System.out.println((r3 == null ? "PASS" : "FAIL") + " | 无 model 字段 → null");
        if (r3 == null) pass++; else fail++;

        System.out.println("===== " + pass + " PASS / " + fail + " FAIL =====");
        System.out.println(fail == 0 ? "VERIFY_OK" : "VERIFY_FAIL");
        if (fail > 0) System.exit(1);
    }
}
```

编译运行（classpath 用 maven dependency 生成）：
```bash
cd "E:\\Desktop\\AI-storyboard\\AILLMGateway"
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
WIN_TEMP=$(python -c "import os; print(os.environ['TEMP'])")
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" dependency:build-classpath -Dmdep.outputFile="$WIN_TEMP\\llmgw-cp.txt" -q
CP="target/classes;$(cat "$WIN_TEMP/llmgw-cp.txt")"
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$WIN_TEMP/llmgw-verify-out" "$WIN_TEMP/HermesVerifyEditModel.java"
"$JAVA_HOME/bin/java" -cp "$WIN_TEMP/llmgw-verify-out;$CP" HermesVerifyEditModel
```
预期：3 PASS / 0 FAIL，VERIFY_OK

- [ ] **步骤 4：清理临时文件并 Commit**

```bash
WIN_TEMP=$(python -c "import os; print(os.environ['TEMP'])")
rm -rf "$WIN_TEMP/HermesVerifyEditModel.java" "$WIN_TEMP/llmgw-verify-out"
cd "E:\Desktop\AI-storyboard"
git add AILLMGateway/src/main/java/com/llmgateway/service/ImageEditService.java
git commit -m "feat: ImageEditService 网关图改图路由转发（multipart 透传 + model_route 复用 + call_log）"
```

---

### 任务 3：Controller 端点 + Backend 切换

**文件：**
- 修改：`AILLMGateway/src/main/java/com/llmgateway/controller/OpenAiCompatController.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java:193-250`

- [ ] **步骤 1：网关 Controller 新增 /v1/images/edits 端点**

在 `OpenAiCompatController.java` 的 `imageGenerations` 方法（约 :28-31）之后插入：

```java
    @PostMapping(value = "/images/edits", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> imageEdits(@RequestBody byte[] body,
                                             @RequestHeader("Content-Type") String contentType) {
        // 图改图：原始 multipart 字节流 + Content-Type（含 boundary）原样透传
        return ResponseEntity.ok(imageEditService.edit(body, contentType));
    }
```

同时修改类声明与构造器（注入 ImageEditService）：

```java
public class OpenAiCompatController {
    private final GatewayRoutingService routingService;
    private final VideoGatewayService videoGatewayService;
    private final ImageEditService imageEditService;

    public OpenAiCompatController(GatewayRoutingService routingService,
                                  VideoGatewayService videoGatewayService,
                                  ImageEditService imageEditService) {
        this.routingService = routingService;
        this.videoGatewayService = videoGatewayService;
        this.imageEditService = imageEditService;
    }
```

新增 import：`com.llmgateway.service.ImageEditService`、`org.springframework.web.bind.annotation.RequestBody`（已有）、`org.springframework.web.bind.annotation.RequestHeader`（若缺则加）。

- [ ] **步骤 2：Backend callImageEdit 切换网关**

修改 `ImageGenerationService.java` 的 `callImageEdit`（:225-237 区域），只改 URI/Authorization 两处 + 删除不再使用的 apiKey 局部变量：

```java
        // 3. 发送请求（multipart body 一次性构建；超时 180s + 重试 1 次）
        byte[] bodyBytes = mp.build();
        HttpResponse<String> resp = sendImageWithRetry(() -> HttpRequest.newBuilder()
            .uri(URI.create(config.getGatewayBaseUrl() + "/v1/images/edits"))   // 改：走网关
            .header("Content-Type", "multipart/form-data; boundary=" + mp.boundary())
            .header("Authorization", "Bearer " + config.getGatewayApiKey())      // 改：网关业务 Key
            .timeout(Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
            .build());
```

删除原 :225 行的 `String apiKey = config.getApiKey();`（不再使用）。

- [ ] **步骤 3：双项目编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
# 网关
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
echo "网关 EXIT=$?"
# Backend
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
echo "Backend EXIT=$?"
```
预期：两个 EXIT=0

- [ ] **步骤 4：Commit**

```bash
cd "E:\Desktop\AI-storyboard"
git add AILLMGateway/src/main/java/com/llmgateway/controller/OpenAiCompatController.java
git add AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java
git commit -m "feat: 图改图 edits 切网关（/v1/images/edits multipart 透传，Backend 换 URI/Key）"
```

---

### 任务 4：E2E 联调验证（真实 edits 调用）

**前置：** 网关运行中（8083）+ Backend 运行中（8082）+ 渠道/路由已配（gpt-image-2 → laozhang）+ Backend .env 已配 LLM_GATEWAY_BASE_URL/API_KEY（上一轮已补）

**文件：**
- 报告：`.superpowers/sdd/task-edits-e2e-report.md`（新写，不 commit）

- [ ] **步骤 1：确认网关与 Backend 运行**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/v1/models   # 预期 401（无 Key）
netstat -ano | grep ":8082.*LISTENING" | head -1                         # Backend 在跑
```

若网关未运行，启动：`cd "E:\Desktop\AI-storyboard\AILLMGateway" && export JAVA_HOME="C:\\Program Files\\Java\\jdk-21" && "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" spring-boot:run -q`（后台）

- [ ] **步骤 2：真实 edits E2E（完善图片场景）**

用 python 构造 multipart（model=gpt-image-2, prompt=把图改亮, image=1x1 红点 PNG），直调网关：

```bash
cd "E:\Desktop\AI-storyboard"
WIN_TEMP=$(python -c "import os; print(os.environ['TEMP'])")
python -c "
import subprocess, os, json, base64
key = open(os.path.join(os.environ['TEMP'], 'llmgw-biz-key.txt')).read().strip()
# 1x1 红色 PNG
png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
boundary = '----gwtest' + os.urandom(4).hex()
parts = []
parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-image-2\r\n'.encode())
parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\nmake the image brighter, keep the subject\r\n'.encode())
parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n'.encode() + png + b'\r\n')
parts.append(f'--{boundary}--\r\n'.encode())
body = b''.join(parts)
r = subprocess.run(['curl', '-s', '-w', '\n%{http_code}', '-X', 'POST', 'http://localhost:8083/v1/images/edits',
    '-H', f'Authorization: Bearer {key}', '-H', f'Content-Type: multipart/form-data; boundary={boundary}',
    '--data-binary', '@-'], input=body, capture_output=True)
lines = r.stdout.rsplit(b'\n', 1)
print('网关 edits 直调 HTTP:', lines[1].decode())
if lines[1].decode() == '200':
    d = json.loads(lines[0])
    b64 = d['data'][0].get('b64_json') or d['data'][0].get('url','')
    print('edits 返回 b64 长度:', len(b64))
else:
    print('body:', lines[0][:200])
"
```
预期：HTTP 200 + b64 长度 > 1000（Laozhang 真实生成改图）

- [ ] **步骤 3：验证 call_log 落库**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -d llm_gateway -c "SELECT model, channel_id, status, duration_ms FROM call_log WHERE model='gpt-image-2' ORDER BY created_at DESC LIMIT 3;"
```
预期：出现 edits 调用的 success 记录（最近一条）

- [ ] **步骤 4：写 E2E 报告**

写 `.superpowers/sdd/task-edits-e2e-report.md`：HTTP 状态码、b64 长度、call_log 记录、异常（如有）。不 commit。

---

## 自检记录

- **规格覆盖度：** 设计 §4.1（Controller 端点）→ 任务 3 步骤 1；§4.2（ImageEditService）→ 任务 2；§4.3（postMultipart）→ 任务 1；§4.4（call_log）→ 任务 2 内嵌；§5（Backend 2 行）→ 任务 3 步骤 2；§9（验证）→ 任务 2 步骤 3 + 任务 4；§8（YAGNI：不做 Gemini/MiniMax 转换、不改 GatewayRoutingService）→ 计划未包含 ✓
- **占位符扫描：** 无 TODO/待定；所有代码块完整
- **类型一致性：** `postMultipart(baseUrl, path, apiKey, contentType, bodyBytes)` 签名在任务 1 定义、任务 2 调用一致；`imageEditService.edit(body, contentType)` 在任务 3 端点与任务 2 定义一致；`parseModelField(byte[])` 任务 2 定义与断言一致
- **Backend .env 依赖：** 上一轮已补 LLM_GATEWAY_BASE_URL/API_KEY（未提交，属本地配置）；若用户重启 Backend 时未包含则任务 4 会失败——已在任务 4 前置中注明
