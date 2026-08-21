# 数据库抽取：newworkflow → ai_storyboard

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将 AI Storyboard 项目的 11 张表从 `newworkflow` 数据库迁移到独立的 `ai_storyboard` 数据库，更新所有配置文件。

**Architecture:** 纯数据库层面迁移——psql 导出 DDL + 数据 → 新库导入 → 改配置 → 验证。零代码改动。

**Tech Stack:** PostgreSQL, psql, Spring Boot YAML

---

## 当前状态

- 数据库：`newworkflow`（localhost:5432, postgres/123456）
- 表：`users`, `projects`, `scenes`, `scene_reference_images`, `conversations`, `agent_messages`, `agent_assets`, `agent_checkpoints`, `assets`, `asset_images`, `scene_assets`
- 配置文件：`application.yml`, `application-local.yml`, `application-prod.yml`
- LLM 网关（8083）是独立应用，不在此范围内

## 风险点

`users` 表在 `newworkflow` 中被其他系统共享。迁移策略：
- **新库 `ai_storyboard` 创建完整的 `users` 表**（本服务自用）
- **`newworkflow` 中的 `users` 保留不动**（其他系统不受影响）
- 两个库的 `users` 数据初始一致，后续各自独立演化

---

### Task 1: 创建新数据库 ai_storyboard

**Objective:** 在 PostgreSQL 中创建空数据库

**Step 1: 创建数据库**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -c "CREATE DATABASE ai_storyboard;"
```

Expected: `CREATE DATABASE`

---

### Task 2: 导出 newworkflow 的表结构和数据

**Objective:** 用 pg_dump 导出 11 张表的 DDL + 数据

**Step 1: 导出指定表**

```bash
PGPASSWORD=123456 pg_dump -h localhost -U postgres -d newworkflow \
  -t users -t projects -t scenes -t scene_reference_images \
  -t conversations -t agent_messages -t agent_assets -t agent_checkpoints \
  -t assets -t asset_images -t scene_assets \
  --no-owner --no-privileges \
  -f /tmp/newworkflow_tables.sql
```

Expected: 文件生成，无报错

**Step 2: 验证导出文件**

```bash
grep "CREATE TABLE" /tmp/newworkflow_tables.sql
```

Expected: 11 个 CREATE TABLE 语句

---

### Task 3: 导入到 ai_storyboard

**Objective:** 将导出的 SQL 导入新库

**Step 1: 导入**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -d ai_storyboard -f /tmp/newworkflow_tables.sql
```

Expected: 无报错

**Step 2: 验证表数量**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -d ai_storyboard -c "\dt"
```

Expected: 11 张表全部列出

**Step 3: 验证数据行数**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -d ai_storyboard -c "
SELECT 'users' AS t, count(*) FROM users
UNION ALL SELECT 'projects', count(*) FROM projects
UNION ALL SELECT 'scenes', count(*) FROM scenes
UNION ALL SELECT 'scene_reference_images', count(*) FROM scene_reference_images
UNION ALL SELECT 'conversations', count(*) FROM conversations
UNION ALL SELECT 'agent_messages', count(*) FROM agent_messages
UNION ALL SELECT 'agent_assets', count(*) FROM agent_assets
UNION ALL SELECT 'agent_checkpoints', count(*) FROM agent_checkpoints
UNION ALL SELECT 'assets', count(*) FROM assets
UNION ALL SELECT 'asset_images', count(*) FROM asset_images
UNION ALL SELECT 'scene_assets', count(*) FROM scene_assets
ORDER BY 1;"
```

Expected: 每张表行数与 newworkflow 一致

---

### Task 4: 修改 application-local.yml

**Objective:** 本地开发环境指向新库

**File:** `AIStoryboardBackend/src/main/resources/application-local.yml:3`

**修改：**

```yaml
# 改前
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/newworkflow

# 改后
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ai_storyboard
```

其余配置（username/password/ai/jwt）不变。

---

### Task 5: 修改 application-prod.yml

**Objective:** 生产环境指向新库

**File:** `AIStoryboardBackend/src/main/resources/application-prod.yml:7`

**修改：**

```yaml
# 改前
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/newworkflow

# 改后
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ai_storyboard
```

---

### Task 6: 检查 application.yml 是否有 datasource URL

**Objective:** 主配置文件检查

**File:** `AIStoryboardBackend/src/main/resources/application.yml`

主配置只声明 `driver-class-name`，不含 URL（URL 在 local/prod profile 中）。无需修改。

---

### Task 7: 端到端验证

**Objective:** 启动后端确认连接新库正常

**Step 1: 编译**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

Expected: 编译成功

**Step 2: 启动后端（local profile）**

```bash
# 确认 8082 端口未被占用后启动
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" spring-boot:run -Dspring-boot.run.profiles=local
```

Expected: 启动成功，无数据库连接错误

**Step 3: 验证 API**

```bash
curl http://localhost:8082/api/auth/login -X POST -H "Content-Type: application/json" -d '{"email":"test@test.com","password":"test"}' -w "\n%{http_code}"
```

Expected: 返回 JSON（401 或 200，取决于账号是否存在）——关键是不报数据库连接错误

**Step 4: 验证 newworkflow 库不受影响**

```bash
PGPASSWORD=123456 psql -h localhost -U postgres -d newworkflow -c "\dt"
```

Expected: 原 11 张表仍在 newworkflow 中（未删除）

---

## 回滚方案

如需回滚，将 `application-local.yml` 和 `application-prod.yml` 中的 `ai_storyboard` 改回 `newworkflow` 即可。原库数据未动。

## 不在范围内

- 删除 newworkflow 中的旧表（确认新库稳定后再手动操作）
- LLM 网关（8083）的数据库配置（独立应用，不受影响）
- 前端配置（前端不直连数据库）
