# Moon 智能体聊天区企业级优化设计(AIAgentClient)

- 日期:2026-08-19
- 分支:`feature/moon-agent-microservice`
- 范围:AIAgentClient 对话区(AgentConversationArea / MessageBubble / AgentInputBox / agentStore / api)
- 目标:对标 ChatGPT/DeepSeek/Claude 企业级对话框,补齐渲染、操作、流式控制能力

## 1. 消息样式(遵循用户既有偏好)

| 角色 | 样式 |
|------|------|
| 助手 | **无气泡 + 左边框**(3px 主色 `--color-primary`)、整宽、17px/1.75、padding-left 16px |
| 用户 | 珊瑚圆角气泡右对齐(`18px 18px 6px 18px`,保留现状) |
| 时间戳 | 消息下方 14px muted(hover 显示),取 `message.createdAt` 格式化 HH:mm |

## 2. Markdown 渲染(新依赖 react-markdown + remark-gfm)

- 完整 GFM:标题/列表/表格/引用/分割线/删除线/任务列表/行内代码
- 自定义组件:
  - 块级代码:墨色底 `#141413` + 语言标签(左上)+ 复制按钮(右上,成功变 ✓ 1.5s)
  - 图片:复用 `ImgWithFallback`(失败降级文案)+ 灯箱(现状保留)
  - 视频/裸图 URL 行:`p` 组件内检测纯文本子节点匹配 → 渲染原生 video / img
  - 链接:target=_blank + 主色
- 样式类 `.md-body` 加进 index.css(表格边框、列表缩进、引用左边框、标题字号层级)

## 3. 流式控制:停止生成

- `api/agent.ts`:`streamChat` / `submitForm` 增加可选 `signal` 参数(透传 fetch)
- `agentStore.ts`:模块级 `activeAbort` + `userStopped` 标记;`stopGenerate()` action abort 并复位
- 中断语义:AbortError 静默(不显示 streamError);已收内容保留;空消息不加「未收到回复」文案;下次 sendMessage 重置标记
- 输入框:streaming 时发送按钮切换为 ⏹ 停止(墨色底 Square 图标),点击 `stopGenerate()`

## 4. 滚动体验

- 离开底部 >80px 时右下角悬浮「回到底部」圆形按钮(白底阴影,ArrowDown),点击平滑滚动

## 5. 消息操作

- 助手/用户消息 hover 显示操作行:时间戳 + 复制按钮(navigator.clipboard,失败降级)

## 6. 明确不做(边界)

- 不改侧栏/弹窗/后端;不加消息点赞/重试/编辑(后端无对应能力);不做全文搜索

## 7. 验证

- `npm install react-markdown remark-gfm` 后 `npx tsc -p tsconfig.app.json --noEmit` + `npm run build` + `npm run lint`
- 手动:流式生成 → 停止 → 复制 → 滚动
