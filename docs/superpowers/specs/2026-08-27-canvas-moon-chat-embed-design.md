# 画布悬浮 Moon 对话小窗（iframe 嵌入 /chat）

- 日期：2026-08-27
- 分支：morrow-design-assistant
- 涉及工程：AIStoryboardClient（Moon 对话前端）、XiaoYueCanvasAgent（画布前端）
- 后端 / agent-core / MoonAgent：**零改动**

## 1. 目标

在画布（XiaoYueCanvasAgent）的**执行服务面板**内嵌入一个 Moon 对话**小窗**：复用现有悬浮入口（`onOpenAgent` 悬浮图标）与 `chat-workspace` / `chat-view` 槽位，把 `agent` 槽位内容从 AgentPanel 换成 `<iframe>` 嵌入 AIStoryboardClient 现成的 Moon `/chat` 页。用户在"执行服务"里即可与 Moon 智能体对话并生成图片/视频（意图识别、生成编排、HITL 确认卡全套保留），**不重建任何对话/生成/HITL 逻辑**。

> 明确：目标是 Moon 智能体的对话（走 MoonAgent `/api/agent`），**不是**画布自身的设计助理 AgentPanel（走 `/api/canvas` 作业契约）。原 AgentPanel 从 `agent` 槽位移除。

## 2. 背景 / 现状（已核实）

四套系统边界：

| 系统 | 角色 | 与本次关系 |
|------|------|-----------|
| `agent-core/` | 共享 AI 生成核心（ImageGenerationService / VideoGenerationService / MinimaxVideo …）+ agent 数据层 | 生成逻辑唯一来源 |
| `MoonAgent/` | 完整月球智能体（编排器 + intent handler + HITL + `SceneGenerationBridgeImpl`），暴露 `/api/agent` | 对话/生成消费方，**不依赖画布** |
| `XiaoYueCanvas/` | 画布后端（Morrow 设计助理作业执行服务），依赖 agent-core 复用生成，`NoopSceneGenerationBridge` | **不依赖 MoonAgent** |
| `XiaoYueCanvasAgent/` | 画布前端（完整画布编辑器 + 侧边栏） | 本次小窗宿主 |
| `AIStoryboardClient/` | Moon 对话前端（`/chat` → `ChatPage`，`api/agent.ts` → MoonAgent `/api/agent`） | 本次 iframe 嵌入源 |

已确认代码事实：
- Moon `/chat` 页 = `AIStoryboardClient/src/pages/ChatPage.tsx`（独立 DeepSeek 风格对话页，含 icon rail + 会话侧栏 + 会话主体 + 底部输入卡 + HITL 卡）。
- `ChatPage` 走 `stores/agentStore.ts` + `api/agent.ts`（SSE 流式、form/submit、任务轮询），后端即 MoonAgent `/api/agent`。
- 画布自身的 `AgentPanel.tsx` 走 `HttpJobAdapter('/api/canvas')` → XiaoYueCanvas 作业契约，**非目标**。
- `/chat` 页当前**不消费 URL 的 `?token=`**（只有 `/editor` 的 `EditorPage` 在约 69 行消费）；且用 `authStore`（localStorage）。跨端口 iframe 时 localStorage 不通 → **必须**在 `/chat` 侧加 `?token=` 自登录。
- 画布与 AIStoryboard 共享 JWT secret + 同一 `users` 表（cross-system 设计），画布 `getJobToken()` 即 AIStoryboard 的 accessToken，可直接作为 iframe 的 `?token=`。
- AIStoryboardClient dev 端口：Vite 默认 **5173**（`/api` 代理 → 8080 网关）。`VITE_CANVAS_URL` 是 ChatPage 跳画布用的，反过来画布侧**没有**指向 AIStoryboard 的 env，需新增。

## 3. 架构

画布悬浮小窗 → `<iframe src="{MOON_CHAT}/chat?embed=1&token=...&userId=...&name=...">` → ChatPage 消费 token 自登录 + 按 `embed=1` 渲染紧凑布局 → 对话/生成走 MoonAgent `/api/agent` → agent-core 生成 → 素材落在 Moon 会话侧（agent_assets）。

```
[XiaoYueCanvasAgent 悬浮窗]
   └─ <iframe> {AIStoryboardClient}/chat?embed=1&token=<JWT>
         └─ ChatPage embed 模式（隐藏 rail/侧栏，只留会话主体+输入卡）
              └─ agentStore → api/agent → MoonAgent /api/agent (SSE)
                    └─ intent → pic/video handler → agent-core 生成 → HITL → agent_assets 落库
```

画布后端（XiaoYueCanvas）不参与对话/生成；本次不动它。**画布后端仍不依赖 MoonAgent**。

## 4. 改动清单（全部前端）

### 4.1 AIStoryboardClient

1. **`/chat` 页 `?token=` 自登录**（让 iframe 能自动登录；复用 `EditorPage` 那 6 行 token 块）
   - 读取 `?token=&refresh=&userId=&name=` → 写 localStorage（accessToken/refreshToken/user）→ `window.history.replaceState` 清理 URL → `useAuthStore.getState().checkAuth()`。
   - 抽成一个小钩子（如 `useUrlTokenLogin()`）或直接在 `ChatPage` 的 `useEffect` 内联；**不**把 `EditorPage` 的逻辑改成由该钩子驱动（避免动 EditorPage，零回归）。
2. **`?embed=1` 紧凑模式**（小窗不挤）
   - `ChatPage` 检测 `?embed=1` → 隐藏最左 icon rail + 会话侧栏，只保留会话主体 + 底部输入卡（CSS class 或条件渲染）。
   - 会话切换：紧凑模式在会话主体顶部保留一个「☾ 会话」小按钮，点击呼出会话列表 overlay（复用 `AgentConversationList`），点选后收起。

### 4.2 XiaoYueCanvasAgent

3. **复用执行服务面板槽位，替换 content**：把 `src/App.tsx` `agent` 槽位（约 344 行 `.chat-view`）里的 `<AgentPanel .../>` 换成 Moon 对话小窗组件（新，如 `MoonChatWindow`）。
   - **不加新 launcher**：保留现有 `onOpenAgent`（悬浮图标 → `setSideView('agent'); setSideOpen(true)`）、`chat-topbar`（画布设置/执行服务标题/收起）与 `reopen-chat` 按钮、inspector 切换。原 AgentPanel 相关 JSX 从此槽位移除（组件文件是否删除由后续 lint/引用情况定；如仍有其它引用或测试则保留）。
   - `MoonChatWindow` 内 `<iframe>`，`src` 拼装：
     `const moonChatUrl = (import.meta.env.VITE_MOON_CHAT_URL || 'http://localhost:5173').replace(/\/$/, '') + '/chat?embed=1' + (token ? '&token=' + encodeURIComponent(token) + '&userId=' + ... + '&name=' + ... : '')`
   - token 取 `getJobToken()`（共享 JWT）；`userId`/`name` 取画布 localStorage 的 `user` 对象（`jobAuth.ts` 从 `?token=&userId=&name=` 跳转时已存入）。
   - **小窗即 chat-view 槽位里的 iframe**：不额外做一个覆盖画布的独立拖拽浮层（ponytail）；若后续要面板本身可拖动/缩放，作为二期小改。
4. **新 env**：`VITE_MOON_CHAT_URL`（默认 `http://localhost:5173`）。
5. **`styles.css`**：`.chat-view` 内 iframe 铺满 + embed 小窗缩放/圆角样式（尽量复用现有 `.floating-drawer` / `.connection-panel` 样式，最小新增）。

## 5. 鉴权模型

- 画布与 AIStoryboard 共享 JWT secret + 同一 `users` 表 → 画布 `getJobToken()` 即 AIStoryboard accessToken。
- iframe 以 `?token=` 传入 → ChatPage 消费写 localStorage → `checkAuth()` 生效。
- 因跨端口（5173 与画布 dev 端口）origin 不同，localStorage 不共享，**必须显式传 token**（不能指望同源）。
- 生产：同域网关下若 origin 相同，`?token=` 机制仍可保留。

## 6. 错误处理

- 画布 token 缺失/过期 → 悬浮球点击不打开（或提示重新登录）。
- iframe 加载失败（`/chat` 未起、端口/地址配置错）→ 小窗内空态提示，不静默。
- `/chat` 内 token 失效 → ChatPage `checkAuth` 落失败 → 引导登录。
- 生成失败 → 由 Moon `/chat` 既有 SSE error / retry 机制处理（复用，不在本设计范围）。

## 7. 验证

- AIStoryboardClient：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`；手动开 `http://localhost:5173/chat?embed=1&token=<有效JWT>&userId=<id>&name=<名>` 验证自登录 + 紧凑布局。
- XiaoYueCanvasAgent：`cd XiaoYueCanvasAgent && npx tsc --noEmit && npm run build`；手动进画布点悬浮球，验证小窗打开、iframe 自动登录、窗口可拖动/缩放。
- 端到端：小窗内发一条"生成一张猫的图" → 验证 Moon 意图识别 + 生图 + 素材出现在 Moon 会话侧。

## 8. 明确不做（范围外）

- 不重建对话/生成/HITL 逻辑（复用 `/chat` 页）。
- **不把 Moon 生成的素材自动落到画布节点/素材库**：本设计画布只嵌入对话，素材落在 Moon 会话侧。若要"生成结果直接进画布节点"，走画布现有资产同步机制，另立一期。
- 不做意图识别进画布后端（画布后端仍不依赖 MoonAgent）。
- 不做 iframe 之外的嵌入方案（不搬运 Moon 对话组件进画布）。
- 不动 `EditorPage` 的 token 逻辑、不动画布后端 DesignAgentJobController/DesignJobService。
