# /chat 上传按钮改造：分镜选择 + 智能干预

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** "+" 按钮改为弹出菜单（上传分镜 / 上传图片），上传分镜时展示项目分镜列表供筛选选择，选中后发给 LLM 自动检测生成状态并触发 HITL 人工介入（优化方案 / 生图 / 生视频）。

**Architecture:** 前端新增 `SceneSelectorModal` + 修改两处 "+" 按钮为下拉菜单。后端新增 `SceneReviewIntentHandler` 处理分镜审查意图，复用现有 HITL 基建（`AgentOrchestratorSupport.runHITLStage`、`AgentParamSelector`、资产选择卡片）。

**Tech Stack:** React 19 + TypeScript, Zustand 5, Spring Boot 4, Spring AI 2.0

---

## 现状分析

### 当前 "+" 按钮行为
- `ChatComposer.tsx`（/chat 页面）和 `AgentChatPanel.tsx`（抽屉）各有一个 "+" 按钮
- 点击直接触发 `<input type="file" accept="image/*">` 文件选择 → `uploadRefImage`

### 现有后端能力（可复用）
- `AgentOrchestratorSupport.runHITLStage` — 通用 HITL 暂停模板
- `AgentParamSelector` — 模型/参数选择器（前端已实现）
- `HumanInputCard` — 人工确认卡片（支持 assets 勾选、params 选择）
- `StoryboardClient.getProjectScenes` — 获取项目分镜
- `AgentTools.writeScenes` / `replaceScenes` — 分镜写库
- `ImageGenerationService` / `VideoGenerationService` — 生图/生视频

### 用户需求流程
```
点击 "+" → 菜单「上传分镜 / 上传图片」
  ↓ 上传分镜
弹出分镜选择弹窗（筛选：全部/未生图/未生视频）
  ↓ 勾选确认
消息发送给 LLM → LLM 分析分镜生成状态
  ↓
HITL 卡片：「优化方案 / 生成图片 / 生成视频」
  ├─ 优化方案 → 参数选择器 → LLM 优化 → 展示 → 用户满意 → 更新分镜 → 追问是否生成图/视频
  ├─ 生成图片 → 直接生图
  └─ 生成视频 → 资产选择 → 生成视频
```

---

## 实现计划

### Task 1: 创建 SceneSelectorModal 组件

**Objective:** 分镜选择弹窗，展示当前项目分镜，支持筛选 + 勾选。

**Files:**
- Create: `AIStoryboardClient/src/components/agent/SceneSelectorModal.tsx`

**Step 1: 组件骨架**

```tsx
import { useState } from 'react';
import { createPortal } from 'react-dom';
import { useProjectStore } from '../../stores/projectStore';
import type { SceneResponse } from '../../api/projects';

interface Props {
  open: boolean;
  onClose: () => void;
  onConfirm: (scenes: SceneResponse[]) => void;
}

type Filter = 'all' | 'no-image' | 'no-video';

export function SceneSelectorModal({ open, onClose, onConfirm }: Props) {
  const scenes = useProjectStore((s) => s.scenes);
  const [filter, setFilter] = useState<Filter>('all');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  if (!open) return null;

  const filtered = scenes.filter((s) => {
    if (filter === 'no-image') return !s.imageUrl;
    if (filter === 'no-video') return !s.videoUrl;
    return true;
  });

  const toggle = (id: string) => setSelected((prev) => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });

  const toggleAll = () => {
    if (selected.size === filtered.length) setSelected(new Set());
    else setSelected(new Set(filtered.map((s) => s.id)));
  };

  const handleConfirm = () => {
    const picked = scenes.filter((s) => selected.has(s.id));
    if (picked.length === 0) return;
    onConfirm(picked);
    setSelected(new Set());
  };

  // ... render (portal to body, overlay + centered card)
}
```

**Step 2: 渲染 UI**
- 顶部：标题「选择分镜」+ 关闭按钮
- 筛选 tab：全部 / 未生图 / 未生视频（高亮当前 tab）
- 分镜列表：每行 = 勾选框 + 缩略图（40x40，无图用占位）+ 分镜号 + scriptContent 摘要（30字截断）+ 状态标签（「已生图」「未生图」「已生视频」「未生视频」）
- 全选 checkbox 在列表顶部
- 底部：「发送给 Moon 智能体」按钮（disabled = 选中数为 0）

**Step 3: 样式**
- 复用 `var(--color-*)` 设计 token
- 弹窗：`position: fixed; inset: 0; z-index: 1000` + 半透明遮罩
- 卡片：`max-width: 560px; max-height: 70vh; overflow-y: auto`
- 状态标签：小 badge，绿色=已生成，灰色=未生成

**验证:** `npx tsc -p tsconfig.app.json --noEmit` 通过。

---

### Task 2: 修改 ChatComposer "+" 按钮为下拉菜单

**Objective:** "+" 按钮点击弹出两个选项。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/ChatComposer.tsx`

**Step 1: 新增状态**

```tsx
const [menuOpen, setMenuOpen] = useState(false);
const [sceneModalOpen, setSceneModalOpen] = useState(false);
const menuBtnRef = useRef<HTMLButtonElement>(null);
```

**Step 2: 替换 "+" 按钮为菜单触发器**

按钮 `onClick` 改为 `setMenuOpen(!menuOpen)`。

**Step 3: 渲染下拉菜单（portal + fixed）**

复用 `MoreMenu` 的 portal 模式：
```tsx
{menuOpen && rect && createPortal(
  <div style={{
    position: 'fixed',
    top: rect.top - 80, // 向上弹出（输入框在底部）
    left: rect.left,
    background: 'white',
    border: '1px solid var(--color-hairline)',
    borderRadius: 10,
    boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
    padding: 4,
    zIndex: 1000,
    minWidth: 140,
  }}>
    <MenuItem onClick={() => { setMenuOpen(false); fileRef.current?.click(); }}>
      上传图片
    </MenuItem>
    <MenuItem onClick={() => { setMenuOpen(false); setSceneModalOpen(true); }}>
      上传分镜
    </MenuItem>
  </div>,
  document.body
)}
```

**Step 4: 外部点击关闭**

```tsx
useEffect(() => {
  if (!menuOpen) return;
  const close = (e: MouseEvent) => {
    if (!menuBtnRef.current?.contains(e.target as Node)) setMenuOpen(false);
  };
  document.addEventListener('mousedown', close);
  return () => document.removeEventListener('mousedown', close);
}, [menuOpen]);
```

**Step 5: SceneSelectorModal 确认回调**

```tsx
const handleSceneConfirm = (selected: SceneResponse[]) => {
  const summary = selected.map((s) => {
    const desc = s.scriptContent?.slice(0, 60) || '无描述';
    const img = s.imageUrl ? '[已有图]' : '[未生图]';
    const vid = s.videoUrl ? '[已有视频]' : '[未生视频]';
    return `分镜${s.sceneNumber}（${img}${vid}）：${desc}`;
  }).join('\n');
  sendMessage(`请分析以下分镜并给出优化和生成建议：\n${summary}`);
  setSceneModalOpen(false);
};
```

**验证:** TypeScript 编译通过，菜单可弹出。

---

### Task 3: 修改 AgentChatPanel "+" 按钮为下拉菜单

**Objective:** 抽屉面板同步改造。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`

**实现:** 与 Task 2 相同逻辑，复用 `SceneSelectorModal`。注意弹窗需 portal 到 body（抽屉有 `overflow: hidden`）。

**验证:** TypeScript 编译通过。

---

### Task 4: 新建后端 SceneReviewIntentHandler

**Objective:** 后端识别分镜审查意图，根据分镜状态生成 HITL 卡片。

**Files:**
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/SceneReviewIntentHandler.java`
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentOrchestratorImpl.java`（注册 handler + 规则前置匹配）

**Step 1: IntentHandler 实现**

```java
@Component
@RequiredArgsConstructor
public class SceneReviewIntentHandler implements IntentHandler {
    private final AgentOrchestratorSupport support;
    private final StoryboardClient storyboardClient;
    private final AgentTools agentTools;
    private final ScriptGenerationService scriptGen;

    @Override public String intentType() { return "intent-scene-review"; }

    @Override public Set<String> resumeActions() {
        return Set.of("review-optimize", "review-gen-image", "review-gen-video", "review-skip");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        // 1. 从消息中解析用户提到的分镜编号（正则提取 "分镜N"）
        // 2. 获取项目分镜，匹配用户提到的分镜
        // 3. 分析每个分镜的状态（有图?有视频?）
        // 4. 生成 HITL 卡片，动态选项根据状态：
        //    - 有分镜缺图 → 「生成图片」选项
        //    - 有分镜缺视频 → 「生成视频」选项
        //    - 所有分镜 → 「优化方案」选项
        //    - 「跳过」选项
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 根据 action 分发：
        // review-optimize → 调 LLM 优化方案 → HITL 确认 → 更新分镜 → 追问生成
        // review-gen-image → 生图流程
        // review-gen-video → 资产选择 → 生视频流程
        // review-skip → 结束
    }
}
```

**Step 2: 注册到 Orchestrator**

在 `AgentOrchestratorImpl` 的 `@PostConstruct buildRegistry` 中自动收集（Spring `List<IntentHandler>` 已实现）。

在规则前置匹配中增加关键词：
```java
if (content.matches(".*分析.*分镜.*|.*处理.*分镜.*|.*优化.*分镜.*|.*review.*scene.*")) {
    return "intent-scene-review";
}
```

**Step 3: resume 分支实现**

- `review-optimize`:
  1. 调 `ScriptGenerationService` 优化分镜方案（传入当前分镜内容 + 用户需求）
  2. 展示优化结果 → HITL 确认（agree/disagree/custom）
  3. agree → `AgentTools.writeScenes` 更新 → 追问「是否生成图片/视频？」
  4. disagree → 重新优化（循环，最多 3 轮）

- `review-gen-image`:
  1. 遍历缺图分镜 → `ImageGenerationService.generateImage`
  2. confirm_result 展示结果

- `review-gen-video`:
  1. HITL 资产选择卡片（复用 `buildAssetOptions`）
  2. 确认后 → `VideoGenerationService.createVideoTask`
  3. task_accepted → 前端轮询

**验证:** 后端编译通过 (`mvn compile`)。

---

### Task 5: 意图识别规则前置匹配

**Objective:** 确保分镜审查消息被正确路由。

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentOrchestratorImpl.java`

**实现:** 在 `run()` 方法的规则前置匹配区域增加：
```java
// 分镜审查：前端组合的「请分析以下分镜」消息
if (content.contains("分析") && content.contains("分镜") && content.contains("[未生")) {
    return "intent-scene-review";
}
if (content.contains("处理") && content.contains("分镜") && content.contains("[未生")) {
    return "intent-scene-review";
}
```

**验证:** 发送测试消息，后端日志确认 intent = `intent-scene-review`。

---

### Task 6: 端到端测试

**Objective:** 验证完整流程。

**测试场景:**
1. ✅ 点击 "+" → 弹出菜单 →「上传图片」→ 原有图片上传不受影响
2. ✅ 点击 "+" →「上传分镜」→ 弹出分镜选择弹窗
3. ✅ 筛选「未生图」→ 勾选分镜 → 确认 → 消息发送
4. ✅ 后端识别 intent-scene-review → HITL 卡片弹出
5. ✅ 选择「优化方案」→ 参数选择 → 优化结果 → 确认 → 分镜更新
6. ✅ 追问生成 → 选择「生成视频」→ 资产选择 → 生成

**验证命令:**
```bash
# 前端
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build

# 后端
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q
```

---

## Files Summary

| 文件 | 改动 |
|------|------|
| `AIStoryboardClient/src/components/agent/SceneSelectorModal.tsx` | 新建 |
| `AIStoryboardClient/src/components/agent/ChatComposer.tsx` | 修改（下拉菜单 + 弹窗） |
| `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx` | 修改（下拉菜单 + 弹窗） |
| `MoonAgent/.../handler/SceneReviewIntentHandler.java` | 新建 |
| `MoonAgent/.../impl/AgentOrchestratorImpl.java` | 修改（注册 + 规则匹配） |

## Risks

1. **LLM 意图识别:** 前端消息格式需稳定，后端规则匹配兜底。如果识别不准，加更多关键词。
2. **分镜数量:** 大项目分镜多时弹窗需滚动。前端 `scenes` 已全量加载，筛选是 O(n)。
3. **消息长度:** 选太多分镜消息会很长。建议限制最多 10 个，或只发摘要。
4. **抽屉弹窗层级:** `AgentChatPanel` 在抽屉内，`SceneSelectorModal` 必须 portal 到 body。
5. **多分镜批量生成:** 生图/生视频是逐个 sceneId 调用，可能很慢。考虑并发限制。
