# 资产库 × 分镜/视频生成智能联动 — 设计文档

日期：2026-08-17
状态：已批准（用户确认"没了，执行"）

## 背景

资产库（Asset：character/prop/scene，项目级+全局，含参考图）与 scene_assets（分镜↔资产多对多，purpose=image/video）基建已存在：
- 分镜生成已全量注入资产设定集（buildSheetText）
- 图片/视频生成已按分镜关联（scene_assets）注入设定集+参考图
- **缺口**：场景关联纯手动、视频方案链完全不感知资产库、分镜生成不询问用户

## 需求（四条决策 + 关联门禁）

1. **aisplit 链弹卡片询问资产**：入口检测项目资产 → 弹「资产选择卡片」（human_input 扩展 assets 字段，勾选），无资产照旧
2. **勾选注入**：仅把勾选资产的文字设定集注入分镜生成（generateScenes 支持资产子集）
3. **自动关联**：分镜 JSON 生成后单独调 LLM 判定（分镜列表+勾选资产 → 每镜 assetIds），写库时一并写 scene_assets（purpose 双写 image+video），失败降级不阻塞
4. **video 链同款资产卡片**：勾选后方案生成注入设定集+参考图（≤9 张），video_plan 卡片告知投入资产
5. **关联性门禁（两链共用）**：勾选资产后、正式生成前，LLM 判定「用户提示词 × 勾选资产」关联性；弱关联 → 弹澄清卡片（重新讲清楚/不使用资产/仍然继续），澄清计数复用 max-clarify-rounds；判定失败静默放行

## 数据流

```
aisplit:
  handle → 检测资产 → [资产选择卡片(assets)] → resume
  → 关联门禁(judgeRelevance) → 弱关联? 澄清卡片(可循环,上限max-clarify-rounds)
  → 剧本优化 gate(勾选资产注入) → 分镜方案 → 分镜 JSON
  → matchScenes 判定(存 plan) → 方案确认卡片(展示建议关联)
  → agree/replace/append → 写分镜 + 写 scene_assets(image+video)

video:
  handle → 检测资产 → [资产选择卡片(assets)] → resume
  → 关联门禁(judgeRelevance) → 弱关联? 澄清卡片
  → 方案生成(勾选资产 sheetText + 参考图) → video_plan 卡片(告知投入资产)
  → generate_video → 视频生成(参考图透传)
```

## 改动清单

### 后端

| 文件 | 改动 |
|------|------|
| `service/agent/AssetMatchingService` + `impl/` | 新：`judgeRelevance(prompt, assets)` → {relevant, reason}；`matchScenes(scenes, assets)` → [{sceneIndex, assetIds}]。ChatClient + BeanOutputConverter 纯解析，失败降级 |
| `AgentOrchestratorSupport` | StagePlan 加 `assets`（List<AssetVO>）；human_input 事件负载带 assets；OrchestrationRequest 加 assetIds；通用资产门禁方法 `runAssetGate` |
| `AisplitIntentHandler` | 入口资产卡片；resume 门禁分发；勾选注入 generateScenes；写库后按 matchScenes 写 scene_assets |
| `VideoIntentHandler` | 入口资产卡片；resume 门禁分发；方案注入资产；plan 存 assetIds |
| `ScriptGenerationService.generateScenes` | 加可选 `assetIds` 参数（null=全量注入，现状不变） |
| `AssetService.linkSceneAssets` | 新：批量写 scene_assets（purpose 双写），无归属校验 |

### 前端

| 文件 | 改动 |
|------|------|
| SSE 类型（SseEvent） | human_input 事件加可选 `assets` |
| AgentChatPanel | human_input 渲染资产勾选组件（复用 AgentParamSelector 卡片内嵌模式） |
| api/agent.ts | form/submit body 支持 `assetIds` |

## 范围外（YAGNI）

- 视频分镜标记（每镜双 prompt，双 purpose 已覆盖）
- 关联结果单独确认卡（随方案确认一并确认）
- 左侧边栏一键生成入口（保留现状全量注入）

## 验证

后端 compile（mvn）；前端 `npx tsc -p tsconfig.app.json --noEmit` + build。
