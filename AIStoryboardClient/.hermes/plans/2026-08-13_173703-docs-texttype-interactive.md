# Docs 页面增强实现计划：TextType 打字标题 + 交互式示意窗口

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 把 /docs 使用指南页从「静态展示」升级为「有打字动画 + 可交互的示意窗口」，让公司内部用户能照着真实界面练习操作。

**Architecture:** 纯前端、单页改动。引入 React Bits 的 `TextType` 打字组件（gsap 驱动）替换 Hero 主标题；把 DocsPage 里 6 个 `MockWindow` 的静态 `<div>` 片段升级为带本地 state 的交互式小部件（可点击按钮、可传图、可输入、可切换项目/保存）。

**Tech Stack:** React 19 + TypeScript（verbatimModuleSyntax 开启）+ gsap 3.15（已装，含 @gsap/react）+ 现有 tokens.css 设计规范。

---

## 现状与约束（已核实）

- 文件：`AIStoryboardClient/src/pages/DocsPage.tsx`（当前 36KB，Hero 标题为 `<h1 className="docs-hero__title">把故事，变成一帧帧画面</h1>`）
- 样式：`AIStoryboardClient/src/styles/docs.css`（tokens：`--color-primary #cc785c`、`--color-canvas #faf9f5`、`--font-display` serif、`--rounded-*`）
- `gsap`、`@gsap/react` 已在 `package.json` 依赖中，**无需新装依赖**（TextType 只依赖 gsap）。
- tsconfig 开启 `verbatimModuleSyntax`：类型必须 `import type`（此前 SpecularButton 已踩过此坑）。
- 现有 Hero 入场动画：`gsap.fromTo('.docs-hero__title', …)` 会与 TextType 打字冲突，需移除该条。

---

## Task 1: 创建 TextType 组件（适配本项目 tsconfig）

**Objective:** 落地 React Bits `TextType` 组件，适配 `verbatimModuleSyntax` 与 Vite（去 Next.js `'use client'`）。

**Files:**
- Create: `AIStoryboardClient/src/components/TextType.tsx`
- Create: `AIStoryboardClient/src/components/TextType.css`

**Step 1: 写组件（完整、可直接粘贴）**

```tsx
import { useEffect, useRef, useState, createElement, useMemo, useCallback, type ElementType, type ReactNode, type HTMLAttributes } from 'react';
import { gsap } from 'gsap';
import './TextType.css';

interface TextTypeProps {
  className?: string;
  showCursor?: boolean;
  hideCursorWhileTyping?: boolean;
  cursorCharacter?: string | ReactNode;
  cursorBlinkDuration?: number;
  cursorClassName?: string;
  text: string | string[];
  as?: ElementType;
  typingSpeed?: number;
  initialDelay?: number;
  pauseDuration?: number;
  deletingSpeed?: number;
  loop?: boolean;
  textColors?: string[];
  variableSpeed?: { min: number; max: number };
  onSentenceComplete?: (sentence: string, index: number) => void;
  startOnVisible?: boolean;
  reverseMode?: boolean;
}

const TextType = ({
  text,
  as: Component = 'div',
  typingSpeed = 50,
  initialDelay = 0,
  pauseDuration = 2000,
  deletingSpeed = 30,
  loop = true,
  className = '',
  showCursor = true,
  hideCursorWhileTyping = false,
  cursorCharacter = '|',
  cursorClassName = '',
  cursorBlinkDuration = 0.5,
  textColors = [],
  variableSpeed,
  onSentenceComplete,
  startOnVisible = false,
  reverseMode = false,
  ...props
}: TextTypeProps & HTMLAttributes<HTMLElement>) => {
  const [displayedText, setDisplayedText] = useState('');
  const [currentCharIndex, setCurrentCharIndex] = useState(0);
  const [isDeleting, setIsDeleting] = useState(false);
  const [currentTextIndex, setCurrentTextIndex] = useState(0);
  const [isVisible, setIsVisible] = useState(!startOnVisible);
  const cursorRef = useRef<HTMLSpanElement>(null);
  const containerRef = useRef<HTMLElement>(null);

  const textArray = useMemo(() => (Array.isArray(text) ? text : [text]), [text]);

  const getRandomSpeed = useCallback(() => {
    if (!variableSpeed) return typingSpeed;
    const { min, max } = variableSpeed;
    return Math.random() * (max - min) + min;
  }, [variableSpeed, typingSpeed]);

  const getCurrentTextColor = () => {
    if (textColors.length === 0) return 'inherit';
    return textColors[currentTextIndex % textColors.length];
  };

  useEffect(() => {
    if (!startOnVisible || !containerRef.current) return;
    const observer = new IntersectionObserver(
      entries => entries.forEach(entry => { if (entry.isIntersecting) setIsVisible(true); }),
      { threshold: 0.1 }
    );
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [startOnVisible]);

  useEffect(() => {
    if (showCursor && cursorRef.current) {
      gsap.set(cursorRef.current, { opacity: 1 });
      gsap.to(cursorRef.current, {
        opacity: 0, duration: cursorBlinkDuration, repeat: -1, yoyo: true, ease: 'power2.inOut',
      });
    }
  }, [showCursor, cursorBlinkDuration]);

  useEffect(() => {
    if (!isVisible) return;
    let timeout: ReturnType<typeof setTimeout>;
    const currentText = textArray[currentTextIndex];
    const processedText = reverseMode ? currentText.split('').reverse().join('') : currentText;

    const executeTypingAnimation = () => {
      if (isDeleting) {
        if (displayedText === '') {
          setIsDeleting(false);
          if (currentTextIndex === textArray.length - 1 && !loop) return;
          if (onSentenceComplete) onSentenceComplete(textArray[currentTextIndex], currentTextIndex);
          setCurrentTextIndex(prev => (prev + 1) % textArray.length);
          setCurrentCharIndex(0);
          timeout = setTimeout(() => {}, pauseDuration);
        } else {
          timeout = setTimeout(() => setDisplayedText(prev => prev.slice(0, -1)), deletingSpeed);
        }
      } else if (currentCharIndex < processedText.length) {
        timeout = setTimeout(() => {
          setDisplayedText(prev => prev + processedText[currentCharIndex]);
          setCurrentCharIndex(prev => prev + 1);
        }, variableSpeed ? getRandomSpeed() : typingSpeed);
      } else if (textArray.length >= 1) {
        if (!loop && currentTextIndex === textArray.length - 1) return;
        timeout = setTimeout(() => setIsDeleting(true), pauseDuration);
      }
    };

    if (currentCharIndex === 0 && !isDeleting && displayedText === '') {
      timeout = setTimeout(executeTypingAnimation, initialDelay);
    } else {
      executeTypingAnimation();
    }
    return () => clearTimeout(timeout);
  }, [currentCharIndex, displayedText, isDeleting, typingSpeed, deletingSpeed, pauseDuration,
      textArray, currentTextIndex, loop, initialDelay, isVisible, reverseMode, variableSpeed, onSentenceComplete]);

  const shouldHideCursor = hideCursorWhileTyping && (currentCharIndex < textArray[currentTextIndex].length || isDeleting);

  return createElement(
    Component,
    { ref: containerRef, className: `text-type ${className}`, ...props },
    <span className="text-type__content" style={{ color: getCurrentTextColor() || 'inherit' }}>{displayedText}</span>,
    showCursor && (
      <span ref={cursorRef} className={`text-type__cursor ${cursorClassName} ${shouldHideCursor ? 'text-type__cursor--hidden' : ''}`}>
        {cursorCharacter}
      </span>
    )
  );
};

export default TextType;
```

**Step 2: 写 CSS（完整）**

```css
.text-type {
  display: inline-block;
  white-space: pre-wrap;
}
.text-type__cursor {
  margin-left: 0.25rem;
  display: inline-block;
  opacity: 1;
  color: var(--color-primary, #cc785c);
}
.text-type__cursor--hidden {
  display: none;
}
```

**Step 3: 验证** — `cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`，预期 0 错误（若报 `ElementType/ReactNode` 类型导入错误，确认已 `import type`）。

---

## Task 2: Hero 标题换成 TextType 打字效果

**Objective:** 主标题「把故事，变成一帧帧画面」逐字打出，serif 排版不变。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`

**Step 1: 引入组件**

```tsx
import TextType from '../components/TextType';
```

**Step 2: 替换标题**

```tsx
{/* 原：<h1 className="docs-hero__title">把故事，变成一帧帧画面</h1> */}
<TextType
  as="h1"
  className="docs-hero__title"
  text="把故事，变成一帧帧画面"
  typingSpeed={60}
  initialDelay={200}
  pauseDuration={4000}
  loop={false}
  showCursor
  cursorCharacter="|"
/>
```

> 注意：`docs-hero__title` 的 serif/字号/颜色由 CSS 承接；TextType 只负责逐字渲染。用 `loop={false}` 让标题打完即停（保留光标闪烁）。

**Step 3: 移除冲突的入场动画** — 在 `useGSAP` 回调里删除这一条：

```tsx
// 删除：
gsap.fromTo('.docs-hero__title', { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.6, ease: 'power2.out', delay: 0.08 });
```

保留 eyebrow / sub / cta 三条入场。

**Step 4: 验证** — tsc + build 通过；浏览器 `/docs` 标题逐字打出、光标闪烁。

---

## Task 3: 其他文字动画（克制）

**Objective:** 给分节小标题（coral eyebrow）加逐字/淡入，能力卡片与步骤点加 hover 微交互。不加夸张动画。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`
- Modify: `AIStoryboardClient/src/styles/docs.css`

**Step 1: CSS hover 微交互（加到 docs.css 末尾）**

```css
.docs-ability, .docs-step, .docs-param, .docs-point {
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.docs-ability:hover, .docs-step:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 20px rgba(20, 20, 19, 0.08);
}
.docs-param:hover {
  border-color: var(--color-primary);
}
```

**Step 2（可选，非必须）** — 若要让分节 eyebrow 逐字显现，用已注册的 `SplitText`（LoginPage 已用同款）：

```tsx
// 在 useGSAP 内对 [data-reveal] 的 .docs-section__eyebrow 做 char stagger，onComplete 后 split.revert()
```

> 跳过项：全文逐字打字会拖慢阅读，YAGNI——只对 Hero 标题用 TextType。

---

## Task 4: 交互式示意窗口（核心体验）

**Objective:** 让示意窗口「真能点、真能传、真能输、真能切」。全部用 DocsPage 内模块级小组件 + 本地 state，不发任何真实请求。

**Files:**
- Modify: `AIStoryboardClient/src/pages/DocsPage.tsx`
- Modify: `AIStoryboardClient/src/styles/docs.css`

**Step 1: 按钮按压反馈（CSS）**

```css
.docs-mock button:active,
.specular-button:active {
  transform: scale(0.96);
}
```

> 说明：`SpecularButton` 已自带 `:active scale(0.97)`；此条统一 mock 内按钮手感。

**Step 2: 交互式上传小组件（放在 DocsPage 模块级）**

```tsx
function DemoUpload({ label }: { label: string }) {
  const [files, setFiles] = useState<{ url: string }[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  return (
    <div>
      <div style={{ ...mInput, cursor: 'pointer', textAlign: 'center' }} onClick={() => inputRef.current?.click()}>
        {label}
      </div>
      <input ref={inputRef} type="file" accept="image/*" multiple hidden
        onChange={(e) => {
          const arr = Array.from(e.target.files ?? []).map(f => ({ url: URL.createObjectURL(f) }));
          setFiles(prev => [...prev, ...arr]);
          e.target.value = '';
        }} />
      {files.length > 0 && (
        <div style={{ display: 'flex', gap: 5, marginTop: 6 }}>
          {files.map((f, i) => (
            <img key={i} src={f.url} style={{ width: 34, height: 34, objectFit: 'cover', borderRadius: 5, border: '1px solid var(--color-hairline)' }} />
          ))}
        </div>
      )}
    </div>
  );
}
```

> 注意：真实 `URL.createObjectURL`，点「上传参考图」弹出文件选择器并回显缩略图——这就是「真能上传」的感觉。纯本地，无网络。

**Step 3: 交互式输入框** — 把「剧本输入面板」mock 里的剧本块从 `<div>` 改成真 `<textarea>`（带 local state）：

```tsx
<textarea defaultValue="雨夜，女主撑着伞在巷口等人…" rows={3} style={mInput} />
```

**Step 4: 交互式项目下拉 + 保存**（替换「项目管理」mock）：

```tsx
function DemoProjectSwitcher() {
  const projects = ['我的短片项目', '广告提案', '动画短片'];
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [saved, setSaved] = useState(false);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <button onClick={() => setOpen(o => !o)} style={{ ...mInput, flex: 1, textAlign: 'left', cursor: 'pointer' }}>
          {projects[active]} ▾
        </button>
        <button onClick={() => setSaved(true)} style={{ ...mChip, background: saved ? '#5db872' : 'transparent', border: '1px solid var(--color-primary)', color: saved ? '#fff' : 'var(--color-primary)', padding: '3px 8px', cursor: 'pointer' }}>
          {saved ? '✓ 已保存' : '💾 保存'}
        </button>
      </div>
      {open && (
        <div style={{ border: '1px solid var(--color-hairline)', borderRadius: 6, overflow: 'hidden' }}>
          {projects.map((p, i) => (
            <div key={p} onClick={() => { setActive(i); setOpen(false); setSaved(false); }}
              style={{ padding: '6px 10px', fontSize: 11, cursor: 'pointer',
                color: i === active ? 'var(--color-ink)' : 'var(--color-body)',
                background: i === active ? 'var(--color-surface-card)' : '#fff' }}>
              {p}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
```

> 点项目 → 高亮切换 + 收起；点「保存」→ 变绿「✓ 已保存」，切项目后重置回「💾 保存」。这就是「真能切换/保存」的感觉。

**Step 5: 生成按钮按压反馈** — 把「生成图片 / 生成视频 / 生成分镜脚本」mock 按钮改成点击后短暂变「⏳ 生成中…」再复位：

```tsx
function DemoGenerateButton({ children }: { children: ReactNode }) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      style={{ ...mBtn, opacity: busy ? 0.7 : 1 }}
      onClick={() => { if (busy) return; setBusy(true); setTimeout(() => setBusy(false), 1200); }}
    >
      {busy ? '⏳ 生成中…' : children}
    </button>
  );
}
```

**Step 6: 逐处替换** — 把下列 mock 里的静态片段换成上面的交互组件：
- 剧本输入面板：剧本 → `<textarea>`；上传参考图 → `<DemoUpload label="📎 上传参考图（可选，最多 10 张）" />`；生成按钮 → `<DemoGenerateButton>生成分镜脚本</DemoGenerateButton>`。
- 预览·图片 / 预览·视频：图片/视频提示词 → `<textarea>`；生成按钮 → `<DemoGenerateButton>`；保存参数 chip 保留静态说明。
- 项目管理 → `<DemoProjectSwitcher />`。

**Step 7: 验证** — tsc + build；浏览器逐项手测：按钮按压有 scale、上传弹文件选择器并回显、textarea 可输入、项目下拉可切换、保存变绿。

---

## 验证清单（最终）

```bash
cd /e/Desktop/AI-storyboard/AIStoryboardClient
npx tsc -p tsconfig.app.json --noEmit   # 预期 0 错误
npm run build                            # 预期 ✓ built, exit 0
```

浏览器 `http://localhost:5173/docs` 手测：
1. Hero 标题逐字打出 + 光标闪烁。
2. 点「上传参考图」弹文件框，选图回显缩略图。
3. textarea 能打字。
4. 项目下拉展开/切换/高亮，保存按钮变绿。
5. 生成按钮点击短暂「⏳ 生成中…」。
6. 能力卡片/步骤卡 hover 上浮。
7. `prefers-reduced-motion` 下动画自动禁用（已内置）。

---

## 风险与取舍

- **风险 1：TextType 与 Hero 入场动画冲突** → Task 2 已显式移除 `.docs-hero__title` 的 fromTo。
- **风险 2：`verbatimModuleSyntax` 类型导入** → Task 1 已用 `import type`（ElementType/ReactNode/HTMLAttributes）。
- **风险 3：交互 mock 的 `URL.createObjectURL` 泄漏** → 仅演示、数量小，可接受；如需严谨可在组件卸载时 `revokeObjectURL`（记为可选增强）。
- **取舍**：交互 mock 用本地 state 模拟，**不接真实后端**（文档页应零副作用、零请求）；这是刻意简化，避免文档页误触真实生成/上传。
- **取舍**：文字动画只对 Hero 标题用 TextType，其余靠已有的 `[data-reveal]` 淡入 + hover 微交互——避免全文打字拖慢阅读（YAGNI）。

## 待确认（open questions）

1. Hero 标题要「打完即停（loop=false，保留光标）」还是「多句循环打字（loop=true）」？计划默认 loop=false。
2. 交互 mock 是否需要在每处「上传参考图」都做真实文件回显，还是仅「剧本输入面板」一处即可？计划默认全部三处复用 `DemoUpload`。
