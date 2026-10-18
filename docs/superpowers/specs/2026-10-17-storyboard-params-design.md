# 分镜生成参数选择（用户偏好 > 模型自选 > 网关能力）设计

> 目标：分镜生产出的每个镜头，其**时长 / 分辨率 / 画幅 / 模型 / 图片参数**必须
> 先满足用户偏好，再看模型自选，最后收敛到网关真实支持的枚举。
> 现状是这条链**完全断的**（用户偏好进不了分镜，网关能力也从不参与校验）。

## 1. 现状与断点

| 环节 | 现状 | 证据 |
|---|---|---|
| 用户偏好入口 | `AgentGenerationPreferences` 已存在，按 `GenerationPreferenceDefaults.TOOL_KEYS` 白名单注入 | 白名单里**没有** `start_storyboard_production` → 偏好永远进不了分镜 |
| 分镜请求载荷 | `StoryboardProductionRequest` 只有剧情/镜头数/资产/角色提示 | **没有**任何生成参数字段 |
| 每镜时长 | 模型自由给，`generateShot` hardcode clamp 1..15，`SceneDurationPlanner` 4..15 | 与模型能力无关（veo 实际只支持 4–8） |
| 分辨率/画幅/模型 | 分镜阶段完全不设，落到 `scenes` 后为 null | 生成时才回落到全局默认 |
| 网关能力 | `GET /v1/models` 的 `params` 已带完整能力（`durations` / `resolutions` / `aspectRatios` / `videoSegmentDuration` / `maxTotalDuration` …） | `GatewayRoutingServiceImpl.buildParams`（`:333-388`） |

实测的网关能力（2026-10-17）：

| 模型 | durations | resolutions | aspectRatios | 分段/总时长上限 |
|---|---|---|---|---|
| MiniMax-H3 | 4–15 | 768P, 2K | 16:9 / 9:16 / 4:3 / 1:1 | 5–60 / 300 |
| veo-3.1-fast | 4–8 | — | — | 5–60 / 300 |

## 2. 优先级（已确认）

```
用户偏好  >  模型显式入参  >  网关能力默认值  >  代码兜底常量
```

**这条链与现有 `GenerationPreferenceDefaults` 的语义相反**：后者是"只补空、不覆盖模型已给的值"。
分镜场景下用户明确选了 2K，模型不得改成 768P，因此：

- 新增 `preferencesOverrideModel=true` 语义，只在 `start_storyboard_production` 生效；
- 被用户偏好覆盖掉模型自选值时，必须回一条 `notes`：「你选了 2K，模型建议 768P，已按你的选择」。

## 3. 数据模型

新增 `StoryboardGenerationParams`（agent-core）：

```java
public record StoryboardGenerationParams(
        String imageModel, String imageSize, String imageQuality, Integer imageN,
        String videoModel, String videoResolution, String videoAspectRatio,
        Integer targetDurationSeconds          // 全片总时长（硬约束）
) {
    public static final StoryboardGenerationParams EMPTY = ...;
}
```

- `StoryboardProductionRequest` 追加第 10 个分量，并保留 9 参构造器（委派 `EMPTY`），
  现有调用点与测试零改动。
- `sceneForStoryboard` 把这些值写进 `scenes`（`imageModel/imageSize/imageQuality/imageN/
  videoModel/videoResolution/videoAspectRatio`），生成链路按分镜级参数覆盖已支持的语义直接生效。

## 4. 校验与收敛（`StoryboardParamResolver`）

读 `gatewayModelService.fetchModels("video" | "image")`（`params` 是 JSON 字符串），逐项收敛：

| 参数 | 规则 | 失败时 |
|---|---|---|
| `videoModel` | 必须在模型列表里 | 回落网关默认视频模型 |
| `videoResolution` | 命中 `resolutions`；否则取**距离最近**的合法值（数值差最小，同差取更高档） | 回 `params.resolutionDefault` |
| `videoAspectRatio` | 命中 `aspectRatios`；否则按画幅比例数值取最近 | 回 `params.aspectRatioDefault` |
| `imageSize` / `imageQuality` | 命中 `sizes` / `qualities` | 回对应 Default |
| 每镜 `durationSeconds` | 收敛到 `durations` 里的最近合法值，且 ≤ `videoSegmentDuration.max` | 回 `durationDefault` |
| 总时长 | ≤ `maxTotalDuration`；超了按镜头数等比压缩每镜时长 | 截断并回 note |
| `targetDurationSeconds` | 硬约束：按镜数分配（复用 `SceneDurationPlanner.allocate`），分配不出（< 镜数×最短）则报错让用户改 | — |

每次收敛都写一条人话 note，随工具回包返回 `appliedParams` + `notes[]`；
SKILL.md 要求模型把 `notes` 里的调整告诉用户（不得静默改用户参数）。

## 5. 工具面

`start_storyboard_production` 新增入参（全部非必填）：

```
video_model, video_resolution, aspect_ratio,
image_model, image_size, image_quality, image_n,
target_duration
```

同时把该工具加进 `GenerationPreferenceDefaults.TOOL_KEYS`，映射：

```
video_model  ← video.model
video_resolution ← video.resolution
aspect_ratio ← video.aspectRatio
image_model  ← image.model
image_size   ← image.size
image_quality ← image.quality
```

## 6. 验收

1. 单测：`StoryboardParamResolver` 的 7 条收敛规则 + 用户偏好覆盖模型自选 + 总时长压缩；
2. 单测：偏好注入后 `StoryboardProductionRequest.generationParams` 与 `scenes` 落值正确；
3. 端到端：带偏好（2K / 9:16 / 总 30s）跑一次 4 镜 → 断言 `scenes.video_resolution=2K`、
   `video_aspect_ratio=9:16`、四镜时长和为 30、回包 `notes` 为空（全部命中）；
4. 端到端：传 `1080P`（不支持）→ 断言收敛到 2K 且 `notes` 含一句人话解释。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 网关不可达时无法校验 | `fetchModels` 返回空 → 用代码兜底常量并标注 `unverified=true`，不阻断生成 |
| 偏好覆盖模型导致提示词与参数不匹配（如 9:16 但提示词写"宽幅"） | 收敛后把最终画幅写进分镜提示词上下文，让模型按画幅写构图 |
| 总时长硬约束导致每镜过短 | 低于 `videoSegmentDuration.min` 时直接报错让用户改，不静默凑 |
