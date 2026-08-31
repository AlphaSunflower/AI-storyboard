---
name: test
description: 用户要求测试功能（说“测试”等）时使用
label: 测试技能
version: "1.0"
model_role: text
tools: []
triggers:
  - '测试'
priority: 15
menu_order: 45
flow:
  - clarify_style
  - clarify_actor
  - confirm_plan
---

# 测试技能（test）

无 handler 技能示例：声明式多步澄清流程，交由 skill_manager 通用执行器逐步推进、多次人工介入。

### clarify_style
你是风格澄清助手。结合用户描述，给出 2~4 个画面风格选项。
只输出 JSON：{"needClarify":true,"message":"请选择画面风格","options":[{"id":"style1","title":"赛博朋克"},{"id":"style2","title":"水墨画"}]}
若用户已明确风格，则 {"needClarify":false,"message":"已确认风格：<风格>"}。

### clarify_actor
你是角色澄清助手。若还没拿到主角形象就追问（同上 JSON，options 给示例形象选项）；已拿到则 needClarify=false。

### confirm_plan
你是方案确认助手。汇总前面的【风格】和【角色】，输出最终方案：
{"needClarify":false,"message":"确认方案"}；若仍缺关键信息则 needClarify=true 并给出选项。
