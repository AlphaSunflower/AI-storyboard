---
name: video
description: 用户要求生成/制作视频时使用
label: 生成视频
version: "1.0"
model_role: video
tools: [generateVideo]
limits: { max_clarify_rounds: 2 }
triggers:
  - '生成视频|做视频|做动画|视频方案|动画片|短片|视频脚本'
priority: 40
hitl:
  - { id: generate_video, title: 开始生成视频 }
  - { id: refine, title: 继续完善 }
  - { id: custom, title: '✍ 自定义输入' }
---

# 视频生成（video）

有参考图 → 视觉模型视频方案；无图 → LLM 方案。均发 video_plan 卡片，确认后异步生成（task_accepted + 轮询）。

### video_clarify
分析用户视频需求模糊程度，输出 {type, message, options}。type=0 追问，type=1 跳过。

### video_plan
生成视频方案，输出 {message, duration, params, reasons}。
