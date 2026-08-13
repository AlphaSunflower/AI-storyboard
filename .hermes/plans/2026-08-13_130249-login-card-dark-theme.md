# 登录表单与背景融合改造方案

> **For Hermes:** 单文件样式改造，无需 subagent，直接按任务顺序手改即可。

**Goal:** 消除登录卡片（纯白）在暗色粒子背景上的突兀感，让表单卡片与背景、顶部 ParticleText 导航同属一个暗色色调族。

**Architecture:** 把卡片从「白底 + ink 深字」改为设计文档里的「暗色产品面」（`product-mockup-card-dark`：surface-dark 底 + on-dark 字），一层 elevation 抬升 + 半透明发丝边框区分卡片与背景。

**Tech Stack:** React inline style（沿用现有 `var(--color-*)` 设计 token），不改依赖、不加组件。

---

## 根因诊断

`LoginPage.tsx` 当前：
- 页面背景 `--color-surface-dark`(#181715) + 珊瑚粒子 + SplashCursor + 顶部 ParticleText（珊瑚→琥珀渐变，暗色系）。
- 登录卡片却是 `background: 'white'`、标题 `--color-ink`、标签 `--color-muted`、输入框 `--color-canvas`（奶油白）+ `--color-hairline` 浅边框。

白卡片悬浮在暗色粒子背景上，明度反差极硬，且奶油色输入框又和暗背景二次冲突 → 观感「突兀、拼贴」。

## 方案选择（取舍）

| 方案 | 做法 | 取舍 |
|------|------|------|
| **A. 卡片转暗色面（推荐）** | 卡片改 `surface-dark-elevated`，文字转 on-dark 系，输入框转 `surface-dark-soft` | 与现有暗色粒子背景/导航自然融合；改动小、只动一个文件 |
| B. 全页翻转成奶油画布 | 背景改 `--color-canvas`，卡片保持白/奶油 | 与「暗色粒子 + SplashCursor + 暗色 ParticleText」的既有特效方向相悖，需连带重调粒子/光标/导航配色，工作量大 |

采用 **方案 A**。设计文档本就定义了暗色产品面（`product-mockup-card-dark`：`surface-dark` 底 + `on-dark` 字 + `surface-dark-elevated` 内面板），本页背景已在该语言里，把卡片并进去即可。

---

## 任务

### Task 1: 卡片容器转暗色 elevation

**Files:** `AIStoryboardClient/src/pages/LoginPage.tsx`（`ref={cardRef}` 的 div，约 101-112 行）

**改动** — 替换该 div 的 `style` 对象：

```tsx
style={{
  pointerEvents: 'auto',
  background: 'var(--color-surface-dark-elevated)',   // #252320，比页面背景高一级
  border: '1px solid rgba(250, 249, 245, 0.08)',      // 半透明发丝边框，替代浅色 hairline
  borderRadius: 'var(--rounded-lg)',
  padding: 'var(--space-xl)',
  maxWidth: 400,
  width: '100%',
  boxShadow: '0 12px 40px rgba(0, 0, 0, 0.4)',        // 暗对暗，需更强投影分离层级
}}
```

### Task 2: 标题转 on-dark

**Files:** 同上（`<h1>`，约 113-122 行）

`color: 'var(--color-ink)'` → `color: 'var(--color-on-dark)'`（#faf9f5 奶油白）。`--text-display-sm` 衬线保持。

### Task 3: 三处 label 标签转 on-dark-soft

**Files:** 同上（用户名 / 邮箱 / 密码 三个 `<label>`，约 126-132、145-151、163-169 行）

三处 `color: 'var(--color-muted)'` → `color: 'var(--color-on-dark-soft)'`（#a09d96）。

### Task 4: 输入框转暗色面

**Files:** 同上（文件末尾 `inputStyle`，约 241-249 行）

替换整个 `inputStyle`：

```tsx
const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '10px 14px',
  height: 40,
  border: '1px solid rgba(250, 249, 245, 0.12)',      // 暗色系半透明边框
  borderRadius: 'var(--rounded-md)',
  fontSize: 14,
  background: 'var(--color-surface-dark-soft)',       // #1f1e1b，比卡片再深半级
  color: 'var(--color-on-dark)',                      // 输入文字奶油白
};
```

> 现有三个输入框无 `placeholder`，无需处理 placeholder 色。若后续加 placeholder，需在 `index.css` 补 `input::placeholder { color: var(--color-on-dark-soft) }`。

### Task 5: 底部「没有账号？」文案转 on-dark-soft

**Files:** 同上（约 211-219 行的 `<p>`）

`color: 'var(--color-muted)'` → `color: 'var(--color-on-dark-soft)'`。「去注册/去登录」链接保持 `--color-primary`（珊瑚在暗底上足够醒目）。

### Task 6（可选，非必须）: 错误文案提亮 + 主按钮 hover

- 错误 `<p>` 的 `color: 'var(--color-error)'`（#c64545 在暗底偏暗）可提亮为 `#e06a5a`。
- 主按钮无 hover 态，可补 `:hover` → `--color-primary-active`（需改用 CSS 类或 onMouseEnter；内联不易写 hover）。二者均属「顺手优化」，不影响「突兀」修复，可先跳过。

---

## Files 汇总

- 修改：`AIStoryboardClient/src/pages/LoginPage.tsx`（唯一改动文件）

## Tests / 验证

无单测（纯样式）。验证：

```bash
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```

预期：exit 0。视觉需 `npm run dev` 亲看：卡片应沉入暗背景、文字/输入框不再发白跳脱，与顶部 ParticleText 导航同色族。

## Risks / 开放问题

1. **对比度**：`--color-on-dark-soft`(#a09d96) 在 #252320 上约 4.6:1，label 属次要文本可接受；主文字用 `--color-on-dark`(#faf9f5)。
2. **浏览器 autofill**：暗色输入框若触发浏览器自动填充，会盖一层浅色/黄色底，破坏观感。若出现，再在 `index.css` 加 `input:-webkit-autofill { -webkit-box-shadow: 0 0 0 40px var(--color-surface-dark-soft) inset; -webkit-text-fill-color: var(--color-on-dark); }`。
3. **注册态额外字段**（用户名）已一并覆盖（Task 3/4 对三处 label + 共用 inputStyle 生效）。
4. 若你觉得暗色卡片还不够「品牌」，可再给标题「AI 分镜表」改成珊瑚色 `--color-primary` 作为点缀——这是审美分歧点，留待你看第一版效果后定。
