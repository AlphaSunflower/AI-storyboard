---
name: delete
description: 用户要求删除/清空分镜时使用
label: 删除分镜
version: "1.0"
model_role: text
tools: []
limits: {}
triggers:
  - '(?:(?:删(?:去|掉|了)?)|(?=清(?:除|掉|光|空))).{0,6}(?:分镜|剧本|故事板)|(?:分镜|剧本|故事板).{0,8}(?:(?:删(?:去|掉|了)?)|(?=清(?:除|掉|光|空)))'
priority: 10
menu_order: 50
hitl:
  - { id: confirm-delete, title: 确认删除全部分镜 }
  - { id: cancel-delete, title: 取消，保留分镜 }
---

# 删除分镜（delete）

用户说「删除/清空分镜」→ HITL 二次确认 → 删除。必须用户点「确认删除」才执行。

### delete_confirm
确认卡片：说明将删除全部分镜（含已生成图/视频素材），提供确认/取消两选项。
