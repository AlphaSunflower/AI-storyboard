# Moon 智能体窗口布局调整 — 实现计划

> **For Hermes:** 使用 subagent-driven-development 技能按任务逐个执行本计划。

**Goal:** 调整 Moon 智能体抽屉布局：①窗口整体加宽；②「☾ Moon 智能体」标题移到会话列表栏顶部，对话窗口顶部改显当前会话标题（资产/清除按钮不动）；③会话切换栏加宽；④底部输入栏可上下拖拽伸缩（带最小/最大限制）；⑤「✨ 优化」按钮移到发送按钮上方（垂直排列）。

**Architecture:** 纯前端样式/布局调整，零后端改动、零接口改动。涉及 3 个组件：`AgentDrawer.tsx`（抽屉宽度）、`AgentConversationList.tsx`（会话栏宽度 + 顶部标题栏）、`AgentChatPanel.tsx`（头部标题替换 + 底部栏可伸缩 + 按钮组垂直化）。底部栏伸缩复用项目已有的 4px 拖拽把手交互模式（EditorPage 三栏 resizable 同款：`onMouseDown` + `mousemove`/`mouseup`，clamp 上下限），不引新依赖（GSAP 已装但纯拖拽用原生事件即可）。

**Tech Stack:** React 19 + TypeScript（内联样式，无 Tailwind 类改造）、Zustand（读 `conversations`/`activeConversationId`）。零后端。

---

## 现状 / 关键代码位置

| 环节 | 位置 | 说明 |
|------|------|------|
| 抽屉宽度 | `AgentDrawer.tsx` L98-100 | `width: '50vw'`、`minWidth: 420`、`maxWidth: '100vw'`（commit 060301f 调整为页面一半） |
| 会话列表栏 | `AgentConversationList.tsx` L47-48 | `width: 138, minWidth: 138`；顶部现状：L57「+ 新建对话」按钮 → L69「🗂 已归档」切换 → 会话项列表 |
| 对话区头部 | `AgentChatPanel.tsx` L65-92 | 左侧 `<span>☾ Moon 智能体</span>`（L66），右侧 📁 资产（L69-80）+ 🧹 清除（L81-90）——**右侧按钮不动** |
| 当前会话标题数据 | `agentStore.ts` | `conversations: AgentConversation[]` + `activeConversationId: string \| null` 均已在 store |
| 输入区 | `AgentChatPanel.tsx` L179-245 | 容器 padding 10；`refImageUrl` 提示条 → `pendingPicUrl` 提示条 → 按钮行（📎 + textarea rows=2 + ✨ 优化 + 发送） |
| 优化按钮 | `AgentChatPanel.tsx` L234-245 | 当前与发送按钮同行；需移到发送按钮**上方**（垂直排列） |
| 拖拽把手范式 | `EditorPage` 三栏 4px handle | `cursor: col-resize`，hover 变 `--color-primary`；底部栏用 `row-resize` 同构实现 |

## 设计决策（含参数默认值，均为低风险样式值，确认后可微调）

1. **抽屉宽度**：`50vw → 62vw`（`minWidth: 420 → 480`，`maxWidth: '100vw'` 保留）。比"页面一半"明显更宽，但留 38vw 给编辑器主区（预览面板还能用）。若嫌窄可再调 64-66vw。
2. **标题迁移**：
   - `AgentConversationList` 顶部新增标题栏：`☾ Moon 智能体`（14px/600 字重，左右 padding 12px，下边框 hairline），下方保留「+ 新建对话」「🗂 已归档」；
   - `AgentChatPanel` 头部左侧由固定文本改为**当前会话标题**：`conversations.find(c => c.id === activeConversationId)?.title`，无会话时显示 `未选择对话`（浅灰占位）。右侧 📁 资产 + 🧹 清除按钮原样保留。
   - 标题超长省略号（`ellipsis + nowrap`，容器 `minWidth: 0` + `flex: 1`），与右侧按钮组不挤压。
3. **会话切换栏宽度**：`138 → 180`（`minWidth` 同步）。标题显示更从容，右侧对话区仍占大头。
4. **底部输入栏可伸缩（带限制）**：
   - 输入区容器改由 `inputAreaHeight` state 控制高度（默认 120px，即当前视觉高度）；
   - 容器**顶部**加 4px 拖拽把手（`cursor: row-resize`，hover/拖拽时变 `--color-primary`，与 EditorPage 把手同风格）；
   - 拖拽逻辑：`onMouseDown` 记录起始 Y → `window mousemove` 计算增量 → `setInputAreaHeight(clamp(90, 40vh))` → `mouseup` 移除监听；
   - 伸缩范围：**min 90px / max 40vh**（"有伸缩限制"）——90px 恰好容纳单行输入 + 按钮，40vh 足够多行草稿/优化结果编辑；
   - textarea 由 `rows={2}` 固定高度改为 `flex: 1` + `resize: none` 填满容器（高度随容器伸缩）；按钮组 `alignItems: 'flex-end'` 跟随；
   - 参考图提示条/优化错误提示/继续完善提示条位于把手下方，随容器一起伸缩（溢出时 textarea 自身滚动，`overflowY: 'auto'`）。
5. **按钮垂直化**：右侧按钮组改为 `flexDirection: 'column'` 容器：上方「✨ 优化」（或「⏳ 优化中…」），下方「发送」；高度/禁用逻辑全部不变。📎 按钮仍在左侧与 textarea 同排（`alignItems: 'flex-end'` 时与底部对齐）。
6. **不做的事（YAGNI）**：不改 store（标题读取只读）、不加拖拽库、不改后端、不重排资产/清除按钮、不做宽度记忆持久化（刷新复位 120px）。

## 任务清单

### Task 1: 抽屉与会话栏加宽 + 标题迁移到会话栏顶部

**Objective:** 宽度调整 + 「☾ Moon 智能体」标题移到会话列表栏顶部。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/AgentDrawer.tsx` L98-99
- Modify: `AIStoryboardClient/src/components/agent/AgentConversationList.tsx`（L44-78 区域）

**Step 1: AgentDrawer 宽度**

```tsx
          width: '62vw',
          minWidth: 480,
```

**Step 2: AgentConversationList 顶部标题栏 + 宽度**

L44-55 容器改为：

```tsx
    <div
      style={{
        position: 'relative',
        width: 180,
        minWidth: 180,
        borderRight: '1px solid var(--color-hairline)',
        background: 'var(--color-surface-soft)',
        display: 'flex',
        flexDirection: 'column',
        overflowY: 'auto',
      }}
    >
      {/* ☾ Moon 智能体标题（迁移自对话窗口头部） */}
      <div
        style={{
          padding: '12px 12px 8px',
          borderBottom: '1px solid var(--color-hairline)',
          fontSize: 14,
          fontWeight: 600,
          color: 'var(--color-ink)',
          whiteSpace: 'nowrap',
        }}
      >
        ☾ Moon 智能体
      </div>
```

（`+ 新建对话` 按钮、`🗂 已归档` 切换原样保留在标题栏下方。）

**Step 3: 验证**

Run: `cd E:\Desktop\AI-storyboard\AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
Expected: 无类型错误

**Step 4: Commit**

```bash
git add AIStoryboardClient/src/components/agent/AgentDrawer.tsx AIStoryboardClient/src/components/agent/AgentConversationList.tsx
git commit -m "style: Moon 抽屉加宽 62vw，会话栏 138→180，标题迁移至会话栏顶部"
```

### Task 2: 对话窗口头部显示当前会话标题

**Objective:** `AgentChatPanel` 头部左侧由固定「☾ Moon 智能体」改为当前会话标题；资产/清除按钮不动。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx` L9（store 解构加 `conversations`）与 L65-92（头部）

**Step 1: store 解构补 conversations**

```tsx
  const { messages, streaming, waitingHumanInput, streamError, refImageUrl, setRefImageUrl, uploadRefImage, sendMessage, clearMessages, confirmResult, pendingPicUrl, cancelRefine, assets, loadAssets, conversations, activeConversationId } = useAgentStore();
```

（原 L19 `const activeConversationId = useAgentStore((s) => s.activeConversationId);` 删除，避免重复订阅。）

**Step 2: 头部标题计算 + 替换**

L65-67 区域改为：

```tsx
      <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--color-hairline)', background: 'white', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span
          title={currentTitle}
          style={{
            flex: 1, minWidth: 0, fontSize: 13, fontWeight: 600, color: 'var(--color-ink)',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}
        >
          {currentTitle}
        </span>
```

其中（组件体内、return 之前）：

```tsx
  // 当前会话标题：对话窗口顶部展示；无会话时占位
  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择对话';
```

**Step 3: 验证 + Commit**

Run: `npx tsc -p tsconfig.app.json --noEmit` → 无类型错误
```bash
git add AIStoryboardClient/src/components/agent/AgentChatPanel.tsx
git commit -m "style: 对话窗口头部显示当前会话标题（资产/清除按钮不变）"
```

### Task 3: 「资产」文案统一为「产出素材」

**Objective:** 用户可见文案命名统一：资产 → 产出素材（按钮、弹窗标题、空态、删除/清除确认）。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`
- Modify: `AIStoryboardClient/src/components/agent/AgentAssetsPanel.tsx`

**Step 1: AgentChatPanel 三处用户可见文案**

| 位置 | 现值 | 改后 |
|------|------|------|
| L101 title | `查看当前对话的生成资产` | `查看当前对话的产出素材` |
| L108 按钮 | `📁 资产{...}` | `📁 产出素材{...}` |
| L280 清除确认 | `生成资产将保留` | `产出素材将保留` |

（组件注释 L16/L97/L313 的「资产」一并改「素材」，保持一致性；store/后端字段名 `assets`/`AgentAsset` 不改——仅用户可见文案。）

**Step 2: AgentAssetsPanel 三处用户可见文案**

| 位置 | 现值 | 改后 |
|------|------|------|
| L79 弹窗标题 | `📁 生成资产（{total}）` | `📁 产出素材（{total}）` |
| L93 空态 | `暂无生成资产——生成的图片/视频会出现在这里` | `暂无产出素材——生成的图片/视频会出现在这里` |
| L114 删除确认 | `删除该资产？` | `删除该素材？` |

**Step 3: 验证 + Commit**

Run: `npx tsc -p tsconfig.app.json --noEmit` → 无类型错误
```bash
git add AIStoryboardClient/src/components/agent/AgentChatPanel.tsx AIStoryboardClient/src/components/agent/AgentAssetsPanel.tsx
git commit -m "style: 资产命名统一为产出素材（按钮/弹窗/空态/确认文案）"
```

### Task 4: 底部输入栏可上下拖拽伸缩（min 90px / max 40vh）

**Objective:** 输入区容器高度由拖拽把手控制，clamp 上下限，textarea 自适应填充。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`

**Step 1: state 与拖拽逻辑（组件体内）**

```tsx
  // 底部输入栏高度（可拖拽伸缩，min 90 / max 40vh）
  const [inputAreaHeight, setInputAreaHeight] = useState(120);
  const dragStartYRef = useRef<number | null>(null);
  const dragStartHRef = useRef(0);

  const startInputDrag = (e: React.MouseEvent) => {
    e.preventDefault();
    dragStartYRef.current = e.clientY;
    dragStartHRef.current = inputAreaHeight;
    const onMove = (ev: MouseEvent) => {
      if (dragStartYRef.current === null) return;
      // 向上拖（ev.clientY 减小）→ 高度增大
      const next = dragStartHRef.current + (dragStartYRef.current - ev.clientY);
      const maxH = Math.round(window.innerHeight * 0.4); // 40vh 上限
      setInputAreaHeight(Math.min(90, Math.max(next, 90)) === 90 && next < 90 ? 90 : Math.min(maxH, Math.max(90, next)));
    };
    const onUp = () => {
      dragStartYRef.current = null;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };
```

（clamp 表达式可简化为 `Math.max(90, Math.min(maxH, next))`——见 Step 2 修正为干净写法。）

**Step 2: 简化 clamp（以最终实现为准）**

```tsx
  const startInputDrag = (e: React.MouseEvent) => {
    e.preventDefault();
    dragStartYRef.current = e.clientY;
    dragStartHRef.current = inputAreaHeight;
    const onMove = (ev: MouseEvent) => {
      if (dragStartYRef.current === null) return;
      const next = dragStartHRef.current + (dragStartYRef.current - ev.clientY);
      const maxH = Math.round(window.innerHeight * 0.4);
      setInputAreaHeight(Math.max(90, Math.min(maxH, next))); // 上下限 clamp
    };
    const onUp = () => {
      dragStartYRef.current = null;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };
```

**Step 3: 输入区容器改造（L179-181 区域）**

```tsx
      {/* 输入区（可拖拽伸缩，min 90 / max 40vh） */}
      <div style={{ padding: '10px 10px 0', borderTop: '1px solid var(--color-hairline)', background: 'white' }}>
        {/* 拖拽把手：上下伸缩 */}
        <div
          onMouseDown={startInputDrag}
          title="拖拽调整输入区高度"
          style={{
            height: 4, margin: '-10px -10px 6px', cursor: 'row-resize',
            background: 'transparent', // hover 变主色
          }}
          onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--color-primary)'; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
        />
        <div style={{ height: inputAreaHeight, display: 'flex', flexDirection: 'column' }}>
          {optimizeError && (...)}
          {refImageUrl && (...)}
          {pendingPicUrl && (...)}
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flex: 1, minHeight: 0 }}>
            📎 按钮
            <textarea style={{ ...原有样式, flex: 1, resize: 'none', overflowY: 'auto' }} />
            <按钮组 column>
          </div>
        </div>
        <div style={{ height: 10 }} /> {/* 底部留白 */}
      </div>
```

关键点：
- 原容器 `padding: 10` 改为 `padding: '10px 10px 0'`（把手负 margin 吸边）；
- textarea `rows={2}` 删除，改 `flex: 1` + `minHeight: 0` + `overflowY: 'auto'`（高度随容器）；
- 参考图/继续完善/优化错误提示条在把手下方、按钮行上方，随容器伸缩。

**Step 4: 验证 + Commit**

Run: `npx tsc -p tsconfig.app.json --noEmit` → 无类型错误
```bash
git add AIStoryboardClient/src/components/agent/AgentChatPanel.tsx
git commit -m "style: 底部输入栏可拖拽伸缩（min 90px / max 40vh）"
```

### Task 5: 优化按钮移到发送按钮上方（垂直排列）

**Objective:** 右侧按钮组改纵向：上方「✨ 优化」、下方「发送」。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`（按钮行区域）

**Step 1: 按钮组容器改为纵向**

原「📎 + textarea + ✨优化 + 发送」同行结构改为：📎 与 textarea 同行（`alignItems: 'flex-end'`），右侧新增纵向容器：

```tsx
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flexShrink: 0 }}>
            <button /* ✨ 优化：样式不变，宽度对齐发送按钮 */
              onClick={handleOptimize}
              disabled={streaming || !!waitingHumanInput || optimizing || text.trim().length < 6}
              title={optimizing ? '正在优化…' : text.trim().length < 6 ? '至少输入 6 个字符才能优化' : '优化为专业的剧情/图片/视频提示词（自动覆盖输入框）'}
              style={{
                height: 32, padding: '0 12px', border: '1px solid var(--color-hairline)',
                borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)',
                color: 'var(--color-primary)', fontSize: 12, cursor: 'pointer',
                opacity: streaming || !!waitingHumanInput || optimizing || text.trim().length < 6 ? 0.45 : 1,
              }}
            >{optimizing ? '⏳ 优化中…' : '✨ 优化'}</button>
            <button /* 发送：原样 */
              onClick={handleSend}
              disabled={streaming || !!waitingHumanInput || optimizing || !text.trim()}
              style={{
                height: 32, padding: '0 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                background: streaming || optimizing || !text.trim() ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
                color: 'white', fontSize: 13, cursor: 'pointer',
              }}
            >发送</button>
          </div>
```

（原同行两个按钮整体搬进 column 容器；禁用/文案/颜色逻辑零改动。）

**Step 2: 验证 + Commit**

Run: `npx tsc -p tsconfig.app.json --noEmit && npm run build` → 无类型错误 + build 成功
```bash
git add AIStoryboardClient/src/components/agent/AgentChatPanel.tsx
git commit -m "style: 优化按钮移至发送按钮上方（垂直排列）"
```

### Task 6: 浏览器手工验证

**Objective:** 布局调整在真实浏览器中逐项核对。

**验证矩阵：**

| # | 场景 | 步骤 | 预期 |
|---|------|------|------|
| 1 | 抽屉宽度 | 打开 Moon | 抽屉明显宽于原来（62vw），大屏 ≥480px，不遮全屏 |
| 2 | 标题迁移 | 打开抽屉 | 会话栏顶部显示「☾ Moon 智能体」；对话窗口头部显示当前会话标题 |
| 3 | 资产/清除不变 | 看对话窗口头部右侧 | 📁 资产、🧹 清除聊天记录按钮仍在原位可用 |
| 4 | 会话栏宽度 | 看会话列表 | 列表栏明显加宽（180px），标题显示更从容 |
| 5 | 无会话占位 | 新建项目无会话时打开抽屉 | 对话窗口头部显示「未选择对话」 |
| 6 | 底部伸缩 | 拖拽输入区顶部 4px 把手 | 上下拖动高度变化；拉到极限处停止（min 90px / max 40vh） |
| 7 | textarea 自适应 | 高度拉高后输入多行 | textarea 填满容器，超长自动滚动 |
| 8 | 优化按钮位置 | 看输入区右侧 | 「✨ 优化」在「发送」上方，功能与禁用逻辑不变（≥6 字符） |
| 9 | 回归 | 发送消息 / 参考图 / 优化 / 标题重命名 | 全部正常 |

**回归注意**：GSAP 入场动画 `clearProps: 'transform'`（抽屉内 fixed 灯箱错位防护）不受影响——未触碰动画代码。

### Task 7: 收尾

- 前端 `tsc -p tsconfig.app.json --noEmit` + `npm run build` 全绿；
- `git status` 只含计划内文件（用户偏好：git add 只加计划内文件）；
- 更新 `CLAUDE.md`「智能体窗口前端约定」小节：抽屉 62vw（minWidth 480）、会话栏 180、标题位置（会话栏顶部 = ☾ Moon 智能体 / 对话窗口顶部 = 当前会话标题）、底部输入栏可伸缩（min 90 / max 40vh）、优化按钮在发送上方。

---

## 文件变更总览

| 文件 | 动作 | 内容 |
|------|------|------|
| `AIStoryboardClient/src/components/agent/AgentDrawer.tsx` | 修改 | 抽屉 `50vw→62vw`、`minWidth 420→480` |
| `AIStoryboardClient/src/components/agent/AgentConversationList.tsx` | 修改 | 栏宽 `138→180`；顶部新增「☾ Moon 智能体」标题栏 |
| `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx` | 修改 | 头部显示当前会话标题；输入区可拖拽伸缩（min 90/max 40vh）；优化按钮移到发送上方；「资产」→「产出素材」文案 |
| `AIStoryboardClient/src/components/agent/AgentAssetsPanel.tsx` | 修改 | 「资产」→「产出素材」文案（弹窗标题/空态/删除确认） |
| `CLAUDE.md` | 修改 | 前端约定更新 |

零后端改动、零接口改动、零 store 改动、无新依赖。

## 风险 / 取舍 / 开放问题

1. **宽度参数（可微调）**：抽屉 62vw / 会话栏 180 / 输入区默认 120px 且 min 90 / max 40vh 均为推荐默认值，视觉确认后可调。开放问题：62vw 是否够宽？（若想要更大可 64-66vw，编辑器主区会进一步被挤压）
2. **拖拽把手实现**：原生 `mousemove` 事件（与 EditorPage 三栏把手同模式）；未用 GSAP——纯拖拽无需动画，且避免与入场动画 transform 干扰。`setInputAreaHeight` 高频调用 React 重渲染，面板小无性能顾虑；如需节流可 `requestAnimationFrame`（本期不做，YAGNI）。
3. **标题迁移语义**：会话栏顶部「☾ Moon 智能体」= 产品名；对话窗口头部 = 当前会话名。两处不再重复，信息层级更清晰。未选择会话时占位「未选择对话」。
4. **伸缩与提示条**：参考图/继续完善/优化错误提示条位于把手下方，随输入区伸缩；输入区被拉高时提示条固定在上部、textarea 在下部（容器 flex column 布局决定）。
5. **回归风险低**：改动均为内联样式 + 头部文本替换；未触碰 store、SSE、生成链路。GSAP 动画代码未动。
6. **测试策略**：纯样式任务，采用「tsc/build + 浏览器手工矩阵」；无单测价值。
