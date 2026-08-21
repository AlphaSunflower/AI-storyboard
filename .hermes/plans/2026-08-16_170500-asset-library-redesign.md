# AI 资产库前端重构 + 图片文件名持久化 实现计划

> **For Hermes:** 逐任务实现，每步跑验证命令确认后再进下一步。

**Goal:** 按用户反馈重构资产库前端：列表式展示 + 工作台（左列表右预览）+ 新建/编辑弹窗 + DepthCarousel 图片展示 + 图片原名持久化 + 单删除按钮。

**Architecture:** 后端 `asset_images` 表补 `file_name` 列存上传原名；前端重写 `AssetLibraryPanel`（列表视图 ↔ 工作台视图两态），集成 React Bits `DepthCarousel`（gsap 依赖已装），图片删除改为"单按钮删当前聚焦图"。

**Tech Stack:** Spring Boot 4 + MyBatis-Plus（后端）；React 19 + TS + Zustand + GSAP（前端）。

---

## 用户反馈逐条落点（理解确认）

| 反馈 | 落点 |
|------|------|
| 新建资产弹新窗口，填名称+文字约束+上传相片 | Task 4：新建弹窗（type/name/description/作用域 + 多图上传，创建后逐张 uploadImage） |
| 要有编辑资产 | Task 5：编辑弹窗（name/description，调 `assetApi.update`） |
| 列表页一行一资产（图/名称/文字约束/操作），悬浮动画 | Task 3：竖向列表 + hover 悬浮特效 |
| 点击后工作台（左列表+右预览） | Task 6：view 状态机 `list ↔ workbench` |
| 图片存原名，不显示"图1图2" | Task 1：后端 `file_name` 列 + Task 6 轮播标注当前文件名 |
| 图片用 DepthCarousel | Task 2：集成组件 + Task 6 使用 |
| 单删除按钮删当前图 | Task 6：`onChange` 跟踪 index，单按钮删 `images[index]` |

---

## Task 1：后端 `asset_images` 加 `file_name` 列

**Files:**
- 创建：`AIStoryboardBackend/src/main/resources/db/migration/V8__asset_image_filename.sql`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/entity/AssetImage.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/dto/response/AssetImageVO.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/impl/AssetServiceImpl.java`

**Step 1：V8 migration**
```sql
-- V8__asset_image_filename.sql
-- 资产图片存上传原始文件名（DepthCarousel 展示用）
ALTER TABLE asset_images ADD COLUMN file_name VARCHAR(255);
```

**Step 2：实体加字段**
`AssetImage.java` 加 `private String fileName;`（`url` 之后）。

**Step 3：VO 加字段**
`AssetImageVO.java` record 加 `String fileName`（`url` 之后）。

**Step 4：Service 存原名 + 映射**
`AssetServiceImpl.uploadImage` 里 `img.setFileName(file.getOriginalFilename());`；
`loadImages` 映射 `new AssetImageVO(i.getId(), i.getUrl(), i.getSortOrder(), i.getFileName())`；
`uploadImage` 返回值 `new AssetImageVO(img.getId(), url, img.getSortOrder(), img.getFileName())`。

**Step 5：验证**
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
# 预期 EXIT=0
PGPASSWORD=123456 psql -h localhost -U postgres -d newworkflow \
  -c "ALTER TABLE asset_images ADD COLUMN IF NOT EXISTS file_name VARCHAR(255);"  # 或直接 -f V8
```

---

## Task 2：集成 React Bits `DepthCarousel`

**Files:**
- 创建：`AIStoryboardClient/src/components/DepthCarousel.tsx`（用户提供的组件源码，原样拷贝）
- 创建：`AIStoryboardClient/src/components/DepthCarousel.css`（用户提供的 CSS，原样拷贝）

**注意：** `object-fit: cover` 会裁图，与用户"图片完整显示 contain"偏好冲突——见文末 Open Questions，默认保留 cover（用户点名要 DepthCarousel 样式）。

**验证：** `cd AIStoryboardClient && node node_modules/typescript/bin/tsc -p tsconfig.app.json --noEmit`（预期 0 错误）。

---

## Task 3：前端类型 + API 补 `fileName`

**Files:**
- 修改：`AIStoryboardClient/src/api/assets.ts`

`AssetImage` 接口加 `fileName: string;`。其余 API 方法（`update` 已存在=编辑，`uploadImage` 已存在）不动。

---

## Task 4：新建资产弹窗（名称 + 文字约束 + 上传相片）

**Files:**
- 修改：`AIStoryboardClient/src/components/asset/AssetLibraryPanel.tsx`

弹窗字段：类型（character/prop/scene 三选一）、名称、文字约束、作用域（项目/全局）、多图选择（`<input type=file multiple accept=image/*>`）。

提交流程：`assetApi.create({type,name,description,projectId})` → 拿到 asset.id → 逐张 `assetApi.uploadImage(asset.id, file)` → 关弹窗 → 刷新列表。

---

## Task 5：编辑资产弹窗

**Files:**
- 修改：`AIStoryboardClient/src/components/asset/AssetLibraryPanel.tsx`

弹窗字段：名称、文字约束（预填当前值）。提交 `assetApi.update(asset.id, {name, description})` → 刷新。

---

## Task 6：重写 AssetLibraryPanel 主体（列表 + 工作台 + DepthCarousel + 单删除）

**Files:**
- 修改：`AIStoryboardClient/src/components/asset/AssetLibraryPanel.tsx`（整体重写）

**两态状态机：**
```tsx
type View = 'list' | 'workbench';
const [view, setView] = useState<View>('list');
const [selectedId, setSelectedId] = useState<string | null>(null);
const selected = assets.find(a => a.id === selectedId) ?? null;
```

**列表视图（view='list'）：** 竖向列表，一行一资产：
```
[缩略图]  [名称 + 文字约束(截断)]   [📷上传  ✏️编辑  🗑删除]
```
hover 悬浮特效（轻量 GSAP：`gsap.to(el, { y:-2, scale:1.01, boxShadow })` onMouseEnter，反向 onMouseLeave；或 CSS transition——默认 CSS，见 Open Questions）。点整行 `setSelectedId(id); setView('workbench')`。

**工作台视图（view='workbench'）：** 左窄列表（同列表项，选中高亮，点选切换 selectedId）+ 右预览面板：
```tsx
const [currentIndex, setCurrentIndex] = useState(0);
<div style={{ height: 420, position: 'relative' }}>
  <DepthCarousel
    items={selected.images.map(img => ({ image: assetUrl(img.url), alt: img.fileName }))}
    onChange={(i) => setCurrentIndex(i)}
    depth={220} spread={90} tilt={22} perspective={1400}
    visibleCards={4} falloff={0.2} blur={6} loop
  />
</div>
<div>当前：{selected.images[currentIndex]?.fileName ?? ''}</div>
{/* 单删除按钮：删当前聚焦图 */}
<button onClick={() => deleteImage(selected.id, selected.images[currentIndex].id)}
        disabled={selected.images.length === 0}>
  删除当前图片
</button>
<button onClick={() => setEditAsset(selected)}>编辑资产</button>
<button onClick={() => setView('list')}>← 返回列表</button>
```

**删除图片后边界：** `currentIndex` 若越界则 clamp 到 `images.length - 1`。

**GSAP：** 面板入场 `back.out`、列表新增项 stagger、工作台切换时预览面板淡入（沿用已注册的 useGSAP）。

---

## Task 7：验证

```bash
# 后端
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
# 前端（真实类型检查，规避 solution-style 假绿）
cd AIStoryboardClient && node node_modules/typescript/bin/tsc -p tsconfig.app.json --noEmit
npm run build
```

运行时冒烟（沿用 8085 + 自签 JWT 流程）：上传带文件名图片 → `GET /assets` 确认 `images[].fileName` 返回原名；前端手工：列表悬浮、新建弹窗、工作台 DepthCarousel 切换、单删除删当前图。

---

## Risks / Tradeoffs / Open Questions

1. **DepthCarousel `object-fit: cover` 会裁图**，与用户"图片完整显示 contain"偏好冲突。默认保留 cover（用户点名 DepthCarousel），如需完整显示需改 CSS 为 `contain`（会出现留白）——待用户确认。
2. **"新窗口"= 页面内弹窗**（SPA 惯例，与现有 rename/delete 弹窗一致），非浏览器新窗口——如确要 `window.open` 新窗口，需额外传参，默认按弹窗实现。
3. **hover 悬浮特效**用 CSS transition 还是 GSAP：CSS 更轻（多列表项性能好），GSAP 更"实"。默认 CSS（`transform + box-shadow` transition），如要 GSAP 换 onMouseEnter/Leave。
4. 后端 `file_name` 为空的历史图片（V8 前的数据）展示时回退 `file` 或空串，不崩溃。
5. 编辑资产是否要顺带"换图/删图排序"——本次只做名称/文字约束编辑，图片管理走工作台。
