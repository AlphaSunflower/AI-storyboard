# 设计：video 模型路由编辑配置新增输入约束字段

日期：2026-08-12
状态：已批准

## 目标

AILLMGateway（8083）admin-ui 的 video 类型模型路由编辑弹窗「参数配置」区新增 12 项输入约束配置，存储 + `/v1/models` 透传（与现有 params 同等待遇）。

## 字段清单（12 项 → 17 列，全部 nullable）

| 字段 | 列名 | 类型 | 语义 |
|---|---|---|---|
| 可参考图范围 | ref_images_min / ref_images_max | INT | 参考图张数 min~max |
| 可参考视频范围 | ref_videos_min / ref_videos_max | INT | 参考视频个数 min~max |
| 音频个数范围 | audio_count_min / audio_count_max | INT | 音频文件个数 min~max |
| 音频单段时长 | audio_segment_duration_min / _max | INT | 每段音频秒数 min~max |
| 视频单段时长 | video_segment_duration_min / _max | INT | 每段视频秒数 min~max |
| 总时长上限 | max_total_duration | INT | 总时长 ≤ N 秒 |
| 混合输入文件上限 | max_total_files | INT | 文件总数 ≤ N |
| 视频单个大小 | max_video_size_mb | INT | 单个视频 ≤ N MB |
| 图片单个大小 | max_image_size_mb | INT | 单张图片 ≤ N MB |
| 音频单个大小 | max_audio_size_mb | INT | 单个音频 ≤ N MB |
| API 请求体上限 | max_request_body_mb | INT | 请求体 ≤ N MB |
| 提示词字数上限 | max_prompt_chars | INT | 提示词 ≤ N 字符 |

## 改动面

**V6 migration**：`model_params` 表 ALTER TABLE ADD COLUMN × 17（nullable，兼容存量行）。

**后端 5 文件**：
- `ModelParams` 实体：+17 字段（Integer）
- `ModelParamsRequest` record：+17 参数
- `ModelParamsVO` record：+17 参数
- `ModelParamsServiceImpl.applyNonNull`：拷 17 字段；新增范围校验（min<=max，同 nMin/nMax 校验款）
- `GatewayRoutingServiceImpl.buildParams`：video 组组装——范围类嵌套 `{min,max}`（同 image 组 `n` 对象风格），单值类平铺数字键

**admin-ui（app.js）**：
- `paramsGroups.video`：范围类 min/max 滑块联动（复用 bindParamSliders），单值滑块/输入框
- `saveModelParams`：video 分支取新值
- `loadModelParamsForEdit`：回显新值

**/v1/models 下发契约**（新增键，主后端 GatewayModelServiceImpl 已原样透传 params 字符串，零改动自动生效）：

```json
"params": {
  "refImages": {"min":0,"max":3},
  "refVideos": {"min":0,"max":1},
  "audioCount": {"min":0,"max":2},
  "audioSegmentDuration": {"min":5,"max":60},
  "videoSegmentDuration": {"min":5,"max":60},
  "maxTotalDuration": 300,
  "maxTotalFiles": 10,
  "maxVideoSizeMB": 100,
  "maxImageSizeMB": 10,
  "maxAudioSizeMB": 15,
  "maxRequestBodyMB": 64,
  "maxPromptChars": 2000
}
```

## 明确不做（YAGNI）

- 主后端不做生成时实际校验拦截（仅透传级）
- 不动 text/image 组
- 范围类不做逗号 TEXT 方案（用户选 A：拆 min/max 列）
