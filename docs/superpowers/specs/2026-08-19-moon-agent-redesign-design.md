# Moon 智能体界面重构设计(AIAgentClient)

- 日期:2026-08-19
- 分支:`feature/moon-agent-microservice`
- 范围:AIAgentClient(独立嵌入式前端,:5174)——视觉重塑 + 功能对齐主前端 + 项目/分镜小窗
- 设计依据:`claude/DESIGN.md`(Anthropic 温暖编辑风 token 规范)

## 1. 设计 Read 与基调

重构 AI 助手产品界面(嵌入式前端 AIAgentClient),受众是分镜创作者。
基调 = Anthropic 温暖编辑风:奶油画布 + 珊瑚点缀 + 克制产品 chrome。
经视觉伴侣三轮确认:B 方向(全浅色 Claude.ai 风)+ 变体 2(无衬线 + 平嵌侧栏)+ 组件样张(17px 字号)。

| 拨盘 | 值 | 理由 |
|------|----|----|
| DESIGN_VARIANCE | 5 | 产品工具,克制,不对称靠内容自然产生 |
| MOTION_INTENSITY | 4 | 保留 GSAP 入场,轻量 |
| VISUAL_DENSITY | 3 | 对话应用,宽松 |

## 2. 视觉系统

### 2.1 布局

- 全浅色:整页画布 `--color-canvas` #faf9f5,无深色块
- 侧栏 240px 平嵌:`--color-surface-soft` #f5f0e8 背景 + 右侧 1px `--color-border-light` 分隔
- **侧栏可左右拉伸**:240px 默认,拖拽范围 200-400px。实现参照主前端 EditorPage 的 resizer 模式——侧栏与对话区间 4px 拖拽把手(`cursor: col-resize`、hover 变主色、`flexShrink: 0`),onMouseDown 记录起点、onMouseMove 更新宽度、onMouseUp 结束;宽度用 useState 存 store 之外(局部 state,刷新回默认)。
- 对话区 flex:1;消息列 max-w 780px 居中
- 弹窗:400px 右侧滑出(保留现状),背景画布色,hairline 分隔
- 素材产出收头部弹窗(现状保留,不移动)

### 2.2 字体与字号

- 全无衬线(系统 sans,保留 `--font-body`),**不使用中文衬线**
- 正文 17px(用户偏好 ≥17px);次级 14-15px;卡片标题 17px 600;空状态标题 20px 600
- 代码/ID 用 `--font-mono`

### 2.3 圆角体系(形状一致性锁)

| 层级 | 值 | 用途 |
|------|----|----|
| 按钮/输入/select | 8-10px | 主按钮、输入框、选择器 |
| 卡片 | 16px | HITL/视频方案/确认结果卡、项目卡、空状态容器 |
| 气泡 | 18px(非对称 4px 角) | 消息气泡 |
| pill | 9999px | 空状态快捷提示 |

### 2.4 组件语言(与 DESIGN.md token 对齐)

- 主按钮:珊瑚实底 #cc785c、白字、hover 亮 5%、active scale 0.98
- 次按钮:白底 + 1px hairline 描边、muted 文字
- 用户气泡:珊瑚实底,圆角 `18px 18px 4px 18px`
- 助手气泡:`--color-surface-soft` #f5f0e8,圆角 `4px 18px 18px 18px`
- HITL/方案/结果卡片:白底 + hairline 描边 + `0 1px 6px rgba(20,20,19,0.04)` 阴影,圆角 16px
- HITL 选项垂直堆叠一行一个(用户既有约定),主操作珊瑚、自定义输入白底描边
- 参数选择器:内嵌 `--color-canvas` 底 + hairline 描边圆角 10px;模型/参数下拉白底;推荐理由珊瑚色
- 空状态:64px surface-card 圆角图标容器 + 标题 + 副文案 + pill 快捷提示

## 3. 项目弹窗(改造 ProjectModal)

**现状**:手输 projectId 文本框。**改为**:按用户拉取项目列表选择。

- 打开时 `GET /api/projects`(JWT 自动带 userId,后端 `listByUser` 过滤)
- 列表:项目卡片(名称 + updatedAt + scenes 数量),点击即选中
- 选中后:`setProjectId(id)` → `loadConversations()`(该项目的会话)→ 清空 messages/waitingHumanInput/streamError/assets
- 顶部显示当前项目名 + 「切换」入口;空列表提示「暂无项目,请先在分镜系统中创建」
- 数据与 AI 分镜系统共享(同一主后端 projects 表,无新后端改动)

## 4. 分镜小窗(改造 StoryboardModal)

**现状**:跳转新页面 `/storyboard`。**改为**:400px 弹窗内两级导航(列表 ↔ 预览)。

- 列表页:`GET /api/projects/{id}` 取 scenes,渲染分镜行(sceneNumber + scriptContent 单行摘要 + imageUrl 缩略图或生成状态徽标)
- 点击分镜 → 预览页:scriptContent 全文、imageUrl/imageUrls 图(点击放大灯箱)、videoUrl 视频播放、镜头参数行(景别 shotType / 运镜 cameraMovement / 时长 duration / 负面提示词 negativePrompt)
- 预览页顶部返回按钮(ChevronLeft)回列表
- 未选项目:引导「请先选择项目」(跳转项目弹窗)
- 只读展示;写操作(增删改分镜/生成)仍在主前端编辑器完成

## 5. 功能对齐(补齐主前端已有能力)

### 5.1 store(agentStore.ts)新增状态与动作

| 状态/动作 | 说明 |
|-----------|------|
| `confirmResult: ConfirmResultInfo \| null` | confirm_result 事件 → 看图确认卡 |
| `waitingVideoPlan: VideoPlanInfo \| null` + `submitVideoPlan(actionId, params?)` | video_plan 事件 → 图生视频方案卡 |
| `pendingPicUrl: string \| null` + `refineAsset()` / `dismissConfirm()` / `cancelRefine()` | 继续完善流程:点继续完善后暂存 PicUrl,聚焦输入框,下一条消息随发 |
| `refImageUrl` + `setRefImageUrl` + `uploadRefImage(file)` | 参考图上传(AgentInputBox 📎) |
| `submitHumanInput(actionId, customText?, params?, assetIds?)` | 参数/资产选择透传(api 层已支持) |
| sendMessage 支持 `opts.picUrl` | 图改图/图生视频参考图随消息发送 |
| SSE 事件处理补全 | `confirm_result` / `video_plan` / `task_accepted` 分支(api 层 SseEvent 类型已完整) |

### 5.2 新组件(从主前端移植并套用新视觉)

| 组件 | 来源 | 变更 |
|------|------|------|
| `VideoPlanCard` | 主前端同名 | 白卡 16px、参数选择器新样式 |
| `ConfirmResultCard` | 主前端同名 | 同上,图片灯箱 |
| `AgentParamSelector` | 主前端同名 | 无衬线、下拉 8px 圆角、推荐理由珊瑚 |
| `ImagePreviewModal` | 主前端同名 | 灯箱,遮罩 rgba(20,20,19,0.6) |

### 5.3 既有组件升级

- `MessageBubble`:图片点击放大(灯箱)+ 图片加载失败降级「(图片已过期或无法加载)」
- `HumanInputCard`:支持 `info.assets` 资产勾选列表(主前端资产选择卡片)
- `AgentInputBox`:加 📎 参考图上传按钮(选中后缩略图 + 移除)
- `AgentSidebar` → B2 全浅色平嵌(删除全部 `surface-dark` 深色样式)
- `AgentModal` 容器:弹窗内页面切换过渡(GSAP x 淡入)

## 6. 动效

- 保留现有 GSAP 入场(侧栏 nav 项 stagger、气泡 y+opacity、弹窗 x+opacity、HITL 卡)
- 新增:分镜小窗列表↔预览切换过渡;空状态入场保留
- 全部 transform/opacity;不新增滚动监听

## 7. 状态设计(空/加载/错误)

- 项目列表:加载骨架行(3 条 shimmer);失败内联错误 + 重试按钮
- 分镜列表:同上
- 空状态:项目为空「暂无项目…」;分镜为空「该项目暂无分镜」;消息空状态(既有 Moon 欢迎页)
- 流式错误:内联红色提示条(既有)

## 8. 明确不做(边界)

- 不改主前端 AIStoryboardClient 的 agent 组件(旧抽屉界面继续供主页面使用)
- 不动后端/API 层(api/agent.ts 已完整;项目/分镜走既有主后端端点)
- 不引入新依赖(复用 lucide-react / gsap / zustand / 既有 ui 组件)
- 不实现分镜/项目的新建、编辑、删除(只读展示 + 切换)

## 9. 验证方式

- `npx tsc -p tsconfig.app.json --noEmit` + `npm run build`(AIAgentClient)
- `npm run lint`(oxlint)
- 手动:dev 起 5174 → 项目选择 → 会话加载 → 分镜小窗两级导航 → 参考图上传 → 生成流式 → HITL → 确认卡
