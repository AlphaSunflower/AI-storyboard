# AI 资产库（人物/道具/场景）— 跨分镜一致性约束 设计文档

日期：2026-08-16
状态：待审查
涉及仓库：AIStoryboardBackend（主后端）；AILLMGateway（LLM 网关，本期零改动）；AIStoryboardClient（前端，暂缓）

## 1. 背景与目标

当前分镜脚本由一次 LLM 调用生成，参考图仅喂给「理解模型」提取风格，不追踪具体人物/道具；生图、生视频每个分镜独立（文生视频只吃各自 `videoPrompt`，图生视频只吃各自首帧图），无任何跨分镜一致性机制。结果：同一个人物/道具在不同分镜里外貌漂移，相邻分镜转场突兀。

本期建立 **AI 资产库**，用「图锁长相 + 文字锁构成」两层硬约束解决跨镜一致性：

1. **三类资产**：人物（character）/ 道具（prop）/ 场景（scene），每个资产 = 多图 + 文字卡（description）。
2. **两级作用域**：项目资产库（project_id 归属）∪ 用户全局资产库（project_id 为空）。
3. **分镜关联资产**：分镜声明本镜用了哪些人物/道具/场景。
4. **三处生成注入**：分镜脚本（约束剧情）、视频（资产图 → reference_image + 文字卡 → prompt）、图片（文字卡 → imagePrompt）。
5. **reference_image 限量**：H3 上限 9 张，按类型优先级截断。

## 2. 技术事实（已核实）

- MiniMax H3 `POST /v2/video_generation` 支持 `reference_image`（≤ 9 张）多模态参考；**与 `first_frame`（首帧图生视频）互斥**，不可混用。
- MiniMax 另有 `S2V-01` 主体参考模型（`subject_reference`，type=character，单张面部图）——人物资产专用通道，本期范围外。
- Veo 3 官方支持参考图 + 首帧/尾帧并存，但本项目网关的 Laozhang 渠道当前**只发纯文本**，`imageUrl` / `referenceImages` 均被丢弃（未实现字段翻译）。
- 网关 `VideoGatewayServiceImpl.createMinimax` 已实现 `referenceImages` → `reference_image` 翻译，且对「参考素材 + 首帧并存」返回 40001 互斥报错。**MiniMax 通道零改动即可用资产参考图。**
- 主后端 `FileStorageService.saveImage` 已支持 URL 下载 + base64 落 `uploads/images/`；`SceneReferenceImage` 表存在但基本未用（本设计不依赖它）。
- scenes 表已有 `image_prompt` / `video_prompt` 等列；迁移文件在 `src/main/resources/db/migration/`，V1~V6，无 Flyway，需 psql 手动应用。

## 3. 数据模型（主后端 PostgreSQL，V7 migration）

沿用现有风格：TEXT 主键 `gen_random_uuid()`、TIMESTAMPTZ、`ON DELETE CASCADE`。

```sql
-- V7__ai_asset_library.sql
CREATE TABLE IF NOT EXISTS assets (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id     TEXT NOT NULL,            -- 归属用户
    project_id  TEXT REFERENCES projects(id) ON DELETE CASCADE,  -- null=用户全局资产库；非空=项目资产库
    type        TEXT NOT NULL,            -- character / prop / scene
    name        TEXT NOT NULL,            -- 资产名（"阿伟"）
    description TEXT,                     -- 文字约束（外貌/外观/构成，注入用）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_assets_user_project ON assets(user_id, project_id);

CREATE TABLE IF NOT EXISTS asset_images (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    asset_id    TEXT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    url         TEXT NOT NULL,            -- /api/files/images/xxx.png
    sort_order  INTEGER NOT NULL DEFAULT 0,  -- 主图 = 最小 sort_order
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_asset_images_asset ON asset_images(asset_id, sort_order);

CREATE TABLE IF NOT EXISTS scene_assets (
    id          TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    scene_id    TEXT NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    asset_id    TEXT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_scene_assets_scene ON scene_assets(scene_id);
CREATE INDEX IF NOT EXISTS idx_scene_assets_asset ON scene_assets(asset_id);
```

实体字段：`created_at` / `updated_at` 用 `OffsetDateTime` + `@TableField(fill = INSERT / INSERT_UPDATE)`（timestamptz 映射铁律，勿用 LocalDateTime）。

## 4. 主后端改动（AIStoryboardBackend）

### 4.1 分层骨架（沿用 Controller 薄层 / Service 接口+impl / Mapper 隔离 规范）

- `entity/`：`Asset`、`AssetImage`、`SceneAsset`
- `mapper/`：`AssetMapper`、`AssetImageMapper`、`SceneAssetMapper`（均 extends `BaseMapper<T>`）
- `service/`：`AssetService`（接口 + `impl/AssetServiceImpl`）
- `controller/`：`AssetController`（薄层，`@RequiredArgsConstructor`，仅收参/校验/封装 `ApiResponse`）
- `dto/request`：`AssetCreateRequest`、`AssetUpdateRequest`、`SceneAssetsUpdateRequest`
- `dto/response`：`AssetVO`（含 images 列表）、`AssetImageVO`

### 4.2 API 端点（全部 JWT 鉴权，`/api/assets/**` 非白名单）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/assets` | 建资产（type/name/description/projectId?）|
| GET | `/api/assets?projectId=&type=` | 列表：项目资产（project_id=本项）∪ 用户全局资产（project_id IS NULL）；返回含图 |
| PUT | `/api/assets/{id}` | 改名 / 改文字约束 |
| DELETE | `/api/assets/{id}` | 删（DB 级联删图 + scene_assets）|
| POST | `/api/assets/{id}/images` | 上传图片（multipart，多张；存 `uploads/images/`，落 asset_images）|
| DELETE | `/api/assets/{id}/images/{imageId}` | 删图 |
| PUT | `/api/scenes/{id}/assets` | 覆盖式设置关联（body `{assetIds:[]}`）|
| GET | `/api/scenes/{id}/assets` | 查关联资产 |

归属校验（沿用现有 40401 防 IDOR 套路）：资产归属当前用户；项目资产额外校验 `project.userId == 当前用户`。资产不存在与无权访问统一 40401 同文案。

### 4.3 生成注入（核心）

**A. 约束分镜脚本**（`ScriptGenerationServiceImpl.generateScenes`）：

查「项目资产 ∪ 用户全局资产」，拼「设定集」文字块，塞进 system prompt（无资产则跳过，零回归）：

```
本片固定设定（所有分镜必须严格遵循，不得改变外貌/服装/道具外观/场景构成）：
- 人物【阿伟】：黑色短发、络腮胡、深绿色围裙、白衬衫
- 道具【手冲壶】：哑光黑细口、木质手柄
- 场景【咖啡店】：暖木吧台、黄铜吊灯、左侧晨光
```

**B. 视频生成**（`VideoGenerationServiceImpl.createVideoTask`）：

- 文字卡：场景关联资产（scene_assets → assets）的 `name + description` 拼进 prompt 前缀。
- 资产图：每资产取主图（`asset_images` sort_order 最小）1 张，按 type 优先级 **character > prop > scene** 排序，累计 ≤ `maxAssetReferenceImages`（=9，H3 硬上限），超限按优先级截断；资产图优先于场景手动参考素材，合并后 `referenceImages` 总数仍 ≤ 9。
- **互斥**：存在资产 referenceImages 时，不传 `imageUrl` 首帧（走 r2va 多模态参考）；无资产才走现有首帧图生视频。

**C. 图片生成**（`ImageGenerationServiceImpl.generateImage`）：

场景关联资产的文字卡拼进 `imagePrompt` 前缀（保证首帧图人物一致）。资产图不注入文生图（gpt-image-2 不吃参考图）；edits 图改图分支沿用现有参考图逻辑，暂不改。

### 4.4 注入实现方式

`AssetService` 暴露查询辅助方法供三个生成服务复用：

- `List<AssetVO> listForProject(userId, projectId)` —— 项目 + 全局资产
- `List<AssetVO> listForScene(sceneId)` —— 分镜关联资产（含图）
- `String buildSheetText(List<AssetVO>)` —— 拼设定集文字块
- `List<String> buildReferenceImages(List<AssetVO>)` —— 主图 + 优先级 + ≤9 截断

生成服务只依赖 `AssetService`，不直接碰 Mapper（保持分层）。

## 5. 网关改动（AILLMGateway）

**本期零改动。** MiniMax 通道的 `referenceImages → reference_image` 翻译与互斥校验已就绪（见 §2）。

Veo/Laozhang 通道补 `imageUrl`/`referenceImages` 字段翻译列为范围外（见 §9），需时再补。

## 6. 前端改动（AIStoryboardClient）

**本期暂缓**，仅留档：

- 资产库面板：左侧栏或独立页，三类型 tab（人物/道具/场景），资产卡（封面图 + 名称 + 文字卡），上传多图（点选主图）、编辑文字卡、删除。
- 分镜关联：预览面板或 SceneCard 加「关联资产」多选（从资产库勾选）。
- 生成时透传：无需前端额外逻辑（后端自动注入），仅在 UI 提示「本镜已注入 N 个资产」。

## 7. 校验与互斥

| 项 | 规则 |
|----|------|
| type 枚举 | character / prop / scene，非法 40001 |
| 图片格式/大小 | image/*，单张 ≤ 30 MB（与 H3 参考图一致），非法 40001 |
| 资产图片数量 | 每资产图数暂不硬限（参考图注入只用主图 1 张）|
| reference_image 上限 | 注入 ≤ 9，按 character > prop > scene 优先级截断 |
| r2va / i2v 互斥 | 有资产参考图 → 不传首帧（业务侧保证；网关 40001 兜底）|
| 归属 | 资产 user_id / project.userId 校验，无权 40401 |
| 空资产 | 无关联资产时注入逻辑跳过，与现状等价（零回归）|

## 8. 测试链路（每一步必须验证）

1. **后端编译**：`mvn compile -q`（JAVA_HOME 用 Windows 路径 `C:\\Program Files\\Java\\jdk-21`）。
2. **建表**：`V7__ai_asset_library.sql` 经 psql 应用到 newworkflow（手动）。
3. **资产 CRUD 冒烟**：起 8085 实例（SERVER_PORT env）+ 自签 JWT → POST 建三类资产 → GET 列表（项目+全局）→ POST 上传多图 → 归属校验（他人资产 40401）。
4. **关联冒烟**：PUT `/api/scenes/{id}/assets` 覆盖式设置 → GET 回读。
5. **注入冒烟**：
   - 分镜脚本：带资产项目调 generate-script，验证返回的 imagePrompt/videoPrompt 携带设定特征；无资产项目零回归。
   - 视频：带资产场景调 generate-video，验证 referenceImages 数量 ≤9、优先级截断、有参考图时不传首帧（网关桩/日志核对）。
   - 图片：带资产场景 generate-image，验证 imagePrompt 含文字卡。
6. **前端**：`npx tsc -p tsconfig.app.json --noEmit` + `npm run build`（本期无前端改动，仅回归编译）。
7. **回归**：Agent 对话链路（图生视频 /form/submit）不受影响；无资产分镜生成行为不变。

## 9. 范围外（留档）

- 前端资产库面板 + 分镜关联 UI（暂缓，后续单独计划）。
- Veo/Laozhang 网关渠道补 reference 字段翻译。
- MiniMax S2V-01 主体参考专用通道（人物面部单图锚定）。
- 资产多图全量注入（本期每资产仅主图 1 张；多图留作资产卡展示与未来多角度锚定）。
- 资产图片的自动去重/相似度校验（用户人工保证）。
