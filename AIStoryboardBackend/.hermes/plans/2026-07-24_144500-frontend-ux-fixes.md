# Frontend UX 修复计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 修复前端 5 个 UX 问题：项目重命名/删除、历史记录、分镜提示词编辑、生成进度反馈、剧本面板折叠/拉伸。

**Architecture:** 纯前端修改（React 组件 + Zustand store），不涉及后端 API 变更。后端 `updateProject`、`deleteProject`、`updateScene` 接口已存在，前端只需调用。

**Tech Stack:** React 18 + TypeScript + Zustand + CSS Variables (DESIGN.md tokens)

---

### 任务 1：项目重命名与删除

**Objective:** 在 AppHeader 下拉菜单中增加重命名和删除按钮

**Files:**
- Modify: `AIStoryboardClient/src/components/layout/AppHeader.tsx`

**Step 1：在项目列表每项右侧添加操作按钮**

```tsx
// 在 projects.map 的 <button> 内部，项目名称后面添加：
<div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
  <span>{p.name}</span>
  <div style={{ display: 'flex', gap: 4 }}>
    {/* 重命名按钮 */}
    <span onClick={(e) => { e.stopPropagation(); handleRenameStart(p); }}
      style={{ fontSize: 12, padding: '2px 4px', cursor: 'pointer', opacity: 0.6 }}
      title="重命名">✏️</span>
    {/* 删除按钮 */}
    <span onClick={(e) => { e.stopPropagation(); handleDeleteConfirm(p); }}
      style={{ fontSize: 12, padding: '2px 4px', cursor: 'pointer', opacity: 0.6 }}
      title="删除">🗑️</span>
  </div>
</div>
```

**Step 2：添加重命名弹窗状态和处理函数**

```tsx
const [renameTarget, setRenameTarget] = useState<ProjectResponse | null>(null);
const [renameName, setRenameName] = useState('');
const [deleteTarget, setDeleteTarget] = useState<ProjectResponse | null>(null);

const handleRenameStart = (p: ProjectResponse) => {
  setRenameTarget(p);
  setRenameName(p.name);
};

const handleRenameSubmit = async () => {
  if (!renameTarget || !renameName.trim()) return;
  await updateProject(renameTarget.id, { name: renameName.trim() });
  await loadProjects();
  if (currentProject?.id === renameTarget.id) loadProject(renameTarget.id);
  setRenameTarget(null);
};

const handleDeleteConfirm = (p: ProjectResponse) => {
  setDeleteTarget(p);
};

const handleDeleteExecute = async () => {
  if (!deleteTarget) return;
  await deleteProject(deleteTarget.id);
  setDeleteTarget(null);
};
```

**Step 3：渲染重命名/删除确认弹窗**

```tsx
{/* 重命名弹窗 */}
{renameTarget && (
  <div style={modalOverlayStyle} onClick={() => setRenameTarget(null)}>
    <div style={modalStyle} onClick={e => e.stopPropagation()}>
      <h3 style={{ font: 'var(--text-title-sm)', margin: '0 0 12px' }}>重命名项目</h3>
      <input value={renameName} onChange={e => setRenameName(e.target.value)}
        style={inputStyle} autoFocus onKeyDown={e => e.key === 'Enter' && handleRenameSubmit()} />
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
        <button onClick={() => setRenameTarget(null)} style={secondaryBtnStyle}>取消</button>
        <button onClick={handleRenameSubmit} style={primaryBtnStyle}>确认</button>
      </div>
    </div>
  </div>
)}

{/* 删除确认弹窗 */}
{deleteTarget && (
  <div style={modalOverlayStyle} onClick={() => setDeleteTarget(null)}>
    <div style={modalStyle} onClick={e => e.stopPropagation()}>
      <h3 style={{ font: 'var(--text-title-sm)', margin: '0 0 8px' }}>删除项目</h3>
      <p style={{ color: 'var(--color-muted)', fontSize: 13, marginBottom: 12 }}>
        确定要删除「{deleteTarget.name}」吗？此操作不可撤销。
      </p>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
        <button onClick={() => setDeleteTarget(null)} style={secondaryBtnStyle}>取消</button>
        <button onClick={handleDeleteExecute} style={{ ...primaryBtnStyle, background: 'var(--color-error)' }}>删除</button>
      </div>
    </div>
  </div>
)}
```

**Step 4：添加 shared styles**

```tsx
const modalOverlayStyle: React.CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(20,20,19,0.3)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100,
};
const modalStyle: React.CSSProperties = {
  background: 'white', borderRadius: 'var(--rounded-lg)', padding: 24,
  minWidth: 320, maxWidth: 400, boxShadow: '0 8px 30px rgba(20,20,19,0.15)',
};
const inputStyle: React.CSSProperties = {
  width: '100%', padding: '8px 12px', borderRadius: 'var(--rounded-md)',
  border: '1px solid var(--color-hairline)', fontSize: 14, outline: 'none',
};
const primaryBtnStyle: React.CSSProperties = {
  padding: '6px 16px', borderRadius: 'var(--rounded-md)', border: 'none',
  background: 'var(--color-primary)', color: 'white', fontSize: 13, cursor: 'pointer',
};
const secondaryBtnStyle: React.CSSProperties = {
  padding: '6px 16px', borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)',
  background: 'white', color: 'var(--color-body)', fontSize: 13, cursor: 'pointer',
};
```

**Verification:**
- 点击项目旁的 ✏️ → 弹出重命名框 → 输入新名称回车 → 列表刷新
- 点击项目旁的 🗑️ → 弹出确认框 → 点删除 → 项目从列表消失
- 运行 `npx tsc --noEmit` 零错误

**Step 5: Commit**

```bash
git add AIStoryboardClient/src/components/layout/AppHeader.tsx
git commit -m "feat: add project rename and delete to header dropdown"
```

---

### 任务 2：项目历史列表页

**Objective:** 添加项目历史面板，显示所有已保存项目（替代仅下拉菜单）

**Files:**
- Create: `AIStoryboardClient/src/components/editor/ProjectHistoryPanel.tsx`
- Modify: `AIStoryboardClient/src/pages/EditorPage.tsx`

**Step 1：创建 ProjectHistoryPanel**

```tsx
// ProjectHistoryPanel.tsx
import { useProjectStore } from '../../stores/projectStore';
import type { ProjectResponse } from '../../api/projects';

export function ProjectHistoryPanel() {
  const { projects, currentProject, loadProject, deleteProject } = useProjectStore();

  return (
    <div style={{
      borderBottom: '1px solid var(--color-hairline)',
      background: 'var(--color-canvas)',
      padding: '8px var(--space-md)',
      display: 'flex', gap: 8, overflowX: 'auto', alignItems: 'center',
      height: 36, minHeight: 36,
    }}>
      <span style={{ fontSize: 11, color: 'var(--color-muted-soft)', marginRight: 4, whiteSpace: 'nowrap' }}>
        历史项目:
      </span>
      {projects.length === 0 ? (
        <span style={{ fontSize: 11, color: 'var(--color-muted-soft)' }}>暂无项目</span>
      ) : (
        projects.map(p => (
          <button key={p.id} onClick={() => loadProject(p.id)}
            style={{
              padding: '2px 10px', borderRadius: 'var(--rounded-pill)', border: 'none',
              background: currentProject?.id === p.id ? 'var(--color-primary)' : 'var(--color-surface-card)',
              color: currentProject?.id === p.id ? 'white' : 'var(--color-body)',
              fontSize: 11, cursor: 'pointer', whiteSpace: 'nowrap',
              fontWeight: currentProject?.id === p.id ? 500 : 400,
            }}>
            {p.name}
            {p.status === 'draft' && <span style={{ opacity: 0.6, marginLeft: 4 }}>·草稿</span>}
          </button>
        ))
      )}
    </div>
  );
}
```

**Step 2：在 EditorPage 中嵌入历史栏**

```tsx
// 在 <AppHeader /> 和 {showDraftBanner && ...} 之间添加：
<ProjectHistoryPanel />
```

**Verification:**
- 页面加载后顶部显示横向历史项目栏
- 点击历史项目标签 → 加载对应项目
- 当前项目高亮（珊瑚色胶囊）
- `npx tsc --noEmit` 零错误

**Step 3: Commit**

```bash
git add AIStoryboardClient/src/components/editor/ProjectHistoryPanel.tsx AIStoryboardClient/src/pages/EditorPage.tsx
git commit -m "feat: add project history bar below header"
```

---

### 任务 3：分镜提示词编辑区

**Objective:** SceneCard 展开后可编辑 imagePrompt 和 videoPrompt，而不是直接调用 AI

**Files:**
- Modify: `AIStoryboardClient/src/components/scene/SceneCard.tsx`
- Modify: `AIStoryboardClient/src/stores/projectStore.ts`（添加 updateScenePrompt action）

**Step 1：SceneCard 改为可展开**

在 SceneCard 内部添加展开状态和提示词编辑区：

```tsx
const [expanded, setExpanded] = useState(false);
const [editImagePrompt, setEditImagePrompt] = useState(scene.imagePrompt || '');
const [editVideoPrompt, setEditVideoPrompt] = useState(scene.videoPrompt || '');

const handleGenerateImage = () => {
  if (!editImagePrompt.trim()) return;
  // 先用编辑后的提示词更新 scene
  sceneApi.update(scene.id, { imagePrompt: editImagePrompt });
  generateImage(scene.id, editImagePrompt);
};

const handleGenerateVideo = () => {
  if (!editVideoPrompt.trim()) return;
  sceneApi.update(scene.id, { videoPrompt: editVideoPrompt });
  generateVideo(scene.id, editVideoPrompt);
};
```

**Step 2：展开显示的提示词编辑区**

在 SceneCard 的 tags 和按钮之间添加：

```tsx
{/* 点击展开/折叠按钮 */}
<button onClick={(e) => { e.stopPropagation(); setExpanded(!expanded); }}
  style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer', padding: '2px 0' }}>
  {expanded ? '收起 ▲' : '展开提示词 ▼'}
</button>

{/* 展开的提示词编辑区 */}
{expanded && (
  <div style={{ marginTop: 8 }}>
    <label style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 3, display: 'block' }}>
      生图提示词
    </label>
    <textarea value={editImagePrompt} onChange={e => setEditImagePrompt(e.target.value)}
      onClick={e => e.stopPropagation()}
      style={{
        width: '100%', minHeight: 50, padding: 6, borderRadius: 'var(--rounded-sm)',
        border: '1px solid var(--color-hairline)', fontSize: 11, resize: 'vertical',
        marginBottom: 6, fontFamily: 'inherit',
      }} placeholder="【镜头构图】→【场景主体】→..." />

    <label style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 3, display: 'block' }}>
      生视频提示词
    </label>
    <textarea value={editVideoPrompt} onChange={e => setEditVideoPrompt(e.target.value)}
      onClick={e => e.stopPropagation()}
      style={{
        width: '100%', minHeight: 40, padding: 6, borderRadius: 'var(--rounded-sm)',
        border: '1px solid var(--color-hairline)', fontSize: 11, resize: 'vertical',
        fontFamily: 'inherit',
      }} placeholder="视频描述..." />
  </div>
)}
```

**Step 3：按钮改为使用编辑后的提示词**

```tsx
<button onClick={(e) => { e.stopPropagation(); handleGenerateImage(); }}
  disabled={!!generatingImage || !editImagePrompt.trim()}
  style={actionBtnStyle(scene.imageStatus, generatingImage)}>
  {imageLabel}
</button>
<button onClick={(e) => { e.stopPropagation(); handleGenerateVideo(); }}
  disabled={!!generatingVideo || !editVideoPrompt.trim()}
  style={actionBtnStyle(scene.videoStatus, generatingVideo)}>
  {videoLabel}
</button>
```

**Verification:**
- 点击"展开提示词 ▼" → 显示 imagePrompt + videoPrompt textarea
- 编辑提示词后点"生成图片" → 调用 AI 接口（使用编辑后的提示词）
- 按钮在没有提示词时禁用
- `npx tsc --noEmit` 零错误

**Step 4: Commit**

```bash
git add AIStoryboardClient/src/components/scene/SceneCard.tsx AIStoryboardClient/src/stores/projectStore.ts
git commit -m "feat: add expandable prompt editor to scene cards"
```

---

### 任务 4：分镜生成进度反馈

**Objective:** 点击"生成分镜脚本"后显示详细进度（非仅按钮文字变化）

**Files:**
- Modify: `AIStoryboardClient/src/stores/projectStore.ts`
- Modify: `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx`
- Create: `AIStoryboardClient/src/components/common/GenerationProgress.tsx`

**Step 1：在 store 添加进度状态**

```tsx
// 在 ProjectState interface 添加：
scriptGenerationStatus: 'idle' | 'generating' | 'done' | 'error';
scriptGenerationMessage: string;
```

**Step 2：修改 generateScript action**

```tsx
generateScript: async (projectId, scriptText, creationType, aspectRatio, model) => {
  set({ isLoading: true, scriptGenerationStatus: 'generating', scriptGenerationMessage: '正在连接 AI...' });
  try {
    set({ scriptGenerationMessage: 'AI 正在分析剧本，拆解分镜...' });
    await aiApi.generateScript({ projectId, scriptText, creationType, aspectRatio, model });
    set({ scriptGenerationMessage: '分镜生成完成，正在加载...' });
    await get().loadProject(projectId);
    set({ isLoading: false, scriptGenerationStatus: 'done', scriptGenerationMessage: '' });
  } catch (e: any) {
    set({ isLoading: false, scriptGenerationStatus: 'error', scriptGenerationMessage: e?.response?.data?.message || '生成失败' });
  }
},
```

**Step 3：创建 GenerationProgress 组件**

```tsx
// GenerationProgress.tsx
import { useProjectStore } from '../../stores/projectStore';

export function GenerationProgress() {
  const { isLoading, scriptGenerationStatus, scriptGenerationMessage } = useProjectStore();

  if (scriptGenerationStatus === 'idle') return null;

  return (
    <div style={{
      padding: '10px var(--space-md)',
      background: scriptGenerationStatus === 'error' ? '#fef2f2' : 'var(--color-canvas)',
      borderBottom: '1px solid var(--color-hairline)',
      display: 'flex', alignItems: 'center', gap: 8, fontSize: 13,
    }}>
      {scriptGenerationStatus === 'generating' && (
        <>
          <span style={{ animation: 'spin 1s linear infinite' }}>⏳</span>
          <span style={{ color: 'var(--color-body)' }}>{scriptGenerationMessage}</span>
        </>
      )}
      {scriptGenerationStatus === 'done' && (
        <span style={{ color: 'var(--color-success)' }}>✅ 分镜生成完成</span>
      )}
      {scriptGenerationStatus === 'error' && (
        <span style={{ color: 'var(--color-error)' }}>❌ {scriptGenerationMessage}</span>
      )}
    </div>
  );
}
```

**Step 4：在 EditorPage 中嵌入**

```tsx
// 在 <AppHeader /> 和 <ProjectHistoryPanel /> 之间添加：
<GenerationProgress />
```

**Verification:**
- 点击"生成分镜脚本" → 顶部出现 ⏳ + "正在连接 AI..." → "AI 正在分析剧本..." → ✅ 完成
- 生成失败 → ❌ + 错误信息
- `npx tsc --noEmit` 零错误

**Step 5: Commit**

```bash
git add AIStoryboardClient/src/stores/projectStore.ts AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx AIStoryboardClient/src/components/common/GenerationProgress.tsx AIStoryboardClient/src/pages/EditorPage.tsx
git commit -m "feat: add generation progress indicator with status messages"
```

---

### 任务 5：剧本输入面板折叠与拉伸

**Objective:** ScriptInputPanel 可通过按钮折叠/展开，折叠后只显示一个小标签

**Files:**
- Modify: `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx`
- Modify: `AIStoryboardClient/src/pages/EditorPage.tsx`

**Step 1：添加折叠状态**

```tsx
const [collapsed, setCollapsed] = useState(false);

// 折叠时只显示一个垂直标签
if (collapsed) {
  return (
    <div style={{
      width: 36, minWidth: 36, borderRight: '1px solid var(--color-hairline)',
      background: 'var(--color-canvas)', cursor: 'pointer',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      writingMode: 'vertical-rl', fontSize: 12, color: 'var(--color-muted)',
    }} onClick={() => setCollapsed(false)}>
      剧本输入 ▶
    </div>
  );
}
```

**Step 2：面板标题栏添加折叠按钮**

```tsx
// 替换现有 <h2>剧本输入</h2>：
<div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
  <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', margin: 0 }}>剧本输入</h2>
  <button onClick={() => setCollapsed(true)}
    style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 14, color: 'var(--color-muted)', padding: '2px 4px' }}>
    ◀
  </button>
</div>
```

**Verification:**
- 点击 ◀ → 面板折叠为 36px 宽竖排"剧本输入 ▶"
- 点击竖排标签 → 展开回 320px 宽
- `npx tsc --noEmit` 零错误

**Step 3: Commit**

```bash
git add AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx
git commit -m "feat: add collapsible script input panel"
```

---

### 验证

全部完成后：

```bash
cd AIStoryboardClient && npx tsc --noEmit   # 类型检查
npm run build                                 # 构建验证
```

### 风险与备注

- **任务 3** 的 `sceneApi.update` 已存在但未导入 SceneCard，需添加导入
- **任务 4** 的"正在分析剧本"消息是模拟的（无法从 API 获取真实进度），实际在 API 返回前显示
- 所有新状态使用内联样式 + CSS Variables，遵循 DESIGN.md
