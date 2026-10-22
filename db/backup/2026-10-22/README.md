# 表结构备份 · 2026-10-22

多租户 Web 项目（浏览器端）**当前完整表结构**的快照，用于：

- 冻结云端仓库后，随时在新机器 / 新环境快速重建数据库
- 后续继续开发 Web 端时，有一个确定的 schema 基线
- 客户端（`moon-desktop`）推导本地库基线时的参考

## 内容

| 文件 | 内容 | 表数 |
|---|---|---|
| `ai_storyboard_schema.sql` | 主业务库：`public` 65 + `asset` 22 + `agentscope` 1 | 88 |
| `llm_gateway_schema.sql` | LLM 网关库：`public` | 11 |
| `tables.md` | 表清单（按 schema 分组，含列数与表注释） | — |
| `restore.ps1` / `restore.sh` | 一键重建脚本 | — |

规模：88 表 / 262 索引 / 881 约束。

## 怎么生成的

1. 以 `deploy/stack/db/ai_storyboard.dump` + `llm_gateway.dump`（仓库内的 PG 18.4 自定义格式 dump）还原到临时 PostgreSQL 18.4 集群；
2. 补上仓库 dump 缺失的三个迁移：
   - `V45__agent_sandbox_sessions.sql`
   - `V54__agent_archive_cleanups.sql`
   - `V55__data_retention_indexes.sql`
3. `pg_dump --schema-only --no-owner --no-privileges` 导出三个 schema；
4. 补 `CREATE EXTENSION pg_trgm SCHEMA public`（`gin_trgm_ops` 索引依赖），去掉重复的 `CREATE SCHEMA public`；
5. **往返校验**：把导出文件再导入空库，比对 `tables/indexes/constraints` 三项计数完全一致（88/262/881），零错误。

## 怎么恢复

```bash
createdb -h 127.0.0.1 -U postgres ai_storyboard
psql    -h 127.0.0.1 -U postgres -d ai_storyboard -f ai_storyboard_schema.sql

createdb -h 127.0.0.1 -U postgres llm_gateway
psql    -h 127.0.0.1 -U postgres -d llm_gateway -f llm_gateway_schema.sql
```

或直接：

```powershell
# Windows（脚本未签名，需绕过执行策略）
powershell -ExecutionPolicy Bypass -File .\restore.ps1 -PgHost 127.0.0.1 -PgPort 5432 -PgUser postgres
```

```bash
# macOS / Linux
./restore.sh 127.0.0.1 5432 postgres
```

恢复后按需灌种子数据：

```bash
psql -d ai_storyboard -f ../deploy/stack/db/sql/post-migrate.sql
psql -d llm_gateway   -f ../deploy/stack/db/sql/seed-models.sql
```

## 依赖与兼容性

- **PostgreSQL 16+ 建议**；dump 由 18.4 生成，含 `SET transaction_timeout = 0`，低于 17 可能报未知参数（删掉该行即可）。
- 需要 `pg_trgm` 扩展，脚本已内置 `CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public`（需超级用户或已预装）。
- **不含业务数据**，只有结构、索引、约束、注释。

## 与线上环境的差异须知

- 本备份 = 仓库内 dump + 上述三个补丁迁移。若线上还有**未入库的手工改动**，以生产 `pg_dump` 为准。
- 带数据的完整快照仍在 `deploy/allinone/db/ai_storyboard.dump`（约 29MB）。
- 迁移文件的现状（很重要）：

| 事实 | 影响 |
|---|---|
| `AIStoryboardBackend/.../db/migration` 有 66 个文件，其中 **11 组版本号重复**（V9/V25/V35/V36/V40/V41/V42/V43/V44/V45/V50） | 不能按 Flyway 顺序重放，只能作为历史留档 |
| Backend 的 Flyway 仅启用 `classpath:db/chat-security`（3 个文件，baseline 43） | `db/migration` 不会自动执行，schema 真实来源是 dump |
| `asset-service` 走独立 schema `asset`，由 Flyway 自动建表 | 该部分可自愈，不在备份的必要范围内，但仍已包含 |
| `XiaoYueCanvas/src/main/sql` 无执行入口 | 画布表依赖 dump，本备份已覆盖 |

因此：**新环境建库请用本目录的 SQL 文件，不要指望迁移文件能重放。**
