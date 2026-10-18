# 分镜方案映射（Storyboard Plan Mapping）设计

> 目标读者：MoonAgent / AIStoryboardBackend / AIStoryboardClient 的实现者。
> 定位：让智能体对分镜列表的批量编辑从「N 镜 × M 字段 = N 次工具往返」变成「写一份方案文件 + 一次 apply」，
> 并把「分镜之间的衔接（素材 + 提示词）」提升为方案里的一等对象。

## 1. 背景：为什么现在的分镜编辑通道慢

| # | 现状 | 证据 |
|---|---|---|
| 1 | 读是全量、无投影：一次 `list_project_storyboard` 返回全项目每镜的全部文本字段（scriptContent / imagePrompt / videoPrompt / negativePrompt / soundDesign / …） | `AgentScopeStoryboardTools.java:1104-1127`（`sceneMap`）、`:153-171` |
| 2 | 写是单镜 patch：改 N 镜要 N 次 `update_project_storyboard_shot`，每次还要回显整镜 | `AgentScopeStoryboardTools.java:203-249` |
| 3 | 每次改动前 skill 强制先读全表 | `skills/storyboard/SKILL.md:47-49` |
| 4 | 结构操作在正式分镜上完全缺失：没有 insert / move / split / merge；只能 append 到末尾或整片覆盖 | 工具清单见 `AgentScopeStoryboardTools.java:60-79` |
| 5 | null = 「不改」，所以「清空某字段」在工具层没有表示法 | `InternalApiServiceImpl.java:286-327` |
| 6 | 编号会漂移：删除后剩余分镜重排 1..N | `InternalApiServiceImpl.java:156-169` |

一次「改 5 镜」的实际往返：1 次全量读（1–2 万 token）+ 5 次 update + 5 次结果回显 ≈ 6–11 次模型往返。
这是「几十秒起步」的直接来源，不是列表 UI 的锅。

**衔接现在是系统猜的，不是声明的**：视频出队时自动把「当前镜主图」当首帧、「下一镜主图」当尾帧
（`StoryboardVideoQueueServiceImpl.java:455-479`），没有跨镜角色一致性、没有提示词承接、没有素材复用声明。

## 2. 目标 / 非目标

**目标**

- 智能体一次调用完成任意规模的分镜批量编辑（新增 / 删除 / 移动 / 改字段 / 重排）。
- 「分镜之间」的衔接成为可声明、可审阅的数据：每段边界挂哪些素材（角色/场景/首尾帧）、提示词如何承接。
- 全链路可回退：apply 失败不留半成品；覆盖冲突显式报错而不是静默盖掉。
- 人的手动编辑与智能体的批量编辑走同一套写入语义。

**非目标（本设计不做）**

- 不换分镜列表的 UI 骨架（大纲表是另一件事，见 §9 阶段 3）。
- 不做分镜版本历史 / undo 树（阶段 4）。
- 不让智能体用脚本「生成」分镜创作内容；脚本只允许做结构排版。

## 3. 方案总览

```
智能体                                沙箱                       MoonAgent                    AIStoryboardBackend
  │                                    │                            │                              │
  ├─ list_project_storyboard ──────────┼────────────────────────────┼─────────────────────────────►│ 骨架 + revision（~1-2k token）
  │◄───────────────────────────────────┼────────────────────────────┼──────────────────────────────┤
  │                                    │                            │                              │
  ├─ write_file work/storyboard/plan.json ►│  方案文件（JSON）           │                              │
  ├─ validate_storyboard_plan(path) ───┼──►│                          │ 读文件 + 静态校验              │
  │◄── 逐条诊断（shots[3].fields.duration 非法）┼─────────────────────┤                              │
  │                                    │                            │                              │
  ├─ apply_storyboard_plan(path, baseRevision) ──────────────────────►│ 一次事务 ─────────────────────►│ 校验 → 应用 → 返回 diff + 新 revision
  │◄── diff（新增/删除/改字段/衔接）─────┼────────────────────────────┼──────────────────────────────┤
```

关键点：**方案文件是数据，校验与应用都在 Java 侧**。模型只负责产出内容，脚本异常不会静默造出畸形分镜。

## 4. 方案文件 schema（v1）

路径约定：`work/storyboard/plan.json`（`work/` 不发布；需要给用户看时另行导出到 `output/`）。

```json
{
  "kind": "storyboard-plan",
  "schemaVersion": 1,
  "baseRevision": 42,
  "intent": "第 3 镜后插入过场，并统一 2-5 镜的雨夜光线",
  "shots": [
    {
      "op": "insert",
      "ref": "new-1",
      "afterSceneId": "s-abc",
      "fields": {
        "scriptContent": "雨水顺着棚顶铁皮滑落，林知夏抬头看了一眼",
        "imagePrompt": "…",
        "duration": 5
      }
    },
    {
      "op": "update",
      "sceneId": "s-xyz",
      "fields": { "negativePrompt": null, "duration": 6 }
    },
    { "op": "delete", "sceneId": "s-zzz" }
  ],
  "transitions": [
    {
      "from": { "sceneId": "s-abc" },
      "to": { "ref": "new-1" },
      "carry": {
        "inheritCharacters": ["林知夏"],
        "inheritLocation": "旧仓库二层",
        "lightContinuity": "冷蓝侧逆光，保留上一镜的雨夜反光",
        "matchOn": "action",
        "note": "上一镜抬手推门，本镜从门内反打，动作方向连续"
      },
      "assets": [
        { "ref": "library:media-123", "role": "REFERENCE", "purpose": "image", "note": "角色定妆" },
        { "ref": "scene:s-abc.image", "role": "FIRST_FRAME", "purpose": "video" }
      ],
      "audio": { "bridge": "雨声环境音延续", "dialogueOverlap": true }
    }
  ]
}
```

### 4.1 `shots[]`

| 字段 | 必填 | 说明 |
|---|---|---|
| `op` | 是 | `insert` / `update` / `delete` |
| `ref` | insert 时必填 | 方案内临时引用名，供 transitions 指向尚未落库的新镜 |
| `sceneId` | update/delete 必填 | 稳定主键。**不支持按 sceneNumber 定位**（编号会漂移） |
| `afterSceneId` | insert 时二选一 | 插在此镜之后；与 `atIndex` 二选一 |
| `atIndex` | insert 时二选一 | 插到第 N 位（从 1 开始），用于「插到片头」 |
| `fields` | insert/update 视情况 | 只传要改的字段，省略 = 不动 |

`fields` 语义（与现状的关键差异）：

- **省略**某个 key → 该字段不动（保持现在的 patch 语义）。
- 显式 `null` → **清空**该字段（补上现在表达不出来的能力）。
- 字符串 `""` → 清空并保留空串（对 `scriptContent` 这类 NOT NULL 语义的字段）。

### 4.2 `transitions[]`（衔接）

一段边界 = 相邻两镜之间。`from` / `to` 各取 `{ "sceneId": "…" }` 或 `{ "ref": "…" }`（指向本次 insert 的新镜）。

| 字段 | 说明 |
|---|---|
| `carry.inheritCharacters` | 跨镜延续的角色名（用于提示词承接与素材校验） |
| `carry.inheritLocation` | 延续的场景 |
| `carry.lightContinuity` | 光线/色温/氛围承接 |
| `carry.matchOn` | 剪辑动机：`action` / `eyeline` / `graphic` / `motion` |
| `carry.note` | 自由说明，直接进 `continuity_note` |
| `assets[]` | 本边界挂到 **to 镜** 的素材：`role` ∈ `FIRST_FRAME` / `LAST_FRAME` / `FIRST_LAST_FRAME` / `REFERENCE`；`purpose` ∈ `image` / `video` |
| `assets[].ref` | `library:{mediaId}` / `scene:{sceneId}.image` / `output:{assetId}` / `url:{https://…}` |
| `audio.bridge` | 声音衔接说明（对白/环境音/音乐是否延续） |
| `audio.dialogueOverlap` | 对白是否跨界叠化 |

**落库方式：衔接不引入独立关系表**，编译成两个副产物：

1. `carry` → `scene_prompts.continuity_note`（新列），生成时由后端拼进 prompt；
2. `assets` → `scene_reference_images`（已有表，`source_type='transition'`，`source_id` = 边界 key）。

理由：这两处正是生成链路已经在消费的地方（`StoryboardVideoQueueServiceImpl.java:455-479`、
`SceneMaterialServiceImpl`），不需要再造一层间接。

## 5. 数据模型改动

migration 留档 `AIStoryboardBackend/src/main/resources/db/migration/V46__storyboard_plan_mapping.sql`（本项目无 Flyway，需 psql 手动应用）：

```sql
-- 分镜版本号：乐观锁 + 变更审阅的锚点
ALTER TABLE projects ADD COLUMN storyboard_revision BIGINT NOT NULL DEFAULT 0;

-- 本镜与前镜的承接说明（由 transitions.carry 编译而来）
ALTER TABLE scene_prompts ADD COLUMN continuity_note TEXT;
```

实体同步：`Project.storyboardRevision`、`ScenePrompt.continuityNote`、`SceneFull.continuityNote`、`SceneResponse.continuityNote`。

`scene_reference_images` 的 `source_type` 新增取值 `transition`（无 schema 变化，仅约定）。

## 6. 应用语义（`StoryboardPlanService.apply`）

单一写入真源：`AIStoryboardBackend` 新增 `StoryboardPlanService` + `StoryboardPlanServiceImpl`，
`SceneController`（前端）与 `InternalApiController`（MoonAgent）都调它。

### 6.1 校验（全部在事务内、写之前完成）

| 检查 | 失败返回 |
|---|---|
| `schemaVersion` 支持 | `40001 unsupported schemaVersion` |
| `baseRevision == 当前 revision` | `40901 revision 冲突`，附 `expected` / `actual` |
| `update`/`delete` 的 `sceneId` 属于本项目且存在 | `40001 shots[i].sceneId 不存在` |
| `insert` 的 `afterSceneId` 存在 | `40001 shots[i].afterSceneId 不存在` |
| `ref` 唯一且被 transitions 引用时能解析 | `40001 transitions[j].to.ref 未定义` |
| `assets[].ref` 可解析（`library:` 校验归属，`scene:` 校验同项目） | `40001 transitions[j].assets[k].ref 不可解析` |
| `duration` ∈ [1, 15]（`SceneDurationPlanner` 口径） | `40001 shots[i].fields.duration 越界` |
| `fields` 出现未知 key | `40001 shots[i].fields.<key> 未知字段` |

错误返回**逐条**而不是首条即停（上限 20 条），路径形的 `locator` 让模型能直接定位改文件：

```json
{ "ok": false, "code": "PLAN_INVALID",
  "errors": [ { "locator": "shots[3].fields.duration", "message": "必须是 1-15 的整数" } ] }
```

这是「失败一次拿到全部问题、改文件重试」的关键，避免又退化成多轮工具调用。

### 6.2 应用顺序

1. `delete`（倒序删，避免影响后续定位）
2. `insert`（按 `atIndex` / `afterSceneId` 解析出位置，落库并回填 `ref → sceneId`）
3. `update`
4. 按最终顺序重排 `scene_number`（1..N，编号只作展示值）
5. `transitions` → 写 `continuity_note` + 挂 `scene_reference_images`
6. `projects.storyboard_revision + 1`
7. 计算并返回 diff

全在同一事务；任一步失败整体回滚，revision 不变。

### 6.3 返回 diff

```json
{ "ok": true, "revision": 43,
  "diff": {
    "inserted": [ { "sceneId": "s-new", "at": 4 } ],
    "deleted":  [ { "sceneId": "s-zzz", "was": 7 } ],
    "updated":  [ { "sceneId": "s-xyz", "fields": { "duration": { "from": 8, "to": 6 } } } ],
    "renumbered": [ { "sceneId": "s-aaa", "from": 5, "to": 4 } ],
    "transitions": [ { "from": "s-abc", "to": "s-new", "assets": 2 } ]
  } }
```

`renumbered` 让模型后续步骤不必依赖编号，也直接喂给前端的变更审阅卡。

## 7. 工具面

### 7.1 新增（MoonAgent，`AgentScopeStoryboardTools` 同组，`storyboard` 技能门控）

| 工具 | 入参 | 语义 |
|---|---|---|
| `validate_storyboard_plan` | `path`（沙箱相对路径）、可选 `plan`（inline 降级） | 只校验不写库，返回逐条诊断 |
| `apply_storyboard_plan` | `path`、可选 `plan`、`baseRevision` | 读文件 → 校验 → 事务应用 → 返回 diff + revision |

要点（沿用红线）：

- **注入型参数不能是 required**：`projectId` / `conversationId` 由 `AgentScopeRunContext` 注入，schema 里不出现。
- `path` 与 `plan` 互斥，至少给一个；`baseRevision` 从最近一次骨架读取里拿，缺失时服务端按「最新」处理并返回 `revisionAssumed: true`。
- 两个工具都走 `AgentScopeToolPipeline` 的既有策略：`validate` → `READ`，`apply` → `STORYBOARD_WRITE`（必进 HITL/审计）。
- 沙箱不可用（`ai.agent.sandbox.enabled=false`）时：`path` 分支直接返回 `ok:false` + `hint`（改用 `plan` inline），不静默失败。

### 7.2 修改

`list_project_storyboard`：默认返回骨架 + `revision`：

```json
{ "projectId": "…", "revision": 42, "count": 37,
  "scenes": [ { "sceneId": "…", "sceneNumber": 1, "summary": "雨夜旧仓库，林知夏推门…",
                "duration": 5, "hasImage": true, "hasVideo": false,
                "continuity": "冷蓝侧逆光（承接分镜1）" } ] }
```

全字段改为 `detail="full"` 显式请求，或 `sceneIds` / `range` 定向读。
`summary` 取 `scriptContent` 首句 ≤40 字。

## 8. 生成链路消费衔接

1. **提示词**：`ImageGenerationService` / `VideoGenerationService` 组装 prompt 处，
   若 `continuity_note` 非空，拼接 `"\n\n[衔接] " + note`（不改模型原始 prompt，不覆盖）。
2. **首尾帧优先级**（`StoryboardVideoQueueServiceImpl.prepareManagedFrames`）：
   显式声明（`role=FIRST_FRAME/LAST_FRAME` 且 `source_type='transition'`）
   → 用户上传 → 现有兜底（当前镜主图 / 下一镜主图）。声明永远赢过猜测。
3. **素材复用校验**：`assets[].ref = library:{mediaId}` 时校验该媒体属于本项目或全局库，
   避免把别人的资产挂进分镜。

## 9. 分期与验收

### 阶段 1（核心，可独立交付）

- migration V46 + 实体/DTO 同步
- `StoryboardPlanService.apply`（校验 / 事务 / diff / revision）
- `InternalApiController` 新端点 `POST /api/internal/projects/{id}/storyboard/apply-plan`
- MoonAgent `validate_storyboard_plan` / `apply_storyboard_plan`
- `list_project_storyboard` 骨架化

验收：
- 单测覆盖 §6.1 每条校验的失败路径 + 事务回滚（revision 不变）
- 一条 e2e：写 plan（插 1 镜 + 改 2 镜 + 删 1 镜 + 1 段衔接）→ apply → 读回断言编号连续、`continuity_note` 落库、`scene_reference_images` 增 2 条
- 往返次数断言：上述编辑 **≤ 3 次工具调用**（骨架读 + 写文件 + apply）

### 阶段 2（衔接进生成链路）

- prompt 拼接 + 首尾帧优先级 + 素材归属校验
- 前端：复用 `metadata.storyboardReview` 通道渲染「变更审阅卡」（inserted/deleted/updated/renumbered/transitions）

### 阶段 3（交互形态）

分镜列表换「大纲表 + 单镜工作台 + 变更审阅」三区（本设计不展开；apply 的 diff 已经是它的数据源）。

### 阶段 4（可选）

- `storyboard_plans` 表（方案留档 + 回滚）
- 允许 `run_python_script` 做结构排版（切分/编号/校验），创作内容仍由模型产出

## 10. 风险

| 风险 | 缓解 |
|---|---|
| 大文件写坏/截断 | 校验返回带 `locator` 的逐条诊断；`validate` 先行；文件上限 2 MB |
| 沙箱不可用 | `plan` inline 降级路径（工具参数直传） |
| 并发覆盖用户手动编辑 | `baseRevision` 乐观锁；不匹配返回 409 + 让模型重读骨架，绝不静默覆盖 |
| 与 `start_storyboard_production` 交错 | 生成流水线提交时也递增 revision；两者不共享写入函数但共享 revision |
| 模型把整片重做误当成一次编辑 | `intent` 字段进审计；`deleted` 超过项目总数 50% 时要求 `confirmBulkDelete: true` |
| `continuity_note` 污染 prompt | 独立列 + 独立拼接，不写回 `imagePrompt`/`videoPrompt` |

## 11. 与既有红线的关系

- apply 的 `STORYBOARD_WRITE` 必须经 `AgentScopeToolPipeline` 的审计与 HITL，不得绕开。
- 工具异常一律以结构化信封返回（`ok:false` + `code` + `errors`），复用 `ToolResultContract.normalize`；
  不得把内层 `ok:false` 包成外层 `ok:true`（`MoonAgent/docs/agent-internals-review.md:132-142` 的既有病灶）。
- 时间字段若进方案（如 `updatedAt`），JSON 必须带 `JavaTimeModule` 且关闭时间戳序列化。
