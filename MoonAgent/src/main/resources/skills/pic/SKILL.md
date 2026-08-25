---
name: pic
description: 用户要求生成/修改图片时使用
label: 生成图片
version: "1.0"
model_role: image
tools: [refineImage]
limits: { max_clarify_rounds: 2 }
triggers:
  - '生成图片|画一张|画个|海报|插画|改图|修图|换背景|去掉.{0,4}(元素|人物|物体)|图片优化'
priority: 50
hitl:
  - { id: generate_image, title: 生成图片 }
  - { id: refine, title: 继续完善 }
  - { id: custom, title: '✍ 自定义输入' }
---

# 图片生成（pic）

有参考图 → 视觉模型改图方案 + HITL；无参考图 → LLM 提示词 + HITL 方案确认后才生成。

### image_clarify
判断用户描述是否足以生成明确图片，输出 {type, message, options}。type=0 追问（只问最关键的），type=1 放行。

### image_prompt
生成图片提示词（强制中文输出，卡片直接展示给用户）。
