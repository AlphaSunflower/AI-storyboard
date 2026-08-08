# LLM 网关后台管理界面 — 设计文档 v2

日期：2026-08-08
状态：待用户审查确认（v2 扩展：用户管理 / 统计 / 通道与模型测试）

## 1. 背景与现状

LLM 网关（`AILLMGateway`，Spring Boot 4 + PostgreSQL 独立库 `llm_gateway`，端口 8083）
自 v2 起已具备后端管理 API 骨架，但缺管理界面，且**缺少用户管理 / 统计 / 连通性测试**三类管理能力。

### 后端现有能力

| 端点 | 说明 |
|------|------|
| `POST /admin/login` | 登录（scrypt），返回 `{accessToken, refreshToken}` |
| `GET/POST /admin/channels`、`PUT/DELETE /admin/channels/{id}` | 渠道 CRUD；Key AES 加密存储，响应脱敏为 `***` |
| `GET/POST /admin/routes`、`PUT/DELETE /admin/routes/{id}` | 模型路由 CRUD（modelName → channelId，defaultParams 为 JSON 文本） |
| `GET/POST /admin/api-keys`、`PUT/DELETE /admin/api-keys/{id}` | 业务调用 Key；签发时 plainKey 仅返回一次 |
| `GET /admin/call-logs?page=&size=&model=` | 调用日志分页（size 上限 50，可按 model 过滤） |

可复用服务层：`UpstreamClient`（postJson/postGemini/get + extractError）、`GatewayRoutingService.route(path, body)`（完整真实转发链路，含 Gemini 格式转换/渠道轮换/日志落库）、`KeyService`（encrypt/decrypt/sha256）、`CallLogService.log(...)`。

鉴权：除 `/admin/login` 外全部 `Authorization: Bearer <accessToken>` + `ROLE_ADMIN`。

## 2. 方案选型

**网关内嵌轻量单页（方案 A，不变）**：`src/main/resources/static/admin-ui/` 三个文件（index.html + app.js + style.css），Spring Boot 托管，访问 `http://<host>:8083/admin-ui/` 即打开。零外部依赖（不用 CDN，适配内网）、零构建、单 jar 部署。放弃 React 独立工程与主项目集成（网关解耦是既有定案）。

### 路径规划（关键坑位）

`/admin/**` 已被 SecurityConfig 保护（JWT + ROLE_ADMIN），**静态页面绝不能放 `/admin/` 下**（会被 AdminJwtFilter 拦截）。静态资源放 `/admin-ui/**`，SecurityConfig 增加 `requestMatchers("/admin-ui/**").permitAll()`。

## 3. 功能范围（6 个页面 + 2 类测试）

| 页面 | 数据来源 | 说明 |
|------|----------|------|
| 登录页 | `POST /admin/login` | 未登录默认视图；401 全局踢回 |
| 仪表盘 | `GET /admin/stats/overview`（新） | 统计卡片 + 7 天趋势 + Top 模型 |
| 渠道管理 | `/admin/channels/**`（已有） | CRUD + 启停 switch + **测试按钮**（新） |
| 模型路由 | `/admin/routes/**`（已有） | CRUD + 渠道下拉 + **测试按钮**（新） |
| API Key | `/admin/api-keys/**`（已有） | 签发（明文一次性弹层）/ 启停 / 删除 |
| 调用日志 | `/admin/call-logs`（已有） | 模型筛选 + 分页 + 错误展开 |
| 用户管理 | `/admin/users/**`（新） | 新建 / 重置密码 / 启停 / 删除（防自锁） |

## 4. 后端新增端点（3 个 Controller + 1 个 Mapper 扩展）

### 4.1 用户管理 `AdminUserController`（新）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/users` | 列表（id/username/role/status/createdAt，**不含 passwordHash**） |
| POST | `/admin/users` | 创建：username + password 必填（scrypt 哈希，同 init runner N=16384）；role 默认 `admin`；用户名重复 40001 |
| PUT | `/admin/users/{id}` | 更新：`password` 非空才重置；`status` 可改 enabled/disabled |
| DELETE | `/admin/users/{id}` | 逻辑删除（MyBatis-Plus @TableLogic） |

- **防自锁**：不能对**当前登录用户**执行禁用/删除/改密码（40301「不能操作当前登录账号」）；当前用户名从 `SecurityContextHolder` 取（AdminJwtFilter 已写入）
- **最后管理员保护**：禁用或删除 `role=admin` 用户前，校验剩余 enabled 管理员 ≥ 1，否则 40301（防止全部管理员被锁死、网关失去管理入口）
- role 字段本期仅 `admin` 单角色，API 支持设置但 UI 只读展示（多角色体系 YAGNI）
- AdminInitRunner 自举逻辑不变，与之兼容

### 4.2 统计 `AdminStatsController`（新）

`GET /admin/stats/overview` → 单端点聚合，结构：

```
{
  "channels":  { "total": n, "enabled": n },
  "routes":    { "total": n },
  "apiKeys":   { "total": n, "enabled": n },
  "users":     { "total": n },
  "calls":     { "total": n, "success": n, "failed": n, "successRate": 0.95 },  // 全量
  "todayCalls":{ "total": n, "success": n, "failed": n, "successRate": 0.9 },   // 当日 00:00 起
  "topModels": [ { "model": "gpt-image-2", "count": 120, "successRate": 0.9 } ], // Top 10
  "trend7d":   [ { "date": "08-02", "count": 35 } ]                              // 近 7 天（含今天）
}
```

- 计数：渠道/路由/Key/用户用 `selectCount`（MyBatis-Plus 逻辑删除自动排除）
- 调用聚合：`CallLogMapper` 新增 `@Select` 方法（PG 语法）：
  - 状态分布：`SELECT status, COUNT(*) FROM call_log GROUP BY status`
  - 今日：`WHERE created_at >= date_trunc('day', now())`
  - Top 模型：`GROUP BY model ORDER BY count DESC LIMIT 10`
  - 7 天趋势：`SELECT to_char(date_trunc('day', created_at), 'MM-DD'), COUNT(*) ... WHERE created_at >= now() - interval '6 days' GROUP BY 1 ORDER BY 1`
- 成功/失败判定：`status = 'success'`（与 CallLogService 落库取值一致，实现时核对）

### 4.3 测试接口（新，2 个端点）

**`POST /admin/channels/{id}/test` — 通道连通性测试**（直连上游，不经过路由）：

- 读渠道 → `KeyService.decrypt` 解密 apiKey
- 按 `type` 构造最小请求（`max_tokens`/`maxOutputTokens` 最小化，仅验证连通不追求正确回复）：
  - `openai_compatible`：`POST {baseUrl}/chat/completions`，`{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}],"max_tokens":1}`
  - `gemini`：`UpstreamClient.postGemini` 发 `{"contents":[{"parts":[{"text":"ping"}]}]}`
  - `minimax`：`POST {baseUrl}/v1/text/chatcompletion_v2`，`{"model":"MiniMax-Text-01","messages":[{"role":"user","content":"ping"}],"max_tokens":1}`
- 超时 10 s；响应 `{ok: true, durationMs}` 或 `{ok: false, durationMs, error}`（错误用 `UpstreamClient.extractError` 或连接异常信息）
- **不落 CallLog**（运维探活，避免污染业务日志）

**`POST /admin/routes/{id}/test` — 模型路由测试**（走真实网关链路）：

- 读路由 → `GatewayRoutingService.route("/chat/completions", {"model": route.modelName, "messages":[{"role":"user","content":"ping"}], "max_tokens":1})`
- 完整走渠道选择 / 格式转换 / 转发 / **真实落一条 CallLog**（与线上调用同路径，可事后在日志页复核）
- 响应 `{ok: true, status: 200, durationMs}` 或 `{ok: false, status, durationMs, error}`
- **注意**：会真实消耗上游 token（max_tokens=1 已最小化），设计上接受

两个端点都放在新 `AdminTestController`，请求头沿用管理 JWT。

### 4.4 汇总改动清单

| 文件 | 改动 |
|------|------|
| `controller/admin/AdminUserController.java` | 新：用户 CRUD + 防自锁 |
| `controller/admin/AdminStatsController.java` | 新：统计聚合 |
| `controller/admin/AdminTestController.java` | 新：通道/路由测试 |
| `mapper/CallLogMapper.java` | 加 4 个 @Select 聚合方法 |
| `config/SecurityConfig.java` | 加 `/admin-ui/**` permitAll |
| `src/main/resources/static/admin-ui/` | 新：index.html + app.js + style.css |

**无 DB 变更、无新依赖、无需 migrations。**

## 5. 前端页面设计

- 布局：左侧导航（~200px）：仪表盘 / 渠道管理 / 模型路由 / API Key / 调用日志 / 用户管理；底部当前用户名 + 退出。hash 路由（`#/dashboard`、`#/channels` 等）
- 设计语言沿用 Anthropic 温暖编辑风：primary `#cc785c`、canvas `#faf9f5`、ink `#141413`、surface `#efe9de`、圆角 8/12 px
- 通用：弹窗表单、删除 confirm、toast（错误透传后端 message）、401 全局踢回登录（清 localStorage token）

### 5.1 仪表盘 `#/dashboard`

- 统计卡片行（6 张）：渠道（启用/总数）、模型路由数、API Key（启用/总数）、用户数、**今日调用（总数+成功率）**、累计调用（总数+成功率）
- 近 7 天调用趋势：**内联 SVG 柱状图**（无依赖手写，坐标轴 + 数值 tooltip）
- Top 模型表（Top 10）：模型名 / 调用次数 / 成功率条（CSS 宽度百分比）
- 空数据态：无调用时显示「暂无调用数据」，不渲染空图表

### 5.2 渠道管理 `#/channels`

- 表格：名称 / 类型徽标（openai_compatible | gemini | minimax）/ BaseURL / 状态 switch / 优先级 / 创建时间 / 操作（测试、编辑、删除）
- 新建/编辑弹窗：name、type 下拉、baseUrl、apiKey（编辑留空=不更换，password 型+小眼睛）、enabled、priority
- **测试按钮** → 调 `/admin/channels/{id}/test`，结果弹窗：成功（绿勾 + 耗时 ms）／失败（红叉 + 错误信息 + 耗时）

### 5.3 模型路由 `#/routes`

- 表格：模型名 / 渠道名（前端加载渠道列表 id→name 映射，停用渠道灰显）/ 默认参数 JSON（截断 + title）/ 创建时间 / 操作（测试、编辑、删除）
- 新建/编辑弹窗：modelName、channelId 下拉、defaultParams JSON 文本域（提交前 JSON.parse 校验）
- **测试按钮** → 调 `/admin/routes/{id}/test`，结果弹窗同渠道测试（含状态码）

### 5.4 API Key `#/api-keys`

- 表格：名称 / 状态 switch / 创建时间 / 操作（删除）
- 新建弹窗：仅 name → 签发后全屏居中一次性 plainKey 弹层（等宽 + 复制 + 警示「仅此一次」），关闭即从内存清除
- 列表永不显示 hash/明文

### 5.5 调用日志 `#/logs`

- 表格：模型 / 渠道（id→name）/ 状态徽标（success 绿 / failed 红）/ 耗时 ms / 错误（截断 + 点击行展开全文）/ 时间
- 顶部模型筛选 + 查询；底部分页（上/下一页 + 页数 + 总数）

### 5.6 用户管理 `#/users`

- 表格：用户名 / 角色（admin 徽标，只读）/ 状态 switch / 创建时间 / 操作（重置密码、删除）
- 当前登录用户行：状态 switch 与删除按钮**禁用**（防自锁，后端同样校验）
- 新建弹窗：用户名 + 初始密码（含显示切换）
- 重置密码弹窗：输入新密码 + 确认
- 删除 confirm；最后管理员行删除后返回 40301 时 toast 展示后端文案

## 6. 取舍说明（v2）

- **砍掉**：多角色权限体系（role 字段保留，仅 admin 单角色）、refresh token 刷新（过期重登，access TTL 1 h）、模型元数据表（"添加模型"即路由管理，模型名+渠道+默认参数已覆盖；如需模型能力描述/上下文长度等元信息，后续再加表）
- **测试接口接受**：真实调用上游消耗极小 token（max_tokens=1）；路由测试落真实日志（与线上同路径，可复核）
- **统计粒度**：今日 + 近 7 天 + Top10 模型，不做自定义时间范围（YAGNI，后续需要再加参数）

## 7. 验证方式

1. `mvn compile`（Windows：`JAVA_HOME="C:\Program Files\Java\jdk-21"` + mvn.cmd）
2. 本地起网关（8083 + 本地 llm_gateway 库，需有真实渠道 Key 才能测通测试接口）：
   - 登录 / 错误密码报错 / 401 踢回
   - 用户管理：新建用户 → 新用户可登录 → 重置密码生效 → 禁用后登录被拒 → 删除 → **禁用自己被拒** → 禁用最后一个 admin 被拒
   - 渠道：CRUD + 启停 switch + 测试按钮（配真实 Key 测通；配错误 Key 测失败文案）
   - 路由：CRUD + 非法 JSON 拦截 + 测试按钮（成功/失败 + 日志页出现该次调用记录）
   - API Key：签发一次性 plainKey → 复制 → 用该 Key 调 `/v1/chat/completions` 验证 → 停用后 401
   - 日志：筛选 + 翻页
   - 仪表盘：卡片数字与日志页一致；7 天趋势与 Top 模型有数据
3. 业务回归：Backend 走网关的 chat/生图/视频不受影响

## 8. 实现计划（确认后）

1. 后端：CallLogMapper 聚合 → AdminStatsController → AdminUserController → AdminTestController → SecurityConfig 放行
2. 前端：style.css → index.html → app.js（登录 → 布局 → 各页 → 仪表盘 SVG 图）
3. 本地起网关自测（curl + 浏览器/computer_use 实测）
4. 中文 commit
