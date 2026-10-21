---
name: ai-storyboard-deployment-handover
description: AI-storyboard 部署交付速查：入口地址、账号密码、MoonAgent 功能/skill/工具清单、可访问站点、上游渠道与自检脚本
license: Apache-2.0
metadata:
  version: 1.0.0
  updated: 2026-10-13
---

# AI-storyboard 部署交付速查

一个 compose 项目拉起全栈（12 个容器）：Postgres / Redis / MinIO / MoonSandbox
+ 6 个 Java 服务（api-gateway、backend、llm-gateway、moon-agent、asset-service、canvas）+ 前端入口。

栈内**不含 Nacos**：服务发现默认关闭（`NACOS_DISCOVERY_ENABLED=false`），ApiGateway 按 `.env` 的
`BACKEND_URI` / `ASSET_SERVICE_URI` / `CANVAS_URI` / `MOON_AGENT_URI` 直连四个上游。

## 一、可访问地址

### 对内（浏览器直接打开）

| 用途 | 地址 | 说明 |
|---|---|---|
| 主站（聊天/项目/分镜/资产库） | `http://localhost/`（迁移后 `http://<目标机IP>/`） | 端口 80，nginx 同时托管主站与画布站 |
| 画布站 | `http://localhost/canvas/` | 节点编排、执行、结果槽 |
| 内嵌 Moon 对话（画布右侧「执行服务」） | `http://localhost/chat?embed=1&canvasLock=1` | 由画布 iframe 加载 |
| 登录页 | `http://localhost/login` | 使用下方主站账号 |
| **模型网关 API**（OpenAI 兼容） | `http://127.0.0.1:8083/v1/...` | chat/completions、images/generations、images/edits、models、videos |
| **模型网关管理后台** | `http://127.0.0.1:8083/admin-ui/` | 渠道 / 路由 / API Key / 模型参数 / 调用日志 / 统计 |
| 网关管理 API | `http://127.0.0.1:8083/admin/**` | 先 `POST /admin/login` 取 JWT |
| MinIO 控制台 | `http://127.0.0.1:9001` | 对象存储 `storyboard` 桶 |

### 仅容器内（不对外发布）

`api-gateway:8080`、`backend:8082`、`llm-gateway:8083`、`moon-agent:8084`、`asset-service:8085`、
`canvas:9090`（上下文路径 `/api/canvas`）、`postgres:5432`（宿主映射 5433）、`redis:6379`、`minio:9000`、`sandbox-runner:8090`。

## 二、账号与密钥

| 对象 | 账号 / 凭据 | 备注 |
|---|---|---|
| **主站（业务）** | `admin@qq.com` / `123456` | role=admin，干净部署：无项目 / 会话 / 分镜 |
| **模型网关管理后台** | `admin` / `admin-init-9d2d14c37360be40` | 另一可用账号 `e2eadmin` / `E2EAdmin123!` |
| 网关 API Key（Agent/画布/资产） | `lg-df377c8084e645ffa34e0dfce1fb3e78` | `llm_gateway.gateway_api_key` 表内 |
| 网关 API Key（主后端） | `lg-5f018f73b71c4bda8b9ebb97268d55a9` | 同上 |
| MinIO | `minioadmin` / `minioadmin123` | 换密码会导致存量对象不可读，勿改 |
| PostgreSQL | `postgres` / `123456` | 宿主 `127.0.0.1:5433`，库 `ai_storyboard`、`llm_gateway` |
| Redis | 无密码 | 仅 `127.0.0.1:6379` |
| 沙箱内部密钥 | `local-moon-sandbox-secret` | runner 与 moon-agent 必须一致 |
| 服务间共享密钥 | `INTERNAL_SECRET`（见 `.env`） | `/api/internal/**`、canvas MCP `X-Internal-Token` |

> 以上全部取自 `deploy/stack/.env`，迁移时整目录带走即可，无需重填。

## 三、对外会访问的站点

### 上游模型渠道（模型网关 `llm_gateway.channel`，12 条启用）

| 渠道 | 类型 | 地址 |
|---|---|---|
| 豆包生成 | openai_compatible | `https://ark.cn-beijing.volces.com/api/v3`（Seedream 生图，含 5.0 Flash） |
| xiaomimimo | openai_compatible | `https://api.xiaomimimo.com/v1`（默认文本/视觉 `mimo-v2.6-pro`） |
| laozhang | openai_compatible | `https://api2.laozhang.ai/v1` |
| deepseek | openai_compatible | `https://api.deepseek.com` |
| aliyun | openai_compatible | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| minimax / minimax-pic | openai_compatible / minimax | `https://api.minimaxi.com`（默认视频 `MiniMax-H3`） |
| gemini | gemini | `https://api2.laozhang.ai/v1beta/models/...:generateContent` |
| localhost-ollama | ollama | `http://localhost:11434/api`（本机 Ollama，未部署时该渠道不可用） |

默认路由：text/vision = `mimo-v2.6-pro`，image = `doubao-seedream-4-0-250828`，video = `MiniMax-H3`。

### 其它外部依赖

- Agent 联网检索：唯一后端 DeepSeek 原生 `web_search`（Anthropic 兼容 Messages + `web_search_20250305`，key 走 `AGENTSCOPE_WEB_SEARCH_API_KEY` 或 `DEEPSEEK_API_KEY`；留空则工具不注册、能力上报为不可用）。自托管 SearXNG 后端已移除
- 沙箱出网白名单（经 squid 代理）：`.pypi.org`、`.pythonhosted.org`（其它域名一律拒绝）
- 仅构建期使用：npm 镜像 `registry.npmmirror.com`、apt 镜像 `mirrors.aliyun.com`、Docker 基础镜像（迁移后无需联网）

## 四、MoonAgent 能力

### 功能

- 流式对话（SSE，`POST /api/agent/conversations/{id}/turns/stream`，事件 `turn_started`→`AGENT_*`→`turn_completed`）
- 剧本 → 分镜生产（草稿/审核/批量写入项目，`storyboard` skill）
- 图像生成与改图（文生图/图生图/批量），视频生成与合并（ffmpeg 已内置）
- 画布编排与执行（建节点/连线/布局/结果槽 + 触发执行；执行命令经 canvas 服务的信箱投给浏览器落地）
- 文档解析（Tika：docx/pdf/xlsx/pptx/md）、资产库检索与引用
- 联网检索（DeepSeek 原生 web_search / web_fetch，抓取页转 Markdown）
- 沙箱执行（Python/shell，`sandbox-runner` 容器内，只读根文件系统 + 出网白名单）
- 异步任务与任务中心（task_list/output/cancel、wait_async_results）、HITL 人工确认（request_human_input）
- 多智能体协作（agent_spawn / agent_send / agent_list）、计划模式（plan_enter/plan_write）、todo 清单

### Skill（6 个，classpath 加载；`AGENTSCOPE_NACOS_ENABLED=false`）

| skill | 触发场景 | allowed-tools |
|---|---|---|
| `canvas-design` | 在画布上查找/创建/更新/连接/布局/删除节点与连线 | 17 |
| `canvas-run` | 执行节点、运行整张画布、查执行状态、取消执行 | 21 |
| `storyboard` | 生成/优化/查看/审查/修改/删除剧情与分镜 | 18 |
| `general` | 普通问答、闲聊、解释、查询会话信息 | 15 |
| `image` | 生成图片、编辑/换背景/去元素、批量生成 | 5 |
| `video` | 生成/完善单段视频、动画、短片 | 3 |

运行时状态接口 `GET /api/agent/conversations/{id}/runtime` 可查看 skills / globalPrompt / mcpMetadata 的来源与服务器清单。

### 工具（共 81 个已注册）

- 画布图操作（12）：`canvas_graph_get_state`、`canvas_graph_list_node_types`、`canvas_graph_get_node`、
  `canvas_graph_create_node(s)`、`canvas_graph_update_node`、`canvas_graph_create_edge`、
  `canvas_graph_import_media(_batch)`、`canvas_graph_create_result_slots`、`canvas_graph_mark_result_slots`、
  `canvas_graph_layout_graph`、`canvas_graph_apply_plan`
  （已删除的 3 个读工具 `get_context` / `list_nodes` / `list_edges` 由 `canvas_graph_get_state` 的分页与 `includeEdges` 覆盖）
- 画布运行（5）：`canvas_create_run`、`canvas_execute_node`、`canvas_execute_graph`、`canvas_get_execution`、`canvas_cancel_execution`
  （后 4 个由 **canvas 服务** 提供，命令经信箱投给打开画布的浏览器执行）
- 分镜（19）：`start/approve/revise_storyboard_production`、`generate_storyboard_image(s)`、`generate_storyboard_video(s)`、
  `list_project_storyboard`、`list/reorder/delete_storyboard_draft_shot`、`upsert/update_project_storyboard_shot`、
  `clear_project_storyboard`、`merge_storyboard_videos`、`save/ get_timeline_manifest`、`choose_storyboard_commit_mode` 等
- 媒体（9）：`generate_image`、`generate_image_batch`、`generate_video`、`refineImage`、`get_generation_task_status`、
  `get_media_critiques`、`assess_generation_risk`、`build_media_spec`、`resolve_reference_assets`
  （`review_generated_media` 默认不注册：生成结果不做自动诊断，只有显式打开 `AGENTSCOPE_CRITIQUE_ENABLED` 才交回模型）
- 文件/文档/沙箱（11）：`read_file`、`write_file`、`edit_file`、`list_files`、`glob_files`、`grep_files`、`execute`、
  `run_python_script`、`read_document`、`list_conversation_documents`、`deliver_artifact`
- 创意与资产（5）：`get/update_creative_context`、`extract_creative_constraints`、`list_asset_groups`、`list_session_assets`
- 任务/协作/计划（19）：`task_list`、`task_cancel`、`task_output`、`wait_async_results`、`list_conversation_tasks`、
  `get_agent_task`、`agent_spawn`、`agent_send`、`agent_list`、`plan_enter`、`plan_exit`、`plan_write`、`todo_write`、
  `request_human_input`、`load_skill_through_path`、`reset_equipped_tools`、`resolve_agent_workflow`
- 联网（2）：`web_search`、`web_fetch`

### MCP 链路（Streamable HTTP）

| MCP server | 地址 | 鉴权 | 工具 |
|---|---|---|---|
| `llm-gateway-media` | `http://llm-gateway:8083/mcp` | `Authorization: Bearer <网关Key>` | `generate_image`、`generate_video` |
| `xiaoyue-canvas`（canvas-server） | `http://canvas:9090/api/canvas/mcp` | `X-Internal-Token` | `canvas_graph_*`、`canvas_execute_*` |

## 五、该有的功能清单（验收用）

**主站**：注册/登录/刷新/登出（JWT + refresh cookie）· 项目增删改查与草稿恢复 · 剧本生成与分镜编辑 ·
AI 生图/生视频与下载 · 资产库（上传/分组/标签/引用/回收站 3 天）· 历史记录 · 用户资料与改密 ·
聊天页（流式、多参考图、文档/图片预览）· 画布入口。

**画布站**：节点库与画布编排（连线/布局/结果槽）· 节点执行与状态 · 资产抽屉/素材引用 ·
通道模型选择 · 生成记录 · 右侧 Moon 对话（执行服务）· 画布封面与项目切换。

**模型网关**：OpenAI 兼容 `/v1`（chat/图片/视频/models）· 多渠道路由与优先级 · 模型参数 Schema ·
调用日志与统计 · 管理后台（渠道/路由/Key/用户/配置）。

**Agent**：见上节「MoonAgent 能力」。

## 六、日常命令与自检

```powershell
cd deploy\stack
docker compose --env-file .env ps                      # 11 个服务应为 running/healthy
powershell -File scripts\verify.ps1                    # 14 项端到端验收（含真实模型调用、SSE）
powershell -File scripts\down.ps1                      # 停栈（保留数据）
docker logs aistoryboard-moon-agent --tail 50          # Agent 日志，找 “[MCP] 已注册远程工具: canvas=true, media=true”
```

迁移到另一台机器：把 `deploy\stack\`（含 `.env`、`db\*.dump`、`offline\`）与 `deploy\docker\minio\data\` 一并拷贝，
在目标机执行 `bash scripts/load-and-up.sh`（无需联网、无需 Maven/Node）。
