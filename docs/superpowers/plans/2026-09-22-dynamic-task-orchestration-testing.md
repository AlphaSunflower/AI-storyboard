# 动态任务编排手动测试说明

分支：`codex/dynamic-task-orchestration`

实现计划：`docs/superpowers/plans/2026-09-22-dynamic-task-orchestration.md`

## 测试目标

- 规划、方案选择和继续完善使用普通对话，不提前创建 HITL 卡片。
- 只有最终生图或生视频动作创建 HITL 卡片。
- 图片节点完成后，动态计划继续到视频节点，并把图片作为视频参考输入。
- checkpoint、计划、节点和步骤记录能正确关联。
- 同一轮不会重复写入 assistant 消息。

## 隔离启动

保留已有服务，在独立端口启动分支：

```powershell
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/ai_storyboard'
$env:SPRING_DATASOURCE_USERNAME='postgres'
$env:SPRING_DATASOURCE_PASSWORD='123456'
$env:INTERNAL_SECRET='codex-local-dynamic-plan-test'
java -jar MoonAgent/target/moon-agent-0.0.1-SNAPSHOT.jar --server.port=8086
```

另开终端启动前端：

```powershell
$env:VITE_API_PROXY_TARGET='http://localhost:8086'
$env:VITE_DEV_USER_ID='9f9e3129-fbac-426c-a1a9-dcdf3f6055b4'
npm --prefix AIStoryboardClient run dev -- --host 127.0.0.1 --port 5174
```

打开 `http://127.0.0.1:5174/`。

`VITE_DEV_USER_ID` 可替换为当前登录用户的真实 ID。只验证编排时可以给
`INTERNAL_SECRET` 任意非空值；需要调用主后端内部接口时，必须与主后端使用同一个值。

## 手动流程

1. 新建会话，发送以下目标：

   ```text
   我要生成一个PPT的排版，这个排版要有科技感，未来感，标签是：'如何和让Moon智能体变得更强大，更好用'，然后再将这个图片作为参考图生成一个视频，我希望这个参考图之前有一个动画驱动最后引出这个PPT
   ```

2. 预期第一轮只出现普通方案消息：
   - 页面显示三套图片方案。
   - 页面不出现 HITL 卡片。
   - 计划状态为 `WAITING_HUMAN`。
   - `agent_checkpoints` 没有新记录。

3. 回复 `1`、`2`、`3` 或直接输入完善意见：
   - 回复编号后出现最终图片生成卡片。
   - 输入完善意见时只返回继续修改后的方案，不生成卡片。

4. 在最终图片卡片点击“开始生成”：
   - 图片生成完成后，计划自动推进到 `media-video`。
   - 视频方案先通过普通对话输出。
   - 选择视频方案后才出现最终视频生成卡片。

## 数据库核对

```sql
select id, status, goal, updated_at
from agent_task_plans
where conversation_id = '<conversation-id>';

select node_id, capability_key, state, attempt_count, output_summary
from agent_task_nodes
where plan_id = (
  select id from agent_task_plans
  where conversation_id = '<conversation-id>'
);

select status, action, plan_id, node_id, form_token
from agent_checkpoints
where conversation_id = '<conversation-id>';

select phase, status, decision, plan_id, node_id, attempt_no
from agent_loop_steps
where loop_run_id in (
  select id from agent_loop_runs
  where conversation_id = '<conversation-id>'
)
order by started_at;

select role, count(*)
from agent_messages
where conversation_id = '<conversation-id>'
group by role;
```

关键断言：

- 初次方案问答阶段 checkpoint 数量为 `0`。
- 选择方案后 checkpoint 的 `plan_id`、`node_id` 非空。
- 步骤记录 `phase=task_node`，且 `plan_id`、`node_id`、`attempt_no` 非空。
- HITL 卡片只有一条对应 assistant 消息。

## 自动门禁

```powershell
mvn -pl agent-core,MoonAgent,AIStoryboardBackend -am test
npm --prefix AIStoryboardClient test -- --run
npm --prefix AIStoryboardClient run build
```

## 回退

关闭动态计划开关后，新会话回退旧流程：

```powershell
$env:DYNAMIC_TASK_PLAN_ENABLED='false'
```

整体回退时直接切回主分支。V28 只新增可空列和表，主分支代码不会读取这些结构。
