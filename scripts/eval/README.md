# MoonAgent 真实回合 eval

用真实 HTTP/SSE 跑 golden case，检查：终态、关键文案、工具序列、耗时、token。

## 用法

```powershell
$env:MOON_BASE_URL="http://127.0.0.1:8080"
$env:MOON_EMAIL="<开发账号>"
$env:MOON_PASSWORD="<密码>"
$env:MOON_PROJECT_ID="<项目 id>"
python scripts/eval/run_eval.py --cases scripts/eval/cases.jsonl
```

- 每个 case 新建会话 → 发一轮 → 收集 `TOOL_CALL_START` / `turn_completed` → 删会话；
- 报告写到 `scripts/eval/reports/eval-<time>.json`；
- 有失败时退出码为 1，可直接接 CI。

## case 字段

| 字段 | 说明 |
|---|---|
| `name` | 用例名 |
| `prompt` | 用户输入 |
| `projectId` | 可选，覆盖 `MOON_PROJECT_ID` |
| `expectStatus` | 默认 `completed`；可写 `paused` / `failed` |
| `expectFinalContains` | 最终回复必须包含的片段 |
| `expectTools` | 必须出现的工具名 |
| `forbidTools` | 不允许出现的工具名 |
| `maxDurationMs` | 单轮耗时上限 |
| `maxTokens` | 单轮总 token 上限 |
| `expectNoTools` | 布尔；为 true 时任何工具调用都判失败 |
| `canvasId` / `requiresCanvas` | 画布编辑 case；需要 `MOON_CANVAS_ID`，缺失时 skip |
| `hitl` | `{ "action": "reject" }` 或 `"approve"`：暂停后自动续流 |
| `steer` | `{ "onEvent": "SUBAGENT_STARTED", "content": "..." }`：事件到达时插入 steer |

## 示例

```json
{"name":"hitl-reject","prompt":"请用 run_python_script 执行 print('eval')","hitl":{"action":"reject"}}
{"name":"task-graph-steer","prompt":"必须调用 run_task_graph...","expectTools":["run_task_graph"],"steer":{"onEvent":"SUBAGENT_STARTED","content":"最终只回复 STEER-EVAL-OK"},"expectFinalContains":["STEER-EVAL-OK"]}
```

## 发布前检查

```powershell
# 单测 + 前端 build + 真实 eval（缺 eval 环境变量时自动 skip eval）
powershell -ExecutionPolicy Bypass -File scripts/eval/preflight.ps1

# 只跑 eval
powershell -ExecutionPolicy Bypass -File scripts/eval/preflight.ps1 -SkipUnit
```

CI 有手动工作流 `.github/workflows/moon-eval.yml`，需要仓库 Secrets：
`MOON_EMAIL` / `MOON_PASSWORD` / `MOON_PROJECT_ID` / `MOON_CANVAS_ID`（可选）/ `MOON_BASE_URL`。
工作流每天 02:00 UTC 定时跑一次（schedule 用 `MOON_BASE_URL` Secret）。

## 真实轨迹怎么来

直接从 `agent_turns` / `agent_turn_events` 挑一条真实回合，用捕获脚本生成 case：

```powershell
python scripts/eval/capture_turn.py `
  --conversation <会话 id> `
  --turn <turnId> `
  --name storyboard-real `
  --out scripts/eval/cases.real.jsonl
```

脚本会读取该 turn 的用户消息、工具序列和终态，生成一条 case；不会抄最终文案（模型输出会变）。
不传 `--prompt` 时取会话最后一条用户消息。

