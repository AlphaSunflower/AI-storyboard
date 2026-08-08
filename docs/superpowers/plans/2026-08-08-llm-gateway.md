# 大模型网关（AILLMGateway）实现计划 v2

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 AI Storyboard 仓库内从空骨架全新开发独立大模型网关服务（`AILLMGateway/`，端口 8083），对外提供 OpenAI 兼容接口（chat/images/videos），对内路由到 Laozhang/Gemini/MiniMax 渠道并管理密钥；Backend 现有生文/生图/视频调用全部切换到网关（edits/Dify 保持直连）。

**架构：** 网关 = 静态 Key 鉴权（`/v1/**`）+ JWT ADMIN 鉴权（`/admin/**`）双通道；路由核心查 `model_route` → 取 `channel` → AES 解密渠道 Key → 透传（openai_compatible）或格式转换（gemini/minimax/laozhang-video）；视频创建/轮询/下载统一 `/v1/videos` 系列端点，网关流式代理下载；管理 API 即时生效（每次请求查 DB）。业务侧仅改 base-url 与 Authorization 头。

**技术栈：** Spring Boot 4.0.0 / JDK 21 / MyBatis-Plus 3.5.16（spring-boot4-starter + jsqlparser）/ PostgreSQL / jjwt 0.12.6 / scrypt（lambdaworks 1.4.0）/ JDK HttpClient。设计文档：`docs/superpowers/specs/2026-08-08-llm-gateway-design.md`。

**参考实现：** 昨天（2026-08-07）曾实现过无视频版本并通过 E2E（commit `6ed5ba7`，已被 reset 摘除但对象仍在）。**只能作参考对照**（`git show 6ed5ba7:<path>` 查看），必须重新编写代码，不得直接 checkout。

---

## 文件结构

**新建（网关，`E:\Desktop\AI-storyboard\AILLMGateway\`）：**

```
AILLMGateway/
├── pom.xml
├── .env.example
└── src/main/
    ├── java/com/llmgateway/
    │   ├── LLMGatewayApplication.java   # 启动类 + loadDotEnv（LLM_GATEWAY_ENV_FILE→CWD→AILLMGateway/.env）
    │   ├── config/
    │   │   ├── GatewayConfig.java       # @ConfigurationProperties(gateway)：jwt/aes/upstream
    │   │   ├── SecurityConfig.java      # 双通道 + ASYNC/ERROR dispatcher permitAll
    │   │   ├── MybatisPlusConfig.java   # 分页插件（管理列表用）
    │   │   └── AdminInitRunner.java     # 首启自举 admin（scrypt 16384）
    │   ├── security/
    │   │   ├── JwtTokenProvider.java    # jjwt 签发/校验（access+refresh）
    │   │   ├── AdminJwtFilter.java      # 仅 /admin/**（shouldNotFilter 限定前缀）
    │   │   └── StaticApiKeyFilter.java  # /v1/** 静态 Key（SHA-256 比对）
    │   ├── entity/                      # Channel/ModelRoute/GatewayApiKey/AdminUser/CallLog
    │   ├── mapper/                      # 5 个 BaseMapper
    │   ├── dto/
    │   │   ├── ApiResponse.java         # {code,message,data}
    │   │   └── admin/                   # AdminLoginRequest/Response/ChannelRequest/RouteRequest/ApiKeyRequest
    │   ├── service/
    │   │   ├── KeyService.java          # AES-256-GCM 加解密 + SHA-256 比对
    │   │   ├── GeminiFormatConverter.java  # OpenAI ↔ Gemini 生图格式互转
    │   │   ├── UpstreamClient.java      # HttpClient 封装（透传/重试/超时）
    │   │   ├── GatewayRoutingService.java  # chat/images 路由核心
    │   │   ├── VideoGatewayService.java # 视频创建/轮询/下载（Laozhang + MiniMax 双协议）★新增
    │   │   └── CallLogService.java      # 异步落日志（含 video_url 暂存）
    │   ├── controller/
    │   │   ├── OpenAiCompatController.java # /v1/chat/completions + /v1/images/generations + /v1/videos 系列
    │   │   └── admin/                   # AdminAuth/AdminChannel/AdminRoute/AdminApiKey/AdminLog Controller
    │   └── exception/
    │       ├── BusinessException.java   # code + message
    │       └── GlobalExceptionHandler.java  # 统一 {error:{message}} + 404 细化
    └── resources/
        ├── application.yml              # 端口8083 + 数据源 + mybatis-plus 逻辑删除 true/false
        └── db/migration/V1__gateway_tables.sql
```

**修改（Backend，`E:\Desktop\AI-storyboard\AIStoryboardBackend\`）：**

- `src/main/java/com/storyboard/service/ai/AiConfigProperties.java` — 新增 `ai.gateway.*`（base-url/api-key）+ `videoGateway*`（视频端点）
- `src/main/java/com/storyboard/service/ai/ScriptGenerationService.java` — chat 切网关
- `src/main/java/com/storyboard/service/ai/ImageGenerationService.java` — 文生图/Gemini 切网关，删 geminiImageModelSet 判断，edits 保留直连
- `src/main/java/com/storyboard/service/ai/ImageRefinePromptService.java` — chat 切网关
- `src/main/java/com/storyboard/service/agent/ConversationTitleService.java` — chat 切网关
- `src/main/java/com/storyboard/service/agent/PromptOptimizeService.java` — chat 切网关
- `src/main/java/com/storyboard/service/ai/VideoPlanService.java` — chat 切网关（第 4 个 chat 调用方）
- `src/main/java/com/storyboard/service/ai/VideoGenerationService.java` — 视频创建/轮询/下载切网关，删 Laozhang 原生逻辑
- `src/main/java/com/storyboard/service/ai/MinimaxVideoService.java` — 改走网关端点，删除直连逻辑

---

## 任务 1：网关项目骨架（pom + 启动类 + 配置）

**文件：**
- 创建：`E:\Desktop\AI-storyboard\AILLMGateway\pom.xml`（覆盖空骨架的 SB 4.1.0 为 SB 4.0.0，与 Backend 一致）
- 创建：`E:\Desktop\AI-storyboard\AILLMGateway\src\main\java\com\llmgateway\LLMGatewayApplication.java`
- 创建：`E:\Desktop\AI-storyboard\AILLMGateway\src\main\resources\application.yml`
- 创建：`E:\Desktop\AI-storyboard\AILLMGateway\.env.example`
- 创建：`E:\Desktop\AI-storyboard\AILLMGateway\src\main\java\com\llmgateway\config\GatewayConfig.java`
- 创建：`E:\Desktop\AI-storyboard\AILLMGateway\src\main\java\com\llmgateway\config\MybatisPlusConfig.java`
- 删除：空骨架的 `AillmGatewayApplication.java`、`AillmGatewayApplicationTests.java`（包名 com.moon 弃用）

- [ ] **步骤 1：重写 pom.xml（SB 4.0.0，全依赖）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.0</version>
        <relativePath/>
    </parent>
    <groupId>com.llmgateway</groupId>
    <artifactId>llm-gateway</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>LLM Gateway</name>
    <properties><java.version>21</java.version></properties>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot4-starter</artifactId><version>3.5.16</version></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-jsqlparser</artifactId><version>3.5.16</version></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>0.12.6</version></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
        <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
        <dependency><groupId>com.lambdaworks</groupId><artifactId>scrypt</artifactId><version>1.4.0</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

- [ ] **步骤 2：启动类（含 loadDotEnv，多候选路径）**

```java
package com.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 网关启动类：手动读 .env（SB4 与 spring-dotenv 不兼容），支持任意目录启动 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync  // CallLogService 异步落库需要
public class LLMGatewayApplication {

    public static void main(String[] args) throws IOException {
        loadDotEnv();
        SpringApplication.run(LLMGatewayApplication.class, args);
    }

    /** 读取 .env（KEY=VALUE 逐行），仅当系统属性未设置时注入；候选路径：LLM_GATEWAY_ENV_FILE → CWD → AILLMGateway/.env */
    private static void loadDotEnv() throws IOException {
        Path envFile = resolveEnvFile();
        if (envFile == null || !Files.exists(envFile)) return;
        Map<String, String> props = new HashMap<>();
        for (String line : Files.readAllLines(envFile)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            int idx = t.indexOf('=');
            props.put(t.substring(0, idx).trim(), t.substring(idx + 1).trim());
        }
        props.forEach((k, v) -> { if (System.getProperty(k) == null) System.setProperty(k, v); });
    }

    private static Path resolveEnvFile() {
        String explicit = System.getProperty("LLM_GATEWAY_ENV_FILE");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        if (Files.exists(Path.of(".env"))) return Path.of(".env");
        if (Files.exists(Path.of("AILLMGateway/.env"))) return Path.of("AILLMGateway/.env");
        return null;
    }
}
```

- [ ] **步骤 3：application.yml（端口 8083 + 数据源 + 逻辑删除 boolean）**

```yaml
spring:
  application:
    name: llm-gateway
  datasource:
    url: jdbc:postgresql://${LLM_GATEWAY_DB_HOST:localhost}:${LLM_GATEWAY_DB_PORT:5432}/${LLM_GATEWAY_DB_NAME:llm_gateway}
    username: ${LLM_GATEWAY_DB_USERNAME:postgres}
    password: ${LLM_GATEWAY_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver

server:
  port: ${LLM_GATEWAY_PORT:8083}

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: assign_uuid
      logic-delete-field: deleted
      logic-delete-value: true      # BOOLEAN 列，不能用 1/0（实测踩坑）
      logic-not-delete-value: false

gateway:
  jwt:
    access-secret: ${LLM_GATEWAY_JWT_ACCESS_SECRET}
    refresh-secret: ${LLM_GATEWAY_JWT_REFRESH_SECRET}
    issuer: ${LLM_GATEWAY_JWT_ISSUER:llm-gateway}
    access-token-ttl: 3600
    refresh-token-ttl: 2592000
  aes:
    secret: ${LLM_GATEWAY_AES_SECRET}   # AES-256，需 32 字节
  upstream:
    connect-timeout-ms: 30000
    request-timeout-ms: 120000
    retry-count: 2
```

- [ ] **步骤 4：.env.example + GatewayConfig + MybatisPlusConfig**

`.env.example`（键与 application.yml 占位符一一对应）：

```
# 数据库（独立库 llm_gateway）
LLM_GATEWAY_DB_HOST=localhost
LLM_GATEWAY_DB_PORT=5432
LLM_GATEWAY_DB_NAME=llm_gateway
LLM_GATEWAY_DB_USERNAME=postgres
LLM_GATEWAY_DB_PASSWORD=123456
# JWT 签名密钥（至少 32 字节）
LLM_GATEWAY_JWT_ACCESS_SECRET=change-me-access-secret-32-bytes-min
LLM_GATEWAY_JWT_REFRESH_SECRET=change-me-refresh-secret-32-bytes-min
# 渠道 API Key AES 加密密钥（AES-256，恰好 32 字节）
LLM_GATEWAY_AES_SECRET=0123456789abcdef0123456789abcdef
# 首次启动初始化管理员密码（自举建 admin；创建成功后建议移除）
LLM_GATEWAY_ADMIN_INIT_PASSWORD=change-me-admin-password
# LLM_GATEWAY_PORT=8083
```

`GatewayConfig.java`：

```java
package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关配置（gateway.*）：JWT/AES/上游超时 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {
    private Jwt jwt = new Jwt();
    private Aes aes = new Aes();
    private Upstream upstream = new Upstream();

    public static class Jwt {
        private String accessSecret;
        private String refreshSecret;
        private String issuer = "llm-gateway";
        private long accessTokenTtl = 3600;
        private long refreshTokenTtl = 2592000;
        // getter/setter 全量
    }
    public static class Aes {
        private String secret;
        // getter/setter
    }
    public static class Upstream {
        private long connectTimeoutMs = 30000;
        private long requestTimeoutMs = 120000;
        private int retryCount = 2;
        // getter/setter
    }
    // 三个字段的 getter/setter
}
```

`MybatisPlusConfig.java`：

```java
package com.llmgateway.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 分页插件（管理列表分页用） */
@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
```

- [ ] **步骤 5：删除空骨架旧文件**

```bash
rm "E:\Desktop\AI-storyboard\AILLMGateway\src\main\java\com\moon\aillmgateway\AillmGatewayApplication.java"
rm "E:\Desktop\AI-storyboard\AILLMGateway\src\test\java\com\moon\aillmgateway\AillmGatewayApplicationTests.java"
rmdir -p "E:\Desktop\AI-storyboard\AILLMGateway\src\test\java\com\moon\aillmgateway" 2>/dev/null || true
# application.yaml（空骨架的）保留会被配置扫描，改名备份
mv "E:\Desktop\AI-storyboard\AILLMGateway\src\main\resources\application.yaml" \
   "E:\Desktop\AI-storyboard\AILLMGateway\src\main\resources\application.yaml.bak" 2>/dev/null || true
```

- [ ] **步骤 6：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
```
预期：BUILD SUCCESS（exit 0）

- [ ] **步骤 7：Commit**

```bash
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "chore: LLM 网关项目骨架（SB4 + MP + jjwt，端口 8083）"
```

---

## 任务 2：建表 SQL + 实体 + Mapper

**文件：**
- 创建：`E:\Desktop\AI-storyboard\AILLMGateway\src\main\resources\db\migration\V1__gateway_tables.sql`
- 创建：`entity/Channel.java`、`entity/ModelRoute.java`、`entity/GatewayApiKey.java`、`entity/AdminUser.java`、`entity/CallLog.java`
- 创建：`mapper/ChannelMapper.java`、`mapper/ModelRouteMapper.java`、`mapper/GatewayApiKeyMapper.java`、`mapper/AdminUserMapper.java`、`mapper/CallLogMapper.java`

- [ ] **步骤 1：建表 SQL（含 call_log.video_url）**

```sql
-- 渠道表：上游供应商（Laozhang / Gemini / MiniMax）
CREATE TABLE channel (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32)  NOT NULL DEFAULT 'openai_compatible',  -- openai_compatible | gemini | minimax
    base_url    VARCHAR(512) NOT NULL,
    api_key     TEXT         NOT NULL,        -- AES 加密密文
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    priority    INT          NOT NULL DEFAULT 0,   -- 同模型多渠道时升序取第一个
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 模型路由表：模型名 → 渠道映射（非唯一：一个模型可指向多个渠道按 priority 轮换）
CREATE TABLE model_route (
    id            VARCHAR(64) PRIMARY KEY,
    model_name    VARCHAR(128) NOT NULL,
    channel_id    VARCHAR(64)  NOT NULL REFERENCES channel(id),
    default_params TEXT,                          -- JSON：size/temperature 等默认参数
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 业务调用 Key 表（/v1/** 静态鉴权）
CREATE TABLE gateway_api_key (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    key_hash    VARCHAR(128) NOT NULL UNIQUE,    -- SHA-256 哈希（明文仅签发时显示一次）
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 管理后台用户表
CREATE TABLE admin_user (
    id            VARCHAR(64) PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash TEXT         NOT NULL,         -- scrypt 哈希
    role          VARCHAR(32)  NOT NULL DEFAULT 'admin',
    status        VARCHAR(32)  NOT NULL DEFAULT 'enabled',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 调用日志表（异步落库；video_url 暂存 MiniMax 限时直链供下载端点使用）
CREATE TABLE call_log (
    id            VARCHAR(64) PRIMARY KEY,
    model         VARCHAR(128),
    channel_id    VARCHAR(64),
    status        VARCHAR(32),
    duration_ms   BIGINT,
    error         TEXT,
    video_url     TEXT,                          -- 视频 succeeded 时暂存 content.url（限时）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_call_log_created_at ON call_log(created_at DESC);
CREATE INDEX idx_call_log_model ON call_log(model);
```

- [ ] **步骤 2：5 个实体（Lombok @Data，OffsetDateTime 时间戳——PG timestamptz 必须 OffsetDateTime）**

`Channel.java` 示例（其余 4 个同模式）：

```java
package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 上游渠道表 */
@Data
@TableName("channel")
public class Channel {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    /** openai_compatible | gemini | minimax */
    private String type;
    private String baseUrl;
    /** AES 加密密文 */
    private String apiKey;
    private Boolean enabled;
    /** 同模型多渠道时升序取第一个 */
    private Integer priority;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Boolean deleted;
}
```

其余实体字段（均含 `@TableId(ASSIGN_UUID)` + `@TableLogic deleted` + OffsetDateTime createdAt/updatedAt）：
- `ModelRoute`：modelName、channelId、defaultParams（TEXT JSON）
- `GatewayApiKey`：name、keyHash、enabled
- `AdminUser`：username、passwordHash、role、status
- `CallLog`：model、channelId、status、durationMs（Long）、error、videoUrl、createdAt（**无 updatedAt，无 deleted**——日志不做逻辑删除；如有 deleted 需加）

- [ ] **步骤 3：5 个 Mapper**

```java
package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.Channel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChannelMapper extends BaseMapper<Channel> {}
```

其余 4 个同模式（ModelRouteMapper/GatewayApiKeyMapper/AdminUserMapper/CallLogMapper）。

- [ ] **步骤 4：编译验证 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "feat: 网关建表 SQL + 5 实体/Mapper（channel/model_route/api_key/admin_user/call_log）"
```

---

## 任务 3：KeyService（AES-256-GCM + SHA-256）+ 统一异常 + ApiResponse

**文件：**
- 创建：`service/KeyService.java`
- 创建：`exception/BusinessException.java`
- 创建：`exception/GlobalExceptionHandler.java`
- 创建：`dto/ApiResponse.java`

- [ ] **步骤 1：BusinessException + ApiResponse**

```java
package com.llmgateway.exception;

import lombok.Getter;

/** 业务异常：code + message（GlobalExceptionHandler 统一转 {error:{message}}） */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

```java
package com.llmgateway.dto;

/** 管理 API 统一响应包装 */
public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "success", data); }
    public static <T> ApiResponse<T> error(int code, String message) { return new ApiResponse<>(code, message, null); }
}
```

- [ ] **步骤 2：KeyService（AES-256-GCM + SHA-256）**

```java
package com.llmgateway.service;

import com.llmgateway.config.GatewayConfig;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 密钥服务：渠道 Key AES-256-GCM 加解密 + 业务 Key SHA-256 哈希比对 */
@Component
public class KeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final byte[] aesKey;

    public KeyService(GatewayConfig config) {
        this.aesKey = config.getAes().getSecret().getBytes(StandardCharsets.UTF_8);
        if (this.aesKey.length != 32) {
            throw new IllegalStateException("LLM_GATEWAY_AES_SECRET 必须恰好 32 字节（AES-256），当前 " + this.aesKey.length + " 字节");
        }
    }

    /** AES-256-GCM 加密：IV 前置 + 密文 + tag，Base64 编码 */
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES 加密失败: " + e.getMessage(), e);
        }
    }

    /** AES-256-GCM 解密（encrypt 的逆过程） */
    public String decrypt(String cipherB64) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherB64);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_BYTES));
            return new String(cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败（渠道 Key 可能被不同密钥加密）: " + e.getMessage(), e);
        }
    }

    /** SHA-256 哈希（业务调用 Key 存储用） */
    public String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **步骤 3：GlobalExceptionHandler（404 细化 + 统一 {error:{message}}）**

```java
package com.llmgateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** 统一异常处理：输出 OAI 风格 {error:{message}} */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Map<String, Object>> err(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("message", message)));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        // 40001→400 / 40301→403 / 40401→404 / 其余→500
        int status = switch (e.getCode()) {
            case 40001 -> 400;
            case 40301 -> 403;
            case 40401 -> 404;
            default -> 500;
        };
        String msg = e.getMessage() == null ? "business error" : e.getMessage();
        return err(status, msg);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        return err(404, "not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("网关未处理异常", e);
        return err(500, e.getMessage() == null ? "internal error" : e.getMessage());
    }
}
```

- [ ] **步骤 4：编译 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "feat: KeyService（AES-256-GCM/SHA-256）+ 统一异常处理 + ApiResponse"
```

---

## 任务 4：安全双通道（/v1 静态 Key + /admin JWT）

**文件：**
- 创建：`security/JwtTokenProvider.java`
- 创建：`security/StaticApiKeyFilter.java`
- 创建：`security/AdminJwtFilter.java`
- 创建：`config/SecurityConfig.java`
- 创建：`config/AdminInitRunner.java`

- [ ] **步骤 1：JwtTokenProvider（jjwt 0.12.6，access+refresh）**

```java
package com.llmgateway.security;

import com.llmgateway.config.GatewayConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** JWT 签发与校验（管理后台用；role 进 claims） */
@Component
public class JwtTokenProvider {

    private final GatewayConfig config;
    private final SecretKey accessKey;
    private final SecretKey refreshKey;

    public JwtTokenProvider(GatewayConfig config) {
        this.config = config;
        if (config.getJwt().getAccessSecret() == null || config.getJwt().getRefreshSecret() == null
                || config.getJwt().getAccessSecret().isBlank() || config.getJwt().getRefreshSecret().isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置：请在 .env 设置 LLM_GATEWAY_JWT_ACCESS_SECRET / LLM_GATEWAY_JWT_REFRESH_SECRET");
        }
        this.accessKey = Keys.hmacShaKeyFor(config.getJwt().getAccessSecret().getBytes(StandardCharsets.UTF_8));
        this.refreshKey = Keys.hmacShaKeyFor(config.getJwt().getRefreshSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 access token（claims：username/role/type=access） */
    public String createAccessToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("type", "access")
                .issuer(config.getJwt().getIssuer())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + config.getJwt().getAccessTokenTtl() * 1000L))
                .signWith(accessKey)
                .compact();
    }

    /** 签发 refresh token */
    public String createRefreshToken(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("type", "refresh")
                .issuer(config.getJwt().getIssuer())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + config.getJwt().getRefreshTokenTtl() * 1000L))
                .signWith(refreshKey)
                .compact();
    }

    /** 校验 access token，返回 claims；失败抛异常 */
    public Claims parseAccessToken(String token) {
        return Jwts.parser().verifyWith(accessKey).build().parseSignedClaims(token).getPayload();
    }

    /** 校验 refresh token，返回 claims；失败抛异常 */
    public Claims parseRefreshToken(String token) {
        return Jwts.parser().verifyWith(refreshKey).build().parseSignedClaims(token).getPayload();
    }
}
```

- [ ] **步骤 2：StaticApiKeyFilter（/v1/** 静态 Key SHA-256 比对）**

```java
package com.llmgateway.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.entity.GatewayApiKey;
import com.llmgateway.mapper.GatewayApiKeyMapper;
import com.llmgateway.service.KeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** /v1/** 静态 API Key 校验：Bearer → SHA-256 → gateway_api_key 表比对 */
@Component
public class StaticApiKeyFilter extends OncePerRequestFilter {

    private final GatewayApiKeyMapper apiKeyMapper;
    private final KeyService keyService;

    public StaticApiKeyFilter(GatewayApiKeyMapper apiKeyMapper, KeyService keyService) {
        this.apiKeyMapper = apiKeyMapper;
        this.keyService = keyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, "missing api key");
            return;
        }
        String key = auth.substring(7);
        GatewayApiKey record = apiKeyMapper.selectOne(new LambdaQueryWrapper<GatewayApiKey>()
                .eq(GatewayApiKey::getKeyHash, keyService.sha256(key))
                .eq(GatewayApiKey::getEnabled, true));
        if (record == null) {
            writeUnauthorized(response, "invalid api key");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("gateway", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\"}}");
    }
}
```

- [ ] **步骤 3：AdminJwtFilter（仅 /admin/** 前缀，设置 ROLE_ADMIN）**

```java
package com.llmgateway.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** /admin/** JWT 过滤器（/admin/login 由 SecurityConfig 放行，不经过本过滤器） */
@Component
public class AdminJwtFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;

    public AdminJwtFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/admin/") || uri.equals("/admin/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response);
            return;
        }
        try {
            Claims claims = tokenProvider.parseAccessToken(auth.substring(7));
            String role = claims.get("role", String.class);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(claims.getSubject(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "ADMIN" : role.toUpperCase())))));
            chain.doFilter(request, response);
        } catch (Exception e) {
            writeUnauthorized(response);
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"unauthorized\"}}");
    }
}
```

- [ ] **步骤 4：SecurityConfig（双通道 + ASYNC/ERROR permitAll）**

```java
package com.llmgateway.config;

import com.llmgateway.security.AdminJwtFilter;
import com.llmgateway.security.StaticApiKeyFilter;
import jakarta.servlet.DispatcherType;
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

/** 安全配置：/v1/** 静态 Key（StaticApiKeyFilter 自校验）+ /admin/** JWT */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AdminJwtFilter adminJwtFilter;
    private final StaticApiKeyFilter staticApiKeyFilter;

    public SecurityConfig(AdminJwtFilter adminJwtFilter, StaticApiKeyFilter staticApiKeyFilter) {
        this.adminJwtFilter = adminJwtFilter;
        this.staticApiKeyFilter = staticApiKeyFilter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()  // SseEmitter/异步必需
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/admin/login").permitAll()
                .requestMatchers("/v1/**").permitAll()           // 静态 Key 由 StaticApiKeyFilter 自校验
                .requestMatchers("/admin/**").hasRole("ADMIN")  // JWT 过滤器设置 ROLE_ADMIN
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":{\"message\":\"unauthorized\"}}");
                })
            )
            .addFilterBefore(staticApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **步骤 5：AdminInitRunner（首启自举 admin，scrypt 16384）**

```java
package com.llmgateway.config;

import com.lambdaworks.crypto.SCryptUtil;
import com.llmgateway.entity.AdminUser;
import com.llmgateway.mapper.AdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 首启自举：admin_user 表空且配置 LLM_GATEWAY_ADMIN_INIT_PASSWORD 时创建 admin（scrypt N=16384） */
@Component
public class AdminInitRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitRunner.class);

    private final AdminUserMapper adminUserMapper;

    public AdminInitRunner(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) return;

        String initPassword = System.getProperty("LLM_GATEWAY_ADMIN_INIT_PASSWORD");
        if (initPassword == null || initPassword.isBlank()) {
            log.warn("【网关初始化】admin_user 表为空且未设置 LLM_GATEWAY_ADMIN_INIT_PASSWORD，管理后台无法登录");
            return;
        }

        AdminUser admin = new AdminUser();
        admin.setUsername("admin");
        admin.setPasswordHash(SCryptUtil.scrypt(initPassword, 16384, 8, 1));
        admin.setRole("admin");
        admin.setStatus("enabled");
        admin.setCreatedAt(OffsetDateTime.now());
        admin.setUpdatedAt(OffsetDateTime.now());
        adminUserMapper.insert(admin);
        log.info("【网关初始化】已创建默认管理员账号 admin");
    }
}
```

- [ ] **步骤 6：编译 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "feat: 网关双通道安全（/v1 静态 Key SHA-256 + /admin JWT）+ 自举管理员"
```

---

## 任务 5：GeminiFormatConverter + UpstreamClient + CallLogService

**文件：**
- 创建：`service/GeminiFormatConverter.java`
- 创建：`service/UpstreamClient.java`
- 创建：`service/CallLogService.java`

- [ ] **步骤 1：GeminiFormatConverter（OpenAI 生图 ↔ Gemini generateContent）**

要点（参考旧实现 `6ed5ba7:.../GeminiFormatConverter.java`，重新编写）：
- `toGeminiRequest(JsonNode openAiBody)`：OpenAI `{model,prompt,size,quality,aspect_ratio,n}` → Gemini `{contents:[{parts:[{text}]}], generationConfig:{aspectRatio|imageSize,imageConfig:{aspectRatio}}}`。`aspect_ratio` 优先映射 `generationConfig.aspectRatio`；无 aspect_ratio 时 size 才映射 imageSize；两者皆无 → 不附带 generationConfig（防上游 400）
- `toOpenAiResponse(String geminiRaw)`：Gemini `{candidates:[{content:{parts:[{inlineData:{data}}]}}]}` → OpenAI `{created,data:[{b64_json}]}`；防御 candidates 缺失（防 NPE）

```java
package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** OpenAI 生图格式 ↔ Gemini generateContent 格式互转 */
@Component
public class GeminiFormatConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OpenAI 请求 → Gemini 请求（aspect_ratio 优先，size 回退；两者皆无不附带 generationConfig） */
    public String toGeminiRequest(String openAiBodyJson) throws Exception {
        JsonNode src = objectMapper.readTree(openAiBodyJson);
        ObjectNode out = objectMapper.createObjectNode();

        // contents: [{parts:[{text: prompt}]}]
        ArrayNode contents = out.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", src.path("prompt").asText(""));

        // generationConfig
        String aspectRatio = src.path("aspect_ratio").asText("");
        String size = src.path("size").asText("");
        if (!aspectRatio.isBlank()) {
            ObjectNode gc = out.putObject("generationConfig");
            gc.put("aspectRatio", aspectRatio);
        } else if (!size.isBlank()) {
            ObjectNode gc = out.putObject("generationConfig");
            gc.put("imageSize", size);
        }
        return objectMapper.writeValueAsString(out);
    }

    /** Gemini 响应 → OpenAI 响应（b64_json）；candidates 缺失返回空 data */
    public String toOpenAiResponse(String geminiRawJson) throws Exception {
        JsonNode src = objectMapper.readTree(geminiRawJson);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("created", System.currentTimeMillis() / 1000);
        ArrayNode data = out.putArray("data");

        JsonNode candidates = src.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode cand : candidates) {
                JsonNode parts = cand.path("content").path("parts");
                if (!parts.isArray()) continue;
                for (JsonNode part : parts) {
                    String b64 = part.path("inlineData").path("data").asText("");
                    if (!b64.isBlank()) {
                        data.addObject().put("b64_json", b64);
                    }
                }
            }
        }
        return objectMapper.writeValueAsString(out);
    }
}
```

- [ ] **步骤 2：UpstreamClient（透传/重试/超时）**

```java
package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 上游调用客户端：透传请求体、替换 Bearer、轻量重试（429/5xx 重试 2 次） */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final HttpClient httpClient;
    private final GatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UpstreamClient(GatewayConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getUpstream().getConnectTimeoutMs()))
                .build();
    }

    /** POST JSON 到 openai_compatible 渠道（base_url + path，Bearer 渠道 Key） */
    public HttpResponse<String> postJson(String baseUrl, String path, String apiKey, String bodyJson) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        return sendWithRetry(request);
    }

    /** POST Gemini 原生格式（Key 走 query 参数） */
    public HttpResponse<String> postGemini(String baseUrl, String apiKey, String bodyJson) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        return sendWithRetry(request);
    }

    /** GET 上游（轮询视频状态等） */
    public HttpResponse<String> get(String url, String apiKey) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .GET().build();
        return sendWithRetry(request);
    }

    /** 带重试的 send：429/5xx 重试 retryCount 次（指数退避） */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        int retries = config.getUpstream().getRetryCount();
        HttpResponse<String> resp = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                if (attempt == retries) throw new RuntimeException("上游请求失败: " + e.getMessage(), e);
                sleep(500L * (attempt + 1));
                continue;
            }
            int code = resp.statusCode();
            if (code != 429 && code < 500) return resp;
            if (attempt == retries) return resp;
            log.warn("上游返回 {}，第 {}/{} 次重试", code, attempt + 1, retries);
            sleep(500L * (attempt + 1));
        }
        return resp;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 提取上游错误体中的 message（OAI 风格 {error:{message}}，兼容多层嵌套） */
    public String extractError(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode msg = node.path("error").path("message");
            if (!msg.isMissingNode()) return msg.asText("upstream error");
            JsonNode direct = node.path("message");
            if (!direct.isMissingNode()) return direct.asText("upstream error");
        } catch (Exception ignored) { }
        String t = body == null ? "" : body.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
```

- [ ] **步骤 3：CallLogService（异步落库 + 视频直链暂存）**

```java
package com.llmgateway.service;

import com.llmgateway.entity.CallLog;
import com.llmgateway.mapper.CallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/** 调用日志异步落库（不阻塞响应）；videoUrl 供视频下载端点暂存 MiniMax 限时直链 */
@Service
public class CallLogService {

    private static final Logger log = LoggerFactory.getLogger(CallLogService.class);

    private final CallLogMapper callLogMapper;

    public CallLogService(CallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    @Async
    public void log(String model, String channelId, String status, long durationMs, String error, String videoUrl) {
        try {
            CallLog record = new CallLog();
            record.setModel(model);
            record.setChannelId(channelId);
            record.setStatus(status);
            record.setDurationMs(durationMs);
            record.setError(error);
            record.setVideoUrl(videoUrl);
            record.setCreatedAt(OffsetDateTime.now());
            callLogMapper.insert(record);
        } catch (Exception e) {
            log.warn("调用日志落库失败: {}", e.getMessage());
        }
    }
}
```

- [ ] **步骤 4：编译 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "feat: 网关上游客户端（透传/重试/超时）+ Gemini 格式转换 + 异步调用日志"
```

---

## 任务 6：GatewayRoutingService + OpenAiCompatController（chat/images）

**文件：**
- 创建：`service/GatewayRoutingService.java`
- 创建：`controller/OpenAiCompatController.java`

- [ ] **步骤 1：GatewayRoutingService（路由核心）**

```java
package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.List;

/**
 * 路由核心：解析 model → 查 model_route → 取 enabled channel（按 priority 升序）
 * → AES 解密渠道 Key → 按渠道类型转发（透传 / Gemini 转换）。
 * 返回上游状态码 + 响应体（透传，不做二次包装）。
 */
@Service
public class GatewayRoutingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutingService.class);

    /** 路由结果：上游 HTTP 状态码 + 响应体 */
    public record RouteResult(int status, String body) {}

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final GeminiFormatConverter geminiConverter;
    private final CallLogService callLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewayRoutingService(ModelRouteMapper routeMapper, ChannelMapper channelMapper,
                                 KeyService keyService, UpstreamClient upstreamClient,
                                 GeminiFormatConverter geminiConverter, CallLogService callLogService) {
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.upstreamClient = upstreamClient;
        this.geminiConverter = geminiConverter;
        this.callLogService = callLogService;
    }

    /** 处理 OpenAI 兼容 chat/images 请求（path 为 /chat/completions 或 /images/generations） */
    public RouteResult route(String path, String requestBody) {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        int status = 500;
        String error = null;
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            String modelName = body.path("model").asText("");
            model = modelName;
            if (modelName.isBlank()) throw new BusinessException(40001, "model 不能为空");

            // 1. 查该模型的所有路由（一个模型可指向多个渠道，按 priority 轮换）
            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getModelName, model));
            if (routes == null || routes.isEmpty()) {
                throw new BusinessException(40401, "no route for model: " + model);
            }

            // 2. 候选渠道（路由指向的 enabled 渠道，按 priority 升序）
            List<Channel> candidates = routes.stream()
                    .map(r -> channelMapper.selectById(r.getChannelId()))
                    .filter(c -> c != null && Boolean.TRUE.equals(c.getEnabled()))
                    .sorted(Comparator.comparingInt(c -> c.getPriority() == null ? 0 : c.getPriority()))
                    .toList();
            if (candidates.isEmpty()) {
                throw new BusinessException(50301, "no available channel for model: " + model);
            }

            // 3. 逐个渠道尝试（失败切下一个）
            for (Channel channel : candidates) {
                try {
                    HttpResponse<String> resp = forward(channel, path, requestBody);
                    status = resp.statusCode();
                    channelId = channel.getId();
                    String bodyStr = resp.body();
                    if (status >= 400) {
                        error = upstreamClient.extractError(bodyStr);
                        log.warn("渠道 {} 返回 {}: {}", channel.getName(), status, error);
                        // 429/5xx 尝试下一个渠道；4xx 业务错误直接透传
                        if (status < 500) {
                            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, error, null);
                            return new RouteResult(status, bodyStr);
                        }
                        continue;
                    }
                    callLogService.log(model, channelId, "success", System.currentTimeMillis() - start, null, null);
                    return new RouteResult(status, bodyStr);
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception e) {
                    error = e.getMessage();
                    log.warn("渠道 {} 调用异常: {}", channel.getName(), error);
                }
            }
            throw new BusinessException(50301, "all channels failed for model: " + model);
        } catch (BusinessException be) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, be.getMessage(), null);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, e.getMessage(), null);
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** 按渠道类型转发：openai_compatible 透传 / gemini 转换 */
    private HttpResponse<String> forward(Channel channel, String path, String requestBody) throws Exception {
        String apiKey = keyService.decrypt(channel.getApiKey());
        if ("gemini".equals(channel.getType())) {
            String geminiBody = geminiConverter.toGeminiRequest(requestBody);
            HttpResponse<String> resp = upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody);
            if (resp.statusCode() == 200) {
                String openAiBody = geminiConverter.toOpenAiResponse(resp.body());
                return new java.net.http.HttpResponse<String>() {
                    public int statusCode() { return 200; }
                    public java.net.http.HttpRequest request() { return null; }
                    public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
                    public java.net.http.HttpResponse.BodySubscriber<String> bodySubscriber() { return null; }
                    public java.lang.Object body() { return openAiBody; }
                    public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
                    public java.net.URI uri() { return null; }
                    public java.net.http.HttpClient.Version version() { return null; }
                };
            }
            return resp;
        }
        // openai_compatible：原路径透传，Bearer 换渠道 Key
        return upstreamClient.postJson(channel.getBaseUrl(), path, apiKey, requestBody);
    }
}
```

> 注意：`forward()` 的 Gemini 200 分支返回匿名 HttpResponse 实现较繁琐，若编译困难可改为返回 `record ForwardResult(int status, String body)` 并在 route() 内统一处理（推荐直接定义内部 record 简化）。

- [ ] **步骤 2：OpenAiCompatController（/v1/chat/completions + /v1/images/generations + /v1/models）**

```java
package com.llmgateway.controller;

import com.llmgateway.service.GatewayRoutingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** OpenAI 兼容对外入口（静态 Key 鉴权由 StaticApiKeyFilter 完成） */
@RestController
@RequestMapping("/v1")
public class OpenAiCompatController {

    private final GatewayRoutingService routingService;

    public OpenAiCompatController(GatewayRoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chatCompletions(@RequestBody String body) {
        GatewayRoutingService.RouteResult result = routingService.route("/chat/completions", body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping(value = "/images/generations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> imageGenerations(@RequestBody String body) {
        GatewayRoutingService.RouteResult result = routingService.route("/images/generations", body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public String models() {
        return "{\"object\":\"list\",\"data\":[]}";
    }
}
```

- [ ] **步骤 3：编译 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "feat: 网关路由核心 + OpenAI 兼容入口（chat/images）"
```

---

## 任务 7：管理 API（登录 + 渠道/路由/Key CRUD + 日志查询）

**文件：**
- 创建：`controller/admin/AdminAuthController.java`
- 创建：`controller/admin/AdminChannelController.java`
- 创建：`controller/admin/AdminRouteController.java`
- 创建：`controller/admin/AdminApiKeyController.java`
- 创建：`controller/admin/AdminLogController.java`
- 创建：`dto/admin/AdminLoginRequest.java`、`AdminLoginResponse.java`、`ChannelRequest.java`、`RouteRequest.java`、`ApiKeyRequest.java`

- [ ] **步骤 1：AdminAuthController（登录 + 刷新）**

```java
package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambdaworks.crypto.SCryptUtil;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.AdminLoginRequest;
import com.llmgateway.dto.admin.AdminLoginResponse;
import com.llmgateway.entity.AdminUser;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.AdminUserMapper;
import com.llmgateway.security.JwtTokenProvider;
import org.springframework.web.bind.annotation.*;

/** 管理后台认证：登录发 JWT（access+refresh） */
@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private final AdminUserMapper adminUserMapper;
    private final JwtTokenProvider tokenProvider;

    public AdminAuthController(AdminUserMapper adminUserMapper, JwtTokenProvider tokenProvider) {
        this.adminUserMapper = adminUserMapper;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/login")
    public ApiResponse<AdminLoginResponse> login(@RequestBody AdminLoginRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            throw new BusinessException(40001, "用户名和密码不能为空");
        }
        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, request.getUsername()));
        if (user == null || !SCryptUtil.check(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(40101, "用户名或密码错误");
        }
        if (!"enabled".equals(user.getStatus())) {
            throw new BusinessException(40301, "账号已禁用");
        }
        String access = tokenProvider.createAccessToken(user.getUsername(), user.getRole());
        String refresh = tokenProvider.createRefreshToken(user.getUsername());
        return ApiResponse.ok(new AdminLoginResponse(access, refresh));
    }
}
```

DTO（record 或 Lombok @Data）：
- `AdminLoginRequest`：username、password
- `AdminLoginResponse`：accessToken、refreshToken

- [ ] **步骤 2：AdminChannelController（渠道 CRUD，Key 写入 AES 加密，读取不返回明文）**

```java
package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.ChannelRequest;
import com.llmgateway.entity.Channel;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.service.KeyService;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/** 渠道管理：Key 写入 AES 加密，读取永远不返回明文 */
@RestController
@RequestMapping("/admin/channels")
public class AdminChannelController {

    private final ChannelMapper channelMapper;
    private final KeyService keyService;

    public AdminChannelController(ChannelMapper channelMapper, KeyService keyService) {
        this.channelMapper = channelMapper;
        this.keyService = keyService;
    }

    @PostMapping
    public ApiResponse<Channel> create(@RequestBody ChannelRequest request) {
        if (request.getName() == null || request.getName().isBlank()
                || request.getBaseUrl() == null || request.getBaseUrl().isBlank()
                || request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new BusinessException(40001, "name/baseUrl/apiKey 不能为空");
        }
        Channel channel = new Channel();
        channel.setName(request.getName());
        channel.setType(request.getType() == null ? "openai_compatible" : request.getType());
        channel.setBaseUrl(request.getBaseUrl());
        channel.setApiKey(keyService.encrypt(request.getApiKey()));   // AES 加密存储
        channel.setEnabled(request.getEnabled() == null ? true : request.getEnabled());
        channel.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        channel.setCreatedAt(OffsetDateTime.now());
        channel.setUpdatedAt(OffsetDateTime.now());
        channelMapper.insert(channel);
        channel.setApiKey("***");   // 返回脱敏
        return ApiResponse.ok(channel);
    }

    @GetMapping
    public ApiResponse<List<Channel>> list() {
        List<Channel> channels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .orderByAsc(Channel::getPriority));
        channels.forEach(c -> c.setApiKey("***"));
        return ApiResponse.ok(channels);
    }

    @PutMapping("/{id}")
    public ApiResponse<Channel> update(@PathVariable String id, @RequestBody ChannelRequest request) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) throw new BusinessException(40401, "渠道不存在");
        if (request.getName() != null) channel.setName(request.getName());
        if (request.getType() != null) channel.setType(request.getType());
        if (request.getBaseUrl() != null) channel.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            channel.setApiKey(keyService.encrypt(request.getApiKey()));  // 传新 Key 才重加密
        }
        if (request.getEnabled() != null) channel.setEnabled(request.getEnabled());
        if (request.getPriority() != null) channel.setPriority(request.getPriority());
        channel.setUpdatedAt(OffsetDateTime.now());
        channelMapper.updateById(channel);
        channel.setApiKey("***");
        return ApiResponse.ok(channel);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        if (channelMapper.deleteById(id) == 0) throw new BusinessException(40401, "渠道不存在");
        return ApiResponse.ok(null);
    }
}
```

- [ ] **步骤 3：AdminRouteController + AdminApiKeyController**

`AdminRouteController`（CRUD `/admin/routes`）：
- POST：modelName + channelId + 可选 defaultParams；校验 modelName/channelId 非空、channel 存在
- GET：列表（join channel name 可选，第一版返回原始行即可）
- PUT/DELETE：按 id

`AdminApiKeyController`（CRUD `/admin/api-keys`）：
- POST：name → 生成 `lg-<32hex>` 明文 → 存 `sha256(明文)` → 响应 `{id, name, plainKey}`（**明文仅此一次**）
- GET：列表（只返回 id/name/enabled，永不返回 hash/明文）
- PUT：enabled 开关
- DELETE：删除

```java
// AdminApiKeyController 核心（Key 签发）
@PostMapping
public ApiResponse<Map<String, String>> create(@RequestBody ApiKeyRequest request) {
    if (request.getName() == null || request.getName().isBlank()) {
        throw new BusinessException(40001, "name 不能为空");
    }
    String plainKey = "lg-" + java.util.UUID.randomUUID().toString().replace("-", "");
    GatewayApiKey record = new GatewayApiKey();
    record.setName(request.getName());
    record.setKeyHash(keyService.sha256(plainKey));
    record.setEnabled(true);
    record.setCreatedAt(OffsetDateTime.now());
    record.setUpdatedAt(OffsetDateTime.now());
    apiKeyMapper.insert(record);
    return ApiResponse.ok(Map.of("id", record.getId(), "name", record.getName(), "plainKey", plainKey));
}
```

- [ ] **步骤 4：AdminLogController（分页倒序，size 上限 50）**

```java
package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.entity.CallLog;
import com.llmgateway.mapper.CallLogMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 调用日志查询（分页倒序） */
@RestController
@RequestMapping("/admin/call-logs")
public class AdminLogController {

    private final CallLogMapper callLogMapper;

    public AdminLogController(CallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String model) {
        size = Math.max(1, Math.min(50, size));   // 分页下界校验
        LambdaQueryWrapper<CallLog> wrapper = new LambdaQueryWrapper<CallLog>()
                .orderByDesc(CallLog::getCreatedAt);
        if (model != null && !model.isBlank()) {
            wrapper.eq(CallLog::getModel, model);
        }
        Page<CallLog> result = callLogMapper.selectPage(Page.of(page, size), wrapper);
        return ApiResponse.ok(Map.of("records", result.getRecords(), "total", result.getTotal(),
                "page", result.getCurrent(), "size", result.getSize()));
    }
}
```

- [ ] **步骤 5：编译 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "feat: 网关管理 API（登录/渠道/路由/Key/日志 CRUD，AES 加密，Key 明文仅创建返回）"
```

---

## 任务 8：视频网关（VideoGatewayService + /v1/videos 端点）★核心新增

**文件：**
- 创建：`service/VideoGatewayService.java`
- 修改：`controller/OpenAiCompatController.java`（加 /v1/videos 系列端点）

- [ ] **步骤 1：视频任务创建（统一请求 → 按 model 路由 → Laozhang/MiniMax 协议转换）**

```java
package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 视频网关：统一 /v1/videos 创建/轮询/下载，按 model 路由到 Laozhang（multipart）
 * 或 MiniMax（JSON content 数组）渠道。下载由网关流式代理（业务只认 /v1/videos/{taskId}/content）。
 */
@Service
public class VideoGatewayService {

    private static final Logger log = LoggerFactory.getLogger(VideoGatewayService.class);

    /** 视频路由结果：上游 HTTP 状态码 + 响应体 */
    public record VideoResult(int status, String body) {}

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final CallLogService callLogService;
    private final CallLogMapper callLogMapper;
    private final GatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public VideoGatewayService(ModelRouteMapper routeMapper, ChannelMapper channelMapper,
                               KeyService keyService, CallLogService callLogService,
                               CallLogMapper callLogMapper, GatewayConfig config) {
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.callLogService = callLogService;
        this.callLogMapper = callLogMapper;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getUpstream().getConnectTimeoutMs()))
                .build();
    }

    /** 按 model 取第一个 enabled 渠道（视频模型：MiniMax-H3→minimax 渠道；veo-*→laozhang 渠道） */
    private Channel resolveChannel(String model) {
        List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .eq(ModelRoute::getModelName, model));
        if (routes == null || routes.isEmpty()) throw new BusinessException(40401, "no route for model: " + model);
        return routes.stream()
                .map(r -> channelMapper.selectById(r.getChannelId()))
                .filter(c -> c != null && Boolean.TRUE.equals(c.getEnabled()))
                .sorted(Comparator.comparingInt(c -> c.getPriority() == null ? 0 : c.getPriority()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(50301, "no available channel for model: " + model));
    }

    /**
     * 创建视频任务。请求体（OpenAI 风格统一格式）：
     * {model, prompt, size?, resolution?, aspectRatio?, duration?, negativePrompt?, seed?, imageUrl?}
     * imageUrl：图生视频首帧（data URI 或 http URL，业务侧已把本地图转 data URI）
     */
    public VideoResult create(String requestBody) {
        long start = System.currentTimeMillis();
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            String model = body.path("model").asText("");
            if (model.isBlank()) throw new BusinessException(40001, "model 不能为空");
            Channel channel = resolveChannel(model);
            String apiKey = keyService.decrypt(channel.getApiKey());

            HttpResponse<String> resp;
            if ("minimax".equals(channel.getType())) {
                resp = createMinimax(channel, apiKey, body);
            } else {
                resp = createLaozhang(channel, apiKey, body);
            }

            int status = resp.statusCode();
            String bodyStr = resp.body();
            if (status == 200) {
                // 从上游响应提取 task_id（minimax: task_id；laozhang: id/taskId）
                JsonNode respJson = objectMapper.readTree(bodyStr);
                String taskId = respJson.path("task_id").asText(
                        respJson.path("id").asText(respJson.path("taskId").asText("")));
                callLogService.log(model, channel.getId(), "created", System.currentTimeMillis() - start, null, null);
                if (taskId.isBlank()) {
                    log.warn("视频创建响应无 task_id: {}", bodyStr.length() > 200 ? bodyStr.substring(0, 200) : bodyStr);
                }
                return new VideoResult(200, bodyStr);
            }
            String error = bodyStr.length() > 300 ? bodyStr.substring(0, 300) : bodyStr;
            callLogService.log(model, channel.getId(), "error", System.currentTimeMillis() - start, error, null);
            return new VideoResult(status, bodyStr);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** MiniMax 创建：POST {base}/v2/video_generation，content 数组 JSON */
    private HttpResponse<String> createMinimax(Channel channel, String apiKey, JsonNode body) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", body.path("model").asText());

        ArrayNode content = payload.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", body.path("prompt").asText(""));

        String imageUrl = body.path("imageUrl").asText("");
        if (!imageUrl.isBlank()) {
            ObjectNode imgPart = content.addObject();
            imgPart.put("type", "image_url");
            imgPart.put("image_url", objectMapper.createObjectNode().put("url", imageUrl));
            imgPart.put("role", "first_frame");
        }

        // 分辨率恒用默认档（省钱；调用方传 720p/1080p/4K/2K 一律忽略）
        payload.put("resolution", config.getVideoDefaultResolution() == null ? "768P" : config.getVideoDefaultResolution());
        int duration = body.path("duration").asInt(8);
        payload.put("duration", Math.max(4, Math.min(15, duration)));   // clamp 4~15
        String ratio = body.path("aspectRatio").asText("");
        if (!ratio.isBlank()) payload.put("ratio", ratio);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(channel.getBaseUrl()) + "/v2/video_generation"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Laozhang 创建：POST {base}/videos，multipart 表单（与业务现状一致） */
    private HttpResponse<String> createLaozhang(Channel channel, String apiKey, JsonNode body) throws Exception {
        String boundary = "----llmgw" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        // multipart 字段
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n").append(body.path("model").asText()).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n").append(body.path("prompt").asText("")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"duration\"\r\n\r\n").append(body.path("duration").asInt(8)).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"size\"\r\n\r\n").append(body.path("size").asText("1280x720")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"resolution\"\r\n\r\n").append(body.path("resolution").asText("720p")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"aspectRatio\"\r\n\r\n").append(body.path("aspectRatio").asText("16:9")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n")
          .append("{\"durationSeconds\":").append(body.path("duration").asInt(8))
          .append(",\"resolution\":\"").append(body.path("resolution").asText("720p"))
          .append("\",\"aspectRatio\":\"").append(body.path("aspectRatio").asText("16:9")).append("\"}\r\n");
        if (body.hasNonNull("negativePrompt")) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"negativePrompt\"\r\n\r\n").append(body.path("negativePrompt").asText()).append("\r\n");
        }
        if (body.hasNonNull("seed")) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"seed\"\r\n\r\n").append(body.path("seed").asLong()).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(channel.getBaseUrl()) + "/videos"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
```

> 注意：Laozhang multipart 的 `input_reference` 文件部分（图生视频）需要二进制体，上述简化版只带文本字段。完整实现需用 MultipartBodyPublisher 或 byte[] 拼接（参考 Backend `MultipartBuilder`）。若第一版图生视频 Laozhang 通道暂不需要，可在创建时忽略 imageUrl（MiniMax 通道已支持图生视频）。

- [ ] **步骤 2：轮询（统一响应 {taskId,status,progress?,error?}）+ 下载（流式代理）**

在 VideoGatewayService 增加：

```java
    /** 轮询视频状态。taskId 反查渠道：查 call_log 最新一条该 model 的记录不够精确，
     *  改为按 taskId 前缀存 channel 标识：简化方案——轮询时遍历该 model 的路由渠道逐个尝试，
     *  命中 200 即返回。 */
    public VideoResult poll(String taskId) {
        long start = System.currentTimeMillis();
        try {
            // 反查渠道：遍历所有 enabled 渠道，尝试查询（Laozhang GET /videos/{id}，MiniMax GET /v2/query/video_generation/{id}）
            // 精确反查需要路由表记录 taskId→channel，第一版用"遍历渠道尝试"简化
            List<Channel> allChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                    .eq(Channel::getEnabled, true)
                    .orderByAsc(Channel::getPriority));
            for (Channel channel : allChannels) {
                try {
                    String apiKey = keyService.decrypt(channel.getApiKey());
                    String url;
                    if ("minimax".equals(channel.getType())) {
                        url = stripTrailingSlash(channel.getBaseUrl()) + "/v2/query/video_generation/" + taskId;
                    } else {
                        url = stripTrailingSlash(channel.getBaseUrl()) + "/videos/" + taskId;
                    }
                    HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Bearer " + apiKey)
                            .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());

                    if (resp.statusCode() == 200) {
                        // MiniMax succeeded → 暂存 video_url 供下载端点使用
                        if ("minimax".equals(channel.getType())) {
                            JsonNode json = objectMapper.readTree(resp.body());
                            String status = json.path("status").asText("");
                            String contentUrl = json.path("content").path("url").asText("");
                            if ("succeeded".equals(status) && !contentUrl.isBlank()) {
                                callLogService.log(json.path("model").asText("video"), channel.getId(), "succeeded",
                                        System.currentTimeMillis() - start, null, contentUrl);
                            } else if ("failed".equals(status)) {
                                String err = json.path("error").path("message").asText("video generation failed");
                                callLogService.log(json.path("model").asText("video"), channel.getId(), "failed",
                                        System.currentTimeMillis() - start, err, null);
                            }
                        } else {
                            callLogService.log("video", channel.getId(), "polled",
                                    System.currentTimeMillis() - start, null, null);
                        }
                        return new VideoResult(200, resp.body());
                    }
                    // 404 = 该渠道无此任务，尝试下一个
                } catch (Exception e) {
                    log.warn("轮询渠道 {} 异常: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(40401, "video task not found: " + taskId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }
```

> **设计说明**：轮询反查渠道用"遍历 enabled 渠道尝试"方案——第一版简化，避免在 call_log 精确记 taskId→channel 映射。若联调发现轮询请求打到错误渠道产生噪声日志，后续可升级为 call_log 增加 task_id 列精确反查。

下载端点（在 OpenAiCompatController 中，用 StreamingResponseBody 流式返回）：

```java
    /** 视频下载：网关流式代理（Laozhang 转发原生端点；MiniMax 用 call_log 暂存的限时直链） */
    @GetMapping(value = "/videos/{taskId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            videoContent(@PathVariable String taskId) {
        return videoGatewayService.download(taskId);
    }
```

VideoGatewayService.download 实现要点：
- Laozhang 渠道：遍历渠道尝试 `GET {base}/videos/{taskId}/content`（Bearer 渠道 Key），命中 200 → StreamingResponseBody 转发 InputStream
- MiniMax 渠道：查 call_log 最新一条 videoUrl 非空记录（`orderByDesc(createdAt)` + `isNotNull(videoUrl)`），命中 → 转发 GET 直链（无鉴权，限时）
- 失败 → 404 `{error:{message:"video content not available"}}`

```java
    /** 视频下载：流式代理（业务只认本端点，永不接触上游 URL/Key） */
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            download(String taskId) {
        try {
            List<Channel> allChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                    .eq(Channel::getEnabled, true));
            for (Channel channel : allChannels) {
                try {
                    String apiKey = keyService.decrypt(channel.getApiKey());
                    if ("minimax".equals(channel.getType())) {
                        // 从 call_log 取最近一条限时直链
                        com.llmgateway.entity.CallLog latest = callLogMapper.selectOne(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.llmgateway.entity.CallLog>()
                                        .isNotNull(com.llmgateway.entity.CallLog::getVideoUrl)
                                        .orderByDesc(com.llmgateway.entity.CallLog::getCreatedAt)
                                        .last("LIMIT 1"));
                        if (latest == null || latest.getVideoUrl().isBlank()) continue;
                        java.net.URI uri = java.net.URI.create(latest.getVideoUrl());
                        HttpRequest req = HttpRequest.newBuilder().uri(uri)
                                .timeout(Duration.ofSeconds(180)).GET().build();
                        HttpResponse<java.io.InputStream> resp = httpClient.send(req,
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() == 200) {
                            return streamResponse(resp.body());
                        }
                    } else {
                        String url = stripTrailingSlash(channel.getBaseUrl()) + "/videos/" + taskId + "/content";
                        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                                .header("Authorization", "Bearer " + apiKey)
                                .timeout(Duration.ofSeconds(180)).GET().build();
                        HttpResponse<java.io.InputStream> resp = httpClient.send(req,
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() == 200) {
                            return streamResponse(resp.body());
                        }
                    }
                } catch (Exception e) {
                    log.warn("下载渠道 {} 异常: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(40401, "video content not available: " + taskId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    private org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            streamResponse(java.io.InputStream in) {
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body =
                out -> { try (in) { in.transferTo(out); } };
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment")
                .body(body);
    }
```

- [ ] **步骤 3：OpenAiCompatController 增加视频端点 + 依赖注入**

```java
// OpenAiCompatController 新增字段与端点
@PostMapping(value = "/videos", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<String> createVideo(@RequestBody String body) {
    VideoGatewayService.VideoResult result = videoGatewayService.create(body);
    return ResponseEntity.status(result.status()).body(result.body());
}

@GetMapping(value = "/videos/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<String> pollVideo(@PathVariable String taskId) {
    VideoGatewayService.VideoResult result = videoGatewayService.poll(taskId);
    return ResponseEntity.status(result.status()).body(result.body());
}

@GetMapping(value = "/videos/{taskId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
        videoContent(@PathVariable String taskId) {
    return videoGatewayService.download(taskId);
}
```

- [ ] **步骤 4：GatewayConfig 增加视频默认档配置**

```java
// GatewayConfig 新增字段（yml 对应 gateway.video.*）
private Video video = new Video();
public static class Video {
    private String defaultResolution = "768P";   // MiniMax 默认档（省钱）
    private String defaultDuration = "8";
    // getter/setter
}
```

application.yml 增加：

```yaml
gateway:
  video:
    default-resolution: ${LLM_GATEWAY_VIDEO_RESOLUTION:768P}
    default-duration: "8"
```

- [ ] **步骤 5：编译 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
cd "E:\Desktop\AI-storyboard" && git add AILLMGateway/ && git commit -m "feat: 网关视频链路（创建/轮询/下载，Laozhang multipart + MiniMax JSON 双协议，流式代理下载）"
```

---

## 任务 9：Backend 业务侧改造（chat/文生图切网关）

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java` — 新增 `ai.gateway.*`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ScriptGenerationService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageRefinePromptService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/agent/ConversationTitleService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/agent/PromptOptimizeService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoPlanService.java`
- 修改：`AIStoryboardBackend/src/main/resources/application.yml`

- [ ] **步骤 1：AiConfigProperties 新增网关配置段**

```java
// AiConfigProperties 新增字段（ai.gateway.* 前缀）
/** 网关基础地址（chat/文生图/视频统一入口） */
private String gatewayBaseUrl;
/** 网关调用密钥（在网关 /admin 签发） */
private String gatewayApiKey;
```

```java
// getter/setter
public String getGatewayBaseUrl() { return gatewayBaseUrl; }
public void setGatewayBaseUrl(String s) { this.gatewayBaseUrl = s; }
public String getGatewayApiKey() { return gatewayApiKey; }
public void setGatewayApiKey(String s) { this.gatewayApiKey = s; }
```

application.yml（`ai.laozhang` 段内或独立 `ai.gateway` 段，与 Backend 现有配置层次一致——设计文档 §8.1 写明新增独立前缀）：

```yaml
ai:
  gateway:
    base-url: ${LLM_GATEWAY_BASE_URL:http://localhost:8083}
    api-key: ${LLM_GATEWAY_API_KEY:}
```

- [ ] **步骤 2：ScriptGenerationService 切网关**

`ScriptGenerationService.java` 中 chat 调用处（约 line 70）：

```java
// 改前：.uri(URI.create(config.getBaseUrlVision()))
// 改后：
.uri(URI.create(config.getGatewayBaseUrl() + "/v1/chat/completions"))
// 改前：.header("Authorization", "Bearer " + config.getApiKey())
// 改后：
.header("Authorization", "Bearer " + config.getGatewayApiKey())
```

- [ ] **步骤 3：ImageGenerationService 切网关（文生图 + Gemini 分支下沉，edits 保留）**

```java
// 纯文生图分支：baseUrlOpenai → gatewayBaseUrl + "/v1/images/generations"；Authorization 换网关 Key
// Gemini 分支：也走网关（网关负责转原生格式），业务侧删除 geminiImageModelSet 判断与 baseUrlGemini 分支
// edits 分支（图改图 multipart）：保持直连 config.getBaseUrlOpenai() + endpointImageEdits，Authorization 用 config.getApiKey()
```

删除：`getGeminiImageModelSet()` 调用点、`baseUrlGemini` 分支逻辑（保留字段定义供 edits 等使用）。

- [ ] **步骤 4：其余 chat 调用方切网关**

`ImageRefinePromptService`、`ConversationTitleService`、`PromptOptimizeService`、`VideoPlanService` 四处，均将：
- `.uri(...baseUrlVision...)` → `.uri(gatewayBaseUrl + "/v1/chat/completions")`
- `.header("Authorization", "Bearer " + apiKey)` → 网关 Key

- [ ] **步骤 5：编译验证 Backend**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

- [ ] **步骤 6：Commit**

```bash
cd "E:\Desktop\AI-storyboard" && git add AIStoryboardBackend/ && git commit -m "feat: Backend 生文/文生图调用切换网关（chat/images 走 /v1，edits 保持直连）"
```

---

## 任务 10：Backend 视频切换（创建/轮询/下载走网关）

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/MinimaxVideoService.java`

- [ ] **步骤 1：VideoGenerationService 视频创建改走网关**

门面 `createVideoTask` 中，无论 provider（minimax/laozhang）统一发到网关：

```java
// 改前：provider 分发（minimax→MinimaxVideoService / laozhang→本地 multipart 逻辑）
// 改后：统一走网关
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos"))
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer " + config.getGatewayApiKey())
    .timeout(Duration.ofSeconds(120))
    .POST(HttpRequest.BodyPublishers.ofString(videoRequestBody))
    .build();
```

请求体（OpenAI 风格统一格式）：

```java
Map<String, Object> body = new HashMap<>();
body.put("model", actualModel);          // alias 映射保留在业务侧
body.put("prompt", prompt);
body.put("size", effSize);
body.put("resolution", effResolution);
body.put("aspectRatio", effAspectRatio);
body.put("duration", effDuration);
if (negativePrompt != null) body.put("negativePrompt", negativePrompt);
if (seed != null) body.put("seed", seed);
if (generatedImageUrl != null) {
    // 本地图转 data URI 内联（图片在业务 uploads，网关无权限访问——设计 §6.2 明确业务侧保留此逻辑）
    String filename = extractFilename(generatedImageUrl);
    Path localFile = fileStorageService.resolveImage(filename);
    if (Files.exists(localFile)) {
        byte[] bytes = Files.readAllBytes(localFile);
        String mime = fileStorageService.contentType(filename);
        body.put("imageUrl", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
    }
}
String jsonBody = objectMapper.writeValueAsString(body);
```

响应解析：`task_id` / `id` / `taskId` 任一 → 返回 taskId 落库（scene.videoTaskId 逻辑不变）。

- [ ] **步骤 2：pollVideoTask 改走网关轮询**

```java
// 改前：provider 分发 + 直连上游查询
// 改后：GET gatewayBaseUrl + "/v1/videos/" + taskId，Bearer 网关 Key
// 响应已是统一格式 {taskId,status,progress?,error?}：
//   succeeded → 调网关下载端点拿视频流 → 本地转存 uploads/videos（逻辑保留在业务侧）
//   failed → 透传 error
//   processing/queued → 返回 processing
```

下载改走网关：

```java
// 改前：downloadVideoContent(baseUrl, taskId) 直连上游
// 改后：GET gatewayBaseUrl + "/v1/videos/" + taskId + "/content"（Bearer 网关 Key）
// 其余（重试 3 次、落盘 uploads/videos、返回 /api/files/videos/xxx.mp4）不变
```

- [ ] **步骤 3：MinimaxVideoService 改走网关**

`MinimaxVideoService` 中创建/轮询/下载的直连逻辑全部替换为网关端点调用（与步骤 1/2 相同模式），原 MiniMax 协议转换代码（content 数组、data URI 内联、768P 恒定）**删除或保留为参考注释**——协议转换已下沉网关。`ai.video-provider` 配置字段保留（兼容）但业务侧不再分发；后续可删除。

- [ ] **步骤 4：编译验证 Backend**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

- [ ] **步骤 5：Commit**

```bash
cd "E:\Desktop\AI-storyboard" && git add AIStoryboardBackend/ && git commit -m "feat: Backend 视频创建/轮询/下载切换网关（协议转换下沉，本地存储保留）"
```

---

## 任务 11：端到端联调验证

**文件：** 无新增，纯验证。

- [ ] **步骤 1：准备环境**

```bash
# 1. 建库 + 跑 V1 SQL
PGPASSWORD=123456 psql -h localhost -U postgres -c "CREATE DATABASE llm_gateway;" 2>/dev/null || echo "库已存在"
PGPASSWORD=123456 psql -h localhost -U postgres -d llm_gateway -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\src\\main\\resources\\db\\migration\\V1__gateway_tables.sql"

# 2. 复制 .env（密钥随机生成）
cp AILLMGateway/.env.example AILLMGateway/.env
# 编辑 AILLMGateway/.env 填入真实密钥（或用 python 生成随机值）

# 3. 启动网关（后台）
cd "E:\Desktop\AI-storyboard\AILLMGateway" && export JAVA_HOME="C:\\Program Files\\Java\\jdk-21" && \
  "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" spring-boot:run -q
# 等待 "Started LLMGatewayApplication"
```

- [ ] **步骤 2：管理 API 冒烟（登录 → 建渠道 → 建路由 → 建 Key）**

```bash
# 登录（密码 = .env 的 LLM_GATEWAY_ADMIN_INIT_PASSWORD）
TOKEN=$(curl -s -X POST http://localhost:8083/admin/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<ADMIN_PW>"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 建 Laozhang 渠道（openai_compatible）
curl -s -X POST http://localhost:8083/admin/channels -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"laozhang","type":"openai_compatible","baseUrl":"https://api2.laozhang.ai/v1","apiKey":"<LAOZHANG_KEY>","priority":0}'

# 建 Gemini 渠道
curl -s -X POST http://localhost:8083/admin/channels -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"gemini","type":"gemini","baseUrl":"https://api2.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent","apiKey":"<LAOZHANG_KEY>","priority":0}'

# 建 MiniMax 视频渠道
curl -s -X POST http://localhost:8083/admin/channels -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"minimax","type":"minimax","baseUrl":"https://api.minimaxi.com","apiKey":"<MINIMAX_KEY>","priority":0}'

# 建路由：gpt-image-2→laozhang；gemini-3-pro-image-preview→gemini；gemini-3-flash-preview→laozhang；MiniMax-H3→minimax；veo-3.1-fast→laozhang
CH_ID=$(curl -s http://localhost:8083/admin/channels -H "Authorization: Bearer $TOKEN" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print([c['id'] for c in d if c['name']=='laozhang'][0])")
curl -s -X POST http://localhost:8083/admin/routes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"modelName\":\"gpt-image-2\",\"channelId\":\"$CH_ID\"}"
# ... 其余路由同理

# 建业务 Key（记下明文）
curl -s -X POST http://localhost:8083/admin/api-keys -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"ai-storyboard"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['plainKey'])"
```

- [ ] **步骤 3：OpenAI 兼容入口验证（chat + 文生图 + Gemini 生图）**

```bash
GATEWAY_KEY=<上一步 plainKey>

# chat（经网关 → Laozhang）
curl -s http://localhost:8083/v1/chat/completions -H "Authorization: Bearer $GATEWAY_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gemini-3-flash-preview","messages":[{"role":"user","content":"说一句话"}]}' | python -c "import sys,json;print(json.load(sys.stdin)['choices'][0]['message']['content'])"

# 文生图（经网关 → Laozhang gpt-image-2）
curl -s http://localhost:8083/v1/images/generations -H "Authorization: Bearer $GATEWAY_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gpt-image-2","prompt":"a red apple on white background","size":"1024x1024"}' -o /tmp/img.json
python -c "import json;d=json.load(open('/tmp/img.json'));print('b64:',len(d['data'][0].get('b64_json','')),'url:',d['data'][0].get('url','')[:60])"

# 错误路径：无 Key → 401；未知模型 → 404
curl -s -o /dev/null -w "no-key:%{http_code}\n" http://localhost:8083/v1/chat/completions -H 'Content-Type: application/json' -d '{"model":"x","messages":[]}'
curl -s -o /dev/null -w "bad-model:%{http_code}\n" http://localhost:8083/v1/chat/completions -H "Authorization: Bearer $GATEWAY_KEY" -H 'Content-Type: application/json' -d '{"model":"no-such-model","messages":[]}'
```

预期：chat 200 返回内容；文生图 200 返回 b64 或 url；no-key 401；bad-model 404。

- [ ] **步骤 4：视频链路验证（MiniMax 通道）**

```bash
# 创建视频任务（经网关 → MiniMax）
TASK=$(curl -s http://localhost:8083/v1/videos -H "Authorization: Bearer $GATEWAY_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"MiniMax-H3","prompt":"a cat walking in the rain","duration":8}' | python -c "import sys,json;print(json.load(sys.stdin)['task_id'])")
echo "task_id=$TASK"

# 轮询（间隔 10s，最多 12 次 ≈ 2min）
for i in $(seq 1 12); do
  sleep 10
  STATUS=$(curl -s http://localhost:8083/v1/videos/$TASK -H "Authorization: Bearer $GATEWAY_KEY" | python -c "import sys,json;print(json.load(sys.stdin).get('status',''))")
  echo "poll $i: $STATUS"
  [ "$STATUS" = "succeeded" ] && break
  [ "$STATUS" = "failed" ] && break
done

# 下载（经网关流式代理）
curl -s http://localhost:8083/v1/videos/$TASK/content -H "Authorization: Bearer $GATEWAY_KEY" -o /tmp/test-video.mp4
ls -la /tmp/test-video.mp4   # 预期非 0 字节
```

- [ ] **步骤 5：Backend 全链路验证（编辑器触发或 curl 直调）**

```bash
# Backend 启动后，curl 调 /api/ai/generate-script（chat 走网关）
curl -s -X POST http://localhost:8082/api/ai/generate-script -H 'Content-Type: application/json' \
  -d '{"projectId":"<PID>","scriptText":"一个关于秋天的故事","creationType":"script"}' | head -c 300

# /api/ai/generate-image（文生图走网关）
# /api/ai/generate-video + status 轮询（视频走网关）
```

注意：Backend `.env` 需补 `LLM_GATEWAY_BASE_URL=http://localhost:8083` + `LLM_GATEWAY_API_KEY=<网关签发的 Key>`。

- [ ] **步骤 6：验证日志落库**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -d llm_gateway -c "SELECT model, channel_id, status, duration_ms FROM call_log ORDER BY created_at DESC LIMIT 10;"
```

预期：chat/images/video 调用都有日志记录；视频 succeeded 的记录含 video_url。

- [ ] **步骤 7：Commit（如有验证期修复）**

```bash
cd "E:\Desktop\AI-storyboard" && git status --short
# 有修复则 git add + commit
```

---

## 自检记录（writing-plans 自检）

**1. 规格覆盖度：** 设计文档 12 节 → 计划 11 任务映射：
- §1 背景动机 → 任务 1-11（贯穿）
- §2 决策（端口/库/API 格式/下载模式）→ 任务 1/8/11
- §3 架构 → 任务 1/6/8
- §4 数据模型 5 表 → 任务 2
- §5.1 OpenAI 兼容入口 → 任务 6/8
- §5.2 管理 API → 任务 7
- §6.1 chat/images 流程 → 任务 6
- §6.2 视频流程 → 任务 8
- §7 错误处理 → 任务 3/6/8
- §8 Backend 改造 6 点 → 任务 9/10
- §9 实现清单 → 任务 1-8
- §10 测试 → 任务 11
- §11 部署 → 任务 11 步骤 1-2
- §12 YAGNI → 无对应任务（正确，明确不做）

**2. 占位符扫描：** 无 TODO/待定；所有步骤含代码或精确命令。

**3. 类型一致性：** VideoGatewayService.VideoResult / GatewayRoutingService.RouteResult 命名统一；`videoGatewayService.download` 返回类型与 Controller 一致；GatewayConfig.video.defaultResolution 与 createMinimax 使用一致；VideoGatewayService 构造器已含 CallLogMapper 注入（download 查 video_url 用，已内联修复）。
