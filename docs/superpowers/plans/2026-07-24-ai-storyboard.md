# AI 分镜表 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 从零搭建 AI 分镜表系统（Spring Boot 4 + React 18），实现用户认证、项目管理、AI 分镜生成、AI 生图/生视频和草稿保存。

**架构：** 经典三层（Controller → Service → Mapper），共享 Node.js 项目的 PostgreSQL 用户表，使用 JWT HS256 认证，AI 调用通过 Laozhang Provider 后端代理。

**技术栈：** Spring Boot 4 + JDK 21 + MyBatis-Plus + jjwt 0.12.6 + Bouncy Castle + PostgreSQL + React 18 + TypeScript + Zustand + Vite

**规格文档：** `docs/superpowers/specs/2026-07-24-ai-storyboard-design.md`

---

## 文件结构（规划）

```
AI-storyboard/
├── AIStoryboardBackend/
│   ├── pom.xml                                         # Maven 依赖
│   ├── .env                                            # 环境变量 (已 gitignore)
│   ├── src/main/java/com/storyboard/
│   │   ├── StoryboardApplication.java                  # Spring Boot 入口
│   │   ├── config/
│   │   │   ├── SecurityConfig.java                     # Spring Security 配置
│   │   │   ├── JwtConfig.java                          # JWT 密钥配置 (从 application.yml 注入)
│   │   │   ├── MyBatisPlusConfig.java                  # MyBatis-Plus 配置
│   │   │   └── WebConfig.java                          # CORS 配置
│   │   ├── security/
│   │   │   ├── JwtTokenProvider.java                   # JWT 签发/验证
│   │   │   ├── JwtAuthenticationFilter.java            # OncePerRequestFilter
│   │   │   └── ScryptPasswordService.java              # scrypt 密码验证
│   │   ├── entity/
│   │   │   ├── User.java                               # users 表实体 (只读)
│   │   │   ├── Project.java                            # projects 表实体
│   │   │   ├── Scene.java                              # scenes 表实体
│   │   │   └── SceneReferenceImage.java                # scene_reference_images 表实体
│   │   ├── dto/
│   │   │   ├── request/
│   │   │   │   ├── LoginRequest.java
│   │   │   │   ├── RegisterRequest.java
│   │   │   │   ├── RefreshTokenRequest.java
│   │   │   │   ├── CreateProjectRequest.java
│   │   │   │   ├── UpdateProjectRequest.java
│   │   │   │   ├── GenerateScriptRequest.java
│   │   │   │   ├── GenerateImageRequest.java
│   │   │   │   └── GenerateVideoRequest.java
│   │   │   └── response/
│   │   │       ├── ApiResponse.java                    # 统一信封 {code,message,data,timestamp}
│   │   │       ├── LoginResponse.java
│   │   │       ├── ProjectResponse.java
│   │   │       ├── SceneResponse.java
│   │   │       └── TaskStatusResponse.java
│   │   ├── mapper/
│   │   │   ├── UserMapper.java                         # 只读 users 表
│   │   │   ├── ProjectMapper.java
│   │   │   ├── SceneMapper.java
│   │   │   └── SceneReferenceImageMapper.java
│   │   ├── service/
│   │   │   ├── AuthService.java                        # 登录/注册/刷新
│   │   │   ├── ProjectService.java                     # 项目 CRUD + 草稿
│   │   │   ├── SceneService.java                       # 分镜 CRUD
│   │   │   └── ai/
│   │   │       ├── AiConfigProperties.java             # AI Provider 配置 (从 yml 注入)
│   │   │       ├── ScriptGenerationService.java        # 分镜脚本生成
│   │   │       ├── ImageGenerationService.java         # 图片生成
│   │   │       └── VideoGenerationService.java         # 视频生成 + 轮询
│   │   ├── controller/
│   │   │   ├── AuthController.java
│   │   │   ├── ProjectController.java
│   │   │   ├── SceneController.java
│   │   │   └── AIController.java
│   │   └── exception/
│   │       ├── BusinessException.java
│   │       └── GlobalExceptionHandler.java
│   └── src/main/resources/
│       ├── application.yml                             # 主配置
│       ├── application-local.yml                       # 本地覆盖 (已 gitignore)
│       └── db/migration/
│           └── V1__create_tables.sql                   # 建表 SQL
│
├── AIStoryboardClient/
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx                                     # 路由
│       ├── api/
│       │   ├── client.ts                               # Axios 实例 + 拦截器
│       │   ├── auth.ts                                 # 认证 API
│       │   ├── projects.ts                             # 项目 API
│       │   ├── scenes.ts                               # 分镜 API
│       │   └── ai.ts                                   # AI 生成 API
│       ├── stores/
│       │   ├── authStore.ts                            # Zustand: 认证状态
│       │   ├── projectStore.ts                         # Zustand: 项目+分镜状态
│       │   └── uiStore.ts                              # Zustand: UI 状态 (完善浮层等)
│       ├── pages/
│       │   ├── LoginPage.tsx
│       │   └── EditorPage.tsx
│       ├── components/
│       │   ├── layout/
│       │   │   └── AppHeader.tsx                       # 顶部导航栏
│       │   ├── editor/
│       │   │   ├── ScriptInputPanel.tsx                # 左栏：剧本输入
│       │   │   ├── SceneListPanel.tsx                  # 中栏：分镜列表
│       │   │   └── PreviewPanel.tsx                    # 右栏：预览区
│       │   ├── scene/
│       │   │   ├── SceneCard.tsx                       # 分镜卡片
│       │   │   └── SceneActions.tsx                    # 分镜操作按钮 (状态机)
│       │   ├── ai/
│       │   │   ├── ImageGenerationModal.tsx            # 完善图片浮层
│       │   │   └── VideoGenerationModal.tsx            # 完善视频浮层
│       │   └── common/
│       │       ├── AspectRatioSelector.tsx             # 画幅选择器
│       │       ├── ModelSelector.tsx                   # 模型选择器
│       │       └── DraftRecoverBanner.tsx              # 草稿恢复提示
│       ├── styles/
│       │   ├── tokens.css                              # DESIGN.md tokens → CSS Variables
│       │   └── global.css                              # 全局样式
│       └── hooks/
│           ├── usePolling.ts                           # 视频轮询 hook
│           └── useAutoSave.ts                          # 草稿自动保存 hook
│
└── docs/
    └── superpowers/
        ├── specs/2026-07-24-ai-storyboard-design.md
        └── plans/2026-07-24-ai-storyboard.md           # 本文件
```

---

## 阶段一：后端脚手架与数据库

### 任务 1.1：初始化 Spring Boot 4 项目骨架

**文件：**
- 创建：`AIStoryboardBackend/pom.xml`
- 创建：`AIStoryboardBackend/.env`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/StoryboardApplication.java`
- 创建：`AIStoryboardBackend/src/main/resources/application.yml`
- 创建：`AIStoryboardBackend/src/main/resources/application-local.yml`

- [ ] **步骤 1：编写 pom.xml**

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
    <groupId>com.storyboard</groupId>
    <artifactId>ai-storyboard-backend</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>AI Storyboard Backend</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>3.5.9</version>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
            <version>1.78</version>
        </dependency>
        <dependency>
            <groupId>me.paulschwarz</groupId>
            <artifactId>spring-dotenv</artifactId>
            <version>4.0.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

注意：`spring-dotenv` 版本改为 4.0.0（Spring Boot 4 兼容的 dotenv 加载器），如果不可用则用 `me.paulschwarz:spring-dotenv:4.0.0`。如果不存在 4.0.0 版本，退回到手动加载方式：在 `StoryboardApplication` 中加载 `.env` 到 `System.setProperty`。

- [ ] **步骤 2：创建 .env 文件占位**

```bash
# PostgreSQL
DB_HOST=localhost
DB_PORT=5432
DB_NAME=newworkflow
DB_USERNAME=postgres
DB_PASSWORD=123456

# JWT
JWT_ACCESS_SECRET=your-access-secret-key-change-in-production
JWT_REFRESH_SECRET=your-refresh-secret-key-change-in-production
JWT_ISSUER=newworkflow-backend

# AI Provider (Laozhang)
LAOZHANG_API_KEY=sk-your-key
LAOZHANG_SORA2_OFFICIAL_API_KEY=sk-your-key
LAOZHANG_BASE_URL_OPENAI=https://api.laozhang.ai/v1
LAOZHANG_BASE_URL_GEMINI=https://api.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent
LAOZHANG_BASE_URL_VISION=https://api.laozhang.ai/v1/chat/completions
```

- [ ] **步骤 3：创建 application.yml**

```yaml
spring:
  application:
    name: ai-storyboard
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:newworkflow}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:123456}
    driver-class-name: org.postgresql.Driver
  profiles:
    active: local

server:
  port: ${SERVER_PORT:8080}

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
  access-secret: ${JWT_ACCESS_SECRET}
  refresh-secret: ${JWT_REFRESH_SECRET}
  issuer: ${JWT_ISSUER:newworkflow-backend}
  access-token-ttl: 3600
  refresh-token-ttl: 2592000

ai:
  laozhang:
    api-key: ${LAOZHANG_API_KEY}
    sora2-official-api-key: ${LAOZHANG_SORA2_OFFICIAL_API_KEY}
    base-url-openai: ${LAOZHANG_BASE_URL_OPENAI:https://api.laozhang.ai/v1}
    base-url-gemini: ${LAOZHANG_BASE_URL_GEMINI:https://api.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent}
    base-url-vision: ${LAOZHANG_BASE_URL_VISION:https://api.laozhang.ai/v1/chat/completions}
    default-image-model: gpt-image-2
    default-vision-model: gemini-3-flash-preview
    poll-interval-ms: 5000
    poll-timeout-ms: 600000
```

- [ ] **步骤 4：创建 application-local.yml**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/newworkflow
    username: postgres
    password: 123456

logging:
  level:
    com.storyboard: DEBUG
    org.springframework.security: DEBUG
```

- [ ] **步骤 5：创建 StoryboardApplication.java**

```java
package com.storyboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StoryboardApplication {
    public static void main(String[] args) {
        SpringApplication.run(StoryboardApplication.class, args);
    }
}
```

- [ ] **步骤 6：验证项目能启动**

```bash
cd AIStoryboardBackend && mvn spring-boot:run
```

预期：Spring Boot 启动成功（数据库连接正常），无报错。

- [ ] **步骤 7：Commit**

```bash
git add AIStoryboardBackend/pom.xml AIStoryboardBackend/.env AIStoryboardBackend/src/
git commit -m "chore: init Spring Boot 4 project with dependencies"
```

---

### 任务 1.2：创建数据库迁移脚本和实体类

**文件：**
- 创建：`AIStoryboardBackend/src/main/resources/db/migration/V1__create_tables.sql`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/entity/User.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/entity/Project.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/entity/Scene.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/entity/SceneReferenceImage.java`

- [ ] **步骤 1：编写建表 SQL**

```sql
-- V1__create_tables.sql
-- 注意：users 表已存在于 newworkflow.public，此处不创建
-- 仅创建 AI 分镜业务表

CREATE TABLE IF NOT EXISTS projects (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id         TEXT NOT NULL,
    name            TEXT NOT NULL DEFAULT '未命名项目',
    description     TEXT,
    creation_type   TEXT NOT NULL DEFAULT 'movie',
    custom_type_desc TEXT,
    aspect_ratio    TEXT NOT NULL DEFAULT '16:9',
    reference_image_url TEXT,
    script_text     TEXT,
    ai_model        TEXT NOT NULL DEFAULT 'gemini-3-flash-preview',
    status          TEXT NOT NULL DEFAULT 'draft',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS scenes (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    project_id      TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    scene_number    INTEGER NOT NULL,
    script_content  TEXT,
    image_prompt    TEXT,
    video_prompt    TEXT,
    negative_prompt TEXT,
    camera_movement TEXT,
    shot_type       TEXT,
    sound_design    TEXT,
    ai_model        TEXT,
    video_resolution TEXT,
    duration        INTEGER,
    image_url       TEXT,
    video_url       TEXT,
    image_status    TEXT NOT NULL DEFAULT 'pending',
    video_status    TEXT NOT NULL DEFAULT 'pending',
    image_task_id   TEXT,
    video_task_id   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_scenes_project ON scenes(project_id, scene_number);

CREATE TABLE IF NOT EXISTS scene_reference_images (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    scene_id    TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    image_url   TEXT NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ref_images_scene ON scene_reference_images(scene_id, sort_order);
```

- [ ] **步骤 2：编写实体类**

```java
// User.java
package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName(value = "users", schema = "public")
public class User {
    @TableId
    private String id;
    private String email;
    private String passwordHash;
    private String displayName;
    private String role;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

```java
// Project.java
package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName(value = "projects", schema = "public")
public class Project {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String name;
    private String description;
    private String creationType;
    private String customTypeDesc;
    private String aspectRatio;
    private String referenceImageUrl;
    private String scriptText;
    private String aiModel;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

```java
// Scene.java
package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName(value = "scenes", schema = "public")
public class Scene {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private Integer sceneNumber;
    private String scriptContent;
    private String imagePrompt;
    private String videoPrompt;
    private String negativePrompt;
    private String cameraMovement;
    private String shotType;
    private String soundDesign;
    private String aiModel;
    private String videoResolution;
    private Integer duration;
    private String imageUrl;
    private String videoUrl;
    private String imageStatus;
    private String videoStatus;
    private String imageTaskId;
    private String videoTaskId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

```java
// SceneReferenceImage.java
package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "scene_reference_images", schema = "public")
public class SceneReferenceImage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sceneId;
    private String imageUrl;
    private Integer sortOrder;
}
```

- [ ] **步骤 3：创建 MyBatisPlusConfig（自动填充时间）**

```java
package com.storyboard.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MyBatisPlusConfig {
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
```

- [ ] **步骤 4：执行建表 SQL**

```bash
psql -U postgres -d newworkflow -f AIStoryboardBackend/src/main/resources/db/migration/V1__create_tables.sql
```

验证：
```sql
SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('projects','scenes','scene_reference_images');
```

- [ ] **步骤 5：Commit**

```bash
git add AIStoryboardBackend/src/main/resources/db/ AIStoryboardBackend/src/main/java/com/storyboard/entity/ AIStoryboardBackend/src/main/java/com/storyboard/config/MyBatisPlusConfig.java
git commit -m "feat: add database tables and entity classes"
```

---

## 阶段二：安全与认证

### 任务 2.1：实现 ScryptPasswordService

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/security/ScryptPasswordService.java`
- 创建：`AIStoryboardBackend/src/test/java/com/storyboard/security/ScryptPasswordServiceTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
package com.storyboard.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScryptPasswordServiceTest {

    private final ScryptPasswordService service = new ScryptPasswordService();

    @Test
    void shouldVerifyPasswordAgainstKnownHash() {
        // 已知密码 "test123" 的哈希，使用 Node.js crypto.scrypt 生成
        // 格式: scrypt:{salt_hex}:{derived_key_hex}
        String storedHash = "scrypt:abcdef0123456789abcdef0123456789:" +
            "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";

        assertTrue(service.verifyPassword("test123", storedHash));
    }

    @Test
    void shouldRejectWrongPassword() {
        String storedHash = "scrypt:abcdef0123456789abcdef0123456789:" +
            "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";

        assertFalse(service.verifyPassword("wrongpassword", storedHash));
    }

    @Test
    void shouldRejectInvalidFormat() {
        assertFalse(service.verifyPassword("test", "invalid-format"));
        assertFalse(service.verifyPassword("test", "wrong:format"));
    }

    @Test
    void shouldHashAndVerifyRoundTrip() throws Exception {
        String password = "mySecurePassword123";
        String hash = service.hashPassword(password);
        assertTrue(hash.startsWith("scrypt:"));
        assertTrue(service.verifyPassword(password, hash));
        assertFalse(service.verifyPassword("wrong", hash));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
cd AIStoryboardBackend && mvn test -Dtest=ScryptPasswordServiceTest
```

预期：FAIL，类不存在。

- [ ] **步骤 3：实现 ScryptPasswordService**

```java
package com.storyboard.security;

import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;

@Service
public class ScryptPasswordService {

    private static final String SCRYPT_PREFIX = "scrypt";
    private static final int SALT_BYTES = 16;
    private static final int KEY_LENGTH = 64;
    private static final int SCRYPT_N = 16384;  // 2^14
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;

    public boolean verifyPassword(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 3 || !SCRYPT_PREFIX.equals(parts[0])) {
                return false;
            }
            byte[] salt = hexToBytes(parts[1]);
            byte[] expectedKey = hexToBytes(parts[2]);
            byte[] derivedKey = SCrypt.generate(
                password.getBytes("UTF-8"), salt,
                SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH
            );
            return MessageDigest.isEqual(derivedKey, expectedKey);
        } catch (Exception e) {
            return false;
        }
    }

    public String hashPassword(String password) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(salt);
        byte[] derived = SCrypt.generate(
            password.getBytes("UTF-8"), salt,
            SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH
        );
        return SCRYPT_PREFIX + ":" + bytesToHex(salt) + ":" + bytesToHex(derived);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

```bash
cd AIStoryboardBackend && mvn test -Dtest=ScryptPasswordServiceTest
```

预期：第 1 个测试 `shouldVerifyPasswordAgainstKnownHash` 会失败（使用了假的哈希），但 round-trip 测试通过。将第 1 个测试更新为用 `hashPassword` 生成真实哈希后再验证。

修正后的测试步骤 1（替换 `shouldVerifyPasswordAgainstKnownHash`）：

```java
@Test
void shouldVerifyPasswordAgainstKnownHash() throws Exception {
    // 用我们的方法生成一个哈希，然后验证
    String password = "testPassword123";
    String hash = service.hashPassword(password);
    assertTrue(service.verifyPassword(password, hash));
}
```

最终全部 PASS。

- [ ] **步骤 5：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/security/ScryptPasswordService.java AIStoryboardBackend/src/test/
git commit -m "feat: implement scrypt password service with tests"
```

---

### 任务 2.2：实现 JwtTokenProvider

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/security/JwtTokenProvider.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/config/JwtConfig.java`
- 创建：`AIStoryboardBackend/src/test/java/com/storyboard/security/JwtTokenProviderTest.java`

- [ ] **步骤 1：创建 JwtConfig**

```java
package com.storyboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {
    private String accessSecret;
    private String refreshSecret;
    private String issuer = "newworkflow-backend";
    private long accessTokenTtl = 3600;
    private long refreshTokenTtl = 2592000;

    // getters and setters
    public String getAccessSecret() { return accessSecret; }
    public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
    public String getRefreshSecret() { return refreshSecret; }
    public void setRefreshSecret(String refreshSecret) { this.refreshSecret = refreshSecret; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public long getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(long accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
    public long getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(long refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
}
```

- [ ] **步骤 2：编写 JWT 测试**

```java
package com.storyboard.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend"
        );
    }

    @Test
    void shouldSignAndVerifyAccessToken() {
        String token = provider.signAccessToken("user-123", "member", "enabled");
        Claims claims = provider.verifyAccessToken(token);

        assertEquals("user-123", claims.getSubject());
        assertEquals("access", claims.get("typ", String.class));
        assertEquals("member", claims.get("role", String.class));
        assertEquals("enabled", claims.get("status", String.class));
        assertEquals("newworkflow-backend", claims.getIssuer());
    }

    @Test
    void shouldRejectExpiredAccessToken() throws Exception {
        // 创建已过期的 token（使用带 TTL 的构造器）
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
            "test-access-secret-key-at-least-256-bits-long!!",
            "test-refresh-secret-key-at-least-256-bits-long",
            "newworkflow-backend",
            -1, // access TTL = -1s (immediately expired)
            2592000
        );
        String token = expiredProvider.signAccessToken("user-123", "member", "enabled");

        assertThrows(Exception.class, () -> expiredProvider.verifyAccessToken(token));
    }

    @Test
    void shouldRejectTokenWithWrongType() {
        String refreshToken = provider.signRefreshToken("user-123");
        assertThrows(IllegalArgumentException.class, () -> provider.verifyAccessToken(refreshToken));
    }
}
```

- [ ] **步骤 3：实现 JwtTokenProvider**

```java
package com.storyboard.security;

import com.storyboard.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final SecretKey accessKey;
    private final SecretKey refreshKey;
    private final String issuer;
    private final long accessTokenTtl;
    private final long refreshTokenTtl;

    public JwtTokenProvider(JwtConfig config) {
        this.accessKey = new SecretKeySpec(config.getAccessSecret().getBytes(), "HmacSHA256");
        this.refreshKey = new SecretKeySpec(config.getRefreshSecret().getBytes(), "HmacSHA256");
        this.issuer = config.getIssuer();
        this.accessTokenTtl = config.getAccessTokenTtl();
        this.refreshTokenTtl = config.getRefreshTokenTtl();
    }

    // 测试用构造器
    JwtTokenProvider(String accessSecret, String refreshSecret, String issuer,
                     long accessTokenTtl, long refreshTokenTtl) {
        this.accessKey = new SecretKeySpec(accessSecret.getBytes(), "HmacSHA256");
        this.refreshKey = new SecretKeySpec(refreshSecret.getBytes(), "HmacSHA256");
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    JwtTokenProvider(String accessSecret, String refreshSecret, String issuer) {
        this(accessSecret, refreshSecret, issuer, 3600, 2592000);
    }

    public String signAccessToken(String userId, String role, String status) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        return Jwts.builder()
            .header().add("alg", "HS256").add("typ", "JWT").and()
            .claim("typ", "access")
            .issuer(issuer)
            .subject(userId)
            .claim("role", role)
            .claim("status", status)
            .issuedAt(new Date(nowSeconds * 1000))
            .expiration(new Date((nowSeconds + accessTokenTtl) * 1000))
            .id(UUID.randomUUID().toString())
            .signWith(accessKey)
            .compact();
    }

    public String signRefreshToken(String userId) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        return Jwts.builder()
            .header().add("alg", "HS256").add("typ", "JWT").and()
            .claim("typ", "refresh")
            .issuer(issuer)
            .subject(userId)
            .issuedAt(new Date(nowSeconds * 1000))
            .expiration(new Date((nowSeconds + refreshTokenTtl) * 1000))
            .id(UUID.randomUUID().toString())
            .signWith(refreshKey)
            .compact();
    }

    public Claims verifyAccessToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(accessKey)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        validateAccessClaims(claims);
        return claims;
    }

    public Claims verifyRefreshToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(refreshKey)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        String typ = claims.get("typ", String.class);
        if (!"refresh".equals(typ)) {
            throw new IllegalArgumentException("TOKEN_TYPE_INVALID");
        }
        return claims;
    }

    private void validateAccessClaims(Claims claims) {
        String typ = claims.get("typ", String.class);
        if (!"access".equals(typ)) throw new IllegalArgumentException("TOKEN_TYPE_INVALID");
        String role = claims.get("role", String.class);
        if (!"member".equals(role) && !"admin".equals(role)) throw new IllegalArgumentException("TOKEN_ROLE_INVALID");
        String status = claims.get("status", String.class);
        if (!"enabled".equals(status) && !"disabled".equals(status)) throw new IllegalArgumentException("TOKEN_STATUS_INVALID");
    }
}
```

- [ ] **步骤 4：运行测试**

```bash
cd AIStoryboardBackend && mvn test -Dtest=JwtTokenProviderTest
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/security/JwtTokenProvider.java AIStoryboardBackend/src/main/java/com/storyboard/config/JwtConfig.java AIStoryboardBackend/src/test/
git commit -m "feat: implement JWT token provider with tests"
```

---

### 任务 2.3：实现认证控制器和安全配置

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/controller/AuthController.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/AuthService.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/UserMapper.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/security/JwtAuthenticationFilter.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/config/SecurityConfig.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/config/WebConfig.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/LoginRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/RegisterRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/response/ApiResponse.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/response/LoginResponse.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/exception/BusinessException.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/exception/GlobalExceptionHandler.java`

- [ ] **步骤 1：创建 DTO**

```java
// LoginRequest.java
package com.storyboard.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}
```

```java
// RegisterRequest.java
package com.storyboard.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 6) String password,
    @NotBlank @Size(max = 80) String displayName
) {}
```

```java
// ApiResponse.java
package com.storyboard.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    int code,
    String message,
    T data,
    LocalDateTime timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, LocalDateTime.now());
    }
}
```

```java
// LoginResponse.java
package com.storyboard.dto.response;

public record LoginResponse(
    String accessToken,
    String refreshToken,
    String userId,
    String displayName
) {}
```

- [ ] **步骤 2：创建 UserMapper**

```java
package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM public.users WHERE status = 'enabled' AND email = #{email}")
    User findByEmail(@Param("email") String email);

    @Update("UPDATE public.users SET last_login_at = #{now} WHERE id = #{id}")
    int updateLastLoginAt(@Param("id") String id, @Param("now") LocalDateTime now);
}
```

Lombok 的 `@Data` 需要添加依赖或手动写 getter/setter。在 pom.xml 中加入：

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

- [ ] **步骤 3：创建 AuthService**

```java
package com.storyboard.service;

import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.entity.User;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.UserMapper;
import com.storyboard.security.JwtTokenProvider;
import com.storyboard.security.ScryptPasswordService;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final ScryptPasswordService passwordService;

    public AuthService(UserMapper userMapper, JwtTokenProvider jwtTokenProvider, ScryptPasswordService passwordService) {
        this.userMapper = userMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordService = passwordService;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByEmail(request.email().toLowerCase().trim());
        if (user == null) {
            throw new BusinessException(40102, "用户名或密码错误");
        }
        if (!passwordService.verifyPassword(request.password(), user.getPasswordHash())) {
            throw new BusinessException(40102, "用户名或密码错误");
        }
        userMapper.updateLastLoginAt(user.getId(), LocalDateTime.now());

        String accessToken = jwtTokenProvider.signAccessToken(user.getId(), user.getRole(), user.getStatus());
        String refreshToken = jwtTokenProvider.signRefreshToken(user.getId());

        return new LoginResponse(accessToken, refreshToken, user.getId(), user.getDisplayName());
    }

    public LoginResponse register(RegisterRequest request) {
        User existing = userMapper.findByEmail(request.email().toLowerCase().trim());
        if (existing != null) {
            throw new BusinessException(40001, "邮箱已被注册");
        }
        User user = new User();
        user.setEmail(request.email().toLowerCase().trim());
        user.setDisplayName(request.displayName());
        user.setRole("member");
        user.setStatus("enabled");
        try {
            user.setPasswordHash(passwordService.hashPassword(request.password()));
        } catch (Exception e) {
            throw new BusinessException(50000, "密码加密失败");
        }
        userMapper.insert(user);

        String accessToken = jwtTokenProvider.signAccessToken(user.getId(), user.getRole(), user.getStatus());
        String refreshToken = jwtTokenProvider.signRefreshToken(user.getId());

        return new LoginResponse(accessToken, refreshToken, user.getId(), user.getDisplayName());
    }

    public LoginResponse refresh(String refreshToken) {
        try {
            var claims = jwtTokenProvider.verifyRefreshToken(refreshToken);
            String userId = claims.getSubject();
            User user = userMapper.selectById(userId);
            if (user == null || "disabled".equals(user.getStatus())) {
                throw new BusinessException(40101, "用户不存在或已禁用");
            }
            String newAccessToken = jwtTokenProvider.signAccessToken(user.getId(), user.getRole(), user.getStatus());
            String newRefreshToken = jwtTokenProvider.signRefreshToken(user.getId());
            return new LoginResponse(newAccessToken, newRefreshToken, user.getId(), user.getDisplayName());
        } catch (Exception e) {
            throw new BusinessException(40101, "Token 无效或已过期");
        }
    }
}
```

- [ ] **步骤 4：创建 BusinessException 和 GlobalExceptionHandler**

```java
// BusinessException.java
package com.storyboard.exception;

public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() { return code; }
}
```

```java
// GlobalExceptionHandler.java
package com.storyboard.exception;

import com.storyboard.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = switch (e.getCode()) {
            case 40101, 40102 -> HttpStatus.UNAUTHORIZED;
            case 40301 -> HttpStatus.FORBIDDEN;
            case 40401 -> HttpStatus.NOT_FOUND;
            case 40001 -> HttpStatus.BAD_REQUEST;
            case 50201, 50202 -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("参数错误");
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(50000, "服务器内部错误"));
    }
}
```

- [ ] **步骤 5：创建 JwtAuthenticationFilter**

```java
package com.storyboard.security;

import com.storyboard.dto.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            var claims = jwtTokenProvider.verifyAccessToken(token);
            String userId = claims.getSubject();
            String role = claims.get("role", String.class);

            List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())
            );
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
            auth.setDetails(Map.of("role", role, "status", claims.get("status", String.class)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(40101, "未授权")
            ));
            return;
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **步骤 6：创建 SecurityConfig**

```java
package com.storyboard.config;

import com.storyboard.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **步骤 7：创建 WebConfig (CORS)**

```java
package com.storyboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
            }
        };
    }
}
```

- [ ] **步骤 8：创建 AuthController**

```java
package com.storyboard.controller;

import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        return ApiResponse.ok(authService.refresh(refreshToken));
    }
}
```

注意：AuthController 中需要导入 `java.util.Map`。

- [ ] **步骤 9：验证认证流程**

启动应用后测试：

```bash
# 测试登录（使用数据库中已有用户）
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"test123"}'

# 预期返回: {"code":200,"data":{"accessToken":"...","refreshToken":"..."...}}
```

- [ ] **步骤 10：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/controller/AuthController.java \
        AIStoryboardBackend/src/main/java/com/storyboard/service/AuthService.java \
        AIStoryboardBackend/src/main/java/com/storyboard/mapper/UserMapper.java \
        AIStoryboardBackend/src/main/java/com/storyboard/security/JwtAuthenticationFilter.java \
        AIStoryboardBackend/src/main/java/com/storyboard/config/SecurityConfig.java \
        AIStoryboardBackend/src/main/java/com/storyboard/config/WebConfig.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/ \
        AIStoryboardBackend/src/main/java/com/storyboard/exception/
git commit -m "feat: implement auth controller with JWT filter and security config"
```

---

## 阶段三：项目管理 CRUD

### 任务 3.1：实现项目 CRUD

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/ProjectMapper.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/ProjectService.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/controller/ProjectController.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/CreateProjectRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/UpdateProjectRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/response/ProjectResponse.java`

- [ ] **步骤 1：创建 DTO**

```java
// CreateProjectRequest.java
package com.storyboard.dto.request;

public record CreateProjectRequest(
    String name,
    String description,
    String creationType,
    String aspectRatio
) {}
```

```java
// UpdateProjectRequest.java
package com.storyboard.dto.request;

public record UpdateProjectRequest(
    String name,
    String description,
    String scriptText,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String referenceImageUrl,
    String aiModel,
    String status
) {}
```

```java
// ProjectResponse.java
package com.storyboard.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record ProjectResponse(
    String id,
    String userId,
    String name,
    String description,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String referenceImageUrl,
    String scriptText,
    String aiModel,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<SceneResponse> scenes
) {}
```

- [ ] **步骤 2：创建 ProjectMapper**

```java
package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    @Select("SELECT * FROM public.projects WHERE user_id = #{userId} ORDER BY updated_at DESC")
    List<Project> findByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM public.projects WHERE user_id = #{userId} AND status = 'draft' ORDER BY updated_at DESC LIMIT 1")
    Project findLatestDraft(@Param("userId") String userId);
}
```

- [ ] **步骤 3：创建 SceneResponse（供 ProjectResponse 引用）**

```java
// SceneResponse.java
package com.storyboard.dto.response;

import java.time.LocalDateTime;

public record SceneResponse(
    String id,
    String projectId,
    Integer sceneNumber,
    String scriptContent,
    String imagePrompt,
    String videoPrompt,
    String negativePrompt,
    String cameraMovement,
    String shotType,
    String soundDesign,
    String aiModel,
    String videoResolution,
    Integer duration,
    String imageUrl,
    String videoUrl,
    String imageStatus,
    String videoStatus,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
```

- [ ] **步骤 4：创建 ProjectService**

```java
package com.storyboard.service;

import com.storyboard.dto.request.CreateProjectRequest;
import com.storyboard.dto.request.UpdateProjectRequest;
import com.storyboard.dto.response.ProjectResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;

    public ProjectService(ProjectMapper projectMapper, SceneMapper sceneMapper) {
        this.projectMapper = projectMapper;
        this.sceneMapper = sceneMapper;
    }

    public List<ProjectResponse> listByUser(String userId) {
        return projectMapper.findByUserId(userId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public ProjectResponse create(String userId, CreateProjectRequest request) {
        Project project = new Project();
        project.setUserId(userId);
        project.setName(request.name() != null ? request.name() : "未命名项目");
        project.setDescription(request.description());
        project.setCreationType(request.creationType() != null ? request.creationType() : "movie");
        project.setAspectRatio(request.aspectRatio() != null ? request.aspectRatio() : "16:9");
        projectMapper.insert(project);
        return toResponse(project);
    }

    public ProjectResponse getById(String userId, String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            throw new BusinessException(40401, "项目不存在");
        }
        return toResponse(project);
    }

    @Transactional
    public ProjectResponse update(String userId, String projectId, UpdateProjectRequest request) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            throw new BusinessException(40401, "项目不存在");
        }
        if (request.name() != null) project.setName(request.name());
        if (request.description() != null) project.setDescription(request.description());
        if (request.scriptText() != null) project.setScriptText(request.scriptText());
        if (request.creationType() != null) project.setCreationType(request.creationType());
        if (request.customTypeDesc() != null) project.setCustomTypeDesc(request.customTypeDesc());
        if (request.aspectRatio() != null) project.setAspectRatio(request.aspectRatio());
        if (request.referenceImageUrl() != null) project.setReferenceImageUrl(request.referenceImageUrl());
        if (request.aiModel() != null) project.setAiModel(request.aiModel());
        if (request.status() != null) project.setStatus(request.status());
        projectMapper.updateById(project);
        return toResponse(project);
    }

    @Transactional
    public void delete(String userId, String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            throw new BusinessException(40401, "项目不存在");
        }
        projectMapper.deleteById(projectId);
    }

    public ProjectResponse getLatestDraft(String userId) {
        Project draft = projectMapper.findLatestDraft(userId);
        return draft != null ? toResponse(draft) : null;
    }

    private ProjectResponse toResponse(Project project) {
        List<Scene> scenes = sceneMapper.findByProjectIdOrdered(project.getId());
        List<SceneResponse> sceneResponses = scenes.stream().map(s -> new SceneResponse(
            s.getId(), s.getProjectId(), s.getSceneNumber(),
            s.getScriptContent(), s.getImagePrompt(), s.getVideoPrompt(),
            s.getNegativePrompt(), s.getCameraMovement(), s.getShotType(),
            s.getSoundDesign(), s.getAiModel(), s.getVideoResolution(),
            s.getDuration(), s.getImageUrl(), s.getVideoUrl(),
            s.getImageStatus(), s.getVideoStatus(), s.getCreatedAt(), s.getUpdatedAt()
        )).toList();
        return new ProjectResponse(
            project.getId(), project.getUserId(), project.getName(),
            project.getDescription(), project.getCreationType(), project.getCustomTypeDesc(),
            project.getAspectRatio(), project.getReferenceImageUrl(), project.getScriptText(),
            project.getAiModel(), project.getStatus(),
            project.getCreatedAt(), project.getUpdatedAt(), sceneResponses
        );
    }
}
```

- [ ] **步骤 5：创建 ProjectController**

```java
package com.storyboard.controller;

import com.storyboard.dto.request.CreateProjectRequest;
import com.storyboard.dto.request.UpdateProjectRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.ProjectResponse;
import com.storyboard.service.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(Authentication auth) {
        return ApiResponse.ok(projectService.listByUser(auth.getName()));
    }

    @PostMapping
    public ApiResponse<ProjectResponse> create(Authentication auth, @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectService.create(auth.getName(), request));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> get(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(projectService.getById(auth.getName(), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectResponse> update(Authentication auth, @PathVariable String id,
                                                @RequestBody UpdateProjectRequest request) {
        return ApiResponse.ok(projectService.update(auth.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String id) {
        projectService.delete(auth.getName(), id);
        return ApiResponse.ok("删除成功", null);
    }

    @GetMapping("/draft")
    public ApiResponse<ProjectResponse> getDraft(Authentication auth) {
        ProjectResponse draft = projectService.getLatestDraft(auth.getName());
        return ApiResponse.ok(draft);
    }
}
```

- [ ] **步骤 6：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/mapper/ProjectMapper.java \
        AIStoryboardBackend/src/main/java/com/storyboard/service/ProjectService.java \
        AIStoryboardBackend/src/main/java/com/storyboard/controller/ProjectController.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/
git commit -m "feat: implement project CRUD with draft support"
```

---

### 任务 3.2：实现分镜 CRUD

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/SceneMapper.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/SceneReferenceImageMapper.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/SceneService.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/controller/SceneController.java`

- [ ] **步骤 1：创建 Mapper**

```java
// SceneMapper.java
package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.Scene;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SceneMapper extends BaseMapper<Scene> {

    @Select("SELECT * FROM public.scenes WHERE project_id = #{projectId} ORDER BY scene_number")
    List<Scene> findByProjectIdOrdered(@Param("projectId") String projectId);

    @Select("SELECT COALESCE(MAX(scene_number), 0) FROM public.scenes WHERE project_id = #{projectId}")
    int maxSceneNumber(@Param("projectId") String projectId);
}
```

```java
// SceneReferenceImageMapper.java
package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.SceneReferenceImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SceneReferenceImageMapper extends BaseMapper<SceneReferenceImage> {

    @Select("SELECT * FROM public.scene_reference_images WHERE scene_id = #{sceneId} ORDER BY sort_order")
    List<SceneReferenceImage> findBySceneId(@Param("sceneId") String sceneId);
}
```

- [ ] **步骤 2：创建 SceneService**

```java
package com.storyboard.service;

import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class SceneService {

    private final SceneMapper sceneMapper;
    private final SceneReferenceImageMapper refImageMapper;

    public SceneService(SceneMapper sceneMapper, SceneReferenceImageMapper refImageMapper) {
        this.sceneMapper = sceneMapper;
        this.refImageMapper = refImageMapper;
    }

    @Transactional
    public SceneResponse addScene(String projectId, Map<String, Object> data) {
        int nextNum = sceneMapper.maxSceneNumber(projectId) + 1;
        Scene scene = new Scene();
        scene.setProjectId(projectId);
        scene.setSceneNumber(nextNum);
        scene.setScriptContent((String) data.getOrDefault("scriptContent", ""));
        scene.setImagePrompt((String) data.getOrDefault("imagePrompt", ""));
        scene.setVideoPrompt((String) data.getOrDefault("videoPrompt", ""));
        scene.setCameraMovement((String) data.getOrDefault("cameraMovement", ""));
        scene.setShotType((String) data.getOrDefault("shotType", ""));
        sceneMapper.insert(scene);
        return toResponse(scene);
    }

    @Transactional
    public SceneResponse updateScene(String sceneId, Map<String, Object> data) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");

        if (data.containsKey("scriptContent")) scene.setScriptContent((String) data.get("scriptContent"));
        if (data.containsKey("imagePrompt")) scene.setImagePrompt((String) data.get("imagePrompt"));
        if (data.containsKey("videoPrompt")) scene.setVideoPrompt((String) data.get("videoPrompt"));
        if (data.containsKey("negativePrompt")) scene.setNegativePrompt((String) data.get("negativePrompt"));
        if (data.containsKey("cameraMovement")) scene.setCameraMovement((String) data.get("cameraMovement"));
        if (data.containsKey("shotType")) scene.setShotType((String) data.get("shotType"));
        if (data.containsKey("soundDesign")) scene.setSoundDesign((String) data.get("soundDesign"));
        if (data.containsKey("aiModel")) scene.setAiModel((String) data.get("aiModel"));
        if (data.containsKey("videoResolution")) scene.setVideoResolution((String) data.get("videoResolution"));
        if (data.containsKey("duration") && data.get("duration") != null) scene.setDuration((Integer) data.get("duration"));

        sceneMapper.updateById(scene);
        return toResponse(scene);
    }

    @Transactional
    public void deleteScene(String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        sceneMapper.deleteById(sceneId);
    }

    private SceneResponse toResponse(Scene s) {
        return new SceneResponse(
            s.getId(), s.getProjectId(), s.getSceneNumber(),
            s.getScriptContent(), s.getImagePrompt(), s.getVideoPrompt(),
            s.getNegativePrompt(), s.getCameraMovement(), s.getShotType(),
            s.getSoundDesign(), s.getAiModel(), s.getVideoResolution(),
            s.getDuration(), s.getImageUrl(), s.getVideoUrl(),
            s.getImageStatus(), s.getVideoStatus(), s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
```

- [ ] **步骤 3：创建 SceneController**

```java
package com.storyboard.controller;

import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.service.SceneService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SceneController {

    private final SceneService sceneService;

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    @PostMapping("/projects/{projectId}/scenes")
    public ApiResponse<SceneResponse> add(@PathVariable String projectId, @RequestBody Map<String, Object> data) {
        return ApiResponse.ok(sceneService.addScene(projectId, data));
    }

    @PutMapping("/scenes/{id}")
    public ApiResponse<SceneResponse> update(@PathVariable String id, @RequestBody Map<String, Object> data) {
        return ApiResponse.ok(sceneService.updateScene(id, data));
    }

    @DeleteMapping("/scenes/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        sceneService.deleteScene(id);
        return ApiResponse.ok("删除成功", null);
    }
}
```

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/mapper/SceneMapper.java \
        AIStoryboardBackend/src/main/java/com/storyboard/mapper/SceneReferenceImageMapper.java \
        AIStoryboardBackend/src/main/java/com/storyboard/service/SceneService.java \
        AIStoryboardBackend/src/main/java/com/storyboard/controller/SceneController.java
git commit -m "feat: implement scene CRUD"
```

---

## 阶段四：AI 服务层

### 任务 4.1：实现 AI 配置和分镜脚本生成

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ScriptGenerationService.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/GenerateScriptRequest.java`

- [ ] **步骤 1：创建 AiConfigProperties**

```java
package com.storyboard.service.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.laozhang")
public class AiConfigProperties {
    private String apiKey;
    private String sora2OfficialApiKey;
    private String baseUrlOpenai = "https://api.laozhang.ai/v1";
    private String baseUrlGemini = "https://api.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent";
    private String baseUrlVision = "https://api.laozhang.ai/v1/chat/completions";
    private String defaultImageModel = "gpt-image-2";
    private String defaultVisionModel = "gemini-3-flash-preview";
    private long pollIntervalMs = 5000;
    private long pollTimeoutMs = 600000;

    // getters and setters
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getSora2OfficialApiKey() { return sora2OfficialApiKey; }
    public void setSora2OfficialApiKey(String s) { this.sora2OfficialApiKey = s; }
    public String getBaseUrlOpenai() { return baseUrlOpenai; }
    public void setBaseUrlOpenai(String s) { this.baseUrlOpenai = s; }
    public String getBaseUrlGemini() { return baseUrlGemini; }
    public void setBaseUrlGemini(String s) { this.baseUrlGemini = s; }
    public String getBaseUrlVision() { return baseUrlVision; }
    public void setBaseUrlVision(String s) { this.baseUrlVision = s; }
    public String getDefaultImageModel() { return defaultImageModel; }
    public void setDefaultImageModel(String s) { this.defaultImageModel = s; }
    public String getDefaultVisionModel() { return defaultVisionModel; }
    public void setDefaultVisionModel(String s) { this.defaultVisionModel = s; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long l) { this.pollIntervalMs = l; }
    public long getPollTimeoutMs() { return pollTimeoutMs; }
    public void setPollTimeoutMs(long l) { this.pollTimeoutMs = l; }
}
```

- [ ] **步骤 2：创建 GenerateScriptRequest**

```java
package com.storyboard.dto.request;

public record GenerateScriptRequest(
    String projectId,
    String scriptText,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String model,
    String referenceImageUrl
) {}
```

- [ ] **步骤 3：创建 ScriptGenerationService**

```java
package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@Service
public class ScriptGenerationService {

    private final AiConfigProperties config;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ScriptGenerationService(AiConfigProperties config, ProjectMapper projectMapper, SceneMapper sceneMapper) {
        this.config = config;
        this.projectMapper = projectMapper;
        this.sceneMapper = sceneMapper;
    }

    public List<Map<String, Object>> generateScenes(String projectId, String scriptText,
                                                      String creationType, String customTypeDesc,
                                                      String aspectRatio, String model) {
        String systemPrompt = buildSystemPrompt(creationType, customTypeDesc, aspectRatio);
        String userPrompt = "请根据以下剧本内容生成分镜脚本，每个分镜包含：镜头号、剧本内容、生图提示词（格式：【镜头构图】→【场景主体】→【环境细节/道具】→【光线与色彩】→【氛围情绪】→【画质/风格】）、生视频提示词、反向提示词、机位和运动、镜头类型、声音设计。\n\n剧本：\n" + scriptText;

        String response = callVisionApi(model, systemPrompt, userPrompt);
        return parseScenes(response, projectId);
    }

    private String buildSystemPrompt(String creationType, String customTypeDesc, String aspectRatio) {
        String style = switch (creationType) {
            case "movie" -> "电影化叙事、氛围渲染、视觉对比";
            case "short_video" -> "快节奏、竖屏为主、3秒抓人";
            case "ad" -> "品牌调性、卖点突出、光影质感";
            case "drama" -> "情绪递进、角色刻画、叙事完整";
            case "documentary" -> "稳重、旁白驱动、信息密度高";
            case "custom" -> customTypeDesc != null ? customTypeDesc : "";
            default -> "电影化叙事";
        };
        return "你是一个专业的分镜师。创作风格：" + style + "。画幅：" + aspectRatio +
            "。请以 JSON 数组格式返回分镜列表，每个分镜包含：sceneNumber(整数), scriptContent, imagePrompt, videoPrompt, negativePrompt, cameraMovement, shotType, soundDesign。";
    }

    private String callVisionApi(String model, String systemPrompt, String userPrompt) {
        try {
            String effectiveModel = model != null ? model : config.getDefaultVisionModel();
            Map<String, Object> body = new HashMap<>();
            body.put("model", effectiveModel);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            body.put("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlVision()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Vision API returned " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("AI 生成分镜脚本失败: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> parseScenes(String response, String projectId) {
        try {
            // 提取 JSON 数组（AI 可能在前后包裹 markdown 代码块）
            String json = response;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.lastIndexOf("```"));
            }
            json = json.trim();
            if (!json.startsWith("[")) {
                json = json.substring(json.indexOf('['), json.lastIndexOf(']') + 1);
            }

            List<Map<String, Object>> scenes = new ArrayList<>();
            JsonNode arr = objectMapper.readTree(json);
            int sceneNum = sceneMapper.maxSceneNumber(projectId);
            for (JsonNode node : arr) {
                sceneNum++;
                Map<String, Object> scene = new HashMap<>();
                scene.put("projectId", projectId);
                scene.put("sceneNumber", sceneNum);
                scene.put("scriptContent", node.path("scriptContent").asText(""));
                scene.put("imagePrompt", node.path("imagePrompt").asText(""));
                scene.put("videoPrompt", node.path("videoPrompt").asText(""));
                scene.put("negativePrompt", node.path("negativePrompt").asText(""));
                scene.put("cameraMovement", node.path("cameraMovement").asText(""));
                scene.put("shotType", node.path("shotType").asText(""));
                scene.put("soundDesign", node.path("soundDesign").asText(""));
                scenes.add(scene);
            }
            return scenes;
        } catch (Exception e) {
            throw new RuntimeException("解析 AI 返回的分镜数据失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **步骤 4：运行验证**

```bash
cd AIStoryboardBackend && mvn compile
```

确保编译通过，无语法错误。

- [ ] **步骤 5：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java \
        AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ScriptGenerationService.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/request/GenerateScriptRequest.java
git commit -m "feat: implement AI script generation service"
```

---

### 任务 4.2：实现 AI 生图服务

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/GenerateImageRequest.java`

- [ ] **步骤 1：创建 GenerateImageRequest**

```java
package com.storyboard.dto.request;

import java.util.List;

public record GenerateImageRequest(
    String sceneId,
    String prompt,
    String model,
    String size,
    String aspectRatio,
    List<String> referenceImages
) {}
```

- [ ] **步骤 2：实现 ImageGenerationService（同步）**

```java
package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Service
public class ImageGenerationService {

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ImageGenerationService(AiConfigProperties config, SceneMapper sceneMapper) {
        this.config = config;
        this.sceneMapper = sceneMapper;
    }

    public String generateImage(String sceneId, String prompt, String model,
                                 String size, String aspectRatio,
                                 java.util.List<String> referenceImages) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        String effectiveModel = model != null ? model : config.getDefaultImageModel();

        try {
            String imageUrl;
            if ("gemini-3-pro-image-preview".equals(effectiveModel)) {
                imageUrl = callGeminiImage(prompt, aspectRatio, referenceImages);
            } else {
                imageUrl = callOpenAIImage(effectiveModel, prompt, size, aspectRatio, referenceImages);
            }

            // 更新 scene
            scene.setImageUrl(imageUrl);
            scene.setImageStatus("completed");
            sceneMapper.updateById(scene);

            return imageUrl;
        } catch (Exception e) {
            scene.setImageStatus("failed");
            sceneMapper.updateById(scene);
            throw new RuntimeException("AI 图片生成失败: " + e.getMessage(), e);
        }
    }

    private String callOpenAIImage(String model, String prompt, String size,
                                    String aspectRatio, java.util.List<String> referenceImages) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size != null ? size : "2K");
        if (referenceImages != null && !referenceImages.isEmpty()) {
            body.put("reference_images", referenceImages);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrlOpenai() + "/images/generations"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + ("gpt-image-2-official".equals(model) ? config.getSora2OfficialApiKey() : config.getApiKey()))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Image API returned " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        return root.path("data").get(0).path("url").asText();
    }

    private String callGeminiImage(String prompt, String aspectRatio,
                                    java.util.List<String> referenceImages) throws Exception {
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);
        body.put("contents", new Object[]{Map.of("parts", new Object[]{part})});
        body.put("generationConfig", Map.of("aspectRatio", aspectRatio != null ? aspectRatio : "16:9"));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrlGemini()))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", config.getApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Gemini API returned " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        // 提取图片 URL（根据 Gemini 响应格式调整）
        return root.path("candidates").get(0).path("content").path("parts").get(0).path("inlineData").path("data").asText();
    }
}
```

- [ ] **步骤 3：编译验证**

```bash
cd AIStoryboardBackend && mvn compile
```

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/request/GenerateImageRequest.java
git commit -m "feat: implement AI image generation service"
```

---

### 任务 4.3：实现 AI 生视频服务（异步轮询）

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/GenerateVideoRequest.java`

- [ ] **步骤 1：创建 GenerateVideoRequest**

```java
package com.storyboard.dto.request;

import java.util.List;

public record GenerateVideoRequest(
    String sceneId,
    String prompt,
    String model,
    String resolution,
    Integer duration,
    List<String> referenceImages
) {}
```

- [ ] **步骤 2：实现 VideoGenerationService**

```java
package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Service
public class VideoGenerationService {

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final Map<String, String> MODEL_ALIAS = Map.of(
        "veo-3.1-fast", "veo-3.1-fast-generate-preview",
        "veo-3.1-fast-fl", "veo-3.1-fast-generate-preview",
        "veo-3.1", "veo-3.1-generate-preview",
        "veo-3.1-fl", "veo-3.1-generate-preview"
    );

    public VideoGenerationService(AiConfigProperties config, SceneMapper sceneMapper) {
        this.config = config;
        this.sceneMapper = sceneMapper;
    }

    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, Integer duration,
                                   java.util.List<String> referenceImages) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        String actualModel = MODEL_ALIAS.getOrDefault(alias, alias);

        try {
            // POST /v1/videos (multipart)
            String boundary = "----FormBoundary" + System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            sb.append(actualModel).append("\r\n");
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n");
            sb.append(prompt).append("\r\n");
            if (resolution != null) {
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"resolution\"\r\n\r\n");
                sb.append(resolution).append("\r\n");
            }
            if (duration != null) {
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"duration\"\r\n\r\n");
                sb.append(duration).append("\r\n");
            }
            sb.append("--").append(boundary).append("--\r\n");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlOpenai() + "/videos"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new RuntimeException("Video API returned " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("id").asText();

            scene.setVideoTaskId(taskId);
            scene.setVideoStatus("generating");
            sceneMapper.updateById(scene);

            return taskId;
        } catch (Exception e) {
            scene.setVideoStatus("failed");
            sceneMapper.updateById(scene);
            throw new RuntimeException("AI 视频生成失败: " + e.getMessage(), e);
        }
    }

    public Map<String, String> pollVideoTask(String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlOpenai() + "/videos/" + taskId))
                .header("Authorization", "Bearer " + config.getApiKey())
                .GET()
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            String status = root.path("status").asText();

            Map<String, String> result = new HashMap<>();
            result.put("taskId", taskId);

            if ("completed".equals(status) || "succeeded".equals(status)) {
                String videoUrl = root.path("url").asText();
                result.put("status", "completed");
                result.put("videoUrl", videoUrl);

                // 更新 scene
                var scenes = sceneMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Scene>()
                        .eq(Scene::getVideoTaskId, taskId)
                );
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoUrl(videoUrl);
                    scene.setVideoStatus("completed");
                    sceneMapper.updateById(scene);
                }
            } else if ("failed".equals(status) || "error".equals(status)) {
                result.put("status", "failed");
                // 更新失败的 scene
                var scenes = sceneMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Scene>()
                        .eq(Scene::getVideoTaskId, taskId)
                );
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoStatus("failed");
                    sceneMapper.updateById(scene);
                }
            } else {
                result.put("status", "processing");
                result.put("progress", root.path("progress").asText(""));
            }
            return result;
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("status", "failed");
            error.put("error", e.getMessage());
            return error;
        }
    }
}
```

- [ ] **步骤 3：编译验证**

```bash
cd AIStoryboardBackend && mvn compile
```

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/request/GenerateVideoRequest.java
git commit -m "feat: implement AI video generation service with polling"
```

---

### 任务 4.4：实现 AIController

**文件：**
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/controller/AIController.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/response/TaskStatusResponse.java`

- [ ] **步骤 1：创建 TaskStatusResponse**

```java
package com.storyboard.dto.response;

public record TaskStatusResponse(
    String taskId,
    String status,
    String videoUrl,
    String progress,
    String error
) {}
```

- [ ] **步骤 2：创建 AIController**

```java
package com.storyboard.controller;

import com.storyboard.dto.request.GenerateImageRequest;
import com.storyboard.dto.request.GenerateScriptRequest;
import com.storyboard.dto.request.GenerateVideoRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.dto.response.TaskStatusResponse;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ProjectService;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.ScriptGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final ScriptGenerationService scriptService;
    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    private final ProjectService projectService;

    public AIController(ScriptGenerationService scriptService, ImageGenerationService imageService,
                        VideoGenerationService videoService, SceneMapper sceneMapper,
                        ProjectService projectService) {
        this.scriptService = scriptService;
        this.imageService = imageService;
        this.videoService = videoService;
        this.sceneMapper = sceneMapper;
        this.projectService = projectService;
    }

    @PostMapping("/generate-script")
    public ApiResponse<Map<String, Object>> generateScript(@RequestBody GenerateScriptRequest request) {
        List<Map<String, Object>> scenes = scriptService.generateScenes(
            request.projectId(), request.scriptText(), request.creationType(),
            request.customTypeDesc(), request.aspectRatio(), request.model()
        );

        // 将生成的 scenes 存入数据库
        for (Map<String, Object> s : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(request.projectId());
            scene.setSceneNumber((Integer) s.get("sceneNumber"));
            scene.setScriptContent((String) s.get("scriptContent"));
            scene.setImagePrompt((String) s.get("imagePrompt"));
            scene.setVideoPrompt((String) s.get("videoPrompt"));
            scene.setNegativePrompt((String) s.get("negativePrompt"));
            scene.setCameraMovement((String) s.get("cameraMovement"));
            scene.setShotType((String) s.get("shotType"));
            scene.setSoundDesign((String) s.get("soundDesign"));
            sceneMapper.insert(scene);
        }

        return ApiResponse.ok(Map.of("projectId", request.projectId(), "sceneCount", scenes.size()));
    }

    @PostMapping("/generate-image")
    public ApiResponse<Map<String, String>> generateImage(@RequestBody GenerateImageRequest request) {
        String imageUrl = imageService.generateImage(
            request.sceneId(), request.prompt(), request.model(),
            request.size(), request.aspectRatio(), request.referenceImages()
        );
        return ApiResponse.ok(Map.of("imageUrl", imageUrl, "sceneId", request.sceneId()));
    }

    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(@RequestBody GenerateVideoRequest request) {
        String taskId = videoService.createVideoTask(
            request.sceneId(), request.prompt(), request.model(),
            request.resolution(), request.duration(), request.referenceImages()
        );
        return ApiResponse.ok(Map.of("taskId", taskId, "sceneId", request.sceneId()));
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        Map<String, String> result = videoService.pollVideoTask(taskId);
        return ApiResponse.ok(new TaskStatusResponse(
            result.get("taskId"), result.get("status"),
            result.get("videoUrl"), result.get("progress"), result.get("error")
        ));
    }
}
```

- [ ] **步骤 3：编译验证**

```bash
cd AIStoryboardBackend && mvn compile
```

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/controller/AIController.java \
        AIStoryboardBackend/src/main/java/com/storyboard/dto/response/TaskStatusResponse.java
git commit -m "feat: implement AI controller with script/image/video endpoints"
```

---

## 阶段五：前端脚手架

### 任务 5.1：初始化 Vite + React + TypeScript 项目

**文件：**
- 创建：`AIStoryboardClient/` 整个项目（`npm create vite`）

- [ ] **步骤 1：创建项目**

```bash
cd E:\Desktop\AI-storyboard
npm create vite@latest AIStoryboardClient -- --template react-ts
cd AIStoryboardClient
npm install
```

- [ ] **步骤 2：安装依赖**

```bash
npm install zustand axios react-router-dom
npm install -D @types/react @types/react-dom
```

- [ ] **步骤 3：验证启动**

```bash
npm run dev
```

预期：Vite 开发服务器启动，浏览器打开看到默认 Vite 页面。

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardClient/
git commit -m "chore: init Vite React TypeScript project"
```

---

### 任务 5.2：创建 DESIGN.md Token → CSS Variables

**文件：**
- 创建：`AIStoryboardClient/src/styles/tokens.css`
- 创建：`AIStoryboardClient/src/styles/global.css`

- [ ] **步骤 1：创建 tokens.css**

```css
:root {
  /* Colors */
  --color-primary: #cc785c;
  --color-primary-active: #a9583e;
  --color-primary-disabled: #e6dfd8;
  --color-ink: #141413;
  --color-body: #3d3d3a;
  --color-body-strong: #252523;
  --color-muted: #6c6a64;
  --color-muted-soft: #8e8b82;
  --color-hairline: #e6dfd8;
  --color-hairline-soft: #ebe6df;
  --color-canvas: #faf9f5;
  --color-surface-soft: #f5f0e8;
  --color-surface-card: #efe9de;
  --color-surface-cream-strong: #e8e0d2;
  --color-surface-dark: #181715;
  --color-surface-dark-elevated: #252320;
  --color-surface-dark-soft: #1f1e1b;
  --color-on-primary: #ffffff;
  --color-on-dark: #faf9f5;
  --color-on-dark-soft: #a09d96;
  --color-success: #5db872;
  --color-warning: #d4a017;
  --color-error: #c64545;

  /* Typography */
  --font-display: 'Tiempos Headline', 'Cormorant Garamond', Georgia, serif;
  --font-body: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  --font-code: 'JetBrains Mono', 'Fira Code', monospace;

  --text-display-sm: 400 28px/1.2 var(--font-display);
  --text-title-lg: 500 22px/1.3 var(--font-body);
  --text-title-md: 500 18px/1.4 var(--font-body);
  --text-title-sm: 500 16px/1.4 var(--font-body);
  --text-body-md: 400 16px/1.55 var(--font-body);
  --text-body-sm: 400 14px/1.55 var(--font-body);
  --text-caption: 500 13px/1.4 var(--font-body);
  --text-caption-upper: 500 12px/1.4 var(--font-body);
  --text-button: 500 14px/1 var(--font-body);

  /* Spacing */
  --space-xs: 8px;
  --space-sm: 12px;
  --space-md: 16px;
  --space-lg: 24px;
  --space-xl: 32px;
  --space-xxl: 48px;

  /* Border Radius */
  --rounded-sm: 6px;
  --rounded-md: 8px;
  --rounded-lg: 12px;
  --rounded-xl: 16px;
  --rounded-pill: 9999px;
}
```

- [ ] **步骤 2：创建 global.css**

```css
@import './tokens.css';

*, *::before, *::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body, #root {
  height: 100%;
}

body {
  font: var(--text-body-md);
  color: var(--color-body);
  background: var(--color-canvas);
  -webkit-font-smoothing: antialiased;
}

button {
  cursor: pointer;
  font: var(--text-button);
}

input, textarea, select {
  font: var(--text-body-md);
}

a {
  color: var(--color-primary);
}
```

- [ ] **步骤 3：在 main.tsx 中导入**

```tsx
import './styles/global.css';
```

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardClient/src/styles/
git commit -m "feat: add DESIGN.md token system as CSS variables"
```

---

### 任务 5.3：创建 API 客户端层

**文件：**
- 创建：`AIStoryboardClient/src/api/client.ts`
- 创建：`AIStoryboardClient/src/api/auth.ts`
- 创建：`AIStoryboardClient/src/api/projects.ts`
- 创建：`AIStoryboardClient/src/api/scenes.ts`
- 创建：`AIStoryboardClient/src/api/ai.ts`

- [ ] **步骤 1：创建 Axios 客户端**

```typescript
// client.ts
import axios from 'axios';

const client = axios.create({
  baseURL: 'http://localhost:8080/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default client;
```

- [ ] **步骤 2：创建 API 模块**

```typescript
// auth.ts
import client from './client';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userId: string;
  displayName: string;
}

export const authApi = {
  login: (data: LoginRequest) =>
    client.post<ApiResponse<LoginResponse>>('/auth/login', data),
  register: (data: RegisterRequest) =>
    client.post<ApiResponse<LoginResponse>>('/auth/register', data),
  refresh: (refreshToken: string) =>
    client.post<ApiResponse<LoginResponse>>('/auth/refresh', { refreshToken }),
};

// 通用响应类型
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}
```

```typescript
// projects.ts
import client from './client';

export interface ProjectResponse {
  id: string;
  name: string;
  description: string;
  creationType: string;
  aspectRatio: string;
  scriptText: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  scenes: SceneResponse[];
}

export interface SceneResponse {
  id: string;
  projectId: string;
  sceneNumber: number;
  scriptContent: string;
  imagePrompt: string;
  videoPrompt: string;
  negativePrompt: string;
  cameraMovement: string;
  shotType: string;
  soundDesign: string;
  imageUrl: string;
  videoUrl: string;
  imageStatus: string;
  videoStatus: string;
}

export const projectApi = {
  list: () => client.get<ApiResponse<ProjectResponse[]>>('/projects'),
  create: (data: { name?: string; creationType?: string; aspectRatio?: string }) =>
    client.post<ApiResponse<ProjectResponse>>('/projects', data),
  get: (id: string) => client.get<ApiResponse<ProjectResponse>>(`/projects/${id}`),
  update: (id: string, data: Record<string, unknown>) =>
    client.put<ApiResponse<ProjectResponse>>(`/projects/${id}`, data),
  delete: (id: string) => client.delete(`/projects/${id}`),
  getDraft: () => client.get<ApiResponse<ProjectResponse | null>>('/projects/draft'),
};
```

```typescript
// scenes.ts
import client from './client';

export const sceneApi = {
  add: (projectId: string, data: Record<string, unknown>) =>
    client.post(`/projects/${projectId}/scenes`, data),
  update: (id: string, data: Record<string, unknown>) =>
    client.put(`/scenes/${id}`, data),
  delete: (id: string) => client.delete(`/scenes/${id}`),
};
```

```typescript
// ai.ts
import client from './client';

export const aiApi = {
  generateScript: (data: {
    projectId: string;
    scriptText: string;
    creationType: string;
    customTypeDesc?: string;
    aspectRatio: string;
    model?: string;
    referenceImageUrl?: string;
  }) => client.post('/ai/generate-script', data),

  generateImage: (data: {
    sceneId: string;
    prompt: string;
    model?: string;
    size?: string;
    aspectRatio?: string;
    referenceImages?: string[];
  }) => client.post('/ai/generate-image', data),

  generateVideo: (data: {
    sceneId: string;
    prompt: string;
    model?: string;
    resolution?: string;
    duration?: number;
    referenceImages?: string[];
  }) => client.post('/ai/generate-video', data),

  pollTask: (taskId: string) =>
    client.get(`/ai/task/${taskId}`),
};
```

- [ ] **步骤 5：Commit**

```bash
git add AIStoryboardClient/src/api/
git commit -m "feat: create API client layer with auth, projects, scenes, AI endpoints"
```

---

### 任务 5.4：创建 Zustand Stores

**文件：**
- 创建：`AIStoryboardClient/src/stores/authStore.ts`
- 创建：`AIStoryboardClient/src/stores/projectStore.ts`

- [ ] **步骤 1：创建 authStore**

```typescript
// authStore.ts
import { create } from 'zustand';
import { authApi, LoginRequest, RegisterRequest } from '../api/auth';

interface User {
  userId: string;
  displayName: string;
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
  checkAuth: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  isLoading: false,
  error: null,

  login: async (data) => {
    set({ isLoading: true, error: null });
    try {
      const res = await authApi.login(data);
      const { accessToken, refreshToken, userId, displayName } = res.data.data;
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', refreshToken);
      set({ user: { userId, displayName }, isAuthenticated: true, isLoading: false });
    } catch (e: any) {
      set({ error: e.response?.data?.message || '登录失败', isLoading: false });
    }
  },

  register: async (data) => {
    set({ isLoading: true, error: null });
    try {
      const res = await authApi.register(data);
      const { accessToken, refreshToken, userId, displayName } = res.data.data;
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', refreshToken);
      set({ user: { userId, displayName }, isAuthenticated: true, isLoading: false });
    } catch (e: any) {
      set({ error: e.response?.data?.message || '注册失败', isLoading: false });
    }
  },

  logout: () => {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    set({ user: null, isAuthenticated: false });
  },

  checkAuth: () => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      set({ isAuthenticated: true });
    }
  },
}));
```

- [ ] **步骤 2：创建 projectStore**

```typescript
// projectStore.ts
import { create } from 'zustand';
import { projectApi, ProjectResponse, SceneResponse } from '../api/projects';
import { sceneApi } from '../api/scenes';
import { aiApi } from '../api/ai';

interface ProjectState {
  projects: ProjectResponse[];
  currentProject: ProjectResponse | null;
  scenes: SceneResponse[];
  selectedSceneId: string | null;
  isLoading: boolean;
  generatingImage: Record<string, boolean>;  // sceneId -> isGenerating
  generatingVideo: Record<string, boolean>;  // sceneId -> isGenerating

  loadProjects: () => Promise<void>;
  createProject: (name: string, creationType: string, aspectRatio: string) => Promise<ProjectResponse>;
  loadProject: (id: string) => Promise<void>;
  updateProject: (id: string, data: Record<string, unknown>) => Promise<void>;
  deleteProject: (id: string) => Promise<void>;
  checkDraft: () => Promise<ProjectResponse | null>;
  selectScene: (sceneId: string) => void;
  generateScript: (projectId: string, scriptText: string, creationType: string, aspectRatio: string, model?: string) => Promise<void>;
  generateImage: (sceneId: string, prompt: string, model?: string, referenceImages?: string[]) => Promise<string>;
  generateVideo: (sceneId: string, prompt: string, model?: string, referenceImages?: string[]) => Promise<string>;
  setGeneratingImage: (sceneId: string, v: boolean) => void;
  setGeneratingVideo: (sceneId: string, v: boolean) => void;
  addScene: (projectId: string) => Promise<void>;
  deleteScene: (sceneId: string) => Promise<void>;
}

export const useProjectStore = create<ProjectState>((set, get) => ({
  projects: [],
  currentProject: null,
  scenes: [],
  selectedSceneId: null,
  isLoading: false,
  generatingImage: {},
  generatingVideo: {},

  loadProjects: async () => {
    set({ isLoading: true });
    const res = await projectApi.list();
    set({ projects: res.data.data || [], isLoading: false });
  },

  createProject: async (name, creationType, aspectRatio) => {
    const res = await projectApi.create({ name, creationType, aspectRatio });
    const project = res.data.data;
    set((s) => ({ projects: [project, ...s.projects] }));
    return project;
  },

  loadProject: async (id) => {
    set({ isLoading: true });
    const res = await projectApi.get(id);
    const project = res.data.data;
    set({
      currentProject: project,
      scenes: project.scenes || [],
      selectedSceneId: null,
      isLoading: false,
    });
  },

  updateProject: async (id, data) => {
    const res = await projectApi.update(id, data);
    set({ currentProject: res.data.data });
  },

  deleteProject: async (id) => {
    await projectApi.delete(id);
    set((s) => ({
      projects: s.projects.filter((p) => p.id !== id),
      currentProject: s.currentProject?.id === id ? null : s.currentProject,
    }));
  },

  checkDraft: async () => {
    const res = await projectApi.getDraft();
    return res.data.data;
  },

  selectScene: (sceneId) => set({ selectedSceneId: sceneId }),

  generateScript: async (projectId, scriptText, creationType, aspectRatio, model) => {
    set({ isLoading: true });
    await aiApi.generateScript({ projectId, scriptText, creationType, aspectRatio, model });
    await get().loadProject(projectId);
    set({ isLoading: false });
  },

  generateImage: async (sceneId, prompt, model, referenceImages) => {
    set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: true } }));
    try {
      const res = await aiApi.generateImage({
        sceneId, prompt, model, size: '2K', aspectRatio: '16:9', referenceImages,
      });
      if (get().currentProject) {
        await get().loadProject(get().currentProject!.id);
      }
      return res.data.data.imageUrl;
    } finally {
      set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: false } }));
    }
  },

  generateVideo: async (sceneId, prompt, model, referenceImages) => {
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: true } }));
    try {
      const res = await aiApi.generateVideo({
        sceneId, prompt, model, referenceImages,
      });
      return res.data.data.taskId;
    } finally {
      set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: false } }));
    }
  },

  setGeneratingImage: (sceneId, v) =>
    set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: v } })),
  setGeneratingVideo: (sceneId, v) =>
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: v } })),

  addScene: async (projectId) => {
    await sceneApi.add(projectId, { scriptContent: '' });
    if (get().currentProject?.id === projectId) {
      await get().loadProject(projectId);
    }
  },

  deleteScene: async (sceneId) => {
    await sceneApi.delete(sceneId);
    if (get().currentProject) {
      await get().loadProject(get().currentProject!.id);
    }
  },
}));
```

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/stores/
git commit -m "feat: create Zustand stores for auth and project state"
```

---

## 阶段六：前端页面

### 任务 6.1：实现登录页面

**文件：**
- 创建：`AIStoryboardClient/src/pages/LoginPage.tsx`
- 修改：`AIStoryboardClient/src/App.tsx`
- 修改：`AIStoryboardClient/src/main.tsx`

- [ ] **步骤 1：设置路由 (App.tsx)**

```tsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { EditorPage } from './pages/EditorPage';
import { useAuthStore } from './stores/authStore';

function App() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={isAuthenticated ? <Navigate to="/editor" /> : <LoginPage />} />
        <Route path="/editor" element={isAuthenticated ? <EditorPage /> : <Navigate to="/login" />} />
        <Route path="*" element={<Navigate to="/editor" />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
```

- [ ] **步骤 2：实现 LoginPage**

```tsx
import { useState } from 'react';
import { useAuthStore } from '../stores/authStore';
import { useNavigate } from 'react-router-dom';

export function LoginPage() {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const { login, register, isLoading, error } = useAuthStore();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isRegister) {
      await register({ email, password, displayName });
    } else {
      await login({ email, password });
    }
    if (useAuthStore.getState().isAuthenticated) {
      navigate('/editor');
    }
  };

  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      minHeight: '100vh', background: 'var(--color-canvas)',
    }}>
      <div style={{
        background: 'white', borderRadius: 'var(--rounded-lg)',
        padding: 'var(--space-xl)', maxWidth: 400, width: '100%',
        boxShadow: '0 1px 3px rgba(20,20,19,0.08)',
      }}>
        <h1 style={{
          font: 'var(--text-display-sm)', color: 'var(--color-ink)',
          marginBottom: 'var(--space-lg)', textAlign: 'center',
        }}>
          AI 分镜表
        </h1>
        <form onSubmit={handleSubmit}>
          {isRegister && (
            <div style={{ marginBottom: 'var(--space-md)' }}>
              <label style={{ display: 'block', marginBottom: 4, color: 'var(--color-muted)' }}>用户名</label>
              <input
                type="text" value={displayName} required
                onChange={(e) => setDisplayName(e.target.value)}
                style={inputStyle}
              />
            </div>
          )}
          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label style={{ display: 'block', marginBottom: 4, color: 'var(--color-muted)' }}>邮箱</label>
            <input
              type="email" value={email} required
              onChange={(e) => setEmail(e.target.value)}
              style={inputStyle}
            />
          </div>
          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label style={{ display: 'block', marginBottom: 4, color: 'var(--color-muted)' }}>密码</label>
            <input
              type="password" value={password} required minLength={6}
              onChange={(e) => setPassword(e.target.value)}
              style={inputStyle}
            />
          </div>
          {error && <p style={{ color: 'var(--color-error)', marginBottom: 12, fontSize: 13 }}>{error}</p>}
          <button
            type="submit" disabled={isLoading}
            style={{
              width: '100%', padding: '10px 20px', height: 40,
              background: 'var(--color-primary)', color: 'var(--color-on-primary)',
              border: 'none', borderRadius: 'var(--rounded-md)',
              fontSize: 14, fontWeight: 500, cursor: 'pointer',
            }}
          >
            {isLoading ? '请稍候...' : (isRegister ? '注册' : '登录')}
          </button>
        </form>
        <p style={{ textAlign: 'center', marginTop: 'var(--space-md)', fontSize: 13, color: 'var(--color-muted)' }}>
          {isRegister ? '已有账号？' : '没有账号？'}
          <button
            onClick={() => setIsRegister(!isRegister)}
            style={{
              background: 'none', border: 'none', color: 'var(--color-primary)',
              cursor: 'pointer', fontSize: 13, textDecoration: 'underline',
            }}
          >
            {isRegister ? '去登录' : '去注册'}
          </button>
        </p>
      </div>
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%', padding: '10px 14px', height: 40,
  border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
  fontSize: 14, background: 'var(--color-canvas)',
};
```

- [ ] **步骤 3：验证**

```bash
cd AIStoryboardClient && npm run dev
```

访问 `http://localhost:5173/login`，确认登录页面可渲染。

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardClient/src/pages/LoginPage.tsx AIStoryboardClient/src/App.tsx AIStoryboardClient/src/main.tsx
git commit -m "feat: implement login/register page with routing"
```

---

### 任务 6.2：实现编辑器主框架（三栏布局）

**文件：**
- 创建：`AIStoryboardClient/src/pages/EditorPage.tsx`
- 创建：`AIStoryboardClient/src/components/layout/AppHeader.tsx`
- 创建：`AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx`
- 创建：`AIStoryboardClient/src/components/editor/SceneListPanel.tsx`
- 创建：`AIStoryboardClient/src/components/editor/PreviewPanel.tsx`
- 创建：`AIStoryboardClient/src/components/common/AspectRatioSelector.tsx`
- 创建：`AIStoryboardClient/src/components/common/ModelSelector.tsx`
- 创建：`AIStoryboardClient/src/components/common/DraftRecoverBanner.tsx`

- [ ] **步骤 1：创建 AppHeader**

```tsx
import { useAuthStore } from '../../stores/authStore';
import { useProjectStore } from '../../stores/projectStore';
import { useNavigate } from 'react-router-dom';

export function AppHeader() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const projects = useProjectStore((s) => s.projects);
  const loadProject = useProjectStore((s) => s.loadProject);
  const navigate = useNavigate();

  return (
    <header style={{
      height: 56, background: 'var(--color-canvas)',
      borderBottom: '1px solid var(--color-hairline)',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '0 var(--space-lg)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-lg)' }}>
        <h1 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', margin: 0 }}>
          AI 分镜表
        </h1>
        <select
          onChange={(e) => { if (e.target.value) loadProject(e.target.value); }}
          style={{
            padding: '6px 12px', borderRadius: 'var(--rounded-md)',
            border: '1px solid var(--color-hairline)', fontSize: 13, background: 'white',
          }}
        >
          <option value="">选择项目...</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>{p.name}</option>
          ))}
        </select>
        <button style={{ ...smallBtn, background: 'var(--color-surface-card)' }}
          onClick={() => navigate(0)}>+ 新建</button>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
        <span style={{ fontSize: 13, color: 'var(--color-muted)' }}>{user?.displayName}</span>
        <button onClick={logout} style={{ ...smallBtn, background: 'transparent', color: 'var(--color-muted)' }}>
          退出
        </button>
      </div>
    </header>
  );
}

const smallBtn: React.CSSProperties = {
  padding: '4px 12px', height: 32, border: 'none',
  borderRadius: 'var(--rounded-md)', fontSize: 13, cursor: 'pointer',
};
```

- [ ] **步骤 2：创建 ScriptInputPanel（左栏）**

```tsx
import { useState } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { AspectRatioSelector } from '../common/AspectRatioSelector';

const creationTypes = [
  { value: 'movie', label: '电影/短片' },
  { value: 'short_video', label: '短视频/抖音' },
  { value: 'ad', label: '产品广告' },
  { value: 'drama', label: '短剧/剧情' },
  { value: 'documentary', label: '纪录/宣传' },
  { value: 'custom', label: '自定义' },
];

export function ScriptInputPanel() {
  const [creationType, setCreationType] = useState('movie');
  const [customDesc, setCustomDesc] = useState('');
  const [aspectRatio, setAspectRatio] = useState('16:9');
  const [scriptText, setScriptText] = useState('');
  const { currentProject, generateScript, isLoading } = useProjectStore();

  const handleGenerate = async () => {
    if (!currentProject || !scriptText.trim()) return;
    await generateScript(currentProject.id, scriptText, creationType, aspectRatio);
  };

  return (
    <div style={{
      width: 260, background: 'var(--color-canvas)',
      borderRight: '1px solid var(--color-hairline)',
      padding: 'var(--space-md)', display: 'flex', flexDirection: 'column', gap: 12,
    }}>
      <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)' }}>剧本输入</h2>

      <div>
        <label style={labelStyle}>创作类型</label>
        <select value={creationType} onChange={(e) => setCreationType(e.target.value)}
          style={selectStyle}>
          {creationTypes.map((t) => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </select>
      </div>

      {creationType === 'custom' && (
        <div>
          <label style={labelStyle}>自定义描述</label>
          <input value={customDesc} onChange={(e) => setCustomDesc(e.target.value)}
            style={inputStyle} placeholder="描述你的创作风格..." />
        </div>
      )}

      <div>
        <label style={labelStyle}>画幅</label>
        <AspectRatioSelector value={aspectRatio} onChange={setAspectRatio} />
      </div>

      <textarea
        value={scriptText}
        onChange={(e) => setScriptText(e.target.value)}
        placeholder="输入剧本内容...&#10;&#10;场景1：&#10;场景2："
        style={{
          flex: 1, minHeight: 200, borderRadius: 'var(--rounded-md)',
          border: '1px solid var(--color-hairline)', padding: 10,
          fontSize: 13, resize: 'none', background: 'white',
        }}
      />

      <button onClick={handleGenerate} disabled={isLoading || !scriptText.trim()}
        style={{
          padding: '10px', borderRadius: 'var(--rounded-md)',
          background: 'var(--color-primary)', color: 'var(--color-on-primary)',
          border: 'none', fontSize: 14, fontWeight: 500, cursor: 'pointer',
        }}>
        {isLoading ? '生成中...' : '生成分镜脚本'}
      </button>
    </div>
  );
}

const labelStyle: React.CSSProperties = { display: 'block', fontSize: 12, color: 'var(--color-muted)', marginBottom: 4 };
const selectStyle: React.CSSProperties = { width: '100%', padding: '6px 10px', borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', fontSize: 13, background: 'white' };
const inputStyle: React.CSSProperties = { width: '100%', padding: '6px 10px', borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', fontSize: 13, background: 'white' };
```

- [ ] **步骤 3：创建 AspectRatioSelector**

```tsx
const ratios = ['16:9', '9:16', '2.35:1', '4:3', '1:1'];

export function AspectRatioSelector({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
      {ratios.map((r) => (
        <button key={r} onClick={() => onChange(r)}
          style={{
            padding: '3px 8px', borderRadius: 'var(--rounded-sm)', fontSize: 11,
            border: `1px solid ${r === value ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
            background: r === value ? 'var(--color-canvas)' : 'white',
            color: r === value ? 'var(--color-primary)' : 'var(--color-body)',
            cursor: 'pointer',
          }}>
          {r}
        </button>
      ))}
    </div>
  );
}
```

- [ ] **步骤 4：创建 SceneCard（中栏基础）**

```tsx
import { SceneResponse } from '../../api/projects';
import { useProjectStore } from '../../stores/projectStore';

export function SceneCard({ scene, isSelected, onSelect }: {
  scene: SceneResponse;
  isSelected: boolean;
  onSelect: () => void;
}) {
  const generatingImage = useProjectStore((s) => s.generatingImage[scene.id]);
  const generatingVideo = useProjectStore((s) => s.generatingVideo[scene.id]);

  return (
    <div onClick={onSelect} style={{
      padding: 12, borderRadius: 'var(--rounded-md)',
      border: isSelected ? '2px solid var(--color-primary)' : '1px solid var(--color-hairline)',
      borderLeft: `3px solid ${isSelected ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
      background: isSelected ? 'var(--color-surface-card)' : 'white',
      cursor: 'pointer', marginBottom: 8,
    }}>
      <div style={{ fontWeight: 600, fontSize: 13, color: 'var(--color-ink)', marginBottom: 4 }}>
        分镜 {scene.sceneNumber}
      </div>
      <div style={{ fontSize: 12, color: 'var(--color-muted)', lineHeight: 1.4, marginBottom: 6 }}>
        {scene.scriptContent?.slice(0, 80) || '空分镜'}
      </div>
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 6 }}>
        {scene.cameraMovement && <Tag>{scene.cameraMovement}</Tag>}
        {scene.shotType && <Tag>{scene.shotType}</Tag>}
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        <button style={actionBtn(scene.imageStatus, generatingImage)} disabled={generatingImage}>
          {generatingImage ? '⏳ 生成中' : scene.imageStatus === 'completed' ? '完善图片' : '生成图片'}
        </button>
        <button style={actionBtn(scene.videoStatus, generatingVideo)} disabled={generatingVideo}>
          {generatingVideo ? '⏳ 生成中' : scene.videoStatus === 'completed' ? '完善视频' : '生成视频'}
        </button>
      </div>
    </div>
  );
}

function Tag({ children }: { children: string }) {
  return (
    <span style={{
      fontSize: 10, padding: '1px 6px', borderRadius: 'var(--rounded-sm)',
      background: 'var(--color-surface-card)', color: 'var(--color-muted)',
    }}>
      {children}
    </span>
  );
}

function actionBtn(status: string, generating?: boolean): React.CSSProperties {
  const isDone = status === 'completed';
  return {
    padding: '4px 8px', fontSize: 10, borderRadius: 'var(--rounded-sm)',
    border: isDone ? '1px solid var(--color-primary)' : 'none',
    background: isDone ? 'transparent' : 'var(--color-primary)',
    color: isDone ? 'var(--color-primary)' : 'var(--color-on-primary)',
    cursor: 'pointer',
  };
}
```

- [ ] **步骤 5：创建 SceneListPanel**

```tsx
import { useProjectStore } from '../../stores/projectStore';
import { SceneCard } from '../scene/SceneCard';

export function SceneListPanel() {
  const { scenes, selectedSceneId, selectScene, addScene, currentProject } = useProjectStore();

  return (
    <div style={{
      width: 340, background: 'var(--color-canvas)',
      borderRight: '1px solid var(--color-hairline)',
      padding: 'var(--space-md)', overflowY: 'auto',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', margin: 0 }}>分镜列表</h2>
        {currentProject && (
          <button onClick={() => addScene(currentProject.id)}
            style={{
              padding: '4px 12px', fontSize: 12, borderRadius: 'var(--rounded-md)',
              border: '1px solid var(--color-hairline)', background: 'white', cursor: 'pointer',
            }}>
            + 添加
          </button>
        )}
      </div>

      {scenes.length === 0 ? (
        <p style={{ color: 'var(--color-muted-soft)', fontSize: 13, textAlign: 'center', marginTop: 40 }}>
          暂无分镜，请输入剧本并点击"生成分镜脚本"
        </p>
      ) : (
        scenes.map((scene) => (
          <SceneCard
            key={scene.id}
            scene={scene}
            isSelected={selectedSceneId === scene.id}
            onSelect={() => selectScene(scene.id)}
          />
        ))
      )}
    </div>
  );
}
```

- [ ] **步骤 6：创建 PreviewPanel**

```tsx
import { useProjectStore } from '../../stores/projectStore';

export function PreviewPanel() {
  const { scenes, selectedSceneId } = useProjectStore();
  const scene = scenes.find((s) => s.id === selectedSceneId);

  if (!scene) {
    return (
      <div style={{
        flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: 'var(--color-muted-soft)', fontSize: 13,
      }}>
        选择左侧分镜查看预览
      </div>
    );
  }

  return (
    <div style={{ flex: 1, padding: 'var(--space-md)', overflowY: 'auto' }}>
      <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', marginBottom: 12 }}>
        预览 — 分镜 {scene.sceneNumber}
      </h2>

      {scene.imageUrl ? (
        <img src={scene.imageUrl} alt="生成预览"
          style={{ width: '100%', borderRadius: 'var(--rounded-md)', marginBottom: 12 }} />
      ) : (
        <div style={{
          width: '100%', height: 200, borderRadius: 'var(--rounded-md)',
          background: 'var(--color-surface-dark)', color: 'var(--color-on-dark-soft)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13,
        }}>
          {scene.imageStatus === 'generating' ? '⏳ 正在生成图片...' : '未生成图片'}
        </div>
      )}

      {scene.videoUrl && (
        <video src={scene.videoUrl} controls
          style={{ width: '100%', borderRadius: 'var(--rounded-md)', marginTop: 8 }} />
      )}

      {scene.imagePrompt && (
        <div style={{
          marginTop: 12, padding: 10, borderRadius: 'var(--rounded-md)',
          background: 'var(--color-surface-card)', fontSize: 12, lineHeight: 1.6,
        }}>
          <strong style={{ color: 'var(--color-muted)' }}>提示词：</strong>
          <p style={{ color: 'var(--color-body)', marginTop: 4 }}>{scene.imagePrompt}</p>
        </div>
      )}
    </div>
  );
}
```

- [ ] **步骤 7：创建 EditorPage**

```tsx
import { useEffect, useState } from 'react';
import { AppHeader } from '../components/layout/AppHeader';
import { ScriptInputPanel } from '../components/editor/ScriptInputPanel';
import { SceneListPanel } from '../components/editor/SceneListPanel';
import { PreviewPanel } from '../components/editor/PreviewPanel';
import { DraftRecoverBanner } from '../components/common/DraftRecoverBanner';
import { useProjectStore } from '../stores/projectStore';

export function EditorPage() {
  const { loadProjects, checkDraft, loadProject, currentProject } = useProjectStore();
  const [showDraftBanner, setShowDraftBanner] = useState(false);
  const [draftProject, setDraftProject] = useState<any>(null);

  useEffect(() => {
    loadProjects();
    checkDraft().then((draft) => {
      if (draft) {
        setDraftProject(draft);
        setShowDraftBanner(true);
      }
    });
  }, []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <AppHeader />
      {showDraftBanner && draftProject && (
        <DraftRecoverBanner
          projectName={draftProject.name}
          onRecover={() => { loadProject(draftProject.id); setShowDraftBanner(false); }}
          onDismiss={() => setShowDraftBanner(false)}
        />
      )}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        <ScriptInputPanel />
        <SceneListPanel />
        <PreviewPanel />
      </div>
    </div>
  );
}
```

- [ ] **步骤 8：创建 DraftRecoverBanner**

```tsx
export function DraftRecoverBanner({ projectName, onRecover, onDismiss }: {
  projectName: string;
  onRecover: () => void;
  onDismiss: () => void;
}) {
  return (
    <div style={{
      padding: '10px var(--space-lg)', background: 'var(--color-surface-card)',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      borderBottom: '1px solid var(--color-hairline)',
    }}>
      <span style={{ fontSize: 13, color: 'var(--color-body-strong)' }}>
        检测到未保存的草稿：<strong>{projectName}</strong>
      </span>
      <div style={{ display: 'flex', gap: 8 }}>
        <button onClick={onRecover} style={{
          padding: '4px 14px', borderRadius: 'var(--rounded-md)', fontSize: 12,
          background: 'var(--color-primary)', color: 'white', border: 'none', cursor: 'pointer',
        }}>
          恢复
        </button>
        <button onClick={onDismiss} style={{
          padding: '4px 14px', borderRadius: 'var(--rounded-md)', fontSize: 12,
          background: 'transparent', color: 'var(--color-muted)', border: '1px solid var(--color-hairline)', cursor: 'pointer',
        }}>
          忽略
        </button>
      </div>
    </div>
  );
}
```

- [ ] **步骤 9：验证**

```bash
cd AIStoryboardClient && npm run dev
```

访问 `http://localhost:5173/editor`，确认三栏布局可渲染。

- [ ] **步骤 10：Commit**

```bash
git add AIStoryboardClient/src/pages/EditorPage.tsx \
        AIStoryboardClient/src/components/
git commit -m "feat: implement editor three-panel layout with all components"
```

---

## 阶段七：完成集成

### 任务 7.1：端到端验证

- [ ] **步骤 1：启动后端**

```bash
cd AIStoryboardBackend && mvn spring-boot:run
```

验证：`curl http://localhost:8080/api/auth/login` 返回正常 JSON。

- [ ] **步骤 2：启动前端**

```bash
cd AIStoryboardClient && npm run dev
```

验证：浏览器打开 `http://localhost:5173`，确认登录→编辑器完整流程。

- [ ] **步骤 3：验证核心流程**

1. 登录（使用已有用户或注册新用户）
2. 新建项目
3. 输入剧本 → 生成分镜脚本
4. 选择分镜 → 生图 → 查看预览
5. 完善图片 → 编辑提示词 → 重新生成
6. 生视频 → 轮询状态
7. 草稿恢复（刷新页面后检查）

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "chore: final integration and verification"
```

---

## 待确认项

- [ ] Laozhang API Key 需填入 `.env` 文件
- [ ] `.env` 和 `application-local.yml` 已加入 `.gitignore`
- [ ] PostgreSQL `newworkflow` 数据库中存在有效的 `users` 表
- [ ] `users` 表中至少有一个测试用户（用于验证登录）

## 备注

1. `spring-dotenv` 版本 4.0.0：如果此版本不可用，需改为手动在 `StoryboardApplication` 的 `main()` 中加载 `.env`。
2. Lombok：如果实体类编译报错（缺少 getter/setter），确保 IDEA 安装了 Lombok 插件，或手动添加 getter/setter 方法。
3. AI 服务层使用 `java.net.http.HttpClient`（JDK 11+内置），无需额外 HTTP 客户端依赖。
4. MyBatis-Plus `spring-boot3-starter` 应与 Spring Boot 4 兼容（使用 Jakarta EE），如果不兼容则需降级 Spring Boot 到 3.x。
