# AI 大模型与 Provider 配置清单

## 概述

本项目集成了两个外部 Provider，提供图片生成、视频生成、视觉理解、3D 重建等 AI 能力。

---

## 一、Provider：Laozhang（老张）

### API 密钥

| 用途 | API Key | 配置路径 |
|------|---------|----------|
| 主密钥 | `sk-bQfq7Zzf9OZ5XkdPCf3254E2D3D64673Be76D08f2830EbCd` | `providers.laozhang.apiKey` |
| Sora2Official 密钥 | `sk-8YBdUaxEHTF1sNAHC50fE6A472Ab4111BeEc018fB3294dAc` | `providers.laozhang.sora2Official.apiKey` |

### Base URL 汇总

| URL | 用途 |
|-----|------|
| `https://api.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent` | Gemini 图片生成（完整端点） |
| `https://api.laozhang.ai/v1` | OpenAI 兼容接口（GPT Image、Veo） |
| `https://api.laozhang.ai/v1/chat/completions` | Vision 视觉理解 |

---

### 1. 图片生成模型

| 模型名称 | 路由 | Base URL | API Key | 说明 |
|----------|------|----------|---------|------|
| `gemini-3-pro-image-preview` | Gemini | `https://api.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent` | 主密钥 | Google Gemini 图片生成 |
| `gpt-image-2` | OpenAI 兼容 | `https://api.laozhang.ai/v1` | 主密钥 | **默认模型**，GPT Image 2 |
| `gpt-image-2-vip` | OpenAI 兼容 | `https://api.laozhang.ai/v1` | 主密钥 | 旧名称，等同 `gpt-image-2` |
| `gpt-image-2-official` | Sora2Official | `https://api.laozhang.ai/v1` | Sora2Official 密钥 | 发送 provider model `gpt-image-2` |

**图片生成支持的尺寸：**
- `1K`、`2K`、`4K`

**图片生成支持的宽高比：**
- `auto`、`1:1`、`16:9`、`9:16`、`4:3`、`3:4`、`21:9`、`3:2`、`2:3`、`5:4`、`4:5`

**参考图限制：** 1~5 张

---

### 2. 视频生成模型

| 模型名称 | Base URL | API Key | 说明 |
|----------|----------|---------|------|
| `veo-3.1-fast-generate-preview` | `https://api.laozhang.ai/v1` | 主密钥 | Veo 3.1 快速版 |
| `veo-3.1-generate-preview` | `https://api.laozhang.ai/v1` | 主密钥 | Veo 3.1 质量版 |

**API 路径：**
- 创建任务：`POST /v1/videos`（multipart form data）
- 轮询状态：`GET /v1/videos/{id}`
- 下载结果：`GET /v1/videos/{id}/content`（MP4）

**轮询间隔：** 5000ms
**超时时间：** 600000ms（10 分钟）

**前端模型别名映射：**

| 前端别名 | 实际模型 |
|----------|----------|
| `veo-3.1-fast` | `veo-3.1-fast-generate-preview` |
| `veo-3.1-fast-fl` | `veo-3.1-fast-generate-preview` |
| `veo-3.1-landscape-fast` | `veo-3.1-fast-generate-preview` |
| `veo-3.1-landscape-fast-fl` | `veo-3.1-fast-generate-preview` |
| `veo-3.1` | `veo-3.1-generate-preview` |
| `veo-3.1-fl` | `veo-3.1-generate-preview` |
| `veo-3.1-landscape` | `veo-3.1-generate-preview` |
| `veo-3.1-landscape-fl` | `veo-3.1-generate-preview` |

---

### 3. 视觉理解模型

| 模型名称 | Base URL | API Key | 说明 |
|----------|----------|---------|------|
| `gemini-3-flash-preview` | `https://api.laozhang.ai/v1/chat/completions` | 主密钥 | 当前配置文件中的值 |
| `gemini-2.5-flash` | `https://api.laozhang.ai/v1/chat/completions` | 主密钥 | 代码默认值（未配置时回退） |

**用途：** 分镜表排列、图片描述、提示词优化等文本/视觉理解任务

**超时时间：** 180000ms（3 分钟）

---

## 二、Provider：RunningHub

### API 配置

| 配置项 | 值 |
|--------|-----|
| API Key | `969b6d5d087f4c8b96ac392be362d9bc` |
| Base URL | `https://www.runninghub.cn` |
| 最大并发数 | 3 |

### 3D 多视角重建

| 工作流模板 | 说明 |
|-----------|------|
| `multi-view-restore-v1` | 多视角重建 v1 |
| `multi-view-restore-v2` | 多视角重建 v2 |

**输入：** 多张图片
**输出：** PLY 格式 3D 模型文件

---

## 三、完整模型速查表

| 功能 | 模型名称 | Provider | 密钥 |
|------|----------|----------|------|
| 图片生成（默认） | `gpt-image-2` | Laozhang | 主密钥 |
| 图片生成（Gemini） | `gemini-3-pro-image-preview` | Laozhang | 主密钥 |
| 图片生成（VIP） | `gpt-image-2-vip` | Laozhang | 主密钥 |
| 图片生成（Official） | `gpt-image-2-official` | Laozhang Sora2Official | Sora2Official 密钥 |
| 视频生成（快速） | `veo-3.1-fast-generate-preview` | Laozhang Veo | 主密钥 |
| 视频生成（质量） | `veo-3.1-generate-preview` | Laozhang Veo | 主密钥 |
| 视觉理解 | `gemini-3-flash-preview` / `gemini-2.5-flash` | Laozhang | 主密钥 |
| 3D 重建 | RunningHub 工作流 | RunningHub | RunningHub 密钥 |

---

## 四、配置文件位置

- 主配置：`backend/config/backend.config.json`
- 示例配置：`backend/config/backend.config.example.json`
- 环境变量覆盖：`backend/shared/src/env.ts`
- 模型常量：`backend/shared/src/constants/aiImageGen.ts`
- 视频常量：`backend/shared/src/constants/aiVideoGen.ts`
- Vision 常量：`backend/shared/src/env.ts`（`laozhangVisionModel` 字段）
