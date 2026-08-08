# LLM 网关后台管理界面 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 LLM 网关（AILLMGateway，8083）实现内嵌管理界面：仪表盘统计、渠道/模型路由/API Key/日志管理、用户管理、通道与模型测试。

**架构：** Spring Boot 托管 `static/admin-ui/` 单页（原生 JS + hash 路由 + 手写 CSS，零依赖）；后端新增 3 个 Controller（用户管理/统计/测试）与 CallLogMapper 聚合查询；SecurityConfig 放行 `/admin-ui/**`（页面放 `/admin/` 下会被 AdminJwtFilter 拦截）。

**技术栈：** Spring Boot 4 + MyBatis-Plus（@Select 聚合）+ PostgreSQL；前端原生 HTML/CSS/JS 单页。

**验证基线：** 项目无 src/test 基础设施（遵循现状，不做 TDD），验证 = `mvn compile` + 本地起网关实测（用户已批准设计文档 v2，路径 `docs/2026-08-08-llm-gateway-admin-ui-design.md`）。

**执行偏好：** 用户偏好 master 直接开发（不建 worktree）、git add 只加计划内文件、中文 commit。

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `AILLMGateway/src/main/java/com/llmgateway/mapper/CallLogMapper.java` | 修改：新增 4 个 @Select 聚合方法（状态分布/今日/Top 模型/7 天趋势） |
| `AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminStatsController.java` | 新建：`GET /admin/stats/overview` 聚合响应 |
| `AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminUserController.java` | 新建：用户 CRUD + 防自锁（不能操作自己/最后管理员保护） |
| `AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminTestController.java` | 新建：`POST /admin/channels/{id}/test` + `POST /admin/routes/{id}/test` |
| `AILLMGateway/src/main/java/com/llmgateway/config/SecurityConfig.java` | 修改：加 `/admin-ui/**` permitAll 一行 |
| `AILLMGateway/src/main/resources/static/admin-ui/style.css` | 新建：Anthropic 温暖编辑风设计 token + 布局/表格/弹窗/toast/徽标样式 |
| `AILLMGateway/src/main/resources/static/admin-ui/index.html` | 新建：登录页 + 侧边导航 + 6 个视图容器骨架 |
| `AILLMGateway/src/main/resources/static/admin-ui/app.js` | 新建：hash 路由、JWT 存取、API 封装、各页渲染、弹窗/表单/测试/SVG 柱状图 |

**无 DB 变更、无新依赖。** 设计文档 v2 为契约（端点路径/请求响应结构已定死，前端按契约开发）。

---

### 任务 1：后端管理端点（用户/统计/测试）

**文件：**
- 修改：`AILLMGateway/src/main/java/com/llmgateway/mapper/CallLogMapper.java`
- 创建：`AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminStatsController.java`
- 创建：`AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminUserController.java`
- 创建：`AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminTestController.java`
- 修改：`AILLMGateway/src/main/java/com/llmgateway/config/SecurityConfig.java`

- [ ] **步骤 1：CallLogMapper 聚合方法**

```java
public interface CallLogMapper extends BaseMapper<CallLog> {
    /** 状态分布（全量） */
    @Select("SELECT status, COUNT(*) AS cnt FROM call_log GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /** 今日（当天 00:00 起）状态分布 */
    @Select("SELECT status, COUNT(*) AS cnt FROM call_log WHERE created_at >= date_trunc('day', now()) GROUP BY status")
    List<Map<String, Object>> countTodayByStatus();

    /** Top 10 模型（按调用次数倒序） */
    @Select("SELECT model, COUNT(*) AS cnt FROM call_log WHERE model IS NOT NULL AND model <> '' GROUP BY model ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> topModels();

    /** 近 7 天每日调用量（含今天，按日期升序） */
    @Select("SELECT to_char(date_trunc('day', created_at), 'MM-DD') AS date, COUNT(*) AS cnt FROM call_log WHERE created_at >= now() - interval '6 days' GROUP BY 1 ORDER BY 1")
    List<Map<String, Object>> trend7d();
}
```

注意：`CallLog` 实体 status 落库取值需核对（`CallLogService.log` 里 success 的 status 字符串，设计文档定为 `'success'`，实现时以源码为准）。

- [ ] **步骤 2：AdminStatsController**

`GET /admin/stats/overview` → `ApiResponse<Map<String, Object>>`，聚合结构（契约见设计文档 4.2）：
- channels/routes/apiKeys/users：`selectCount(null)` 与 enabled 条件计数
- calls/todayCalls：从 countByStatus/countTodayByStatus 拆 success/failed/total，`successRate = success * 1.0 / total`（total=0 时为 0）
- topModels：topModels() 结果补 successRate（需按 model 关联 status 分布——简化：另加一个 `@Select` 按 model+status 统计，或在 topModels SQL 中用 `COUNT(*) FILTER (WHERE status = 'success')` 一次查出）
- trend7d：直接透传

- [ ] **步骤 3：AdminUserController**（防自锁双校验）

| 端点 | 逻辑 |
|------|------|
| `GET /admin/users` | `selectList`（排除 passwordHash：循环 setPasswordHash(null)，参考 AdminApiKeyController 抹 hash 模式） |
| `POST /admin/users` | username/password 非空校验 40001；用户名重复（selectCount eq username > 0）40001；scrypt 哈希（`SCryptUtil.scrypt(pwd, 16384, 8, 1)`，与 AdminInitRunner 一致）；role 默认 admin、status 默认 enabled |
| `PUT /admin/users/{id}` | 40401 不存在；password 非空才重哈希；status 可改；**若目标是当前登录用户**（`SecurityContextHolder.getContext().getAuthentication().getName()` equals 目标 username）→ 40301「不能操作当前登录账号」 |
| `DELETE /admin/users/{id}` | 40401；不能删自己 40301；**最后管理员保护**：目标 role=admin 时，先 `selectCount(eq role=admin, eq status=enabled)`，≤1 → 40301「必须至少保留一个启用的管理员」 |

- [ ] **步骤 4：AdminTestController**

`POST /admin/channels/{id}/test`：
- 40401 渠道不存在；`keyService.decrypt` 解密 apiKey（确认 KeyService 有 decrypt 公开方法，转发已用它解密）
- 按 type 构造最小请求并直发（复用 `UpstreamClient.postJson` / `postGemini`）：
  - `openai_compatible`：`POST {baseUrl}/chat/completions` body `{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}],"max_tokens":1}`
  - `gemini`：postGemini 发 `{"contents":[{"parts":[{"text":"ping"}]}]}`
  - `minimax`：`POST {baseUrl}/v1/text/chatcompletion_v2` body `{"model":"MiniMax-Text-01","messages":[{"role":"user","content":"ping"}],"max_tokens":1}`
- HTTP 2xx → `{ok:true, durationMs}`；否则 `{ok:false, durationMs, error: upstreamClient.extractError(body)}`；异常（ConnectException/超时）→ `{ok:false, error: 异常消息}`
- 超时：`HttpRequest.newBuilder().timeout(Duration.ofSeconds(10))`（UpstreamClient.postJson 内部可能无 timeout——实现时若 postJson 无法传超时，则在此 Controller 内用独立 HttpClient 直发，不强行改 UpstreamClient 签名）
- **不落 CallLog**

`POST /admin/routes/{id}/test`：
- 40401 路由不存在；`routeMapper.selectById` 后调 `gatewayRoutingService.route("/chat/completions", "{\"model\":\"<route.modelName>\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}")`
- `RouteResult.status == 200` 且 body 含 `"choices"` → `{ok:true, status, durationMs}`；否则 `{ok:false, status, durationMs, error}`（error 取 body 里 message 或 extractError）
- 计时用 `System.currentTimeMillis()` 包住 route 调用；**会真实落一条 CallLog**（设计接受，可复核）

- [ ] **步骤 5：SecurityConfig 放行静态页**

`requestMatchers("/admin-ui/**").permitAll()`（放在 `/admin/login` 放行附近）。

- [ ] **步骤 6：编译验证 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AILLMGateway\\pom.xml" compile -q
# 预期：BUILD SUCCESS
git add AILLMGateway/src/main/java/com/llmgateway/mapper/CallLogMapper.java AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminStatsController.java AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminUserController.java AILLMGateway/src/main/java/com/llmgateway/controller/admin/AdminTestController.java AILLMGateway/src/main/java/com/llmgateway/config/SecurityConfig.java
git commit -m "feat: 网关管理后端新增用户/统计/测试端点（防自锁+聚合SQL）"
```

---

### 任务 2：前端管理界面（静态三文件）

**文件：**
- 创建：`AILLMGateway/src/main/resources/static/admin-ui/style.css`
- 创建：`AILLMGateway/src/main/resources/static/admin-ui/index.html`
- 创建：`AILLMGateway/src/main/resources/static/admin-ui/app.js`

- [ ] **步骤 1：style.css** — 设计 token（primary `#cc785c`、canvas `#faf9f5`、ink `#141413`、surface `#efe9de`、radius 8/12 px）+ 登录卡片、侧边栏（200px）、内容区、表格、状态徽标（success 绿 / failed 红 / 类型徽标）、switch、弹窗遮罩、toast、一次性 Key 全屏弹层、SVG 图表容器

- [ ] **步骤 2：index.html** — 登录视图（隐藏）+ 主布局（侧边栏菜单 6 项 + 内容区 6 个 section 容器，默认隐藏按路由切换）；引入 style.css + app.js

- [ ] **步骤 3：app.js** — 核心模块：
  - API 封装：`api(path, options)` 自动带 `Authorization: Bearer <localStorage admin_token>`；非 2xx 或 `res.code !== 0` → 抛 `message`；**401 → 清 token → 显示登录视图**
  - 登录：`POST /admin/login` → 存 token → `location.hash = '#/dashboard'`
  - hash 路由：`hashchange` → 显示对应 section + `loadPage()` 拉数据渲染；未登录一律回登录视图
  - 渠道页：列表渲染 + 新建/编辑弹窗（type 下拉/密码输入+小眼睛/priority 数字）+ switch 启停（PUT 只传 enabled）+ 删除 confirm + **测试按钮** → 结果弹窗
  - 路由页：渠道 id→name 映射（GET channels 缓存）+ 表单弹窗 + defaultParams JSON.parse 校验 + **测试按钮**
  - API Key 页：签发 → 一次性 plainKey 全屏弹层（复制按钮，关闭后变量置空）+ switch + 删除
  - 日志页：模型筛选 + 分页（上/下页、页数、总数）+ 错误行点击展开
  - 用户页：列表 + 新建（用户名+初始密码）+ 重置密码弹窗 + switch（自己那行禁用）+ 删除 confirm（自己那行禁用）
  - 仪表盘：`GET /admin/stats/overview` → 6 卡片 + **内联 SVG 柱状图**（trend7d，手写坐标轴，无依赖）+ Top 模型表（成功率 CSS 条）；无数据时空态文案
  - toast：成功/失败（红色边框，文案透传后端 message）

- [ ] **步骤 4：Commit**

```bash
git add AILLMGateway/src/main/resources/static/admin-ui/
git commit -m "feat: 网关管理界面单页（仪表盘/渠道/路由/Key/日志/用户管理）"
```

---

### 任务 3：集成验证（主代理亲自执行）

- [ ] **步骤 1：编译 + 起网关**

```bash
# 编译（任务 1 步骤 6 同款命令）
# 启动：cd AILLMGateway && java -jar target/... 或 mvn spring-boot:run，背景进程 + 健康检查 http://localhost:8083/admin/login OPTIONS
```

- [ ] **步骤 2：curl 冒烟**（登录拿 token → users CRUD → stats → 测试端点 404/参数校验路径）

- [ ] **步骤 3：浏览器实测**（computer_use / 截图）：登录页 → 仪表盘 → 各页 CRUD → 测试按钮 → 用户防自锁（禁用自己被拒）→ 401 踢回

- [ ] **步骤 4：业务回归**：`/v1/chat/completions` 用网关 Key 调一次确认转发链路未破坏

- [ ] **步骤 5：Commit 设计文档与计划**（若未随实现提交）

```bash
git add docs/2026-08-08-llm-gateway-admin-ui-design.md docs/superpowers/plans/2026-08-08-llm-gateway-admin-ui.md
git commit -m "docs: 网关管理界面设计文档与实现计划"
```

---

## 自检记录

- **规格覆盖度**：设计 v2 全部章节（用户管理/统计/测试/6 页面/防自锁/一次性 Key/401 踢回）均有对应任务；渠道/路由/Key/日志页复用已有 API，任务 2 覆盖 UI
- **占位符扫描**：无 TODO/待定；步骤含具体代码或明确契约
- **类型一致性**：`RouteResult.status/body`、`UpstreamClient.postJson/postGemini/extractError`、`KeyService.decrypt`、`ApiResponse.ok/error` 均为现有签名；`ApiResponse` 是 record（`code/message/data`），新 Controller 全部复用
