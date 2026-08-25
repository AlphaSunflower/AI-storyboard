# MoonAgent 接入 Nacos AI Registry 实施计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将 MoonAgent 的 Prompts 和 Agent 定义注册到 Nacos v3.2.3 AI Registry，实现通过 Nacos 控制台统一管理提示词和 Agent 元数据。

**Architecture:** Nacos v3.2.3 提供 AI Registry（Prompt / Skill / AgentSpec / A2A / MCP），通过 Admin API (`/v3/admin/ai/*`) 管理。MoonAgent 当前有 25 个本地 YAML 提示词文件，启动时由 `PromptConfig` 从 classpath 加载。本方案分两层：① 脚本层——批量上传 prompts + 创建 AgentSpec 到 Nacos；② 运行时层——可选改造 `PromptConfig` 从 Nacos 读取提示词（热更新能力）。

**Tech Stack:** Nacos v3.2.3 Admin API (HTTP), bash (curl), Java 21 (Spring Boot)

---

## 当前状态

| 资源 | Nacos 现状 | 本地现状 |
|------|-----------|---------|
| Prompts | 0 个 | 25 个 YAML 文件 (`prompts/` 目录) |
| AgentSpecs | 186 个（社区） | moon-agent 未注册 |
| Skills | 174 个（社区） | 无可打包 skill |
| Agents API | `/v3/admin/ai/agents/*` 返回 404（v3.3.0 功能） | N/A |

**可用 API（v3.2.3 实测）：**
- Prompt: `POST /v3/admin/ai/prompt/draft` → `POST /v3/admin/ai/prompt/publish`
- AgentSpec: `POST /v3/admin/ai/agentspecs/draft` → `POST /v3/admin/ai/agentspecs/publish`
- A2A: `POST /v3/admin/ai/a2a` (Create AgentCard)

---

## Task 1: 批量注册 Prompts 到 Nacos

**Objective:** 将 MoonAgent 的 25 个 YAML 提示词文件通过 Nacos Admin API 注册为 Prompt 资源。

**Files:**
- Create: `deploy/nacos/register-prompts.sh`

**Step 1: 编写批量上传脚本**

脚本逻辑：
1. 登录 Nacos 获取 token
2. 遍历 `MoonAgent/src/main/resources/prompts/**/*.yaml`
3. 解析每个 YAML 的 `name`、`template`、`variables`、`description`、`version`
4. 调用 `POST /v3/admin/ai/prompt/draft` 创建 draft
5. 调用 `POST /v3/admin/ai/prompt/publish` 发布

关键参数映射：
- `promptKey` = YAML 的 `name` 字段（如 `services/intent-recognition`）
- `template` = YAML 的 `template` 字段
- `variables` = YAML 的 `variables` 数组，转 JSON 字符串
- `targetVersion` = YAML 的 `version` 字段（如 `"1.1"`）

```bash
#!/usr/bin/env bash
# deploy/nacos/register-prompts.sh
# 批量注册 MoonAgent prompts 到 Nacos AI Registry
set -euo pipefail

NACOS_ADDR="${NACOS_ADDR:-localhost:8848}"
NACOS_USER="${NACOS_USER:-nacos}"
NACOS_PASS="${NACOS_PASS:-nacos}"
PROMPTS_DIR="${PROMPTS_DIR:-$(dirname "$0")/../../MoonAgent/src/main/resources/prompts}"

echo "=== 获取 Nacos Token ==="
TOKEN=$(curl -sf -X POST "http://${NACOS_ADDR}/nacos/v1/auth/login" \
  -d "username=${NACOS_USER}&password=${NACOS_PASS}" | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

SUCCESS=0
FAIL=0

for yaml_file in $(find "$PROMPTS_DIR" -name '*.yaml' | sort); do
  # 用 python 解析 YAML（避免依赖 yq）
  eval "$(python3 -c "
import yaml, json, sys
with open('$yaml_file') as f:
    d = yaml.safe_load(f)
name = d.get('name','')
template = d.get('template','')
version = d.get('version','1.0')
desc = d.get('description','')
variables = d.get('variables', [])
# 输出 shell-safe 变量
print(f'PROMPT_KEY={json.dumps(name)}')
print(f'PROMPT_TEMPLATE={json.dumps(template)}')
print(f'PROMPT_VERSION={json.dumps(version)}')
print(f'PROMPT_DESC={json.dumps(desc, ensure_ascii=False)}')
print(f'PROMPT_VARS={json.dumps(variables, ensure_ascii=False)}')
")"

  if [ -z "$PROMPT_KEY" ] || [ "$PROMPT_KEY" = '""' ]; then
    echo "SKIP: $yaml_file (无 name)"
    continue
  fi

  echo "注册: $PROMPT_KEY (v$PROMPT_VERSION)"

  # 创建 draft
  DRAFT_RESP=$(curl -sf -X POST "http://${NACOS_ADDR}/nacos/v3/admin/ai/prompt/draft" \
    -H "Authorization: Bearer $TOKEN" \
    --data-urlencode "promptKey=$PROMPT_KEY" \
    --data-urlencode "targetVersion=$PROMPT_VERSION" \
    --data-urlencode "template=$PROMPT_TEMPLATE" \
    --data-urlencode "variables=$PROMPT_VARS" \
    --data-urlencode "description=$PROMPT_DESC" \
    --data-urlencode "commitMsg=Initial import from MoonAgent YAML" \
    2>/dev/null || echo '{"code":-1}')

  DRAFT_CODE=$(echo "$DRAFT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',-1))" 2>/dev/null || echo "-1")

  if [ "$DRAFT_CODE" != "0" ]; then
    echo "  FAIL draft: $DRAFT_RESP"
    ((FAIL++))
    continue
  fi

  # 发布
  PUB_RESP=$(curl -sf -X POST "http://${NACOS_ADDR}/nacos/v3/admin/ai/prompt/publish" \
    -H "Authorization: Bearer $TOKEN" \
    --data-urlencode "promptKey=$PROMPT_KEY" \
    --data-urlencode "version=$PROMPT_VERSION" \
    2>/dev/null || echo '{"code":-1}')

  PUB_CODE=$(echo "$PUB_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',-1))" 2>/dev/null || echo "-1")

  if [ "$PUB_CODE" = "0" ]; then
    echo "  OK"
    ((SUCCESS++))
  else
    echo "  FAIL publish: $PUB_RESP"
    ((FAIL++))
  fi
done

echo ""
echo "=== 完成: $SUCCESS 成功, $FAIL 失败 ==="
```

**Step 2: 运行脚本验证**

```bash
cd E:/Desktop/AI-storyboard
bash deploy/nacos/register-prompts.sh
```

预期：25 个 prompt 全部注册成功。在 Nacos 控制台 (http://localhost:8850) 的 AI → Prompt 管理页面可以看到。

**Step 3: 验证 Nacos 中的 Prompt**

```bash
TOKEN=$(curl -sf -X POST 'http://localhost:8848/nacos/v1/auth/login' -d 'username=nacos&password=nacos' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")
curl -sf -H "Authorization: Bearer $TOKEN" 'http://localhost:8848/nacos/v3/admin/ai/prompt/list?pageNo=1&pageSize=30' | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'Total: {d[\"data\"][\"totalCount\"]}')
for p in d['data']['pageItems']:
    print(f'  {p[\"promptKey\"]} v{p.get(\"labels\",{}).get(\"latest\",\"?\")}')"
```

预期：totalCount=25，列出所有 prompts。

---

## Task 2: 注册 AgentSpec（Agent 元数据定义）

**Objective:** 将 moon-agent 作为 AgentSpec 注册到 Nacos，描述其能力、端点、元数据。

**Files:**
- Create: `deploy/nacos/register-agent-spec.sh`

**Step 1: 创建 AgentSpec 定义**

AgentSpec 内容（JSON）：

```json
{
  "name": "moon-agent",
  "description": "Moon 智能体 — AI 分镜创作助手，支持剧本生成、图片生成、视频生成、分镜管理",
  "version": "1.0.0",
  "endpoints": [
    {
      "type": "http",
      "url": "http://localhost:8084",
      "description": "MoonAgent REST API"
    }
  ],
  "capabilities": [
    "storyboard-generation",
    "image-generation",
    "video-generation",
    "scene-management",
    "intent-recognition",
    "conversation"
  ],
  "tags": ["storyboard", "ai-agent", "creative"]
}
```

上传脚本：

```bash
#!/usr/bin/env bash
# deploy/nacos/register-agent-spec.sh
set -euo pipefail

NACOS_ADDR="${NACOS_ADDR:-localhost:8848}"
NACOS_USER="${NACOS_USER:-nacos}"
NACOS_PASS="${NACOS_PASS:-nacos}"

TOKEN=$(curl -sf -X POST "http://${NACOS_ADDR}/nacos/v1/auth/login" \
  -d "username=${NACOS_USER}&password=${NACOS_PASS}" | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

SPEC_NAME="moon-agent"
SPEC_VERSION="1.0.0"
SPEC_DESC="Moon 智能体 — AI 分镜创作助手，支持剧本生成、图片生成、视频生成、分镜管理与 Agent 对话"

echo "=== 创建 AgentSpec Draft ==="
curl -sf -X POST "http://${NACOS_ADDR}/nacos/v3/admin/ai/agentspecs/draft" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "name=$SPEC_NAME" \
  --data-urlencode "targetVersion=$SPEC_VERSION" \
  --data-urlencode "description=$SPEC_DESC" \
  --data-urlencode 'bizTags=["ai-storyboard","creative-agent"]' \
  --data-urlencode "content=$(cat <<'EOF'
{
  "agent": {
    "name": "moon-agent",
    "displayName": "Moon 智能体",
    "description": "AI 分镜创作助手 — 剧本/图片/视频/分镜全链路",
    "version": "1.0.0",
    "capabilities": [
      "storyboard-generation",
      "image-generation", 
      "video-generation",
      "scene-management",
      "intent-recognition",
      "conversation"
    ],
    "endpoints": [
      {
        "type": "http",
        "url": "http://localhost:8084",
        "port": 8084,
        "protocol": "rest"
      }
    ],
    "skills": [
      "intent-recognition",
      "script-generation",
      "image-generation",
      "video-generation",
      "scene-review",
      "agent-conversation"
    ]
  }
}
EOF
)" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Draft: code={d[\"code\"]} {d[\"message\"]}')"

echo "=== 发布 AgentSpec ==="
curl -sf -X POST "http://${NACOS_ADDR}/nacos/v3/admin/ai/agentspecs/publish" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "name=$SPEC_NAME" \
  --data-urlencode "version=$SPEC_VERSION" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Publish: code={d[\"code\"]} {d[\"message\"]}')"
```

**Step 2: 运行并验证**

```bash
bash deploy/nacos/register-agent-spec.sh
```

验证：
```bash
curl -sf -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8848/nacos/v3/admin/ai/agentspecs?name=moon-agent' | python3 -m json.tool
```

---

## Task 3（可选）: PromptConfig 改造 — Nacos 热加载

**Objective:** 让 `PromptConfig` 优先从 Nacos 读取 prompt，Nacos 不可用时 fallback 到本地 YAML。实现提示词热更新（Nacos 修改 → 无需重启生效）。

**此 Task 为增强功能，非必须。** 当前本地 YAML 方案已满足运行需求，Nacos 注册主要用于可视化管理和版本控制。

**如果实施，改造点：**

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/config/PromptConfig.java`

改造方案（伪代码）：
1. `@PostConstruct loadAll()` 保持不变（本地 YAML 加载为 fallback）
2. 新增 `NacosPromptSource`：启动后异步从 Nacos `/v3/client/ai/prompt` 拉取，覆盖本地同名 prompt
3. 可选：定时轮询（30s）检查 Nacos prompt 版本变化，实现热更新
4. `get(name)` 优先返回 Nacos 版本，无则返回本地版本

**风险：** Nacos Admin API 鉴权需要 token，运行时需管理 token 刷新。增加复杂度。

**建议：** 先不做此 Task。Nacos Prompt 管理主要用于：
- 可视化编辑和版本控制
- 团队协作审核
- 修改后手动触发重新部署（或后续加 webhook）

---

## Task 4: 验证 Nacos 控制台可视化

**Objective:** 确认所有注册的资源在 Nacos 控制台可正常显示和操作。

**Step 1: 访问 Nacos 控制台**

打开浏览器访问 http://localhost:8850，用 nacos/nacos 登录。

**Step 2: 检查 Prompt 管理**

左侧导航 → AI → Prompt 管理，确认：
- 25 个 prompts 全部显示
- 每个 prompt 有版本号
- 点击可查看 template 内容
- 可编辑 draft 并重新发布

**Step 3: 检查 AgentSpec 管理**

左侧导航 → AI → AgentSpec 管理，确认：
- moon-agent 显示
- 描述、版本、标签正确
- 可查看详情

**Step 4: 检查服务注册**

左侧导航 → 服务管理 → 服务列表，确认：
- moon-agent 服务存在
- 192.168.100.1:8084 实例健康

---

## 验证命令汇总

```bash
# 登录获取 token
TOKEN=$(curl -sf -X POST 'http://localhost:8848/nacos/v1/auth/login' \
  -d 'username=nacos&password=nacos' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# 列出所有 prompts
curl -sf -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8848/nacos/v3/admin/ai/prompt/list?pageNo=1&pageSize=30'

# 查看特定 prompt 详情
curl -sf -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8848/nacos/v3/admin/ai/prompt/detail?promptKey=services/intent-recognition'

# 查看 AgentSpec
curl -sf -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8848/nacos/v3/admin/ai/agentspecs?name=moon-agent'

# 查看服务实例
curl -sf -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8848/nacos/v3/admin/ns/instance/list?serviceName=moon-agent'
```

---

## 风险与注意事项

1. **Nacos Admin API 需要认证**：所有 `/v3/admin/*` 请求需要 Bearer token（从 `/v1/auth/login` 获取，有效期 5 小时）
2. **Prompt template 中的特殊字符**：YAML template 含大量中文和特殊符号，curl `--data-urlencode` 处理；如果 bash 转义有问题，改用 python requests 脚本
3. **版本号格式**：Nacos prompt version 是字符串，YAML 中的 `"1.1"` 直接使用
4. **重复注册**：draft+publish 幂等（同 version 覆盖），脚本可重复运行
5. **Agents API 不可用**：`/v3/admin/ai/agents/*` 是 v3.3.0 功能，当前 v3.2.3 不支持，用 AgentSpecs 替代
6. **Nacos 控制台端口**：8850（容器 8080 映射），非 8848（API 端口）
