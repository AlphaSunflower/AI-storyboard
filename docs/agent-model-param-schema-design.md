# Agent 模型参数自描述 Schema（设计定案）

> 落地分支：`feature/model-param-schema`
> 状态：设计定案（评审后进入分切片实现）
> 架构决策：**网关归一化（A）+ 未知状态兜底**（2026-08-26 确认）

## 0. 背景与根痛点

现有 `model_params` 用**固定列**表达参数能力（temperature / n / sizes / qualities / durations / resolutions / aspectRatios / ref* 输入约束），前端 `AgentParamSelector` 用 `PARAM_META` 硬编码 6 个参数键。缺陷：

- **每模型参数不同**：加一个带 `prompt_optimizer` / `style{style_type,style_weight}` / `output_format` / `watermark` / `sequential_image_generation_options{max_images}` / `subject_reference[]` 的模型，就得加列或塞逃生舱。
- **无法表达嵌套**（object / array-of-object）。
- **响应也各异**：图是同步（b64 vs url / data URI 前缀 / 自定义路径 / 单对象 vs 数组），视频是异步（创建→轮询→下载，task_id 字段、status 枚举、media 直链 vs content 端点、download 路径各不相同）。
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
    {"key":"seed","label":"随机种子","type":"number","show":false},
    {"key":"negative_prompt","label":"反向提示词","type":"string","show":true},
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
| `key` | 上游请求体键名（可含点路径，如 `style.style_type` 用于扁平注入） |
| `label` | UI 显示名 |
| `type` | `enum` / `range` / `number` / `bool` / `string` / `object` / `array` / `arrayofobject` |
| `options` | enum 可选项（导入时由「逗号分隔」生成） |
| `min`/`max` | range 边界（导入时由「数字取最大范围」生成） |
| `default` | 默认值（模型名/提示词默认不显示——`model`、`promptKey` 自内建） |
| `show` | 是否在 MoonAgent 卡/分镜参数上展示；`false` 视为隐式透传默认 |
| `role` | `image_upload`（用户上传图注入点）/ `reference`（参考物）/ `response`（响应格式） |
| `cardinality` | `single`（单图=字符串）/ `multi`（多图=数组）——`role:image_upload` 必填 |
| `fields[]` | `object`/`arrayofobject` 的嵌套子字段（递归） |

参数类型语义：`enum`（下拉）、`range`（有边界数字，滑块/数字）、`number`（自由整数，如 seed）、`bool`（开关）、`string`（自由文本，如 negative_prompt/reasoning/instructions）、`object`（嵌套面板，递归 fields）、`array`（原始值数组，如 `[url1,url2]`）、`arrayofobject`（对象数组，每项按 fields 渲染）。

特殊键：`model`（模型，选择器固定）与 `promptKey`（提示词，系统填充）**不由用户编辑、不进 UI**，导入时自动识别。

**role:reference 的两种形态**（注入逻辑 M4 都支持）：① 顶层裸数组——`referenceImages`/`referenceVideos`/`referenceAudios`（MiniMax，值为 url 字符串数组）；② 嵌套对象数组——`subject_reference:[{type,image|image_file}]`（Hailuo），内部媒体字段 `cardinality` 单/多图。两种都靠 `role:reference` 递归声明 + 内部 `role:image_upload` 指示媒体注入点。

### 2.2 response.async（视频异步：创建→轮询→下载）

```json
"response": {
  "async": {
    "taskIdPath":"task_id",
    "statusPath":"status",
    "stateMap":{"pending":"queued","processing":"running","success":"succeeded","fail":"failed"},
    "mediaPath":"video_url",
    "mediaMode":"media_url",
    "downloadPath":"content",
    "errorPath":"error.message",
    "pollIntervalSec":5,
    "timeoutSec":300
  }
}
```

**未知状态兜底**：`stateMap` 未命中的原始状态值，一律归一为 `running`（不误判失败）。

`taskIdPath`/`statusPath`/`mediaPath`/`errorPath` 均为 **JSON 点路径**（支持 `task.status`、`data.video_url`、`error.message`、`content.url` 等嵌套）。

**下载策略 `mediaMode`**：
- `media_url`：轮询成功响应**直接携带最终媒体**（data URI 或 http 直链，如 MiniMax `task.content.url` / `video_url`）→ 网关直接 fetch 该地址转存。
- `content_endpoint`：轮询成功只给引用，需网关**代理下载端点**（如 Laozhang `GET {base}/videos/{id}/content`）→ `downloadPath` 为相对路径模板，替换 `{taskId}` 拼到 base。

### 2.3 response.sync（图片/文本同步：一次出结果）

```json
"response": {
  "sync": {
    "dataPath":"data",
    "dataIsArray":true,
    "itemsAreUrls":false,
    "mediaField":"b64_json",
    "fallbackField":"url",
    "stripDataUri":true,
    "errorPath":"error.message",
    "extraFields":["revised_prompt"]
  }
}
```

- `dataPath`：结果载体点路径（`data` / `result.images` / `data[0]` 语义）。
- `dataIsArray`：`true`=dataPath 指向数组（逐项取图）；`false`=指向单对象（如 `{data:{url:"..."}}`）。
- `itemsAreUrls`：当 items 本身是**裸 url 字符串数组**（如 `{images:["http...","http..."]}`）时置 `true`，此时跳过 `mediaField`，数组元素即最终媒体；`false`=items 是对象数组，逐项取 `mediaField`→`fallbackField`。
- `mediaField`/`fallbackField`：优先读取字段，缺则回退（b64_json / url / 自定义）。
- `stripDataUri`：剥 `data:image/png;base64,` 前缀。
- `errorPath`：错误消息点路径；`extraFields`：要透传给后端的其余字段（如 `revised_prompt`）。

### 2.4 生成中轮询/下载适配要点

- 创建响应 task id 提取按 `taskIdPath`（`task_id`/`id`/`taskId`/`data.id`/`jobId`）。
- 轮询状态按 `statusPath` + `stateMap`（未知→`running` 兜底）。
- 成功媒体按 `mediaPath`（点路径）+ `mediaMode`（直链 fetch / content 端点代理）。
- 失败错误按 `errorPath`（`error.message` / `error` / `message`）。
- 全部路径缺失/解析异常 → 透传上游原始响应 + log.warn，不吞错误。

> ponytail 上限：多段视频 `content[]` 数组拼接暂不支持，`mediaPath` 取首个；多路输出/配混不在此设计范围。

## 3. JSON 一键导入（Admin）

贴一个完整请求体（如 `{"model":"image-01","prompt":"...","aspect_ratio":"4:3","n":3,"style":{"style_type":"www","style_weight":5}}`），工具自省：

- 顶层每个 key 生成一条 param；
- `model` / `prompt`（及指定 promptKey）→ 标为内建，不进 UI；
- string 值 → `enum`（用户填逗号分隔选项）；无选项则 `string` 自由文本；
- number 值 → 由用户选 `range`（填最大范围）或 `number`（自由整数，如 seed）；
- bool → `bool`；对象 → `object`（递归 fields）；数组 → `array`/`arrayofobject`；
- 图生图/参考物：识别 image 类键 → `role:image_upload` + `cardinality`（字符串=single，数组=multi）；`subject_reference` 类 → `role:reference`（嵌套字段递归，内部 image 字段标 image_upload）。

## 4. 四类适配矩阵（请求 + 响应全覆盖验证）

> 下列为真实样本，逐条映射到 schema，证明"不管什么模型接口都能适配"。

### 4.1 文生图（text-to-image，同步图）

- 请求 `image-01`：`{model,prompt,aspect_ratio:"4:3",response_format:"url",n:3,prompt_optimizer:true,style:{style_type,style_weight}}`
  → model/prompt 内建；aspect_ratio=enum；response_format=enum(role:response)；n=range；prompt_optimizer=bool；style=object{style_type enum, style_weight range}。✓
- 请求 `doubao-seedream`：`{model,prompt,size:"2K",output_format:"png",watermark:false}`
  → size=enum{...2K...}；output_format=enum；watermark=bool。✓
- 响应：`{created,data:[{b64_json|url,revised_prompt}]}`；`{data:{url}}`；`{data:[{url}]}`
  → sync：dataPath=data,dataIsArray=true,mediaField=b64_json,fallbackField=url,stripDataUri,extraFields=[revised_prompt]。单对象时 dataIsArray=false。✓

### 4.2 图生图（image-to-image，同步图）

- 请求 `image-01`：`{model,prompt,aspect_ratio:"16:9",subject_reference:[{type:"character",image_file:"url"}],n:2}`
  → subject_reference=arrayofobject(role:reference){type enum, image_file string(role:image_upload,cardinality:single)}。✓
- 请求 `doubao`：`{model,prompt,image:"url",size,output_format,watermark}` → image=string(role:image_upload,cardinality:single)。✓
- 请求 `doubao` 多图融合：`{model,prompt,image:[u1,u2],sequential_image_generation:"auto",sequential_image_generation_options:{max_images:3},size,output_format,watermark}`
  → image=array(role:image_upload,cardinality:multi)；sequential_image_generation=enum；sequential_image_generation_options=object{max_images range}。✓
- 响应：同 4.1 sync（`data[]` b64/url，单/多图取自 `data` 数组长度）。✓

### 4.3 文生视频（text-to-video，异步）

- 请求 `MiniMax-Hailuo-2.3`：`{model,prompt,duration:6,resolution:"768P",prompt_optimizer:true}`
  → duration=range{4..15}；resolution=enum{768P,2K,...}；prompt_optimizer=bool。✓
- 网关 MiniMax 上游实际体：`{model,content:[{type:"text","text":prompt}],resolution,duration,ratio}`（内容数组是网关转换层职责，不占 schema）；Laozhang multipart 字段是网关转换层职责，不占 schema。✓
- 创建响应：`{task_id}` / `{id}` / `{taskId}` → taskIdPath。✓
- 轮询响应：`{status:"queued"|"running"|"succeeded"|"failed",progress}` 或 MiniMax `{task:{status,model,error:{message}}}` → statusPath=status（或 task.status）+ stateMap（含 PENDING/PROCESSING/SUCCESS 等）+ 未知兜底 running。✓
- 下载：MiniMax 成功 `task.content.url`(直链)→ mediaMode=media_url；Laozhang `GET /videos/{id}/content` → mediaMode=content_endpoint, downloadPath=content。✓

### 4.4 图生视频 / 参考物生视频（image-to-video / reference-to-video，异步）

- 请求 `S2V-01`：`{prompt,subject_reference:[{type:"character",image:["url"]}],model:"S2V-01"}`
  → subject_reference=arrayofobject(role:reference){type enum, image array(role:image_upload,cardinality:multi)}。模型在 JSON 末尾仅键序问题，无影响。✓
- 请求 MiniMax i2v（网关转换层）：imageUrl→content[{type:image_url,role:first_frame}]；referenceImages/Videos/Audios→content 内对应 role 段。M4 注入时 `role:image_upload`（首帧）与 `role:reference`（参考素材）映射到网关已识别的 `first_frame`/`reference_*` 角色。✓
- 创建/轮询/下载响应：同 4.3 async。✓

**结论**：四类请求/响应均可由上述 schema 表达；换模型 = 填一份 spec（键名/type/options/stateMap/路径），**核心零改动**。已知非目标：multipart `/images/edits` 原生字节流、纯二进制图响应、多段视频拼接（ponytail 上限见 §2.4）。

## 5. 归一化责任归属：边缘用网关（A）

- **协议转换下沉网关（现有设计原则）**。网关读每模型 spec，把上游参差响应统一成 OpenAI 契约。
- 后端（AIStoryboardBackend / MoonAgent）保持**单一通用解析器**：图认 `{data:[{b64_json|url}]}`，视频认 `{taskId,status(succeeded/failed/running),url,error}`。
- 网关对 Gemini 图已做归一化（`GeminiFormatConverter`），此设计是其通用化扩展。
- 已存在硬编码归一处（`VideoGatewayServiceImpl` create 提 task_id / `normalizePoll` switch / download 按渠道取 media），**Milestone 2 改为数据驱动**，用 `stateMap`、`taskIdPath`、`mediaPath`(dot-path)、`mediaMode`、`downloadPath` 替换硬编码分支。

## 6. 落地切片（每步 mvn compile 验证）

- **M1（已完成）**：`params_schema` 列（V8 迁移，已执行）+ 实体/Request/VO 字段 + admin 可存可回显。**设计文档**。
- **M2**：网关归一化数据驱动——`VideoGatewayServiceImpl` create/poll/download 读 spec（taskIdPath/statusPath/stateMap+兜底/mediaPath/mediaMode/downloadPath）；图响应归一化（`/v1/images/generations` 按 sync spec 重打包，含 dataIsArray/itemsAreUrls）。**高风险，单独验证**。
- **M3**：`/v1/models` 的 `params` 携带完整 spec（前端/MoonAgent 消费）。
- **M4**：MoonAgent 合并器（按 spec 注入请求体 + role:image_upload 注入用户上传图/参考物 + promptKey 填提示词，取值优先级 用户>LLM 推荐>schema 默认）。
- **M5**：`buildModelOptionsText` 从 spec 生成（取代写死的 resolutions/durations/aspectRatios/sizes/qualities 分支）。
- **M6**：前端 `AgentParamSelector` 重构为 schema 驱动通用渲染（enum/range/number/bool/object；show:false 隐式；model/prompt 不渲染）。
- **M7**：Admin schema 编辑器 + JSON 一键导入。

## 7. 关键约定

- `stateMap` 未知值 → `running`（兜底，防漏配误判失败）。
- 取值优先级：**用户提交 > LLM 推荐 > checkpoint plan 原值 > schema default > config 默认**（沿用现有约定）。
- 图生图/参考物为**模型级能力**（`modes[]`），支持才给开关。
- `show` 控制**是否在 MoonAgent 卡/分镜参数上渲染**；`false` 仍透传（不进 UI）。
- 响应归一化失败（字段缺失/解析异常）→ 透传上游原始响应 + log.warn，不吞错误。
