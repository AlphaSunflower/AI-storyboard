# 代码审计报告

> 日期：2026-08-19 ｜ 范围：ApiGateway / AIStoryboardBackend / AILLMGateway / MoonAgent / AIStoryboardClient
> 方法：静态代码审计（安全面 + 分层规范 + 契约一致性），对照 CLAUDE.md 规范与已知 Pitfalls。
> 严重级：P0 阻断/高危 ｜ P1 高 ｜ P2 中 ｜ P3 建议。

## 汇总表

| # | 级别 | 模块 | 位置 | 问题 | 建议 |
|---|---|---|---|---|---|
| 1 | **P0** | 主后端 | SceneController.java + SceneServiceImpl.java | **分镜 6 端点全链路无归属校验（IDOR）**：addScene 不校验 projectId 归属（任意用户可向他人项目插分镜）、updateScene/deleteScene/listReferences/uploadReference/deleteReference 均无 userId 参数（知道 sceneId 即可改/删/传参考图）。对比 AssetController/ProjectController/MoonAgent 全部 `auth.getName()` 透传 service 校验。 | SceneService 方法签名加 userId，操作前校验 project.userId == userId（对齐 ProjectService.getById(auth.getName(), id) 模式） |
| 2 | **P1** | 主后端+MoonAgent | GatewayAuthenticationFilter.java:41 / GatewayAuthenticationFilter.java:45（MoonAgent） | **直连端口可伪造 X-User-Id 头冒充任意用户**：两个服务的过滤器无条件信任 `X-User-Id` 头（主后端还优先于 JWT 回退）。鉴权唯一防线是 ApiGateway 验签后改写头；若 8082/8084 被网络直达（内网横向/误暴露），等同任意用户接管。 | 网关写入额外的共享签名头（如 HMAC(secret, userId)）下游校验，或下游仅在生产 profile 信任 X-User-* 且要求网关来源标记；部署时 8082/8084 不得暴露公网 |
| 3 | **P1** | 主后端+ApiGateway | InternalApiController.java:29 `@Value("${internal.secret:moon-internal-secret-2024}")` + ApiGateway application.yaml 同默认值入库 | **internal.secret 默认值公开硬编码**（git 可见）：生产若未用环境变量覆盖，任何能直连 8082 或能带该头经网关转发者（网关已挡 /api/internal，故主要风险=直连+已知密钥）可增删改任意项目分镜。 | 移除默认值 fallback，启动时无配置即失败（fail-fast）；生产用环境变量注入 |
| 4 | P2 | ApiGateway | JwtAuthenticationFilter.java:29-33 白名单 `/api/files/` | 文件下载免鉴权靠"文件名不可猜测"，无访问控制；生成文件 URL 有泄露风险（如分享/日志中出现）即公开可读。 | 若需收紧：文件 URL 加一次性签名（如 /api/files/images/{filename}?token=HMAC）；评估业务取舍 |
| 5 | P2 | ApiGateway | SecurityConfig.java:34 `.anyRequest().permitAll()` | 网关鉴权完全依赖自定义 Filter 的白名单逻辑；**新增路由默认免鉴权**，漏配 = 裸奔。属于架构性风险（双保险结构）。 | 在 JwtAuthenticationFilter 外保留 SecurityConfig 层 deny-by-default（.anyRequest().authenticated()），自定义 Filter 只做"附加头"而非"唯一闸门" |
| 6 | P2 | 全部 | 各服务 CORS `AllowedOriginPatterns("*") + allowCredentials(true)` | 开发便利但生产同域部署时无必要；任意站点可发起跨域请求（凭证携带）。 | 生产 profile 收紧为显式来源列表 |
| 7 | P2 | 主后端 | InternalApiController checkToken 用 `String.equals` | 密钥比对非恒定时间，理论上可时序侧信道（本地内网场景风险极低）。 | 用 MessageDigest.isEqual 恒定时间比对 |
| 8 | P3 | LLM 网关 | AdminJwtFilter.java:28-31 shouldNotFilter 只排除 `/admin/login` | `/admin-ui` 静态页不经 admin JWT（页面壳无数据，风险低）；但若后续在页面注入数据接口需注意。 | 维持现状即可；页面加载敏感数据走 /admin/** API |
| 9 | P3 | 前端 | DocsPage.tsx:279,327 裸 `x.imgs[0]` | Dify 遗留资产直链展示（未走 assetUrl）；若 Dify 已下线为死路径。 | 确认 Dify 下线后清理 |

## 已核实无问题（抽查通过项）

- **实体时间字段**：entity 全用 `OffsetDateTime`（timestamptz 规范 ✓，无 LocalDateTime 违规）
- **SQL 注入面**：mapper 无 `${}` 字符串拼接，全程 MyBatis-Plus 参数化 ✓
- **Controller 分层**：无直连 Mapper/HttpClient 业务逻辑；统一 `ApiResponse` 包裹；仅 InternalApiController 直返 entity（内部契约，可接受）✓
- **HttpClient 超时**：全部 `HttpClient.newBuilder()` 带 connectTimeout；AI 服务 per-request timeout 120–300s 覆盖 ✓
- **JWT 校验强度**：HS256 + issuer + typ(access/refresh) + role + status 四重校验，jjwt 标准库 ✓
- **LLM 网关密钥面**：API key 存库仅 sha256 hash（明文仅签发时一次性返回）；/admin/** 全量 AdminJwtFilter ✓
- **MoonAgent 归属校验**：全部端点经 `getOwnedConversation(userId, id)`，40401 统一防 IDOR 枚举 ✓（对照 P0-1，迁移后的新代码反而规范）
- **主后端 AccessLogFilter**：仅记 method/path/status/耗时/user，不落 header/body，无密钥泄露 ✓
- **前端**：api 统一走 axios client（token 注入 + 401 清理）；assetUrl 41 处引用覆盖主要图片展示；`tsc -p tsconfig.app.json --noEmit` 基线 0 错误 ✓

## 测试中修复 / 追加发现（2026-08-19 E2E 阶段）

| # | 级别 | 位置 | 问题 | 处置 |
|---|---|---|---|---|
| 10 | P1（已修） | MoonAgentApplication.java | MoonAgent 未扫描 CommonCore 的 `@RestControllerAdvice`（com.storyboard.common），全部 BusinessException → 500（40401 等状态码映射失效，与 CLAUDE.md 声明不符） | 主类加 `scanBasePackages={"com.moon.moonagent","com.storyboard.common"}`，实测修复（404/400 正确返回） |
| 11 | P3 | 主后端 ProjectService | 删末位项目 → 403「至少保留一个项目」为业务规则（非缺陷），但行为未写入文档 | E2E 报告已记录 |
| 12 | P3 | 环境 | 主后端本地实例连 `ai_storyboard` 库（非 CLAUDE.md 所述 newworkflow；llm_gateway 独立库） | 文档已按实测修正（CLAUDE.md 的 newworkflow 可能为部署库） |

## 关键架构说明（非问题）

- 网关对 `/api/internal/**` 外部请求直接 403（`JwtAuthenticationFilter.java:49-52`），内网面靠 `X-Internal-Token` 共享密钥 —— 双层防线设计正确，P1-3 仅指默认值硬编码。
- SSE 链路：主后端 `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` + MoonAgent `implements Filter`（非 OncePerRequestFilter）——均为已修的已知坑，现状正确。

## E2E 关联

- P0-1 将在 B3/B4 以「用户 B 改用户 A 的分镜」用例实测验证（预期：当前可越权 → 证实 P0）。
- P1-2 以「直连 8084 带 X-User-Id 头拉他人会话」实测（预期：成功 → 证实）。
- P1-3 以「带默认 internal.secret 直连 8082 /api/internal」实测（预期：成功 → 证实）。
