# XiaoYueCanvas 画布图入库设计

- 日期：2026-08-29
- 状态：待评审
- 涉及：XiaoYueCanvasAgent（前端画布）+ XiaoYueCanvas（后端 job-service）+ agent-core（共享实体）
- 关联 sketch：参考仓库 `xiakeman-ai-short-drama/src/features/canvas`（画布交互范式，仅借鉴工程能力）

## 背景与目标

当前画布节点/连线/视口**纯前端存 IndexedDB**（`projectStorage.ts` `canvases` store），后端 `morrow_canvases` 只存元数据（id/projectId/title）。`MorrowProjectController` 注释明确写"节点实体内容仍在浏览器 IndexedDB（按画布 id），本地仅做缓存"。

目标：**节点图内容入库，不再依赖 IndexedDB 作为持久源**。画布保有两种操作路径且并存：

1. **Agent 编排**：`toolRegistry` → `canvasCommandService` 逐命令改画布
2. **人工微调 / 纯人工**：用户直接在 `CanvasWorkspace` 上拖拽/改 prompt/连线

两条路径最终都汇聚到前端 canvasStore，经 `saveCanvas` 提交，故后端只需收**整幅图快照**。

## 决策：整幅快照 + revision 乐观锁（非节点独立表）

理由：

- 前端是唯一写方，`canvasCommandService` / `toolRegistry` 是全部改动的汇聚点——任何增量命令执行后，最终都用**当前全量图**提交一次。后端无需感知每个命令，无需节点独立表。
- 独立节点表（模式 B）的"增量 upsert + 删除标记 + diff 同步"是为**多端并发**设计的复杂度，而本画布单客户端 + 会话级互斥（`ConversationLock`）已锁住并发。引入独立表是过度设计。
- `graph_json` 一列存整幅 `{nodes, edges, viewport}`，与前端 `CanvasDocumentV1` 结构一致，零映射、零转换。

**并发防护**：用 `revision` 乐观锁。Agent 编排与人微调可能并发写同一画布，保存时带 `baseRevision`，后端 CAS `WHERE canvas_id=? AND revision=?`，失败返回冲突并回传当前 revision，前端拿新 revision 后提示刷新/重放。

## 数据模型

新表 `morrow_canvas_graphs`（画布一图一行，PK = canvas_id）：

```sql
CREATE TABLE IF NOT EXISTS morrow_canvas_graphs (
    canvas_id  text PRIMARY KEY REFERENCES morrow_canvases(id) ON DELETE CASCADE,
    graph_json text NOT NULL,
    revision   integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_morrow_canvas_graphs_canvas ON morrow_canvas_graphs(canvas_id);
```

- `graph_json` = `{"nodes": [...], "edges": [...], "viewport": {"x":..,"y":..,"zoom":..}}`（前端 `CanvasDocumentV1` 去掉 schemaVersion/id/projectId/title 等元数据后的图载荷）
- `revision` 初始 0，每次成功保存 +1
- 资产二进制（图/视频 Blob、thumbnail/preview 变体）**仍留 IndexedDB**，节点 `data.assetId` 指向资产 key，跨端一致。资产落库是独立决策，本次不动。

## 后端改动（XiaoYueCanvas）

新增（放 XiaoYueCanvas 自身，不污染 agent-core 共享实体）：

- `entity/CanvasGraph.java` — `@TableName("morrow_canvas_graphs")`，`@TableId` = canvasId，含 graphJson/revision/时间戳
- `mapper/CanvasGraphMapper.java` — `extends BaseMapper<CanvasGraph>`
- `dto/request/CanvasGraphSaveRequest.java` — `record(JsonNode graph, Integer baseRevision)`
- `dto/response/CanvasGraphResponse.java` — `record(JsonNode graph, int revision, String updatedAt)`
- `service/CanvasGraphService.java`（接口）+ `service/impl/CanvasGraphServiceImpl.java`
  - `load(userId, canvasId)` → 无记录返回空图(revision 0)；有则返回
  - `save(userId, canvasId, graph, baseRevision)` → 归属校验 + 乐观锁 CAS
- `controller/MorrowCanvasGraphController.java` — `GET /canvases/{id}/graph`、`PUT /canvases/{id}/graph`

归属校验复用 `agent-core` `MorrowCanvasServiceImpl.get(userId, canvasId)`（已做 project.userId 归属校验）。

### 端点契约

```
GET /api/canvas/canvases/{canvasId}/graph
→ 200 {graph:{nodes,edges,viewport}, revision, updatedAt}
   （无记录：graph={nodes:[],edges:[],viewport:{x:0,y:0,zoom:1}}, revision=0）

PUT /api/canvas/canvases/{canvasId}/graph
body: {graph:{nodes,edges,viewport}, baseRevision}
→ 200 {graph, revision, updatedAt}
→ 40901 冲突（revision 不匹配，data.revision = 当前 revision）
```

`baseRevision` 为 null（历史无图/首存）时按空白语义：若记录已存在则返回冲突，不存在则建行 revision=0→1。

## 前端改动（XiaoYueCanvasAgent）——本步暂缓

按偏好前端可暂缓，后续点名补。计划改动：

- `projectStorage.ts:saveCanvas` / `loadCanvas`：改为调 `PUT/GET /canvases/{id}/graph`，IndexedDB 降级为本地缓存（读不到后端再读本地）
- `canvasStore`：保存成功回写 revision；保存冲突时提示并拉取新 revision
- 保留 IndexedDB 资产（Blob+变体）与懒迁移逻辑

## 工程能力移植（后续增强，非本步）

参考画布可吸收、侵入性小的三样（视需要再做）：

1. `connectivity` 数据流强约束（maxInputs/maxOutputs/acceptTypes）
2. undo/redo 的 base64 OOM 防护（`snapshotState` 排除 >4KB data: 字段 + `mergeDataUrlFields` 恢复）
3. 图片缓存驱逐（删节点时清 Blob/URL）

## 验证

- `mvn -f XiaoYueCanvas/pom.xml compile`（JAVA_HOME 用 Windows 路径 `E:\Development\jdk25`）
- 建表 SQL 手动 psql 应用（项目未用 Flyway，migration 留档）
- 无运行期验证（前端未接），编译通过 + 契约自检为准
