# 左侧栏重构 + 参考图优化 计划

## 目标
1. 参考图上传放到生成按钮下方，用 shadcn/ui 组件
2. 剧本栏改为左侧紧贴菜单模式
3. 修复历史项目被遮盖问题，优化左栏整体布局
4. 每个分镜可额外导入参考图供视频/图片生成

---

## 新布局

```
┌─────────────────────────────────────────────────────┐
│ AppHeader                                            │
├─────────────────────────────────────────────────────┤
│ GenerationProgress                                   │
├──────────┬──────────────────┬───────────────────────┤
│ 左菜单    │  分镜列表          │  预览区                │
│ (可折叠)  │  (340px)          │  (flex)               │
│          │                  │                       │
│ 剧本输入  │  分镜卡片 × N     │  图片/视频预览         │
│ 创作类型  │                  │  提示词编辑            │
│ 模型选择  │                  │                       │
│ 画幅     │                  │                       │
│ 参考图   │                  │                       │
│ [生成]   │                  │                       │
│──────────│                  │                       │
│ 历史项目  │                  │                       │
│ ·项目1   │                  │                       │
│ ·项目2   │                  │                       │
└──────────┴──────────────────┴───────────────────────┘
```

左菜单可折叠为 36px 竖排标签，展开约 280px。
历史项目在左菜单底部独立滚动区域。

---

## 任务

### 任务1: 安装 shadcn/ui + 添加 Button 组件

```bash
cd AIStoryboardClient
npx shadcn@latest init -d  # 默认配置
npx shadcn@latest add button
```

### 任务2: 重构左侧栏布局

创建新组件 `AIStoryboardClient/src/components/editor/LeftSidebar.tsx`，整合：
- ScriptInputPanel (内嵌而非独立面板)
- 参考图上传区
- 模型选择器
- ProjectHistoryPanel

EditorPage 改为:
```tsx
<div style={{display:'flex', flex:1}}>
  <LeftSidebar />
  <SceneListPanel />
  <PreviewPanel />
</div>
```

### 任务3: 参考图上传改用 shadcn Button + 文件input

```tsx
import { Button } from '@/components/ui/button';
<Button variant="outline" asChild>
  <label>
    选择参考图
    <input type="file" accept="image/*" hidden onChange={...} />
  </label>
</Button>
```

### 任务4: 分镜额外参考图

在 SceneCard 展开区添加参考图上传：
```tsx
<input type="file" accept="image/*" multiple onChange={handleSceneRefImages} />
```
上传后在生成时传入 referenceImages 参数。
