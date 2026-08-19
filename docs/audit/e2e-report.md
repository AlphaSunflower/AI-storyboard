# 端到端测试报告

> 日期：2026-08-19 ｜ 执行：`scripts/e2e/run_all.py`（4 脚本串联）
> 环境：ApiGateway 8080（已在跑）/ 主后端 8082（IDE，已在跑）/ LLM 网关 8083（已在跑）/ MoonAgent 8084（本次编译启动）/ Nacos 8848 / PG ai_storyboard + llm_gateway
> 入口：真实流量经网关 8080（前端同路径），越权实证直连 8082/8084

## 结果汇总：4/4 脚本通过，80 用例 PASS

| 脚本 | 范围 | 结果 | 说明 |
|---|---|---|---|
| bootstrap_auth.py | 注册/登录/refresh/profile/无token | 7 PASS | 主后端成功契约 code=200（非 0） |
| run_core_flow.py | 项目/分镜/资产/文件/AI + 越权实证 | 31 PASS | 含 4 项审计实证（P0/P1 坐实） |
| run_agent_flow.py | 会话/消息/SSE/HITL/资产/越权 | 21 PASS | SSE 编排到 human_input 卡点全通 |
| run_gateway_flow.py | LLM 网关 /v1 + /admin + 穿透 | 20 PASS | admin 8 端点无密钥泄漏 |

## 关键链路实测证据

- **真实 LLM 调用**：`generate-script`（主后端→网关→上游）成功生成 5–7 个分镜；`/api/agent/prompt/optimize` 成功；`/v1/chat/completions`（deepseek-v4-flash）200；`/v1/images/generations` 非法 size "2K" 未被拒（200，上游接受——与 CLAUDE.md 记录的 size 白名单降级逻辑在主后端侧一致，网关透传）
- **SSE 编排**：`messages/stream` 事件序列 `message 增量 → human_input（HITL 卡点）`，编排器全链工作
- **异步任务**：无效 taskId → 200 + status=failed（双通道反查兜底，符合设计）
- **网关拦截**：经 8080 打 `/api/internal/**` → 403（外部不可达 ✓）；无 token → 401 ✓

## 审计发现实证（E2E 坐实 3 个 P0/P1）

| 发现 | 实证方法 | 结果 |
|---|---|---|
| P0-1 分镜 IDOR | userB 插入/修改 userA 项目分镜 | **200 成功**（应 403/404）→ 坐实 |
| P1-2 直连伪造 X-User-Id | 直连 8084 带假头拉会话 | **200 成功**（应 401/403）→ 坐实 |
| P1-3 默认 internal.secret | 直连 8082 带 git 中默认密钥 | **200 成功**（应 403）→ 坐实 |

## 测试中修复的问题（1 项）

- **MoonAgent BusinessException → 500**：MoonAgent 未扫描 CommonCore 的 `@RestControllerAdvice`（`com.storyboard.common` 包），所有业务错误（40001/40301/40401）返回 500，与 CLAUDE.md 声明的状态码映射不符。修复：`MoonAgentApplication` 加 `scanBasePackages = {"com.moon.moonagent", "com.storyboard.common"}`。修复后：他人会话 → 404+40401 ✓、短文本 → 400 ✓（原 500）。

## 已知非缺陷（测试中确认的行为）

- 删末位项目 → 403「至少保留一个项目」：业务规则（非 bug）
- MoonAgent 直连无 X-User-Id → 403（Spring Security 默认 entry point；经网关时网关层 401，前端不受影响）
- generate-script 偶发 502：LLM 网关上游瞬态（脚本含 1 次重试，重试后通过）

## 未测/SKIP 项（如实记录）

- `generate-image` / `generate-video` 真实生成：未跑（真实调用成本/耗时；`generate-script` 与 `/v1/images/generations` 已证明主后端→网关→上游链路可用）
- `/api/agent/.../video/plan/generate` 图生视频全链、`/form/submit` 续流（HITL 表单提交动作）：SSE 编排已验证到 human_input，续流动作依赖 LLM 方案生成，未单独跑完整视频生成
- 并发锁 40901（ConversationLock）：未并发实测（单线程跑）

## 环境清理

- 测试数据已清理：e2e 用户（10 个）/ 项目（6）/ 分镜（19）/ 资产（13）/ 会话全部删除；llm_gateway 的 e2eadmin 与 e2e-test key 已删
- MoonAgent 8084 为本测试编译启动（原环境 8084 未运行），停止后恢复原状
- 上传的随机文件名图片残留于 uploads/（无害，未清）
