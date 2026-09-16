# 画布结果槽顺序填充与一键执行

- 日期：2026-09-16
- 项目：Morrow Creative Canvas
- 范围：`XiaoYueCanvasAgent`

## 目标

让预埋的空图片/视频占位节点成为“结果槽”，承接上游生成结果，并支持一键执行整条流程后继续向下游传播。

## 执行规则

1. 生成节点的输出可以连接多个有序占位节点作为结果槽。
2. 结果槽顺序必须来自明确元数据，例如 `resultSlot` + `slotOrder` 或 `portOrder`，不能按画布坐标排序。
3. 上游任务返回 `M` 个结果，下游有 `N` 个兼容类型槽：
   - `M <= N`：前 `M` 个结果按顺序填槽，调用已有 `attachAssetToNode`，写入 `assetId` 并设置 `success/ready`。已填槽节点视为成功，其下游继续执行；未填的必填槽标记 `blocked`，并阻塞后代。
   - `M > N`：前 `N` 个结果填槽，剩余 `M-N` 个结果自动创建新的图片或视频结果节点，并连接到生成节点；不能强行连接到只接受单输入的下游端口。
4. 图片槽只能承接图片，视频槽只能承接视频。
5. 一个结果槽不能同时归属多个生产者的输出；执行前必须校验冲突并报错。
6. 结果槽本身不是模型执行节点，不调用生成 API；在计划中作为等待结果写入的虚拟步骤。结果写入后执行引擎必须能穿过它继续寻找下游可启动节点。
7. 没有结果槽时保持现有 `media-nodes` 行为：创建新的 `result:*` 节点。
8. 取消、失败、重试不得留下假成功；重试应清理上一轮未完成状态并保持幂等。
9. 图片和视频共享逻辑，不复制两套实现。

## 重点修改位置

- `XiaoYueCanvasAgent/src/canvas/generationMaterializer.ts`
- `XiaoYueCanvasAgent/src/canvas/generationRuntime.ts`
- `XiaoYueCanvasAgent/src/canvas/graphContracts.ts`
- `XiaoYueCanvasAgent/src/canvas/graphExecutionPlanner.ts`
- `XiaoYueCanvasAgent/src/canvas/graphExecutionService.ts`
- `XiaoYueCanvasAgent/src/store/canvasStore.ts`
- 相关 InspectorPanel / 连线或节点 UI，用于标记输出连线为结果槽
- 相关单元测试和 Playwright E2E

## 实现要求

- 先写失败测试，再实现。
- 覆盖 `M<N`、`M=N`、`M>N`。
- 覆盖图片/视频类型不匹配。
- 覆盖无槽位时保持旧行为。
- 覆盖结果槽下游继续执行。
- 覆盖取消、失败和阻塞。
- 保留当前未提交改动，不回滚无关文件。
- 最后运行 `XiaoYueCanvasAgent` 全量 `npm test`、`npm run build` 和相关 E2E。
