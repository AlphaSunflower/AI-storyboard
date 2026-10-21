# 项目库 · 产出素材按对话归档（一个对话一个文件夹）

日期：2026-10-18
状态：已实现

## 背景

项目库同时承担四件事：原始素材存储、给智能体上传参考素材、把资产组交给 Moon 约束角色/景物，
以及承接智能体与画布的生成结果。前三件事都以「项目 / 文件夹 / 资产组」为组织方式，
只有第四件（产出素材）此前是一个跨对话的平铺列表：

- `asset.library_assets` 只有 `source_type/source_id`，没有对话维度；
- 项目库「产出素材」只按 `origin=GENERATED` 过滤，无法回答「这张图是哪个对话生成的」；
- 生成回写事件其实已经带 `metadata.conversationId`，但落库时被丢弃。

结果是一个项目里多个对话的图片、视频、文档混在一起，既难找，也无法回到产出它的对话。

## 目标

1. **一个对话一个文件夹**：项目库「产出素材」根视图按对话分组成虚拟文件夹；
2. **对话内产出自动归位**：生成结果回写时携带 `conversationId`，直接落到对应文件夹；
3. **可以回到对话**：文件夹卡片和素材详情都能跳回 Moon 对话；
4. **不改动原始素材组织**：上传素材仍走项目目录，不因为这次改动多出一层对话文件夹。

## 设计

### 数据模型

`asset.library_assets` 增加一列：

```sql
source_conversation_id TEXT
```

- 只对 `source_type IN ('generation','canvas-generation')` 的产出写入；
- 上传/导入素材为 `NULL`，继续由 `library_asset_folders` 管理；
- 索引 `idx_library_assets_conversation(owner_user_id, owner_project_id, source_conversation_id, created_at DESC)`。

迁移 `asset-service/db/migration/V5__library_conversation_bucket.sql` 同时从
`public.agent_assets` 回填历史产出（`library_assets.source_id = agent_assets.id`），
不依赖 MoonAgent 重新跑 outbox。

**为什么是虚拟文件夹**：真实写入 `library_folders` 会让同一份素材同时出现在项目目录和对话目录，
并在对话重命名/删除时产生同步问题。对话文件夹只存在于「产出素材」视图，按
`source_conversation_id` 聚合得到。

### 回写链路

| 入口 | 改动 |
|------|------|
| MoonAgent `AssetOutputOutboxServiceImpl` | 已有 `metadata.conversationId`，沿用 |
| 前端「导入到项目库」（`importMediaToProjectLibrary`） | 新增可选 `conversationId`，写入 `metadata` |
| 聊天气泡拖拽到项目库 | `ChatMediaDragPayload` 增加可选 `conversationId` |
| 编辑器导入 | 不传对话，落到「未归属产出」 |
| `GenerationOutputServiceImpl` | 从 `metadata.conversationId` 取值写入 `source_conversation_id`；同名生成记录已存在时幂等补写空归属 |

### 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/library/outputs/conversations?projectId&q&type&tagIds&tagMode` | 按对话聚合：数量、图片/视频/文档数、封面、最多 4 张预览、最后生成时间 |
| GET | `/api/library/assets?origin=GENERATED&conversationId=...` | 进入某个对话文件夹后的素材列表；`conversationId=UNASSIGNED` 表示未归属 |

`AssetSummary` 增加 `conversationId` 字段（追加在末尾，保留旧构造器，避免跨服务调用点全量改动）。
对话标题不落资产库：标题真源在 MoonAgent，前端用 `GET /api/agent/conversations?projectId=...`
合并；对话已删除时退化为 `对话 · {id 前 8 位}`。

### 前端

- 「产出素材」根视图渲染对话卡片：预览拼图、标题、`共 N 项 · 图片 x · 视频 y`、最后生成时间、
  最近素材名；悬停显示「打开对话」；
- 卡片进入后复用现有素材网格与检查器，面包屑变为 `产出素材 / 对话标题`，并提供「打开对话」；
- 素材检查器显示「产出对话」入口；
- 搜索/类型/标签筛选继续作用于产出素材；空结果显示「清空筛选」，项目确实没有产出时给「去 Moon 生成」；
- 产出视图不接受外部文件拖入、不显示空白区右键菜单（避免把上传误当成对话产出）；
- 对话文件夹状态写入 URL `conversationId`，刷新/分享可恢复。

## 兼容与边界

- 历史产出在迁移时回填；无法回填的（画布或其他生产方没有 `agent_assets` 记录）进入「未归属产出」；
- 对话被删除后素材仍保留，文件夹以 ID 兜底显示，不级联删除；
- 上传素材、资产组、@ 引用、资产组快照的语义不变。

## 第二阶段：资产组使用视图

### 问题

项目库的资产组此前只能看到「组里有什么」，看不到「谁在用」：

- 资产组绑定关系存在 MoonAgent 的 `agent_conversation_asset_groups`，项目库读不到；
- 快照时间在 asset-service 的 `library_group_snapshots`，同样没有暴露；
- 用户必须先回到 Moon 对话，再从会话里绑定资产组，无法从项目库直接操作。

### 设计

**MoonAgent** 新增只读接口：

```
GET /api/agent/projects/{projectId}/asset-group-bindings
→ [{ groupId, conversationId, conversationTitle, snapshotId, groupVersion, target, boundAt }]
```

- 按 `(user_id, project_id)` 查会话，再按会话批量查绑定行；
- **不创建新快照**（会话级 `list` 会刷新快照，项目级只读不能有副作用）；
- 是否过期由前端比较 `binding.groupVersion` 与资产组当前 `version` 得出。

**asset-service** 新增只读接口：

```
GET /api/library/group-insights?projectId=...
→ [{ groupId, version, mediaCount, snapshotCount, lastSnapshotAt }]
```

- 项目视图连同本人的全局资产组一起返回，与素材列表 `includeGlobal` 口径一致；
- `lastSnapshotAt = max(library_group_snapshots.created_at)`，用于回答「最近一次冻结是什么时候」。

**前端**

- 资产组卡片直接显示 `N 个对话使用 · M 项素材`；
- 单击资产组卡片（检查器）和双击进入资产组（浏览区）都显示同一个 `GroupUsagePanel`：
  - 使用中的对话 chip（点击跳回对话）；
  - `快照过期` 标记（绑定版本落后于资产组当前版本）；
  - 「最近快照」时间；
  - 「绑定到所选对话」：下拉列出当前项目的全部对话，默认选中 Moon 当前打开的对话（标「当前」），
    也可以在项目库里直接改选其他对话后绑定；项目还没有对话时提示先去 Moon 创建；
  - 每个已绑定对话可单独解除绑定。
- 目标对话默认跟随 `agentStore.activeConversationId`，但它只是**默认值**：下拉框的数据源是
  `GET /api/agent/conversations?projectId=...`，所以刷新、换标签页或从项目库直接进入时，
  仍然可以选择任意本项目对话完成绑定。

### 验证

- MoonAgent：`ConversationAssetGroupServiceImplTest` 覆盖绑定行 → 对话标题/冻结版本映射、无会话时短路；
- asset-service：`AssetCatalogServiceImplTest` 覆盖 `listGroupInsights` 的项目归属校验与委托；
- 前端：`LibraryWorkspacePage.test.tsx` 覆盖卡片用量、快照过期标记、对话下拉改选与「绑定到所选对话」调用；
- 真实接口：8086 实例 `GET /api/library/group-insights` 返回成员数/快照数（本地实测 1 个组、4 项成员）。

## 第三阶段：重复合并与镜头版本堆叠

### 重复合并

- `library_blobs.sha256` 已唯一，按 `blob_id` 聚合 `library_media` 即可找出同一文件的多个素材；
- `GET /api/library/duplicates?projectId=` 返回分组；`POST /api/library/duplicates/merge`
  保留一份，把目录、标签、项目关联、资产组项和产出引用迁到保留项，其余进回收站；
- 只允许同项目、同范围、确实共享同一 blob 的素材合并，单次最多 50 份；
- 前端左侧「重复项」视图：分组卡片 + 合并弹窗（单选保留项、勾选要合并的副本）。

### 镜头版本堆叠

- `library_assets` 增加 `source_scene_id`，生成回写从 `metadata.sceneId` 落库；
  V6 迁移从 `public.agent_assets.scene_id` 回填历史产出；
- 同一对话 + 同一镜头的多次生成即一组版本；前端在产出对话文件夹里把 ≥2 份的镜头
  收成「镜头组 · N 版」卡片，单份素材仍按普通卡片展示；
- 点开版本组可查看全部版本，最多同时选两个并排对比；「当前版本」暂按最新创建时间隐式确定，
  后续如需要手工指定当前版本，再补 `is_current_variant` 或版本表；
- 回写幂等补写：同一生成记录后续带回 `conversationId/sceneId` 时，只补空值。

### 验证

- asset-service：`DuplicateServiceImplTest`（引用迁移、非同文件拒绝、空选择拒绝）、
  `GenerationOutputServiceImplTest`（sceneId 落库）；
- 前端：`LibraryWorkspacePage.test.tsx` 覆盖重复分组/合并、镜头版本堆叠与对比弹窗；
- 真实接口：8086 实例 `GET /api/library/duplicates` 返回 4 组重复；
  `GET /api/library/assets` 返回的产出已带 `sceneId`，本地库回填 86 条镜头归属、6 组多版本镜头。

## 第四阶段：挑片标记与保存视图

### 挑片标记

- `library_assets` 增加 `rating (0–5)`、`flag (pick/reject)`、`color_label`；
- `POST /api/library/assets/{id}/mark` 与 `POST /api/library/assets/bulk/mark` 整组覆盖三项标记，
  空值即清除；`rating` 夹在 0–5，旗标/色标做白名单校验；
- 挑片标记**不递增 `version`**：版本号只随名称/描述/媒体等真正的内容修订递增。标记是
  即时保存的 last-write-wins 标注，连续点星不应把卡片上的 v 号刷高，也不应让正在编辑的
  `expectedVersion` 被标记判成过期（回归：`LibraryRepositoryReferenceSqlTest`）；
- 素材列表、产出对话聚合都支持 `minRating / flag / colorLabel` 过滤；
- 前端筛选栏加评分/旗标/色标三个下拉；素材卡右上角显示色标、旗标和星级；
  检查器提供 5 星、已选/弃用、五色色标编辑。

### 保存视图

- `asset.library_saved_views` 保存 `filters JSONB / sort JSONB / view_mode / pinned`；
- 接口 `GET/POST /api/library/views`、`PUT/DELETE /api/library/views/{id}`；
  只允许项目库已有筛选键（q/type/tagIds/minRating/flag/colorLabel…），
  名称同项目唯一，服务端做长度与键白名单清洗；
- 前端左侧「我的视图」：列表、固定、删除；「＋」把当前筛选命名保存；
  点击视图恢复全部筛选条件与视图模式；「全部素材」一键清空。

### 验证

- asset-service：`AssetCatalogServiceImplTest`（mark 归一化、bulk mark 统计）、
  `SavedViewServiceImplTest`（过滤键清洗、同名/不存在处理）；
- 前端：`LibraryWorkspacePage.test.tsx` 覆盖保存视图应用筛选、检查器打星；
- 真实接口：8086 实例创建/列出/删除「验收视图」成功；mark 接口写入 `rating=5/pick/red`
  后读取一致，并已恢复测试素材原值；本地 `flyway_schema_history` 记录 V7 成功。

## 第五阶段：使用位置与未使用清理

### 使用位置

`GET /api/library/assets/{id}/usage` 汇总四类引用：

| 类型 | 数据源 | 匹配方式 |
|------|--------|----------|
| 分镜 | `public.scene_assets`（资产组）、`public.scene_reference_images`、`scenes.image_url/video_url` | 资产 ID 或 URL 路径 |
| 对话 | `public.agent_assets` + `public.conversations` | URL 路径或 `source_id` |
| 资产组 | `asset.library_group_items` | `member_asset_id`（排除资产组自身） |
| 画布 | `public.canvas_assets` + `public.morrow_canvases` | `library_asset_id` 或对象 URL |

- URL 统一去掉 query 和 origin 后比较，避免签名/端口差异；
- 业务表在 `public` schema，全部用 `to_regclass` 守卫：独立部署缺少这些表时退化为空引用，
  不让项目库主流程 500；
- 检查器显示「使用位置 · N」，分镜/资产组/画布按 chip 展示，对话 chip 可直接跳回 Moon。

### 未使用清理

- `GET /api/library/assets/unused?projectId=`：未被分镜、资产组、画布引用、且不属于任何资产组的素材；
- 前端左侧「未使用」视图：网格多选 + 「清理选中」，复用 `bulkTrash` 进回收站（3 天可恢复）；
- 生成产出也会出现在未使用里（它们可能只是对话产物、未被分镜采用），清理前由用户自行判断。

### 验证

- asset-service：`AssetCatalogServiceImplTest` 覆盖 usage 委托与未使用项目归属校验；
- 前端：`LibraryWorkspacePage.test.tsx` 覆盖使用位置展示、未使用多选清理；
- 真实接口：8086 实例对已引用资产组返回 `分镜 1 · 项目名`，未引用资产返回 `total=0`；
  未使用接口返回分页列表（本地项目 173 项）；
- 所有 public 表缺失时接口走空引用，不影响项目库浏览。

## 第六阶段：任务队列持久化与重试

- `libraryTaskStore` 改用 Zustand `persist` + `sessionStorage`，只持久化 `tasks / panelOpen`；
- 刷新时 `merge` 把 `queued / uploading / processing` 统一改为 `failed`，错误文案
  「页面刷新后任务中断，可重试」，不会留下永远转圈的假任务；
- 任务面板补齐到项目库工作台（此前只有旧版 LibraryPage 渲染），新增：
  - 全部 / 进行中 / 失败 三个筛选与计数；
  - 失败项单条「重试」、「重试全部失败」、「清理已完成 / 清理失败」；
  - 批次字段 `batchId / batchLabel`（为后续按批分组预留）。
- 重试分三类：
  - **AI 分析**：按 `assetId` 重新调用 `/library/assets/analysis`，状态回到 processing；
  - **URL 导入**：任务保存 `retryUrl / producer / mediaType`，按稳定幂等键
    `retry:{taskId}` 重新登记生成结果，不会因重复点击产生多份；
  - **直接上传**：原 `File` 只存在页面内存的 `Map` 中，当前会话内可重传；刷新后文件丢失，
    任务会明确提示「请重新选择文件上传」；
  - 目录树导入不保留确认令牌，重试时提示重新选择文件夹。
- 上传完成时释放内存中的 `File` 引用，避免大文件长期占内存。

### 验证

- 前端：`LibraryTaskQueue.test.tsx` 覆盖失败筛选与单条重试、批次标签；
  `LibraryWorkspacePage.test.tsx` 覆盖从任务面板重试失败分析并调用
  `requestAnalysis(['a1'])`；
- `libraryTaskStore.test.ts` 保持通过（persist 包装后状态 API 不变）；
- `npm run build` 成功。

## 第七阶段：缩略图、命令面板与渲染优化

### 缩略图

- `GET /api/library/media/thumbnails?path=/api/files/images/x.png&token=...&size=320`：
  复用 coverUrl 上已有的文件签名，asset-service 读 MinIO 原图、用 ImageIO 缩放后写
  `thumbnails/{sha256(objectKey:size)}.jpg` 缓存；再次请求直接命中缓存；
- 尺寸夹在 64–1024，原图上限 40MB；WebP/SVG 等没有内置解码器时回退原图；
- 端点加入 SecurityConfig 白名单（签名即授权），不要求浏览器额外带 JWT；
- 前端 `libraryThumbnailUrl(coverUrl, size)`：只改写 `/api/files/` 下的图片地址并保留 token，
  素材卡、产出对话卡、重复项、版本弹窗、未使用卡统一改用缩略图；详情/对比仍用原图。

### 命令面板

- `Ctrl/Cmd + K` 打开，输入即过滤三类结果：动作、保存视图、素材；
- 动作覆盖六个库视图、上传、新建目录/资产组、保存当前筛选、刷新、打开 Moon；
- 键盘 `↑/↓` 选择、`Enter` 执行、`Esc` 关闭；素材结果直接打开检查器，视图结果恢复筛选；
- 素材来源是当前已加载的 `allKnownAssets`，不额外请求；数据量大时最多展示 12 条匹配。

### 渲染优化

- 素材卡继续 `loading="lazy"`，并加 `content-visibility: auto` + `contain-intrinsic-size`，
  浏览器跳过视口外卡片的布局与绘制；
- 缩略图 + 分页（每页 100）+ 渲染跳过共同降低大目录首屏成本；
- 新增 `VirtualAssetGrid`：纯资产视图（无文件夹/资产组/版本堆叠）超过 40 项时启用，
  按容器宽度算列数、按 `rowHeight + gap` 算行，只挂载可视行 + 前后 3 行 overscan；
  滚动容器是 `.library-workspace__tree.is-folders`，监听 scroll / resize；
- 卡片仍由原 `renderAssetCard` 渲染，拖拽、多选、右键菜单、双击预览行为不变；
  混合文件夹/资产组的目录继续用普通网格，避免破坏既有交互。

### 验证

- 8086 实例实测：带签名的 jpg 封面请求缩略图返回 `200 image/jpeg`，8KB（原图缩到 256px），
  第二次命中缓存；
- 前端 `libraryMedia.test.ts` 覆盖签名改写与非图片/无签名回退；
- `LibraryCommandPalette.test.tsx` 覆盖过滤 + Enter 执行、点击素材结果；
- `VirtualAssetGrid.test.tsx` 用 200 项验证只挂载可视行（渲染数 < 200）；
- `LibraryWorkspacePage.test.tsx` 覆盖 `Ctrl+K` 打开面板并跳转「未使用」；
- 前端相关 5 个测试文件 **36 个通过**；`npm run build`、全 reactor 编译成功；asset-service **125 个测试通过**。

## 第八阶段：智能搜索（AI 描述 / 主色 / 找相似）

> 当前环境没有 pgvector，LLM 网关也没有 embeddings 路由，因此不做向量语义检索；
> 用「AI 描述 + pg_trgm 三元组」做轻量语义匹配，主色和相似度在服务端计算。

### 数据

- V8 增加 `library_assets.dominant_color`（`#rrggbb`）与 `pg_trgm` 扩展，
  `name / ai_description` 建 GIN trigram 索引；
- 图片缩略图首次生成时顺带提取主色：5 bit/通道量化后取最高频桶平均值，写回素材；
- `LibraryColorBackfillWorker` 每 10 分钟补 100 条历史素材主色，初始延迟 30s，失败跳过。

### 接口

`GET /api/library/assets/search?projectId=&q=&color=&similarTo=&limit=`

- **关键词**：`name / ai_description / description` 的 `ILIKE` + pg_trgm `%` 相似度，
  名称权重 2、AI 描述权重 1，按总分排序；
- **找相似**：取源素材同项目、同类型的 400 个候选，Java 侧对名称/AI 描述/描述做中文二元组
  Jaccard（名称权重 2），再加标签重叠分，排除源素材本身；
- **主色**：取有 `dominant_color` 的图片/视频候选（最多 500），按 RGB 平方距离排序。

### 前端

- 搜索栏新增「智能」开关；开启后输入关键词走服务端搜索，结果进入独立「智能搜索」视图；
- 智能条提供 10 个主色圆点、当前相似素材、清空条件；
- 素材检查器新增「找相似」，一键用当前素材的 AI 描述/标签找同类型素材；
- 主色结果为空时提示「主色由后台逐步补齐」，不假装有结果。

### 验证

- 真实接口（8086）：
  - `q=视角` 返回 5 条按相似度排序的名称命中；
  - `q=室内空间` 命中 AI 描述中的室内场景；
  - `similarTo=<视角1>` 返回 5 条同类型候选；
  - 先通过缩略图接口补齐主色后，`color=#5c645a` 返回对应素材；
- asset-service **128 个测试通过**（新增关键词、相似排序、颜色距离）；
- 前端相关 5 个测试文件 **46 个通过**（智能开关、主色搜索、检查器找相似）；
- `npm run build`、全 reactor 编译成功；本地 `flyway_schema_history` 记录 V8 成功。

## 验证

- `asset-service`：`GenerationOutputServiceImplTest`（对话归属写入/幂等补写）、
  `AssetCatalogServiceImplTest`（对话聚合与封面签名）；
- 前端：`LibraryWorkspacePage.test.tsx`（对话文件夹渲染、进入文件夹按 `conversationId` 拉取、打开对话入口）、
  `projectLibraryImport.test.ts`（`conversationId` 进入 metadata）、`projectLibraryDrag.test.ts`；
- `mvn -DskipTests compile`（全 reactor）、`npm run build`。
