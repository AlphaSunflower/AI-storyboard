# 视频时长和分辨率预设选择器 实现计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将前端"画幅比例"选择器替换为视频时长+分辨率预设选择器，后端确保参数以字符串形式传递。

**Architecture:** 前端新增 `VIDEO_PRESETS` 配置和 `VideoPresetSelector` 组件，替换 LeftSidebar/ScriptInputPanel 中的 `AspectRatioSelector`。Store 新增 `videoPreset` 状态，`generateVideo` 调用时透传 preset 的 resolution/size/duration/aspectRatio。后端已基本就绪，仅需确认 metadata 中 4K 分辨率正确传递。

**Tech Stack:** React 19 + TypeScript + Zustand 5, Spring Boot 4 + JDK HttpClient

---

## 背景

当前前端侧边栏有"画幅比例"（`AspectRatioSelector`）用于脚本生成，视频生成时硬编码使用后端默认值（720p/8s/16:9）。用户需要为视频生成提供 5 个预设选项，替换画幅比例为时长+分辨率选择。

### 五个预设

| 预设标签 | seconds | duration | size | resolution | aspectRatio | metadata.resolution |
|----------|---------|----------|------|------------|-------------|---------------------|
| 4秒 横屏 720p | "4" | "4" | "1280x720" | "720p" | "16:9" | "720p" |
| 8秒 横屏 720p | "8" | "8" | "1280x720" | "720p" | "16:9" | "720p" |
| 8秒 横屏 1080p | "8" | "8" | "1920x1080" | "1080p" | "16:9" | "1080p" |
| 8秒 竖屏 1080p | "8" | "8" | "1080x1920" | "1080p" | "9:16" | "1080p" |
| 8秒 横屏 4K | "8" | "8" | "3840x2160" | "4k" | "16:9" | "4k" |

### 关键规则

- `seconds` 和 `duration` 传字符串，不传数字
- 1080p 和 4K 只能与 8 秒组合使用
- 4K 的 metadata 中 `resolution` 必须为 `"4k"`
- 图生视频上传本地图片文件（后端已实现）

---

### Task 1: 新增 VIDEO_PRESETS 配置

**Objective:** 在 `config.ts` 中添加视频预设数组和默认预设

**Files:**
- Modify: `AIStoryboardClient/src/config.ts`

**Step 1: 添加预设定义**

在 `DEFAULT_VIDEO_MODEL` 之后添加：

```ts
/** 视频时长+分辨率预设 */
export interface VideoPreset {
  value: string;
  label: string;
  seconds: string;
  duration: string;
  size: string;
  resolution: string;
  aspectRatio: string;
}

export const VIDEO_PRESETS: VideoPreset[] = [
  { value: '4s-720p', label: '4秒 横屏 720p', seconds: '4', duration: '4', size: '1280x720', resolution: '720p', aspectRatio: '16:9' },
  { value: '8s-720p', label: '8秒 横屏 720p', seconds: '8', duration: '8', size: '1280x720', resolution: '720p', aspectRatio: '16:9' },
  { value: '8s-1080p', label: '8秒 横屏 1080p', seconds: '8', duration: '8', size: '1920x1080', resolution: '1080p', aspectRatio: '16:9' },
  { value: '8s-1080p-v', label: '8秒 竖屏 1080p', seconds: '8', duration: '8', size: '1080x1920', resolution: '1080p', aspectRatio: '9:16' },
  { value: '8s-4k', label: '8秒 横屏 4K', seconds: '8', duration: '8', size: '3840x2160', resolution: '4k', aspectRatio: '16:9' },
];

export const DEFAULT_VIDEO_PRESET: string = VIDEO_PRESETS[1].value; // 8s 720p
```

**Step 2: 验证**

```bash
cd AIStoryboardClient && npx tsc --noEmit
```

---

### Task 2: 创建 VideoPresetSelector 组件

**Objective:** 创建预设选择下拉组件

**Files:**
- Create: `AIStoryboardClient/src/components/common/VideoPresetSelector.tsx`

**Step 1: 编写组件**

```tsx
import { VIDEO_PRESETS } from '../../config';

interface VideoPresetSelectorProps {
  value: string;
  onChange: (value: string) => void;
}

export function VideoPresetSelector({ value, onChange }: VideoPresetSelectorProps) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      style={{
        width: '100%',
        fontSize: 12,
        padding: '8px 10px',
        borderRadius: 'var(--rounded-sm)',
        border: '1px solid var(--color-hairline)',
        background: 'white',
        color: 'var(--color-ink)',
        boxSizing: 'border-box',
        fontFamily: 'inherit',
        outline: 'none',
        cursor: 'pointer',
        appearance: 'auto',
        paddingRight: 28,
      }}
    >
      {VIDEO_PRESETS.map((p) => (
        <option key={p.value} value={p.value}>
          {p.label}
        </option>
      ))}
    </select>
  );
}
```

**Step 2: 验证**

```bash
cd AIStoryboardClient && npx tsc --noEmit
```

---

### Task 3: Store 添加 videoPreset 状态并传递预设参数

**Objective:** 在 Zustand store 中添加 `videoPreset` 状态，`generateVideo` 调用时从 preset 获取参数

**Files:**
- Modify: `AIStoryboardClient/src/stores/projectStore.ts`

**Step 1: 添加 videoPreset 到 interface 和初始状态**

在 store interface 中添加：
```ts
videoPreset: string;
setVideoPreset: (p: string) => void;
```

初始值：
```ts
videoPreset: DEFAULT_VIDEO_PRESET,
```

**Step 2: 添加 setter**

```ts
setVideoPreset: (p) => set({ videoPreset: p }),
```

**Step 3: 修改 generateVideo 传递预设参数**

修改 `generateVideo` 实现，从 preset 读取参数：

```ts
generateVideo: async (sceneId, prompt, model, referenceImages, generatedImageUrl) => {
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: true } }));
    try {
      const preset = VIDEO_PRESETS.find(p => p.value === get().videoPreset) || VIDEO_PRESETS[1];
      const res = await aiApi.generateVideo({
        sceneId, prompt, model, referenceImages, generatedImageUrl,
        resolution: preset.resolution,
        size: preset.size,
        aspectRatio: preset.aspectRatio,
        duration: parseInt(preset.duration),
      });
      // ... rest unchanged
```

**Step 4: 验证**

```bash
cd AIStoryboardClient && npx tsc --noEmit
```

---

### Task 4: 替换侧边栏中的画幅比例为预设选择器

**Objective:** LeftSidebar 和 ScriptInputPanel 中将 `AspectRatioSelector` 替换为 `VideoPresetSelector`

**Files:**
- Modify: `AIStoryboardClient/src/components/editor/LeftSidebar.tsx`
- Modify: `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx`

**Step 1: 修改 LeftSidebar**

- 导入 `VideoPresetSelector` 和 `DEFAULT_VIDEO_PRESET`
- 从 store 获取 `videoPreset` 和 `setVideoPreset`
- 添加 `videoPreset` 本地状态（初始 `DEFAULT_VIDEO_PRESET`）
- 将画幅比例部分（`<AspectRatioSelector ...>`）替换为：

```tsx
<div style={{ flexShrink: 0 }}>
  <label style={labelStyle}>时长和分辨率</label>
  <VideoPresetSelector value={videoPreset} onChange={setVideoPreset} />
</div>
```

- `generateScript` 调用时传入 `aspectRatio` 从 preset 中获取
- `createProject` 同理

**Step 2: 修改 ScriptInputPanel** — 同 LeftSidebar

**Step 3: 验证**

```bash
cd AIStoryboardClient && npx tsc --noEmit
```

---

### Task 5: SceneCard 无需修改 — 确认参数流程

**验证:** SceneCard 调用 `generateVideo(sceneId, prompt, model, refs, generatedImageUrl)`，参数在 store 中从 preset 补充。无需额外修改。

---

### Task 6: 后端确认 4K metadata.resolution

**Objective:** 确认当 `resolution="4k"` 时 metadata 正确包含 `"resolution":"4k"`

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/VideoGenerationService.java`

**Step 1: 检查当前 metadata 构建**

当前代码：
```java
String metadata = objectMapper.writeValueAsString(Map.of(
    "durationSeconds", effDuration,
    "resolution", effResolution,
    "aspectRatio", effAspectRatio
));
```

当 `effResolution = "4k"` 时，`objectMapper.writeValueAsString` 会生成 `{"durationSeconds":8,"resolution":"4k","aspectRatio":"16:9"}` — 这已正确。**无需修改**。

**验证:**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

---

### Task 7: 清理 — 移除未使用的 AspectRatioSelector 引用

**Objective:** 确认 `AspectRatioSelector` 不再被引用后可移除

**Files:**
- Check: `AIStoryboardClient/src/components/common/AspectRatioSelector.tsx`
- Modify: 移除 `LeftSidebar.tsx` 和 `ScriptInputPanel.tsx` 中对 `AspectRatioSelector` 的 import

**验证:**

```bash
grep -r "AspectRatioSelector" AIStoryboardClient/src/ --include="*.tsx" --include="*.ts"
# 应无输出（或确认无引用后删除文件）
cd AIStoryboardClient && npx tsc --noEmit
```

---

### Task 8: 完整编译验证

```bash
# 后端
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
echo "BACKEND: $?"

# 前端
cd AIStoryboardClient && npx tsc --noEmit
echo "FRONTEND: $?"
```

---

## 行为矩阵

| 用户选择预设 | 调用 generateVideo 时的参数 |
|------------|---------------------------|
| 4秒 横屏 720p | resolution="720p", size="1280x720", aspectRatio="16:9", duration=4 |
| 8秒 横屏 720p | resolution="720p", size="1280x720", aspectRatio="16:9", duration=8 |
| 8秒 横屏 1080p | resolution="1080p", size="1920x1080", aspectRatio="16:9", duration=8 |
| 8秒 竖屏 1080p | resolution="1080p", size="1080x1920", aspectRatio="9:16", duration=8 |
| 8秒 横屏 4K | resolution="4k", size="3840x2160", aspectRatio="16:9", duration=8 |

后端接收后，seconds/duration 均转为字符串，metadata 中 resolution 随 preset 传递（4K 时为 "4k"）。

---

## 风险

- **脚本生成受影响**: `aspectRatio` 原本从 selector 获取，现在需要从 preset 中取。默认预设 "8s 720p" 的 aspectRatio 为 "16:9"，与之前默认一致。
- **AspectRatioSelector 移除**: 确认无其他引用后安全删除。
