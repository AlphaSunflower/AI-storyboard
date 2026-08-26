# Agent 模型参数自描述 Schema（设计定案）

> 落地分支：`feature/model-param-schema`
> 状态：设计定案（评审后进入分切片实现）
> 架构决策：**网关归一化（A）+ 未知状态兜底**（2026-08-26 确认）

## 0. 背景与根痛点

现有 `model_params` 用**固定列**表达参数能力（temperature / n / sizes / qualities / durations / resolutions / aspectRatios / ref* 输入约束），前端 `AgentParamSelector` 用 `PARAM_META` 硬编码 6 个参数键。缺陷：

- **每模型参数不同**：加一个带 `prompt_optimizer` / `style{style_type,style_weight}` / `output_format` / `watermark` / `sequential_image_generation_options{max_images}` / `subject_reference[]` 的模型，就得加列或塞逃生舱。
- **无法表达嵌套**（object / array-of-object）。
- **响应也各异**：图是同步（b64 vs url / data URI 前缀 / 自定义路径），视频是异步（创建→轮询→下载，task_id 字段、status 枚举、media 内联 vs 引用、download 端点各不相同）。
- **展示/隐藏、图生图/参考物开关、单图/多图上传键** 均无字段表达。

## 1. 核心思想：一张自描述 Schema，同时驱动 4 个面

每模型一份 **JSON Schema（存 `model_params.params_schema`）**，同时驱动：

1. **Admin 编辑器**（schema 编辑器 + 「从请求体 JSON 一键导入」）
2. **前端选择器**（`AgentParamSelector` 通用渲染：enum→下拉 / range→滑块 / bool→开关 / object→嵌套面板）
3. **后端合并器**（把用户提交值 + LLM 推荐 + schema 默认按 key 路径注入上游请求体）
4. **网关归一化器**（把上游响应统一成 OpenAI 契约：图→`{data:[{b64_json|url}]}`，视频→`{taskId,status,url,error}`，其中 status 经 `stateMap` 映射、未知状态兜底 running）

## 2. Schema 形状

### 2.1 request（参数定义，model/prompt 不进 UI）

```json
{
  "modes": ["text-to-image", "image-to-image"],
  "promptKey": "prompt",
  "request": [
    {"key":"aspect_ratio","label":"画幅","type":"enum","options":["1:1","4:3","16:9","9:16","21:9"],"default":"4:3","show":true},
    {"key":"n","label":"生成个数","type":"range","min":1,"max":4,"default":1,"show":true},
    {"key":"prompt_optimizer","label":"提示词优化","type":"bool","default":true,"show":false},
    {"key":"style","label":"风格","type":"object","fields":[
        {"key":"style_type","label":"风格类型","type":"enum","options":["www","photo"],"default":"www"},
        {"key":"style_weight","label":"风格强度","type":"range","min":1,"max":10,"default":5}]},
    {"key":"response_format","label":"响应格式","type":"enum","options":["url","b64_json"],"default":"url","show":false,"role":"response"},
    {"key":"image","label":"参考图","type":"string","role":"image_upload","cardinality":"multi","show":true},
    {"key":"subject_reference","label":"参考物","type":"arrayofobject","role":"reference","show":true,"fields":[
        {"key":"type","label":"类型","type":"enum","options":["character","face","subject"]},
        {"key":"image","label":"图片","type":"array","role":"image_upload","cardinality":"multi"}]}
  ]
}
```

字段语义：

| 字段 | 说明 |
|------|------|
| `key` | 上游请求体键名 |
| `label` | UI 显示名 |
| `type` | `enum` / `range` / `bool` / `string` / `object` / `array` / `arrayofobject` |
| `options` | enum 可选项（导入时由「逗号分隔」生成） |
| `min`/`max` | range 边界（导入时由「数字取最大范围」生成） |
| `default` | 默认值（模型名/提示词默认不显示——`model`、`promptKey` 自内建） |
| `show` | 是否在 MoonAgent 卡/分镜参数上展示；`false` 视为隐式透传默认 |
| `role` | `image_upload`（用户上传图注入点）/ `reference`（参考物）/ `response`（响应格式） |
| `cardinality` | `single`（单图=字符串）/ `multi`（多图=数组）——`role:image_upload` 必填 |
| `fields[]` | `object`/`arrayofobject` 的嵌套子字段（递归） |

特殊键：`model`（模型，选择器固定）与 `promptKey`（提示词，系统填充）**不由用户编辑、不进 UI**，导入时自动识别。

### 2.2 response.async（视频异步：创建→轮询→下载）

```json
"response": {
  "async": {
    "taskIdPath":"task_id",
    "statusPath":"status",
    "stateMap":{"pending":"queued","processing":"running","success":"succeeded","fail":"failed"},
    "mediaPath":"video_url",
    "mediaInline":false,
    "downloadPath":"content",
    "errorPath":"error.message",
    "pollIntervalSec":5,
    "timeoutSec":300
  }
}
```

**未知状态兜底**：`stateMap` 未命中的原始状态值，一律归一为 `running`（不误判失败）。

### 2.3 response.sync（图片/文本同步：一次出结果）

```json
"response": {
  "sync": {
    "dataPath":"data",
    "mediaField":"b64_json",
    "fallbackField":"url",
    "stripDataUri":true,
    "errorPath":"error.message",
    "extraFields":["revised_prompt"]
  }
}
```

### 2.4 支持「生成中轮询/下载」适配

- `taskIdPath`：创建响应里任务 id 的 JSON 路径（`task_id`/`id`/`taskId`/`data.id`/`jobId`）。
- `statusPath` + `stateMap`：轮询响应里状态路径 + 原始值→规范化值映射（未知兜底 running）。
- `mediaPath` / `mediaInline`：成功时媒体在响应里的路径；`true`=内联 data URI 直接下，`false`=地址只是引用，需再走 `downloadPath`。
- `downloadPath`：`downloadPath` 为相对路径模板（如 `content` → `GET {base}/v1/videos/{taskId}/content`）。

## 3. JSON 一键导入（Admin）

贴一个完整请求体（如 `{"model":"image-01","prompt":"...","aspect_ratio":"4:3","n":3,"style":{"style_type":"www","style_weight":5}}`），工具自省：

- 顶层每个 key 生成一条 param；
- `model` / `prompt`（及指定 promptKey）→ 标为内建，不进 UI；
- string 值 → `enum`（用户填逗号分隔选项）；
- number 值 → `range`（用户填 max，min 默认 1 或用户填）；
- bool → `bool`；对象 → `object`（递归 fields）；数组 → `array`/`arrayofobject`；
- 图生图/参考物：识别 image 类键 → `role:image_upload` + `cardinality`（字符串=single，数组=multi）；`subject_reference` 类 → `role:reference`。

## 4. 归一化责任归属：边缘用网关（A）

- **协议转换下沉网关（现有设计原则）**。网关读每模型 spec，把上游参差响应统一成 OpenAI 契约。
- 后端（AIStoryboardBackend / MoonAgent）保持**单一通用解析器**：图认 `{data:[{b64_json|url}]}`，视频认 `{taskId,status(succeeded/failed/running),url,error}`。
- 网关对 Gemini 图已做归一化（`GeminiFormatConverter`），此设计是其通用化扩展。
- 已存在硬编码归一处（`VideoGatewayServiceImpl` create 提 task_id / `normalizePoll` switch / download 按渠道取 media），**Milestone 2 改为数据驱动**，用 `stateMap`、`taskIdPath`、`mediaPath`、`downloadPath` 替换硬编码分支。

## 5. 落地切片（每步 mvn compile 验证）

- **M1（本步）**：`params_schema` 列（V8 迁移）+ 实体/Request/VO 字段 + admin 可存可回显。**设计文档**。
- **M2**：网关归一化数据驱动——`VideoGatewayServiceImpl` 的 create/poll/download 读 spec（taskIdPath/statusPath/stateMap+兜底/mediaPath/downloadPath）；图响应归一化（`/v1/images/generations` 按 sync spec 重打包）。**高风险，单独验证**。
- **M3**：`/v1/models` 的 `params` 携带完整 spec（前端/MoonAgent 消费）。
- **M4**：MoonAgent 合并器（按 spec 注入请求体 + role:image_upload 注入用户上传图/参考物 + promptKey 填提示词，取值优先级 用户>LLM 推荐>schema 默认）。
- **M5**：`buildModelOptionsText` 从 spec 生成（取代写死的 resolutions/durations/aspectRatios/sizes/qualities 分支）。
- **M6**：前端 `AgentParamSelector` 重构为 schema 驱动通用渲染（enum/range/bool/object；show:false 隐式；model/prompt 不渲染）。
- **M7**：Admin schema 编辑器 + JSON 一键导入。

## 6. 关键约定

- `stateMap` 未知值 → `running`（兜底，防漏配误判失败）。
- 取值优先级：**用户提交 > LLM 推荐 > checkpoint plan 原值 > schema default > config 默认**（沿用现有约定）。
- 图生图/参考物为**模型级能力**（`modes[]`），支持才给开关。
- `show` 控制**是否在 MoonAgent 卡/分镜参数上渲染**；`false` 仍透传（不进 UI）。
- 响应归一化失败（字段缺失/解析异常）→ 透传上游原始响应 + log.warn，不吞错误。
