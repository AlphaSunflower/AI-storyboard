# AI 分镜表 (AI Storyboard) 系统设计


## 一、项目定位

| 维度 | 决策 |
|------|------|
| 定位 | 内部工具，团队内部使用 |
| 与现有系统关系 | Node.js AI 画布项目的延伸子模块 |
| 用户体系 | 共享现有 Node.js 项目的用户表（newworkflow.public.users） |
| 认证方式 | 复用 JWT (HS256) + scrypt 密码哈希 |
| 部署 | 本地开发优先，后续再考虑服务器部署 |

---

## 二、技术栈

| 层级 | 选型 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 4 + JDK 21 |  |
| ORM | MyBatis-Plus 3.5.x | 需求指定 |
| JWT | jjwt 0.12.6 | 与 Node.js 项目 JWT 规范对齐 |
| 密码验证 | Bouncy Castle scrypt (N=16384, r=8, p=1, keylen=64) | 与 Node.js crypto.scrypt 默认参数对齐 |
| 数据库 | PostgreSQL，schema: newworkflow.public | 共享现有 Node.js 项目数据库 |
| 前端框架 | React 18 + TypeScript |  |
| 状态管理 | Zustand | 轻量，适合单页编辑器 |
| HTTP 客户端 | Axios | 拦截器支持 JWT 注入 |
| 构建工具 | Vite | 快速 HMR |
| UI 风格 | DESIGN.md (Anthropic token system) | 奶油底色 + 珊瑚暖色 + 深海军蓝 |
| AI 生成 | Laozhang Provider | 见 ai-models-and-providers.md |

### 依赖列表

**后端 (pom.xml):**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.0</version>        <!-- Spring Boot 4 -->
</parent>
<properties>
    <java.version>21</java.version>  <!-- JDK 21 -->
</properties>
```

关键依赖：
- `spring-boot-starter-web`
- `spring-boot-starter-security`
- `mybatis-plus-spring-boot3-starter`
- `jjwt-api / jjwt-impl / jjwt-jackson` (0.12.6)
- `bcprov-jdk18on` (1.78)
- `postgresql`
- `spring-boot-starter-validation`

### 配置加载

- `.env` + `application.yml`：通过 springboot3-dotenv 加载
- `application-local.yml`：本地开发默认值，加入 `.gitignore`
- JWT 密钥、AI Provider key 等敏感配置从环境变量或 `.env` 读取

---

## 三、项目结构

```
AI-storyboard/
├── AIStoryboardBackend/          # Spring Boot 4 后端
│   └── src/main/java/com/storyboard/
│       ├── config/               # SecurityConfig, JwtConfig, MyBatisPlusConfig
│       ├── controller/           # AuthController, ProjectController, StoryboardController, AIController
│       ├── service/              # 业务逻辑层（接口 + impl）
│       ├── mapper/               # MyBatis-Plus Mapper 接口
│       ├── entity/               # 数据实体
│       ├── dto/                  # 请求/响应 DTO
│       ├── security/             # JwtTokenProvider, JwtAuthenticationFilter, ScryptPasswordService
│       └── ai/                   # AI Provider 适配层
│           ├── LaozhangImageService
│           ├── LaozhangVideoService
│           └── LaozhangVisionService
│
├── AIStoryboardClient/           # React 18 + TypeScript 前端
│   └── src/
│       ├── pages/                # LoginPage, EditorPage
│       ├── components/           # ScriptInput, StoryboardCanvas, SceneCard, ImagePreview, VideoPreview
│       ├── stores/               # Zustand: authStore, projectStore, sceneStore
│       ├── api/                  # Axios 封装
│       └── styles/               # DESIGN.md tokens → CSS Variables
│
└── docs/
    ├── require.txt
    ├── ai-models-and-providers.md
    └── java-springboot-jwt-integration.md
```

---

## 四、数据库设计

所有表在 `newworkflow.public` schema 下。`users` 表已存在（不修改），新增 3 张业务表。

### 4.1 projects 表（项目）

```sql
CREATE TABLE IF NOT EXISTS projects (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id         TEXT NOT NULL REFERENCES users(id),
    name            TEXT NOT NULL DEFAULT '未命名项目',
    description     TEXT,
    creation_type   TEXT NOT NULL DEFAULT 'movie',  -- movie|short_video|ad|drama|documentary|custom
    custom_type_desc TEXT,                          -- creation_type=custom 时填写
    aspect_ratio    TEXT NOT NULL DEFAULT '16:9',   -- 16:9|9:16|2.35:1|4:3|1:1
    reference_image_url TEXT,
    script_text     TEXT,
    ai_model        TEXT NOT NULL DEFAULT 'gemini-3-flash-preview',
    status          TEXT NOT NULL DEFAULT 'draft',  -- draft|completed
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4.2 scenes 表（分镜）

```sql
CREATE TABLE IF NOT EXISTS scenes (
    id              TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    project_id      TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    scene_number    INTEGER NOT NULL,               -- 排序号
    script_content  TEXT,                           -- 分镜剧本原文
    image_prompt    TEXT,                           -- 生图提示词
    video_prompt    TEXT,                           -- 生视频提示词
    negative_prompt TEXT,
    camera_movement TEXT,                           -- 机位/运动
    shot_type       TEXT,                           -- 镜头类型（大全景/中景/特写等）
    sound_design    TEXT,                           -- 声音设计
    ai_model        TEXT,                           -- 此分镜选用的 AI 生成模型
    video_resolution TEXT,                          -- 视频分辨率
    duration        INTEGER,                        -- 视频时长（秒）
    image_url       TEXT,                           -- AI 生成图片 URL
    video_url       TEXT,                           -- AI 生成视频 URL
    image_status    TEXT NOT NULL DEFAULT 'pending', -- pending|generating|completed|failed
    video_status    TEXT NOT NULL DEFAULT 'pending', -- pending|generating|completed|failed
    image_task_id   TEXT,                           -- AI 图片生成 task ID
    video_task_id   TEXT,                           -- AI 视频生成 task ID
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_scenes_project ON scenes(project_id, scene_number);
```

### 4.3 scene_reference_images 表（分镜参考图）

```sql
CREATE TABLE IF NOT EXISTS scene_reference_images (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    scene_id    TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    image_url   TEXT NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0
);
```

---

## 五、API 设计

统一响应信封格式（与 Node.js 项目一致）：

```json
{ "code": 200, "message": "success", "data": {}, "timestamp": "..." }
```

### 5.1 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录（scrypt 验证密码）→ accessToken + refreshToken |
| POST | `/api/auth/register` | 注册（写入 users 表） |
| POST | `/api/auth/refresh` | 刷新 token |

### 5.2 项目管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/projects` | 当前用户的项目列表 |
| POST | `/api/projects` | 新建项目 |
| GET | `/api/projects/:id` | 项目详情（含所有 scenes） |
| PUT | `/api/projects/:id` | 更新/保存项目 |
| DELETE | `/api/projects/:id` | 删除项目 |

### 5.3 分镜管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/projects/:id/scenes` | 添加分镜（手动添加或 AI 批量生成） |
| PUT | `/api/scenes/:id` | 编辑单个分镜（提示词修改等） |
| DELETE | `/api/scenes/:id` | 删除分镜 |

### 5.4 AI 生成

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/generate-script` | 输入剧本 → AI 拆解为分镜列表 |
| POST | `/api/ai/generate-image` | 根据提示词 + 可选参考图 → 生图 |
| POST | `/api/ai/generate-video` | 图生视频 → 返回 taskId |
| GET | `/api/ai/task/:taskId` | 轮询视频生成任务状态 |
| GET | `/api/ai/models` | 可用模型列表（从配置读取） |

#### 5.4.1 generate-script 请求/响应

**请求：**
```json
{
    "projectId": "uuid",
    "scriptText": "场景1：清晨的城市街道...",
    "creationType": "movie",
    "customTypeDesc": "",
    "aspectRatio": "16:9",
    "model": "gemini-3-flash-preview",
    "referenceImageUrl": ""
}
```

根据 creationType 注入不同 System Prompt：

| 类型 | System Prompt 风格指令 |
|------|----------------------|
| movie | 电影化叙事、氛围渲染、视觉对比 |
| short_video | 快节奏、竖屏为主、3秒抓人 |
| ad | 品牌调性、卖点突出、光影质感 |
| drama | 情绪递进、角色刻画、叙事完整 |
| documentary | 稳重、旁白驱动、信息密度高 |
| custom | 使用 customTypeDesc 字段 |

**响应：**
```json
{
    "code": 200,
    "data": {
        "projectId": "uuid",
        "scenes": [
            {
                "id": "uuid",
                "sceneNumber": 1,
                "scriptContent": "...",
                "imagePrompt": "【镜头构图】大全景，低角度拍摄 → 【场景主体】...",
                "videoPrompt": "...",
                "negativePrompt": "...",
                "cameraMovement": "定镜",
                "shotType": "大全景",
                "soundDesign": "..."
            }
        ]
    }
}
```

每个 scene 的 image_prompt 格式：`【镜头构图】→ 【场景主体】→ 【环境细节/道具】→ 【光线与色彩】→ 【氛围情绪】→ 【画质/风格】`

#### 5.4.2 generate-image 请求/响应

**请求：**
```json
{
    "sceneId": "uuid",
    "prompt": "...",
    "model": "gpt-image-2",
    "size": "2K",
    "aspectRatio": "16:9",
    "referenceImages": ["https://...jpg"]
}
```

- model 默认 `gpt-image-2`，可选 `gemini-3-pro-image-preview`、`gpt-image-2-official`
- 后端根据 model 路由到正确的 Laozhang endpoint
- referenceImages：完善模式填入当前生成图 URL；重新生成模式为空数组

**响应（同步）：**
```json
{ "code": 200, "data": { "imageUrl": "...", "sceneId": "..." } }
```

#### 5.4.3 generate-video 请求/响应

**请求：**
```json
{
    "sceneId": "uuid",
    "prompt": "视频描述...",
    "model": "veo-3.1-fast",
    "resolution": "1080p",
    "duration": 8,
    "referenceImages": ["https://...jpg"]
}
```

前端别名 → 实际模型映射：

| 前端别名 | 实际模型 |
|----------|----------|
| veo-3.1-fast | veo-3.1-fast-generate-preview |
| veo-3.1 | veo-3.1-generate-preview |

- referenceImages 最多 3 张
- 后端调用 `POST /v1/videos`（multipart）创建任务

**响应（异步）：**
```json
{ "code": 200, "data": { "taskId": "...", "sceneId": "..." } }
```

**轮询 GET `/api/ai/task/:taskId`：**
```json
{
    "code": 200,
    "data": {
        "status": "processing",
        "videoUrl": null,
        "progress": "正在生成..."
    }
}
```

轮询间隔 5s，超时 10min。status 为 `completed` 时 videoUrl 有值。

---

## 六、前端编辑器设计

### 6.1 页面结构

| 页面 | 路由 | 说明 |
|------|------|------|
| 登录页 | `/login` | JWT 解析成功自动跳转编辑器 |
| 编辑器 | `/editor` | 单页三栏布局 |

### 6.2 编辑器三栏布局

```
┌──────────┬────────────────┬──────────────────────┐
│ 剧本输入  │   分镜列表      │   预览区              │
│ (240px)  │   (360px)      │   (自适应)            │
│          │                │                      │
│创作类型   │ 分镜1 ─────── │  🖼️ 图片/视频预览     │
│画幅选择   │ 分镜2 ─────── │                      │
│模型选择   │ 分镜3 ─────── │  提示词详情           │
│参考图上传 │ + 添加分镜     │                      │
│剧本输入框 │                │                      │
│          │                │                      │
│[生成分镜] │                │                      │
└──────────┴────────────────┴──────────────────────┘
```

### 6.3 分镜卡片

每个分镜卡片展示：
- 分镜编号、剧本内容摘要
- 机位/运动/镜头类型标签
- 按钮区（根据状态动态变化）

### 6.4 生图/生视频按钮状态机

| 状态 | 按钮 | 说明 |
|------|------|------|
| pending（未生成） | [生成图片/视频] | 调用 AI 接口 |
| generating（生成中） | [⏳ 生成中...] | 禁用，轮询中 |
| completed（已完成） | [完善] [重新生成] [📥下载] | 三个按钮 |
| failed（失败） | [重试] | 重新调用 |

### 6.5 "完善"与"重新生成"的区别

两者调用**同一 API**，区别在前端参数：

- **完善图片**：打开浮层 → 预设当前提示词（可编辑）+ 当前生成图作为参考图 → 调用 `POST /api/ai/generate-image`
- **重新生成**：直接用原提示词（不带当前生成图作为参考）→ 调用同一 API
- 完善视频同理：打开浮层预设当前视频 prompt + 当前图片作为参考

### 6.6 完善浮层

- 显示当前提示词（textarea 可编辑）
- 显示当前生成图/视频缩略图（自动作为参考图）
- 额外参考图上传区（最多 4 张，图片完善；最多 3 张含当前图，视频完善）
- [确认生成] / [取消] 按钮

### 6.7 草稿自动保存

- 触发：分镜内容变化后 3 秒 debounce
- 存储：后端 projects 表（status='draft'）
- 恢复：页面加载时检测 draft 项目 → 提示"检测到未保存的草稿，是否恢复？"

### 6.8 项目模块

- 项目列表（侧栏或下拉）
- 新建项目（弹窗填名称/描述）
- 重命名项目
- 保存项目（status: draft → completed）

---

## 七、错误处理

### 7.1 错误码体系

| HTTP | code | message | 场景 |
|------|------|---------|------|
| 401 | 40101 | 未授权 | Token 缺失/过期/无效 |
| 401 | 40102 | 用户名或密码错误 | 登录失败 |
| 403 | 40301 | 无权限访问 | 非本人项目 |
| 404 | 40401 | 资源不存在 | 项目/分镜不存在 |
| 400 | 40001 | 参数错误 | 校验失败 |
| 502 | 50201 | AI 服务响应超时 | Laozhang API 超时 |
| 502 | 50202 | AI 生成失败 | Laozhang API 返回错误 |
| 500 | 50000 | 服务器内部错误 | 未预期异常 |

### 7.2 前端错误处理层级

1. **Axios 拦截器**：401 自动跳转登录 + 清除 token；5xx 全局 Toast
2. **Zustand Store**：AI 失败 → scene 状态设为 failed → UI 展示重试按钮
3. **组件层**：表单校验提示、空状态引导

---

## 八、安全设计

1. **JWT 密钥**：accessToken 和 refreshToken 使用独立密钥，从环境变量读取
2. **密码哈希**：scrypt (N=16384, r=8, p=1, keylen=64)，与 Node.js 项目完全一致
3. **JWT 验证**：issuer 必须为 `"newworkflow-backend"`，typ 必须为 `"access"`
4. **API Key 隔离**：AI Provider API Key 只存在于后端配置中，前端不可见
5. **图片/视频代理**：后端从 Laozhang 下载后转为自有 URL，不暴露原始链接
6. **用户隔离**：所有项目/分镜查询按 user_id 过滤

---

## 九、测试策略

| 层级 | 工具 | 覆盖目标 |
|------|------|---------|
| 后端单元测试 | JUnit 5 + Mockito | JWT、密码验证、AI 参数构造（100%） |
| 后端集成测试 | @SpringBootTest + Testcontainers | Controller 全链路 |
| 前端组件测试 | Vitest + React Testing Library | 按钮状态机、草稿恢复 |
| 业务 CRUD | 集成测试 | 80%+ |

---

## 十、关键设计决策汇总

| # | 决策 | 原因 |
|---|------|------|
| 1 | 经典三层架构（非异步任务队列） | 内部工具，Laozhang 生图同步/生视频轮询够用 |
| 2 | 每个 scene 独立存 ai_model | 不同分镜可用不同模型生成 |
| 3 | "完善"和"重新生成"共用同一 API | 后端无状态，区别仅在前端参考图参数 |
| 4 | 生图同步 + 生视频异步轮询 | 与 Laozhang API 特性对齐 |
| 5 | AI 生成后下载到自有存储 | 避免暴露 Laozhang 原始 URL |
| 6 | Spring Boot 4 + JDK 21 | 用户指定 |
| 7 | Zustand 状态管理 | 轻量，适合单页编辑器状态复杂度 |
| 8 | 自定义 UI 组件（DESIGN.md tokens） | 遵循 Anthropic 设计规范 |
