# 网关系统可调配置 DB 化方案

> 2026-08-17。背景：gateway.upstream.* 3 个 tunable 参数此前写死在 application.yml，改值需改文件+重启且无集中管理入口。本次迁移至 DB（sys_config 表），提供 admin-ui 界面修改，**落库后重启生效（不做热更新）**。

## 设计要点

- **存储**：网关库 llm_gateway 建 `sys_config` 表（key-value），种子数据 = 原 application.yml 的值（幂等，ON CONFLICT DO NOTHING）。
- **消费**：应用启动时 `SysConfigServiceImpl@PostConstruct` 把 DB 值加载进 `GatewayConfig.Upstream`；**DB 无行或值非法 → 保留代码默认值，只 warn 不阻断启动**。
- **连接超时特例**：`UpstreamClient` 构造 HttpClient 时固化 connectTimeout，故加 `@DependsOn("sysConfigServiceImpl")` 保证 DB 加载先于客户端构造；request-timeout / retry-count 每次请求现读，天然生效。
- **修改接口**：`/admin/config`（AdminJwtFilter ADMIN 鉴权），GET 回显 / PUT 批量更新。修改只落库，重启网关后生效。
- **新增配置键**：在 `SysConfigServiceImpl.SPECS` 注册（键 → 解析校验 + binder）+ 往表里插行即可，消费方零改动。

## 迁移清单（psql 手动执行，与既有 V*.sql 一致）

```bash
psql -U postgres -d llm_gateway -f AILLMGateway/src/main/resources/db/migration/V8__sys_config.sql
```

## 配置项一览

| key | 类型 | 初始值 | 校验范围 | 备注 |
|-----|------|--------|----------|------|
| gateway.upstream.connect-timeout-ms | long | 30000 | [1000,300000] | HttpClient 连接超时（构造时固化） |
| gateway.upstream.request-timeout-ms | long | 120000 | [1000,600000] | 每请求超时（SSE 取 max(值,300s)） |
| gateway.upstream.retry-count | int | 2 | [0,10] | 429/5xx 重试次数 |

## 接口

网关（ADMIN JWT，AdminJwtFilter 已保护）：

```
GET /admin/config                  → {code,message,data:[{key,value,remark,updatedAt}]}
PUT /admin/config                  body {"items":[{"key":"gateway.upstream.retry-count","value":"3"}]}
                                     → 全量回显；非法键/值 → 40001（HTTP 400）
```

## 前端入口

网关 admin-ui 侧边栏「系统配置」tab（`/admin-ui` → `#/config`），表格回显 + 保存按钮；保存后提示「重启网关后生效」。

## 改动文件

网关：`V8__sys_config.sql`、`entity/SysConfig.java`、`mapper/SysConfigMapper.java`、
`service/SysConfigService.java`、`service/impl/SysConfigServiceImpl.java`、
`controller/admin/AdminConfigController.java`、`dto/admin/ConfigUpdateRequest.java`、`dto/vo/SysConfigVO.java`、
`service/UpstreamClient.java`（`@DependsOn`）、`application.yml`（删 gateway.upstream 段）、
`static/admin-ui/index.html` + `app.js`（系统配置 tab）。

## 说明（2026-08-17 定案）

- **后端 ai.agent.\*（intent-threshold / max-clarify-rounds / max-regenerate-rounds）不 DB 化**：按用户定案回到 application.yml 管理（`ai.agent` 段，代码默认值见 `AgentAiConfigProperties`）。曾实现的 `neweworkflow.sys_config` + `/api/admin/config` + 主端 ⚙ 系统配置 面板已全部回滚删除。
- **不做热更新是有意的**：connection 级（HttpClient）和密钥类配置热改有副作用；tunable 均为低频调优项，重启 10 秒内完成。
- 网关 `video.default-resolution/duration`、后端 `gemini-image-models/video-model-aliases` 已从 yml 删除：分辨率/时长/模型名跟随 model_params / model_route（每模型权威默认），不再设全局默认。
