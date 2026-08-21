# /docs 页面 ReactBits 增强计划

> **For Hermes:** 直接在本仓库按下方任务逐条实现即可（小改动，无需子代理分发）。

**Goal:** 在 `/docs`（`DocsPage.tsx`）再补几处 ReactBits 动画组件，进一步强化「技术感」。

**Architecture:** 复用仓库里已下载、但 docs 页尚未用到的 5 个 ReactBits 组件，按「技术感收益 / 风险」排序插入。零新增依赖（`motion`、`ogl`、`gsap` 均已在 package.json）。

**Tech Stack:** React 19 + Vite，ReactBits（SplashCursor / Particles / ParticleText / ElasticSlider / BounceCards），gsap，ogl，motion。

---

## 现状盘点

| 组件 | 仓库位置 | 当前使用处 | docs 页是否已用 |
|------|---------|-----------|----------------|
| SpecularButton | src/components | docs | ✅ 已用（nav + hero CTA） |
| TextType | src/components | docs | ✅ 已用（hero 标题） |
| CardSwap / Card | src/components | docs | ✅ 已用（核心能力速览） |
| Carousel | src/components | docs | ✅ 已用（Moon 能力示例） |
| WarpText | src/components | docs | ✅ 已用（footer 品牌） |
| **SplashCursor** | src/components | LoginPage | ❌ 未用 |
| **Particles** | src/components | LoginPage | ❌ 未用 |
| **ParticleText** | src/components | LoginPage | ❌ 未用 |
| **ElasticSlider** | src/components | PreviewPanel | ❌ 未用 |
| **BounceCards** | src/components | PreviewPanel | ❌ 未用 |

唯一要改的文件：`AIStoryboardClient/src/pages/DocsPage.tsx`（CSS 仅当 hero 无定位时补一条）。

---

## Task 1：SplashCursor — 全局流体光标拖尾（性价比最高）

**Objective:** 全页加一层 WebGL 流体光标拖尾，鼠标划过泛起珊瑚色涟漪。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`（import 区 + 根节点内首行）

**Step 1: 加 import**

```tsx
import SplashCursor from '../components/SplashCursor';
```

**Step 2: 渲染在 `.docs-page` 根节点最前**

```tsx
return (
  <div ref={rootRef} className="docs-page">
    <SplashCursor RAINBOW_MODE={false} COLOR="#cc785c" />
    <nav className="docs-nav">
```

**说明 / 取舍**
- 组件自身 `position: fixed; pointerEvents: none; zIndex: 1`，不挡点击、不挡滚动，作为背景 wash 层（LoginPage 已验证）。
- 配色用珊瑚 `#cc785c` 贴合设计系统；想要更「炫技」可改 `RAINBOW_MODE` 默认（true）彩虹模式，但会破坏 editorial 暖色调，默认选珊瑚。
- 组件内部 canvas `id="fluid"`，全页只允许一个实例（本计划只加一个，无冲突）。

---

## Task 2：Particles — Hero 区 WebGL 粒子背景（最大「技术感」）

**Objective:** Hero 标题背后挂一层缓慢漂浮、随鼠标位移的 WebGL 粒子场。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`（import + `<header className="docs-hero">` 内）
- Modify: `AIStoryboardClient/src/styles/docs.css`（hero 定位，仅当无 relative）

**Step 1: 加 import**

```tsx
import Particles from '../components/Particles';
```

**Step 2: hero 内插背景层（在 `.docs-hero__inner` 之前）**

```tsx
<header className="docs-hero">
  <div className="docs-hero__bg">
    <Particles
      particleCount={120}
      particleSpread={12}
      speed={0.15}
      particleColors={['#cc785c', '#e8a55a', '#faf9f5']}
      moveParticlesOnHover
      particleHoverFactor={0.5}
      alphaParticles
      particleBaseSize={120}
      sizeRandomness={1}
      cameraDistance={22}
    />
  </div>
  <div className="docs-hero__inner">
```

**Step 3: CSS 定位（`docs.css`，若 `.docs-hero` 尚无定位则补）**

```css
.docs-hero { position: relative; }
.docs-hero__bg { position: absolute; inset: 0; pointer-events: none; }
.docs-hero__inner { position: relative; z-index: 1; }
```

**说明 / 取舍**
- `Particles` 用 ogl 渲染，canvas 透明，落在 `.docs-hero__inner` 文字后面即可。
- `particleCount=120` 克制、不吃性能；`moveParticlesOnHover` 给交互感。
- 若 hero 已有 `position: relative` 则只补后两条；`.docs-hero__inner` 需 z-index 压住粒子。

---

## Task 3：ElasticSlider — demo 滑杆换成弹性弹簧滑杆

**Objective:** 把两个 demo 面板里 `原生 <input type="range">` 换成有弹簧回弹的 ElasticSlider，手感和真实编辑器一致（PreviewPanel 已用同款）。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`（import + 两处 range）

**Step 1: 加 import**

```tsx
import ElasticSlider from '../components/ElasticSlider';
```

**Step 2: `DemoImagePanel` 的「生成个数」（约 line 302）**

```tsx
{/* 替换 */}
<div style={pFieldRow}><span style={pFieldLabel}>生成个数</span><input type="range" min={1} max={4} defaultValue={1} style={{ flex: 1, accentColor: 'var(--color-primary)' }} /></div>

{/* 为 */}
<div style={pFieldRow}><span style={pFieldLabel}>生成个数</span><ElasticSlider defaultValue={1} startingValue={1} maxValue={4} isStepped stepSize={1} /></div>
```

**Step 3: `DemoVideoPanel` 的「时长(秒)」（约 line 363）**

```tsx
{/* 替换 */}
<div style={pFieldRow}><span style={pFieldLabel}>时长(秒)</span><input type="range" min={4} max={12} defaultValue={8} style={{ flex: 1, accentColor: 'var(--color-primary)' }} /></div>

{/* 为 */}
<div style={pFieldRow}><span style={pFieldLabel}>时长(秒)</span><ElasticSlider defaultValue={8} startingValue={4} maxValue={12} isStepped stepSize={1} /></div>
```

**说明 / 取舍**
- ElasticSlider 自带 CSS（组件内部 import），并输出一个 `<p class="value-indicator">` 数字，mock 里直接可用。
- 依赖 `motion/react`（已在 PreviewPanel 使用，已装）。
- 不改真实编辑器（那里已是 ElasticSlider），仅统一 demo 手感。

---

## Task 4：ParticleText — 品牌名粒子聚合（可选，低优先）

**Objective:** nav 品牌名或 footer 加「粒子聚合成字」的 ParticleText，hover 时字散开再聚回。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`

**方案 A（推荐）— footer 品牌替换 WarpText（line 1210）**

```tsx
import ParticleText from '../components/ParticleText';

{/* footer 内替换 WarpText */}
<ParticleText
  text="AlphaSunflower AI分镜"
  particleSize={2}
  density={3}
  color="#cc785c"
  highlightColor="#e8a55a"
  scatter={90}
  trigger="hover"
  fontSize={22}
  fontWeight={700}
  glow={false}
  style={{ width: 320, height: 48, margin: '0 auto' }}
/>
```

**方案 B — nav 品牌名（line 595，短文案更稳）**

```tsx
<a className="docs-nav__brand" href="/editor">
  <ParticleText
    text="AlphaSunflower"
    particleSize={1.5}
    density={3}
    color="#cc785c"
    highlightColor="#e8a55a"
    scatter={60}
    trigger="hover"
    fontSize={16}
    fontWeight={700}
    glow={false}
    style={{ width: 140, height: 22 }}
  />
  <span>AI分镜</span>
</a>
```

**说明 / 取舍**
- ParticleText 是 canvas，需显式 `width/height` 容器，中文「AI分镜」若一起入参可能字形采样偏弱，方案 B 把中文留在 `<span>` 里更稳。
- 与 WarpText 二选一，别两个文字特效叠在 footer 上。
- 影响面小，作为「锦上添花」排在 ElasticSlider 之后。

---

## Task 5：BounceCards — 成片扇形浏览（可选，需真实图片）

**Objective:** 「逐镜生成」或「产出素材」处放一组扇形散开、hover 挤开的成片缩略图。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`

**插入点建议：** 「逐镜生成图片与视频」section（`id="generate"`，约 line 933）的 params 网格与 mock 之间，加一个小画廊。

```tsx
import BounceCards from '../components/BounceCards';

<BounceCards
  images={['/src/assets/hero.png', ...]}   // 需 3~5 张真实成片图
  containerWidth={420}
  containerHeight={320}
  enableHover
  onImageClick={(url) => window.open(url, '_blank')}
/>
```

**说明 / 取舍**
- BounceCards 强依赖真实图片 URL，当前 docs 用渐变占位块；没真图时扇形只有空色块，效果打折。
- 现有素材只有 `src/assets/hero.png`；要好看需另备 3~5 张生成样图（可放进 `src/assets/`）。
- 因此列最后、可选：有图才值得做。

---

## Tests / 验证

```bash
cd AIStoryboardClient
npx tsc -p tsconfig.app.json --noEmit
npm run build
# 浏览器 http://localhost:5173/docs 逐项确认：
# 1. 光标划过有珊瑚涟漪；2. hero 粒子漂浮且不盖住标题文字；
# 3. 图片/视频 demo 滑杆可拖动且有回弹；4. 其余原有交互（CardSwap 轮换、Carousel 滑动、上传回显）不受影响。
```

## Risks / Tradeoffs

- **性能**：Particles(WebGL) + SplashCursor(流体 WebGL) 同屏，低端机可能掉帧。两个组件都不自查 `prefers-reduced-motion`。缓手：像现有 GSAP 块一样，在挂载前 `if (matchMedia('(prefers-reduced-motion: reduce)').matches)` 跳过 Task 1/2；或只上一个（SplashCursor 优先，开销更低）。
- **视觉一致性**：彩虹模式 / 多色粒子会冲淡珊瑚 editorial 风，默认全部锁珊瑚 `#cc785c` + 琥珀 `#e8a55a` + 米白 `#faf9f5`。
- **z-index**：SplashCursor `zIndex:1` 会被内容层盖住，属预期（做背景 wash）；hero 粒子需 `.docs-hero__inner` 抬到 `z-index:1` 之上。
- **ParticleText 中文采样**：中文入 `text` 字形采样可能不完整，中文文案留在普通 `<span>`。

## Open Questions

- Task 1 配色选珊瑚还是彩虹？（默认珊瑚，更贴设计系统）
- Task 5 是否有现成生成样图可用？没有则跳过。
- 是否顺带加一个「技术栈 / 架构」展示小节（Spring Boot 4 + React 19 + LLM 网关），用 CardSwap/BounceCards 做？——超出本次「加 reactbits」范围，需你确认。
