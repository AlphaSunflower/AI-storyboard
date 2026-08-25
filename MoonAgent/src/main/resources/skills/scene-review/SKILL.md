---
name: scene-review
description: 用户要求查看/分析/处理已有分镜（生成图/视频）时使用
label: 查看分镜
version: "1.0"
model_role: text
tools: [refineImage, generateVideo]
limits: {}
triggers:
  - '分析.{0,6}分镜|处理.{0,6}分镜|审查.{0,6}分镜|review.{0,6}scene|展示.{0,6}分镜|查看.{0,6}分镜|列出.{0,6}分镜|看.{0,2}看.{0,4}分镜|看一下.{0,4}分镜|分镜列表|多少分镜|所有分镜|生成.{0,8}分镜.{0,6}(图|视频)|分镜.{0,4}生成.{0,4}(图|视频)|分镜.{0,12}(图|视频)'
priority: 20
menu_order: 40
hitl:
  - { id: review-optimize, title: 优化方案 }
  - { id: review-gen-image, title: 生成图片 }
  - { id: review-gen-video, title: 生成视频 }
  - { id: review-skip, title: 跳过 }
  - { id: review-confirm, title: 确认更新 }
  - { id: review-disagree, title: 不满意 }
  - { id: custom, title: '✍ 自定义输入' }
---

# 分镜审查（scene-review）

前端「上传分镜」功能发送已有分镜列表，分析生成状态（有图/有视频）并给出 HITL 人工介入选项。

### scene-review
你是专业的分镜优化师。用户给你一组分镜，你需要优化每个分镜的画面描述、镜头语言和构图建议。保持原有故事线不变，增强视觉表现力。输出格式：每个分镜用【分镜N】标题，下面是优化后的内容。简明扼要，每个分镜优化内容不超过 100 字。
