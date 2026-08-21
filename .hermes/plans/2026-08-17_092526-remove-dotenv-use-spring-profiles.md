# 移除 .env 加载，改用 Spring Profile（local / prod）实现计划

> **For Hermes:** 按任务逐项实现即可，无需 subagent 并行（改动集中在 2 个子项目的配置文件与 3 个 Java 文件）。

**Goal:** 后端与网关不再通过 `.env` + `System.setProperty()` 加载环境变量，改用标准 Spring Profile：`application.yml` 声明激活环境，`application-local.yml` / `application-prod.yml` 各自承载本地/生产配置。

**Architecture:** Spring Boot 原生 Profile 分层——`application.yml` 放两环境共享且无密钥的配置（应用名、端口、mybatis-plus、actuator、TTL、模型名/默认值），`application-{profile}.yml` 放环境专属配置（数据源、JWT 密钥、AES 密钥、网关地址与 Key、管理员自举密码）。`application.yml` 内 `spring.profiles.active: local` 作为默认，生产通过 `--spring.profiles.active=prod` 或 `SPRING_PROFILES_ACTIVE=prod` 覆盖。

**Tech Stack:** Spring Boot 4（两项目同构），无新增依赖。

---

## 现状（已核实）

- 两项目主类都在 `main()` 里调 `loadDotEnv()`：读取 `.env`（`KEY=VALUE` 逐行）→ `System.setProperty()` 注入。
- `.env`、`application-local.yml` 均在根 `.gitignore` 中被忽略（不入库）。
- 后端 `application.yml` 已含 `spring.profiles.active: local`；网关 `application.yml` 无 profiles 配置、也无 local/prod 文件。
- 后端 `application-local.yml` 里的 `ai.laozhang.api-key` / `sora2-official-api-key` 已死配置：`AiConfigProperties` 无对应字段（grep 全库无 `sora2`、无 `apiKey` 字段引用），可直接丢弃。
- **唯一隐藏耦合**：网关 `AdminInitRunner` 用 `System.getProperty("LLM_GATEWAY_ADMIN_INIT_PASSWORD")` 读自举密码，依赖 `.env` 注入；移除 `.env` 后必须改为 Spring 绑定，否则首启无法建 admin。

后端 `.env` 键（→ 迁移到 profile）：`DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD`、`JWT_ACCESS_SECRET/JWT_REFRESH_SECRET/JWT_ISSUER`、`LLM_GATEWAY_BASE_URL/LLM_GATEWAY_API_KEY`。
网关 `.env` 键：`LLM_GATEWAY_DB_*`、`LLM_GATEWAY_JWT_ACCESS/REFRESH_SECRET`、`LLM_GATEWAY_AES_SECRET`、`LLM_GATEWAY_ADMIN_INIT_PASSWORD`、`LLM_GATEWAY_VIDEO_RESOLUTION`。

---

## 任务分解

### Task 1: 后端——移除 `.env` 加载

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/StoryboardApplication.java`

删除 `loadDotEnv()` 方法及 `main()` 中的调用，移除不再需要的 import（`java.io.IOException`、`java.nio.file.Files`、`java.nio.file.Path`、`java.nio.file.Paths`）。

目标文件：

```java
package com.storyboard;

import com.storyboard.config.JwtConfig;
import com.storyboard.service.ai.AgentAiConfigProperties;
import com.storyboard.service.ai.AiConfigProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JwtConfig.class, AiConfigProperties.class, AiConfigProperties.Gateway.class, AgentAiConfigProperties.class})
@MapperScan("com.storyboard.mapper")
public class StoryboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoryboardApplication.class, args);
    }
}
```

### Task 2: 后端——精简 `application.yml`（保留共享无密钥配置）

**Files:**
- Modify: `AIStoryboardBackend/src/main/resources/application.yml`

原则：删除所有环境专属/含密钥的项（数据源 url/username/password、jwt access/refresh secret、`spring.ai.openai.base-url`/`api-key`、`ai.gateway.base-url`/`api-key`），只留共享配置；`spring.profiles.active: local` 保留。

目标内容：

```yaml
spring:
  application:
    name: ai-storyboard
  profiles:
    active: local            # 默认本地环境；生产用 --spring.profiles.active=prod 覆盖
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 25MB
  datasource:
    driver-class-name: org.postgresql.Driver

server:
  port: 8082

# 只暴露 health 端点（部署探活）
management:
  endpoints:
    web:
      exposure:
        include: health

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: assign_uuid
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

jwt:
  issuer: newworkflow-backend
  access-token-ttl: 3600
  refresh-token-ttl: 2592000

# AI 模型名/默认值（无密钥；LLM 调用统一走 ai.gateway，见 profile 文件）
ai:
  agent:
    intent-threshold: 0.6
    max-clarify-rounds: 2
    max-regenerate-rounds: 3
  laozhang:
    minimax-video-model: MiniMax-H3
    gemini-image-models: gemini-3-pro-image-preview
    video-model-aliases: '{"veo-3.1-fast":"veo-3.1-fast-generate-preview","veo-3.1-fast-fl":"veo-3.1-fast-generate-preview","veo-3.1":"veo-3.1-generate-preview","veo-3.1-fl":"veo-3.1-generate-preview"}'
    default-image-model: gpt-image-2
    default-image-edit-model: gpt-image-2
    default-vision-model: gemini-3-flash-preview
    default-image-size: "1024x1024"
    default-video-duration: "8"
    video-upload-dir: uploads/videos
    video-file-extension: .mp4
    video-url-prefix: /api/files/videos/
```

### Task 3: 后端——重写 `application-local.yml`（本地全量配置）

**Files:**
- Modify: `AIStoryboardBackend/src/main/resources/application-local.yml`

合并原 `.env` 的本地值 + 原 local 文件中的 JWT/日志配置；丢弃死配置 `ai.laozhang.api-key` / `sora2-official-api-key`。

目标内容（本地 dev 值，`.gitignore` 已忽略）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/newworkflow
    username: postgres
    password: 123456
  ai:
    openai:
      base-url: http://localhost:8083/v1
      api-key: <本地网关业务 Key，在网关 /admin 签发，填 lg-…>

jwt:
  access-secret: d02f1779750841f8b4de65e47a913e89
  refresh-secret: cd311f4121c24cf8b761775f9dafae78

ai:
  gateway:
    base-url: http://localhost:8083
    api-key: <同上面 spring.ai.openai.api-key>

logging:
  level:
    com.storyboard: DEBUG
    org.springframework.security: DEBUG
```

> 说明：`spring.ai.openai.api-key` 与 `ai.gateway.api-key` 值相同（都是网关签发的业务 Key），从原 `.env` 的 `LLM_GATEWAY_API_KEY` 迁移。

### Task 4: 后端——新建 `application-prod.yml`（生产全量配置）

**Files:**
- Create: `AIStoryboardBackend/src/main/resources/application-prod.yml`

生产值从服务器现有 `.env` 迁移（DB 主机、生产 JWT、网关地址与 Key）。模板：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<prod-db-host>:5432/newworkflow
    username: postgres
    password: <prod-db-password>
  ai:
    openai:
      base-url: http://<gateway-host>:8083/v1
      api-key: <prod-gateway-key>

jwt:
  access-secret: <prod-access-secret>
  refresh-secret: <prod-refresh-secret>

ai:
  gateway:
    base-url: http://<gateway-host>:8083
    api-key: <prod-gateway-key>

server:
  port: 8082
```

### Task 5: 网关——移除 `.env` 加载

**Files:**
- Modify: `AILLMGateway/src/main/java/com/llmgateway/LLMGatewayApplication.java`

删除 `loadDotEnv()`、`resolveEnvFile()` 及 `main()` 中的调用，移除 import（`java.io.IOException`、`java.nio.file.Files`、`java.nio.file.Path`、`java.util.HashMap`、`java.util.Map`）。

目标文件：

```java
package com.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** 网关启动类（配置由 Spring Profile application-{profile}.yml 提供） */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync  // CallLogService 异步落库需要
public class LLMGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LLMGatewayApplication.class, args);
    }
}
```

### Task 6: 网关——管理员自举密码改为 Spring 绑定

**Files:**
- Modify: `AILLMGateway/src/main/java/com/llmgateway/config/GatewayConfig.java`
- Modify: `AILLMGateway/src/main/java/com/llmgateway/config/AdminInitRunner.java`

`GatewayConfig` 增加字段（`gateway.admin-init-password`）：

```java
    /** 首启管理员自举密码（gateway.admin-init-password；仅 admin_user 表为空时生效） */
    private String adminInitPassword;

    public String getAdminInitPassword() { return adminInitPassword; }
    public void setAdminInitPassword(String adminInitPassword) { this.adminInitPassword = adminInitPassword; }
```

`AdminInitRunner` 改为注入 `GatewayConfig`：

```java
    private final GatewayConfig gatewayConfig;

    public AdminInitRunner(AdminUserMapper adminUserMapper, GatewayConfig gatewayConfig) {
        this.adminUserMapper = adminUserMapper;
        this.gatewayConfig = gatewayConfig;
    }

    // run() 内：
    String initPassword = gatewayConfig.getAdminInitPassword();
```

### Task 7: 网关——精简 `application.yml`

**Files:**
- Modify: `AILLMGateway/src/main/resources/application.yml`

加 `spring.profiles.active: local`，删除数据源/JWT/AES 的 `${...}` 占位（迁到 profile 文件），保留共享配置（上游超时、视频默认档等）。

目标内容：

```yaml
spring:
  application:
    name: llm-gateway
  profiles:
    active: local            # 默认本地；生产用 --spring.profiles.active=prod 覆盖
  datasource:
    driver-class-name: org.postgresql.Driver

server:
  port: 8083

management:
  endpoints:
    web:
      exposure:
        include: health

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: assign_uuid
      logic-delete-field: deleted
      logic-delete-value: true
      logic-not-delete-value: false

gateway:
  jwt:
    issuer: llm-gateway
    access-token-ttl: 3600
    refresh-token-ttl: 2592000
  upstream:
    connect-timeout-ms: 30000
    request-timeout-ms: 120000
    retry-count: 2
  video:
    default-resolution: 768P
    default-duration: "8"
```

### Task 8: 网关——新建 `application-local.yml`

**Files:**
- Create: `AILLMGateway/src/main/resources/application-local.yml`

本地值来自 `AILLMGateway/.env.example` 默认值：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/llm_gateway
    username: postgres
    password: 123456

gateway:
  jwt:
    access-secret: change-me-access-secret-32-bytes-min
    refresh-secret: change-me-refresh-secret-32-bytes-min
  aes:
    secret: 0123456789abcdef0123456789abcdef   # AES-256 恰好 32 字节
  admin-init-password: change-me-admin-password   # 仅首启建 admin 用，可留空跳过
```

### Task 9: 网关——新建 `application-prod.yml`

**Files:**
- Create: `AILLMGateway/src/main/resources/application-prod.yml`

生产值从服务器现有 `.env` 迁移：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<prod-db-host>:5432/llm_gateway
    username: postgres
    password: <prod-db-password>

gateway:
  jwt:
    access-secret: <prod-access-secret-32-bytes>
    refresh-secret: <prod-refresh-secret-32-bytes>
  aes:
    secret: <prod-aes-32-bytes>
  admin-init-password: <prod-admin-init-password>

server:
  port: 8083
```

### Task 10: `.gitignore` 与 `.env.example` 收尾

**Files:**
- Modify: `../.gitignore`（仓库根 `E:\Desktop\AI-storyboard\.gitignore`）

在 `application-local.yml` 行后追加：

```gitignore
application-prod.yml
```

- Modify: `E:\Desktop\AI-storyboard\.env.example`（仓库根，已 tracked）——改为指向 Profile 方案或删除（内容已过时，描述的是旧 `.env` 机制）。
- Modify: `E:\Desktop\AI-storyboard\AILLMGateway\.env.example`（已 tracked）——同上。

> 两个 `.env` 文件（`AIStoryboardBackend/.env`、`AILLMGateway/.env`）移除加载后成为惰性文件，可保留（已 gitignore）或删除，由用户确认后处理。

---

## 验证

1. 编译两项目（Windows 路径 + mvn.cmd）：
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
```
2. 冒烟（本地默认 profile，需本地 PostgreSQL `newworkflow` / `llm_gateway` 在跑）：
   - 先起网关：`mvn spring-boot:run`，日志应出现 `The following 1 profile is active: "local"` 且**不再**打印 `未设置 LLM_GATEWAY_ADMIN_INIT_PASSWORD` 的告警（若 admin 表为空且配了密码，则应打印「已创建默认管理员账号 admin」）。
   - 再起后端：日志出现 `The following 1 profile is active: "local"`，能连库、监听 8082，Spring AI 网关调用正常（不再出现 `Bearer null` / 401）。
3. 生产激活（择一验证，不启动也可仅看日志）：
```bash
java -jar app.jar --spring.profiles.active=prod
# 或
SPRING_PROFILES_ACTIVE=prod java -jar app.jar
```
日志应出现 `The following 1 profile is active: "prod"`。

> 注意：若 8082/8083 正被 IDE 起的实例占用，先 clarify 再停（见记忆：IDE 起的实例勿擅杀）；打包前停运行中 jar。

---

## 风险 / 取舍 / 待确认

- **prod 密钥入不入库**：`application-prod.yml` 与 `application-local.yml` 同列 `.gitignore`，生产值不提交。若希望 prod 文件入库用 `${VAR}` 占位、由服务器 docker-compose 注入，改 Task 4/9 即可（保留 `${...}` 占位符、删 `.gitignore` 里的 `application-prod.yml`）——此为唯一需用户拍板的点。
- **admin-init-password 语义变化**：从 `System.getProperty` 改为 `gateway.admin-init-password`，key 名变更，生产服务器需同步在 `application-prod.yml` 填新 key。
- **旧 `.env` 残留**：两 `.env` 变惰性文件，不删则无副作用，仅易误导。
- **死配置清理**：`ai.laozhang.api-key` / `sora2-official-api-key` 已确认无字段绑定，随 Task 3 一并删除（不影响运行）。

**未决（供用户确认）：**
1. `application-prod.yml` 采用「gitignore + 硬编码生产值」，还是「入库 + `${VAR}` 占位 + docker 注入」？
2. 两个 `.env` 文件与两个 `.env.example` 是删除还是保留/改写？
