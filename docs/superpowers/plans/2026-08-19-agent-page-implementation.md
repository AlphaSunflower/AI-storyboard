# AI Agent 独立页面实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 创建 AI Agent 独立页面，双面板布局（左侧导航 + 右侧全屏对话），与现有编辑器页面并列

**架构：** 新增 `/agent` 路由，复用现有 `agentStore` 和消息组件，新增页面级组件处理布局和浮动弹窗

**技术栈：** React 19 + TypeScript + Tailwind CSS 4 + Zustand 5 + GSAP（弹窗动画）

---

## 文件结构

```
AIStoryboardClient/src/
├── pages/
│   └── AgentPage.tsx                 # 新增：Agent 独立页面
├── components/agent/
│   ├── AgentSidebar.tsx              # 新增：左侧导航
│   ├── AgentConversationArea.tsx     # 新增：主对话区域
│   ├── AgentInputBox.tsx             # 新增：底部输入框
│   ├── AgentModal.tsx                # 新增：浮动弹窗容器
│   ├── StoryboardModal.tsx           # 新增：分镜弹窗（外链跳转）
│   ├── AssetsModal.tsx               # 新增：资产弹窗（分页）
│   ├── ProjectModal.tsx              # 新增：项目弹窗
│   ├── SettingsModal.tsx             # 新增：设置弹窗
│   └── HumanInputCard.tsx            # 修改：选项改为垂直堆叠
├── stores/
│   └── agentStore.ts                 # 修改：新增页面状态
└── App.tsx                           # 修改：新增 /agent 路由
```

---

## 任务 1：扩展 agentStore 支持新页面状态

**文件：**
- 修改：`AIStoryboardClient/src/stores/agentStore.ts`

- [ ] **步骤 1：新增页面状态字段**

在 `AgentState` 接口中添加：

```typescript
// 新页面状态
activeModal: 'storyboard' | 'assets' | 'project' | 'settings' | null;
setActiveModal: (modal: AgentState['activeModal']) => void;
historyExpanded: boolean;
setHistoryExpanded: (v: boolean) => void;
```

- [ ] **步骤 2：在 create() 中实现状态**

```typescript
activeModal: null,
setActiveModal: (modal) => set({ activeModal: modal }),
historyExpanded: false,
setHistoryExpanded: (v) => set({ historyExpanded: v }),
```

- [ ] **步骤 3：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardClient/src/stores/agentStore.ts
git commit -m "feat: extend agentStore with page-level state for new agent page"
```

---

## 任务 2：创建 AgentSidebar 组件

**文件：**
- 创建：`AIStoryboardClient/src/components/agent/AgentSidebar.tsx`

- [ ] **步骤 1：编写组件骨架**

```tsx
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';

export function AgentSidebar() {
  const { setActiveModal, historyExpanded, setHistoryExpanded, conversations, activeConversationId, selectConversation, createConversation } = useAgentStore();
  const currentProject = useProjectStore((s) => s.currentProject);

  const navItems = [
    { icon: '➕', label: '新对话', action: () => createConversation() },
    { icon: '🎬', label: '分镜', action: () => setActiveModal('storyboard') },
    { icon: '🖼️', label: '资产', action: () => setActiveModal('assets') },
    { icon: '📁', label: '项目', action: () => setActiveModal('project') },
  ];

  return (
    <div className="w-60 flex-shrink-0 border-r flex flex-col" style={{ borderColor: 'var(--color-hairline)', background: 'var(--color-surface-soft)' }}>
      {/* Logo */}
      <div className="p-4 border-b" style={{ borderColor: 'var(--color-hairline)' }}>
        <span className="text-lg font-semibold" style={{ color: 'var(--color-ink)' }}>AI Storyboard</span>
      </div>

      {/* 主导航 */}
      <div className="flex-1 overflow-y-auto py-2">
        {navItems.map((item) => (
          <button
            key={item.label}
            onClick={item.action}
            className="w-full px-4 py-2.5 flex items-center gap-3 hover:bg-black/5 transition-colors text-left"
            style={{ color: 'var(--color-body)' }}
          >
            <span className="text-base">{item.icon}</span>
            <span className="text-sm">{item.label}</span>
          </button>
        ))}

        {/* 分隔线 */}
        <div className="my-2 mx-4 border-t" style={{ borderColor: 'var(--color-hairline)' }} />

        {/* 历史对话 */}
        <button
          onClick={() => setHistoryExpanded(!historyExpanded)}
          className="w-full px-4 py-2.5 flex items-center gap-3 hover:bg-black/5 transition-colors text-left"
          style={{ color: 'var(--color-body)' }}
        >
          <span className="text-base">💬</span>
          <span className="text-sm">历史</span>
          <span className="ml-auto text-xs" style={{ color: 'var(--color-muted)' }}>{historyExpanded ? '▼' : '▶'}</span>
        </button>

        {historyExpanded && (
          <div className="ml-4">
            {conversations.slice(0, 20).map((conv) => (
              <button
                key={conv.id}
                onClick={() => selectConversation(conv.id)}
                className={`w-full px-4 py-2 text-left text-sm truncate transition-colors ${
                  conv.id === activeConversationId ? 'bg-black/10 font-medium' : 'hover:bg-black/5'
                }`}
                style={{ color: 'var(--color-body)' }}
              >
                {conv.title}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* 底部设置 */}
      <div className="border-t p-2" style={{ borderColor: 'var(--color-hairline)' }}>
        <button
          onClick={() => setActiveModal('settings')}
          className="w-full px-4 py-2.5 flex items-center gap-3 hover:bg-black/5 transition-colors text-left rounded-lg"
          style={{ color: 'var(--color-body)' }}
        >
          <span className="text-base">⚙️</span>
          <span className="text-sm">设置</span>
        </button>
      </div>
    </div>
  );
}
```

- [ ] **步骤 2：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/components/agent/AgentSidebar.tsx
git commit -m "feat: add AgentSidebar component for new agent page"
```

---

## 任务 3：创建 AgentInputBox 组件

**文件：**
- 创建：`AIStoryboardClient/src/components/agent/AgentInputBox.tsx`

- [ ] **步骤 1：编写组件**

```tsx
import { useState, useRef } from 'react';
import { useAgentStore } from '../../stores/agentStore';

export function AgentInputBox() {
  const { sendMessage, streaming, waitingHumanInput, waitingVideoPlan, uploadRefImage } = useAgentStore();
  const [text, setText] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput || waitingVideoPlan) return;
    setText('');
    sendMessage(content);
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    await uploadRefImage(file);
    e.target.value = '';
  };

  const disabled = streaming || !!waitingHumanInput || !!waitingVideoPlan;

  return (
    <div className="border-t p-4" style={{ borderColor: 'var(--color-hairline)' }}>
      <div className="flex items-end gap-2 rounded-xl p-2" style={{ background: 'var(--color-surface-card)' }}>
        {/* 附件按钮 */}
        <button
          onClick={() => fileRef.current?.click()}
          disabled={disabled}
          className="p-2 rounded-lg hover:bg-black/5 transition-colors disabled:opacity-50"
          title="上传附件"
        >
          <span className="text-lg" style={{ color: 'var(--color-muted)' }}>+</span>
        </button>
        <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleFile} />

        {/* 输入框 */}
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); } }}
          placeholder="输入消息..."
          disabled={disabled}
          rows={1}
          className="flex-1 resize-none bg-transparent outline-none text-sm py-2 disabled:opacity-50"
          style={{ color: 'var(--color-ink)' }}
        />

        {/* 发送按钮 */}
        <button
          onClick={handleSend}
          disabled={disabled || !text.trim()}
          className="px-4 py-2 rounded-lg text-sm font-medium transition-colors disabled:opacity-50"
          style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)' }}
        >
          发送
        </button>
      </div>

      {/* 提示文案 */}
      <p className="text-xs mt-2 text-center" style={{ color: 'var(--color-muted)' }}>
        AI 可能会犯错，请核实重要信息。
      </p>
    </div>
  );
}
```

- [ ] **步骤 2：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/components/agent/AgentInputBox.tsx
git commit -m "feat: add AgentInputBox component for new agent page"
```

---

## 任务 4：创建 AgentConversationArea 组件

**文件：**
- 创建：`AIStoryboardClient/src/components/agent/AgentConversationArea.tsx`

- [ ] **步骤 1：编写组件**

复用现有 `MessageBubble`、`HumanInputCard`、`ConfirmResultCard`、`VideoPlanCard` 组件。

```tsx
import { useEffect, useRef } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';
import { ConfirmResultCard } from './ConfirmResultCard';
import { VideoPlanCard } from './VideoPlanCard';

export function AgentConversationArea() {
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, confirmResult,
    conversations, activeConversationId, renameConversation, workflowHint,
  } = useAgentStore();
  const scrollRef = useRef<HTMLDivElement>(null);

  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '新对话';

  // 自动滚底
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput, waitingVideoPlan]);

  const handleRename = () => {
    const name = prompt('重命名对话', currentTitle);
    if (name && activeConversationId) renameConversation(activeConversationId, name);
  };

  const isEmpty = messages.length === 0;

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* 标题栏 */}
      <div className="px-6 py-3 border-b flex items-center justify-between" style={{ borderColor: 'var(--color-hairline)' }}>
        <button onClick={handleRename} className="text-sm font-medium hover:underline" style={{ color: 'var(--color-ink)' }}>
          {currentTitle} ▼
        </button>
      </div>

      {/* 消息区 */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        {isEmpty ? (
          <div className="h-full flex items-center justify-center">
            <p className="text-xl" style={{ color: 'var(--color-muted)' }}>你好，有什么可以帮你的？</p>
          </div>
        ) : (
          <div className="max-w-3xl mx-auto py-6 px-4 space-y-4">
            {messages.map((msg) => (
              <MessageBubble key={msg.id} message={msg} />
            ))}
            {streaming && workflowHint && (
              <div className="text-sm py-2" style={{ color: 'var(--color-muted)' }}>{workflowHint}</div>
            )}
            {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
            {waitingVideoPlan && <VideoPlanCard info={waitingVideoPlan} />}
            {confirmResult && <ConfirmResultCard info={confirmResult} />}
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **步骤 2：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/components/agent/AgentConversationArea.tsx
git commit -m "feat: add AgentConversationArea component for new agent page"
```

---

## 任务 5：创建 AgentModal 浮动弹窗容器

**文件：**
- 创建：`AIStoryboardClient/src/components/agent/AgentModal.tsx`

- [ ] **步骤 1：编写通用弹窗容器**

```tsx
import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';

interface AgentModalProps {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  width?: number;
}

export function AgentModal({ title, onClose, children, width = 500 }: AgentModalProps) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const modalRef = useRef<HTMLDivElement>(null);

  useGSAP(() => {
    gsap.fromTo(overlayRef.current, { opacity: 0 }, { opacity: 1, duration: 0.2 });
    gsap.fromTo(modalRef.current, { y: 20, opacity: 0 }, { y: 0, opacity: 1, duration: 0.3, ease: 'back.out(1.2)' });
  }, []);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <div ref={overlayRef} className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.4)' }} onClick={onClose}>
      <div
        ref={modalRef}
        className="rounded-xl shadow-xl overflow-hidden"
        style={{ width, maxHeight: '80vh', background: 'var(--color-canvas)' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 标题栏 */}
        <div className="px-6 py-4 border-b flex items-center justify-between" style={{ borderColor: 'var(--color-hairline)' }}>
          <h2 className="text-base font-semibold" style={{ color: 'var(--color-ink)' }}>{title}</h2>
          <button onClick={onClose} className="p-1 rounded hover:bg-black/5" style={{ color: 'var(--color-muted)' }}>✕</button>
        </div>

        {/* 内容 */}
        <div className="p-6 overflow-y-auto" style={{ maxHeight: 'calc(80vh - 60px)' }}>
          {children}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **步骤 2：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/components/agent/AgentModal.tsx
git commit -m "feat: add AgentModal floating modal container"
```

---

## 任务 6：创建具体弹窗内容组件

**文件：**
- 创建：`AIStoryboardClient/src/components/agent/StoryboardModal.tsx`
- 创建：`AIStoryboardClient/src/components/agent/AssetsModal.tsx`
- 创建：`AIStoryboardClient/src/components/agent/ProjectModal.tsx`
- 创建：`AIStoryboardClient/src/components/agent/SettingsModal.tsx`

- [ ] **步骤 1：StoryboardModal（外链跳转）**

```tsx
import { useProjectStore } from '../../stores/projectStore';

export function StoryboardModal() {
  const currentProject = useProjectStore((s) => s.currentProject);

  const handleOpen = () => {
    // 跳转到项目对应的分镜网址
    window.open(`/editor?projectId=${currentProject?.id}`, '_blank');
  };

  return (
    <div className="text-center py-8">
      <p className="text-sm mb-4" style={{ color: 'var(--color-body)' }}>
        查看当前项目的分镜导出情况
      </p>
      <button
        onClick={handleOpen}
        disabled={!currentProject}
        className="px-6 py-2.5 rounded-lg text-sm font-medium disabled:opacity-50"
        style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)' }}
      >
        打开分镜页面
      </button>
    </div>
  );
}
```

- [ ] **步骤 2：AssetsModal（分页）**

```tsx
import { useEffect } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';

export function AssetsModal() {
  const { assets, loadAssets, deleteAsset } = useAgentStore();

  useEffect(() => { loadAssets(1); }, [loadAssets]);

  const handlePage = (page: number) => loadAssets(page);

  if (!assets) return <div className="text-center py-8" style={{ color: 'var(--color-muted)' }}>加载中...</div>;

  return (
    <div>
      {assets.records.length === 0 ? (
        <p className="text-center py-8" style={{ color: 'var(--color-muted)' }}>暂无资产</p>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3">
            {assets.records.map((asset) => (
              <div key={asset.id} className="relative group rounded-lg overflow-hidden" style={{ background: 'var(--color-surface-card)' }}>
                {asset.type === 'image' ? (
                  <img src={assetUrl(asset.url)} alt="" className="w-full aspect-square object-cover" />
                ) : (
                  <video src={assetUrl(asset.url)} className="w-full aspect-square object-cover" />
                )}
                <button
                  onClick={() => deleteAsset(asset.id)}
                  className="absolute top-2 right-2 p-1 rounded bg-black/50 text-white opacity-0 group-hover:opacity-100 transition-opacity text-xs"
                >
                  删除
                </button>
              </div>
            ))}
          </div>

          {/* 分页 */}
          {assets.total > assets.size && (
            <div className="flex justify-center gap-2 mt-4">
              {Array.from({ length: Math.ceil(assets.total / assets.size) }, (_, i) => (
                <button
                  key={i}
                  onClick={() => handlePage(i + 1)}
                  className={`px-3 py-1 rounded text-sm ${assets.page === i + 1 ? 'font-bold' : ''}`}
                  style={{
                    background: assets.page === i + 1 ? 'var(--color-primary)' : 'transparent',
                    color: assets.page === i + 1 ? 'var(--color-on-primary)' : 'var(--color-body)',
                  }}
                >
                  {i + 1}
                </button>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
```

- [ ] **步骤 3：ProjectModal**

```tsx
import { useEffect } from 'react';
import { useProjectStore } from '../../stores/projectStore';

export function ProjectModal() {
  const { projects, currentProject, loadProjects, setCurrentProject } = useProjectStore();

  useEffect(() => { loadProjects(); }, [loadProjects]);

  return (
    <div className="space-y-2">
      {projects.map((p) => (
        <button
          key={p.id}
          onClick={() => setCurrentProject(p)}
          className={`w-full text-left px-4 py-3 rounded-lg transition-colors ${
            p.id === currentProject?.id ? 'ring-2' : 'hover:bg-black/5'
          }`}
          style={{
            background: p.id === currentProject?.id ? 'var(--color-surface-card)' : 'transparent',
            ringColor: 'var(--color-primary)',
          }}
        >
          <div className="text-sm font-medium" style={{ color: 'var(--color-ink)' }}>{p.name}</div>
          <div className="text-xs mt-1" style={{ color: 'var(--color-muted)' }}>{p.status}</div>
        </button>
      ))}
    </div>
  );
}
```

- [ ] **步骤 4：SettingsModal**

```tsx
import { useAuthStore } from '../../stores/authStore';

export function SettingsModal() {
  const { user, logout } = useAuthStore();

  return (
    <div className="space-y-6">
      <div>
        <label className="text-xs font-medium" style={{ color: 'var(--color-muted)' }}>用户名</label>
        <p className="text-sm mt-1" style={{ color: 'var(--color-ink)' }}>{user?.name ?? '未登录'}</p>
      </div>
      <button
        onClick={logout}
        className="px-4 py-2 rounded-lg text-sm border hover:bg-black/5 transition-colors"
        style={{ borderColor: 'var(--color-hairline)', color: 'var(--color-body)' }}
      >
        退出登录
      </button>
    </div>
  );
}
```

- [ ] **步骤 5：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 6：Commit**

```bash
git add AIStoryboardClient/src/components/agent/StoryboardModal.tsx AIStoryboardClient/src/components/agent/AssetsModal.tsx AIStoryboardClient/src/components/agent/ProjectModal.tsx AIStoryboardClient/src/components/agent/SettingsModal.tsx
git commit -m "feat: add modal content components (storyboard, assets, project, settings)"
```

---

## 任务 7：创建 AgentPage 主页面

**文件：**
- 创建：`AIStoryboardClient/src/pages/AgentPage.tsx`

- [ ] **步骤 1：编写页面组件**

```tsx
import { useAgentStore } from '../stores/agentStore';
import { AgentSidebar } from '../components/agent/AgentSidebar';
import { AgentConversationArea } from '../components/agent/AgentConversationArea';
import { AgentInputBox } from '../components/agent/AgentInputBox';
import { AgentModal } from '../components/agent/AgentModal';
import { StoryboardModal } from '../components/agent/StoryboardModal';
import { AssetsModal } from '../components/agent/AssetsModal';
import { ProjectModal } from '../components/agent/ProjectModal';
import { SettingsModal } from '../components/agent/SettingsModal';

const modalConfig = {
  storyboard: { title: '分镜', Component: StoryboardModal },
  assets: { title: '资产库', Component: AssetsModal },
  project: { title: '项目', Component: ProjectModal },
  settings: { title: '设置', Component: SettingsModal },
} as const;

export function AgentPage() {
  const { activeModal, setActiveModal } = useAgentStore();

  return (
    <div className="h-screen flex" style={{ background: 'var(--color-canvas)' }}>
      {/* 左侧导航 */}
      <AgentSidebar />

      {/* 主对话区域 */}
      <div className="flex-1 flex flex-col min-w-0">
        <AgentConversationArea />
        <AgentInputBox />
      </div>

      {/* 浮动弹窗 */}
      {activeModal && (() => {
        const { title, Component } = modalConfig[activeModal];
        return (
          <AgentModal title={title} onClose={() => setActiveModal(null)}>
            <Component />
          </AgentModal>
        );
      })()}
    </div>
  );
}
```

- [ ] **步骤 2：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/pages/AgentPage.tsx
git commit -m "feat: add AgentPage with two-panel layout"
```

---

## 任务 8：更新路由、HumanInputCard 和 HITL 气泡行为

**文件：**
- 修改：`AIStoryboardClient/src/App.tsx`
- 修改：`AIStoryboardClient/src/components/agent/HumanInputCard.tsx`
- 修改：`AIStoryboardClient/src/stores/agentStore.ts`

- [ ] **步骤 1：添加 /agent 路由**

```tsx
import { AgentPage } from './pages/AgentPage';

// 在 Routes 中添加
<Route path="/agent" element={<AgentPage />} />
```

- [ ] **步骤 2：修改 HumanInputCard 选项为垂直堆叠**

找到选项渲染区域，将 `flex flex-wrap gap-2` 改为 `flex flex-col gap-2`：

```tsx
// 修改前
<div className="flex flex-wrap gap-2">

// 修改后
<div className="flex flex-col gap-2">
```

- [ ] **步骤 3：HITL 后新建气泡（不追加到原气泡）**

在 `agentStore.ts` 的 `submitHumanInput` 方法中，HITL resume 结束后清除 `pendingAssistantId`，使下一轮回复生成新气泡：

```typescript
// submitHumanInput 的 finally 块中
finally {
  set({
    streaming: false,
    pendingAssistantId: null,  // HITL 结束，下次回复用新气泡
  });
}
```

- [ ] **步骤 3：验证 TypeScript 编译**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardClient/src/App.tsx AIStoryboardClient/src/components/agent/HumanInputCard.tsx
git commit -m "feat: add /agent route and vertical HITL action layout"
```

---

## 任务 9：整体验证

- [ ] **步骤 1：TypeScript 编译检查**

运行：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
预期：无错误

- [ ] **步骤 2：构建验证**

运行：`cd AIStoryboardClient && npm run build`
预期：构建成功

- [ ] **步骤 3：访问测试**

运行：`cd AIStoryboardClient && npm run dev`
访问 `http://localhost:5173/agent`，验证：
- 左侧导航显示正确
- 点击导航项弹出对应弹窗
- 对话区域能正常显示消息
- 输入框能发送消息
- HITL 选项垂直堆叠

- [ ] **步骤 4：最终 Commit**

```bash
git add -A
git commit -m "feat: complete AI Agent standalone page implementation"
```
