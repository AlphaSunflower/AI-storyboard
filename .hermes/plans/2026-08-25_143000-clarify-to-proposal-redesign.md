# 意图明确+提示词模糊时的交互重设计（最终版）

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 意图明确但提示词模糊时，保留原多维度分析 + 额外增加人工介入步骤（有剧本/提示词吗？）

**Architecture:** SKILL.md clarify prompt 增加"意图明确但提示词模糊"判断场景；Handler 在 clarify 后插入 source gate HITL。

---

## 最终交互流程

```
用户："生成分镜"
  │
  ├─ 1. 多维度分析（保留原样）
  │     LLM 判断：type=0（意图明确但提示词模糊）
  │     message: "你的意图是生成分镜，但还没有给出具体内容方向。
  │               · 产品宣传片（科技感、6个镜头）
  │               · 动漫短片（热血、快节奏）"
  │     options: [日系动漫·热血·16:9横版] [写实·温馨·9:16竖版] [✍ 自定义输入]
  │
  ├─ 2. 用户选选项/自定义补充（保留原样）
  │
  ├─ 3. ★ 新增：剧本来源人工介入 ★
  │     "你有现成的剧本或提示词吗？有的话可以先填写，没有也没关系，我来帮你生成。"
  │     [有，我来填写] → 用户输入剧本 → 进剧本优化
  │     [没有，帮我生成] → LLM 自由生成
  │
  └─ 4. HITL 确认卡（满意/不满意/继续完善）
```

## 改动文件

| 文件 | 改动 |
|---|---|
| `skills/aisplit/SKILL.md` | requirement-clarify 增加"意图明确但提示词模糊"场景判断 |
| `skills/pic/SKILL.md` | image-clarify 增加"意图明确但提示词模糊"场景判断 |
| `skills/video/SKILL.md` | video-clarify 增加"意图明确但提示词模糊"场景判断 |
| `AisplitIntentHandler.java` | +showScriptSourceGate + script-source resume 分支 |
| `PicIntentHandler.java` | +showPromptSourceGate + handleImagePrompt + prompt-source resume |
| `VideoIntentHandler.java` | +showVideoSourceGate + video-source resume 分支 |
| `PlanGraph.java` | CP_ACTION_TO_INTENT 注册 3 个新 checkpoint action |
