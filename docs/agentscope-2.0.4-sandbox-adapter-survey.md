# AgentScope 2.0.4 官方 Sandbox API 实证与适配对照

> 状态：实证调研（第一步），不含任何代码改动。
> 版本：AgentScope 2.0.4（`agentscope-core` / `agentscope-harness`）。
> 目的：确认官方 sandbox 体系的真实类、接口与装配契约，并与本仓库现有自研实现逐项对照，
> 为后续「MoonSandbox 适配官方 SandboxClient」的实现计划提供决策依据。

## 0. 实证方法

全部结论来自本机 Maven 仓库中的官方制品，可复现：

```powershell
# 制品位置
$repo = "D:\dev\m2-repo\io\agentscope"

# 类清单（编译产物）
jar tf "$repo\agentscope-harness\2.0.4\agentscope-harness-2.0.4.jar" | Select-String "sandbox|artifact|FilesystemTool|ShellExecute"
jar tf "$repo\agentscope-core\2.0.4\agentscope-core-2.0.4.jar"   | Select-String "sandbox|Sandbox"

# 源码核对
Expand-Archive "$repo\agentscope-harness\2.0.4\agentscope-harness-2.0.4-sources.jar" -DestinationPath "$env:TEMP\as-harness-src-204" -Force
```

实测结论：

- `agentscope-core-2.0.4.jar` 中不存在任何 `sandbox` / `Sandbox` 类；sandbox 体系全部位于
  `agentscope-harness`。
- `agentscope-harness-2.0.4.jar` 中 `io/agentscope/harness/agent/sandbox/**` 编译类共 64 个。

---

## 1. 官方类清单

全部类位于包 `io.agentscope.harness.agent.*`，归属制品 `agentscope-harness`。

### 1.1 沙箱核心

| 类 | 包路径 | 角色 |
| --- | --- | --- |
| `SandboxClient<O extends SandboxClientOptions>` | `io.agentscope.harness.agent.sandbox` | 沙箱工厂：创建 / 恢复 / 删除 / 序列化 |
| `Sandbox` | `io.agentscope.harness.agent.sandbox` | 单个活动沙箱：生命周期 + 执行 + 工作区存档 |
| `SandboxState` | `io.agentscope.harness.agent.sandbox` | 可序列化沙箱状态（跨调用恢复） |
| `SandboxClientOptions` | `io.agentscope.harness.agent.sandbox` | 后端配置基类，可自建 client |
| `SandboxContext` | `io.agentscope.harness.agent.sandbox` | 不可变沙箱配置，随 `RuntimeContext` 传递 |
| `SandboxManager` | `io.agentscope.harness.agent.sandbox` | acquire / release / persistState / clearState |
| `SessionSandboxStateStore` | `io.agentscope.harness.agent.sandbox` | 按隔离键读写 `SandboxState` |
| `SandboxIsolationKey` | `io.agentscope.harness.agent.sandbox` | 由 scope + userId/sessionId/agentId 解析 |
| `ExecResult` | `io.agentscope.harness.agent.sandbox` | 命令执行结果记录 |
| `WorkspaceSpec` | `io.agentscope.harness.agent.sandbox` | 工作区根、预置条目、环境变量 |
| `SandboxFileTransfer` | `io.agentscope.harness.agent.sandbox` | 可选文件上传/下载能力接口 |

### 1.2 快照

| 类 | 包路径 | 角色 |
| --- | --- | --- |
| `SandboxSnapshotSpec` | `io.agentscope.harness.agent.sandbox.snapshot` | 快照策略接口 |
| `SandboxSnapshot` | `io.agentscope.harness.agent.sandbox.snapshot` | 快照数据接口 |
| `NoopSnapshotSpec` / `LocalSnapshotSpec` / `RemoteSnapshotSpec` | `io.agentscope.harness.agent.sandbox.snapshot` | 三种内置策略 |

### 1.3 文件系统与工具

| 类 | 包路径 | 角色 |
| --- | --- | --- |
| `SandboxFilesystemSpec` | `io.agentscope.harness.agent.filesystem.spec` | 声明式沙箱文件系统配置（抽象类） |
| `AbstractSandboxFilesystem` | `io.agentscope.harness.agent.filesystem.sandbox` | 文件系统 + shell 执行能力接口 |
| `BaseSandboxFilesystem` | `io.agentscope.harness.agent.filesystem.sandbox` | 抽象基类，提供 ls/read/write/edit/grep/glob/delete/move |
| `SandboxBackedFilesystem` | `io.agentscope.harness.agent.filesystem.sandbox` | 运行时绑定具体 `Sandbox` 的实现 |
| `FilesystemTool` | `io.agentscope.harness.agent.tool` | 注册 read/write/edit/grep/glob/list 文件工具 |
| `ShellExecuteTool` | `io.agentscope.harness.agent.tool` | 注册 `execute` shell 工具 |
| `ArtifactDeliveryTool` | `io.agentscope.harness.agent.tool` | 注册 `deliver_artifact` 交付工具 |
| `ArtifactDeliveryTarget` | `io.agentscope.harness.agent.artifact` | 产物交付 SPI（函数式接口） |
| `ArtifactDeliveryRequest` / `ArtifactDeliveryResult` | `io.agentscope.harness.agent.artifact` | 交付请求 / 结果记录 |

---

## 2. 连接契约（逐方法）

### 2.1 `SandboxClient`（5 个必需方法）

来源：`io/agentscope/harness/agent/sandbox/SandboxClient.java`

| 行 | 签名 | 语义 |
| --- | --- | --- |
| 32 | `Sandbox create(WorkspaceSpec, SandboxSnapshotSpec, O)` | 新建，返回 **未 start** 状态，调用方需自行 `start()` |
| 37 | `Sandbox resume(SandboxState)` | 从已序列化状态恢复 |
| 39 | `void delete(Sandbox)` | 销毁后端资源 |
| 41 | `String serializeState(SandboxState)` | 序列化为 JSON |
| 43 | `SandboxState deserializeState(String)` | 反序列化 |
| 45 | `default SandboxState deserializeState(String, SandboxSnapshotSpec)` | 默认委托到单参版本；可覆写以重绑快照 |

### 2.2 `Sandbox` 生命周期（9 个方法）

来源：`io/agentscope/harness/agent/sandbox/Sandbox.java`

| 行 | 方法 | 说明 |
| --- | --- | --- |
| 44 | `void start()` | 初始化或恢复工作区 |
| 46 | `void stop()` | 仅持久化快照，**不销毁资源** |
| 51 | `default void shutdown()` | 销毁后端资源（可选覆写） |
| 53 | `void close()` | stop + shutdown |
| 55 | `boolean isRunning()` | 运行态 |
| 62 | `SandboxState getState()` | 返回当前可序列化状态 |
| 71 | `ExecResult exec(RuntimeContext, String command, Integer timeoutSeconds)` | 执行 shell 命令 |
| 74 | `InputStream persistWorkspace()` | 导出工作区归档 |
| 76 | `void hydrateWorkspace(InputStream)` | 导入工作区归档 |

关键语义：`stop()` 与 `shutdown()` 必须区分；`persistWorkspace` / `hydrateWorkspace` 是框架在
`SessionSandboxStateStore` + `SandboxManager.persistState()` 链路里调用的存档机制。

### 2.3 `ExecResult`

来源：`io/agentscope/harness/agent/sandbox/ExecResult.java`

```java
public record ExecResult(int exitCode, String stdout, String stderr, boolean truncated)
```

`ok()` 判定 `exitCode == 0`；`combinedOutput()` 把 stderr 以 `[stderr]` 前缀拼接。

### 2.4 `SandboxState`（可序列化字段）

来源：`io/agentscope/harness/agent/sandbox/SandboxState.java`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `String` | 会话标识 |
| `workspaceSpec`（JSON `manifest`） | `WorkspaceSpec` | 工作区描述 |
| `snapshot` | `SandboxSnapshot` | 快照引用 |
| `workspaceProjectionHash` | `String` | 工作区投影哈希 |
| `workspaceRootReady` | `boolean` | 驱动 start 的四分支逻辑 |

子类经 `HarnessSandboxJacksonModule` 注册 Jackson，而非类上 `@JsonSubTypes`。

### 2.5 `SandboxClientOptions`

来源：`io/agentscope/harness/agent/sandbox/SandboxClientOptions.java`

| 行 | 方法 | 说明 |
| --- | --- | --- |
| 41 | `abstract String getType()` | JSON 判别名（内置 `"docker"`） |
| 51 | `abstract SandboxClient<? extends SandboxClientOptions> createClient()` | 自建 client |
| 60 | `String getWorkspaceRoot()` | 默认 `/workspace`，可覆写 |

---

## 3. 自动装配契约（HarnessAgent.Builder）

来源：`io/agentscope/harness/agent/HarnessAgent.java`

当 `builder.filesystem(SandboxFilesystemSpec)` 被调用时，框架按以下顺序自动装配：

| 行 | 动作 |
| --- | --- |
| 2451 | `capturedSandboxFs = new SandboxBackedFilesystem()` |
| 2457 | `defaultSandboxContext = sandboxFilesystemSpec.toSandboxContext(resolvedWorkspace)` |
| 2469 | `new SessionSandboxStateStore(effectiveSession, resolvedAgentId)` |
| 2475 | `new SandboxManager(client, stateStore, agentId, executionGuard)` |
| 2481 | `new SandboxLifecycleMiddleware(sandboxManager, capturedSandboxFs)` |
| 2525 | `artifactDeliveryEnabled = artifactDeliveryTarget != null && !disableFilesystemTools` |
| 2715 | `registerTool(new FilesystemTool(filesystem, pathNormalizer))` |
| 2717-2720 | `artifactDeliveryEnabled` 时注册 `ArtifactDeliveryTool` |
| 2722-2723 | 文件系统是 `AbstractSandboxFilesystem` 且未 `disableShellTool` 时注册 `ShellExecuteTool` |

辅助契约：

- `SandboxFilesystemSpec` 是抽象类，需实现 4 个钩子（来源 `SandboxFilesystemSpec.java`）：
  `createClient()`(50)、`clientOptions()`(52)、`snapshotSpec()`(54)、`workspaceSpec()`(56)。
  `toSandboxContext(Path)` 会把 workspace projection 合并进 `WorkspaceSpec`。
- 启用 sandbox 模式时，框架要求 `AgentStateStore` 能跨 JVM 持久化，否则日志告警
  `Sandbox mode is using a local AgentStateStore`（HarnessAgent.java:2460 一带）。
- `SandboxManager` 构造：`(SandboxClient<?>, SessionSandboxStateStore, String agentId[, SandboxExecutionGuard])`
  （`SandboxManager.java:52-67`）。
- `SessionSandboxStateStore`：`load(key)` / `save(key, json)` / `delete(key)`，
  内部 `SandboxStateSlot` 记录（`SessionSandboxStateStore.java:32-98`）。
- `ArtifactDeliveryTarget` 是 `@FunctionalInterface`：
  `ArtifactDeliveryResult deliver(RuntimeContext, ArtifactDeliveryRequest)`
  （`ArtifactDeliveryTarget.java`）。

### 3.1 `AbstractSandboxFilesystem` 能力面

来源：`io/agentscope/harness/agent/filesystem/sandbox/AbstractSandboxFilesystem.java`
与 `BaseSandboxFilesystem.java`

- `AbstractSandboxFilesystem`: `String id()` + `ExecuteResponse execute(RuntimeContext, String, Integer)`。
- `BaseSandboxFilesystem` 提供默认实现：`ls`/`read`/`write`/`edit`/`grep`/`glob`/`delete`/`move`/`exists`
  （行 73/133/195/235/322/370/416/428/443）。
- `SandboxBackedFilesystem` 额外实现 `setSandbox`/`getSandbox`，以及 `uploadFiles`/`downloadFiles`
  （行 72/77/123/178）。

---

## 4. 工具契约与现有实现对照

### 4.1 官方工具签名

来源：`io/agentscope/harness/agent/tool/{FilesystemTool,ShellExecuteTool,ArtifactDeliveryTool}.java`

| 工具名 | 定义 | 参数 |
| --- | --- | --- |
| `read_file` | FilesystemTool:110-116 | `path`, `offset?`, `limit?` |
| `write_file` | FilesystemTool:139-142 | `path`, `content` |
| `edit_file` | FilesystemTool:151-156 | `path`, `old_string`, `new_string`, `replace_all?` |
| `grep_files` | FilesystemTool:179-185 | `pattern`, `path?`, `glob?`, `limit?` |
| `glob_files` | FilesystemTool:229-235 | `pattern`, `path?`, `limit?` |
| `list_files` | FilesystemTool:283-287 | `path` |
| `execute` | ShellExecuteTool:32,44-50 | `command`, `working_directory?`, `timeout?` |
| `deliver_artifact` | ArtifactDeliveryTool:71-82 | `filePath`, `fileName?`, `description?`, `force?` |

### 4.2 现有自研工具

来源：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/agentscope/AgentScopeSandboxTools.java`

| 常量 | 行 | 工具名 |
| --- | --- | --- |
| `WRITE_FILE` | 19 | `write_file` |
| `READ_FILE` | 20 | `read_file` |
| `LIST_FILES` | 21 | `list_files` |
| `RUN_PYTHON_SCRIPT` | 22 | `run_python_script` |

`tools()` 返回顺序见行 30。`write_file` 强制路径归一到 `output/`（`deliverablePath`）；
`run_python_script` 走异步任务 `startSandboxScriptTask`。

### 4.3 重名不同义项（迁移高风险点）

| 工具名 | 官方语义 | 现有语义 | 风险 |
| --- | --- | --- | --- |
| `write_file` | 任意工作区路径 | 强制 `output/` 归一 | 同名不同路径策略，直接切换会改变模型产出落点 |
| `read_file` | 行号 + `offset/limit` | 字符 `offset/maxChars` | 参数语义不兼容 |
| `list_files` | 官方列表封装（有截断策略） | 直接透传 runner 文件列表 | 输出结构不同 |
| `execute` | 任意 shell（`ShellExecuteTool`） | 无 | 现有模型只能用 `run_python_script` |
| `run_python_script` | 官方无此工具 | 异步 Python 任务 | 迁移后要么保留自研工具，要么改提示词 |

---

## 5. 现有实现事实

### 5.1 MoonAgent 侧

- 当前 `HarnessAgent.Builder` 未调用 `filesystem(...)`，使用的是默认本地文件系统；
  同时显式 `disableFilesystemTools()` 与 `disableShellTool()`
  （`MoonAgentRuntime.java:192`, `:211-213`）。
- 自研沙箱客户端：`MoonSandboxClient`
  （`MoonAgent/.../ai/agent/sandbox/MoonSandboxClient.java`）。
  - `write` :40 → `POST /files/write`
  - `read` :48 → `POST /files/read`
  - `list` :55 → `GET /files`
  - `execute` :69 → `POST /execute`（参数是 `script`，不是 shell command）
  - `download` :78 → `GET /files/download`
  - `cleanup` :96 → `DELETE`
  - `base()` :141 → `{baseUrl}/internal/sandbox/{conversationId}`
- 产物回扫：`SandboxOutputReconciler.reconcile()`（:25）遍历 runner `output/`，
  调 `SandboxArtifactPublisher.publishPath()`（:60）。
- 发布幂等：`SandboxArtifactPublisher` 按
  `conversationId|path|contentHash` 生成 `idempotencyKey`（:80），查重（:85），
  写入 `agent_assets` 并标 `gen_type=sandbox_generated`（:107-108）。
- 调用点：`AgentChatServiceImpl` 在 :1185 与 :1315 调 `reconcileSandboxOutputs(...)`。
- 异步执行：`AgentScopeAsyncTaskService.startSandboxScriptTask`（:543），
  执行链在 :1797-1806 调 `sandboxClient.execute(...)` 与 `sandboxPublisher.publish(...)`。

### 5.2 MoonSandbox runner 侧

来源：`MoonSandbox/src/main/java/com/moon/sandbox/web/SandboxController.java`

| 行 | 端点 | 能力 |
| --- | --- | --- |
| 25 | `@RequestMapping("/internal/sandbox/{conversationId}")` | 会话隔离键 = conversationId |
| 37 | `POST /files/write` | 写文件（CREATE/OVERWRITE/APPEND） |
| 43 | `POST /files/read` | 读文件（字符偏移） |
| 49 | `GET /files` | 列目录 |
| 55 | `GET /files/download` | 下载字节 |
| 63 | `POST /execute` | 执行 Python 脚本 |
| 71 | `DELETE` | 清理会话 |

执行实现：`SandboxExecutionService.execute`（:37）把脚本写入
`work/.runner/{uuid}.py`（:65），用 `ProcessBuilder` 以 `python3` 启动（:70）。
**没有任何任意 shell 命令入口。**

---

## 6. 适配缺口与候选解法

### 缺口 1：`ShellExecuteTool` 无后端

- 现状：runner 只有 Python 脚本入口，`Sandbox.execute(RuntimeContext, command, timeout)`
  语义是任意 shell。
- 候选 A（扩展 runner）：新增 shell 执行端点或在 `/execute` 增加 `command` 模式，
  复用现有并发闸门与超时/截断逻辑。
- 候选 B（放弃官方 shell）：继续 `disableShellTool()`，只复用官方文件工具与产物交付。
- 推荐：A（新增 shell 端点），但必须保留白名单/资源上限，避免把 runner 变成任意命令执行面。

### 缺口 2：工作区快照无落点

- 现状：runner 无 upload/download 整包端点，`persistWorkspace` / `hydrateWorkspace` 无处实现。
- 候选 A（扩展 runner）：新增工作区归档 upload/download 端点，实现真实快照。
- 候选 B（no-op 快照）：`persistWorkspace` 返回空流、`hydrateWorkspace` 忽略，
  依赖 Docker 命名卷 `sandbox-workspaces` 的持久性。
- 代价：B 在 runner 重启不丢，但跨节点/换卷无法恢复；与现有「命名卷即真源」一致。
- 推荐：B 起步（`NoopSnapshotSpec` 语义 + 显式注释），A 作为后续增强。

### 缺口 3：隔离键映射

- 现状：官方 `SandboxIsolationKey` 依赖 `RuntimeContext` 的 userId/sessionId/agentId
  （`SandboxIsolationKey.resolve`）。
- 现状：runner 用 `conversationId` 路径参数。
- 候选：`IsolationScope.SESSION` + `sessionId = conversationId`；
  或 `IsolationScope.USER` + `userId`（会跨会话共享沙箱）。
- 推荐：`SESSION` + `conversationId`，与现有会话级工作区语义一致。

### 缺口 4：当前装配并非 sandbox 模式

- 现状：`MoonAgentRuntime` 未接 `SandboxFilesystemSpec`，且关闭了文件与 shell 工具，
  沙箱能力全部由自研 `AgentScopeSandboxTools` 提供。
- 影响：迁移需要同时改「装配点（Builder）」与「工具来源（自研 → 官方）」，
  不能只替换 `MoonSandboxClient`。

### 缺口 5：产物交付模型差异

- 现状：`SandboxOutputReconciler` 每轮自动扫 `output/` 并发布（`reconcile` :25）。
- 官方：`ArtifactDeliveryTool` 由模型显式调 `deliver_artifact`，经 `ArtifactDeliveryTarget`
  交付（`ArtifactDeliveryRequest(filePath, content, fileName, description, force)`）。
- 影响面：
  - `agent_assets` 幂等键目前基于 `conversationId|path|contentHash`；
    官方交付按 `fileName` + `force` 语义，冲突策略不同。
  - 消息 `metadata.sandboxAttachments` 与前端文件卡依赖 `reconcile` 回写；
    若只为官方交付实现 `ArtifactDeliveryTarget`，仍需把结果回写消息元数据。
- 候选：双轨兼容——`ArtifactDeliveryTarget` 落 `agent_assets` 并回写附件元数据，
  保留 `reconcile` 作为兜底；或单轨切换。
- 推荐：双轨起步，确认官方路径稳定后再评估下线 `reconcile`。

---

## 7. 决策点（需在后续 migration 计划中拍板）

1. **runner 是否扩展 shell 与归档端点**：决定能否真正复用 `ShellExecuteTool` 与
   `persistWorkspace`/`hydrateWorkspace`，还是接受功能降级。
2. **隔离范围选 `SESSION`（conversationId）还是 `USER`**：直接影响沙箱复用粒度与状态键。
3. **产物交付走官方 `deliver_artifact` 单轨还是与 `SandboxOutputReconciler` 双轨**：
   影响 `agent_assets` 幂等、消息附件元数据与前端文件卡。

---

## 8. 抽样验证清单

以下断言已在本次调研中核对，可作为后续回归的基线：

- `api.agentscope-core-2.0.4.jar` 无 `sandbox`/`Sandbox` 类。
- `agentscope-harness-2.0.4.jar` 的 `sandbox/**` 编译类数量为 64。
- `SandboxClient` 恰好 5 个必需方法，另有 1 个 `deserializeState(String, SandboxSnapshotSpec)`
  的 default 重载。
- `Sandbox` 恰好 9 个方法（含 `shutdown` 默认实现）。
- `SandboxFilesystemSpec` 恰好 4 个抽象钩子。
- `ArtifactDeliveryRequest` 为 5 组件 record，`ArtifactDeliveryResult` 为 4 组件 record。
- `HarnessAgent.Builder` 的自动注册点位于 2451/2457/2469/2475/2481/2715/2717/2722。

---

## 9. 落地后的现状与后续 gate（2026-10-11 补充）

上述缺口已按「双轨起步」实施完毕：runner 新增 `/shell`、`/archive`、`/files/upload`；
MoonAgent 新增 `SandboxClient` 适配层（`SESSION` 隔离 + `NoopSnapshotSpec`）；
官方 `FilesystemTool`/`ShellExecuteTool`/`ArtifactDeliveryTool` 已注册，自研 `write_file`/`read_file`/`list_files` 退役。

以下 gate 用来判断后续是否能继续收敛，全部以可查询数据为准。

### 9.1 双轨产物：下线 `SandboxOutputReconciler` 的 gate

实测（2026-10-11）：

```sql
-- 官方显式交付路径的真实调用次数
select count(*) from agent_tool_invocations where tool_name = 'deliver_artifact';
-- 官方沙箱工具上线（00:49）之后的所有工具调用
select tool_name, count(*) from agent_tool_invocations
 where created_at >= '2026-10-11 00:49:00+08' group by tool_name;
```

结果：`deliver_artifact` 历史调用 **0 次**；官方工具上线后 `agent_tool_invocations` **0 条**。
即官方交付路径尚无任何真实流量，`SandboxOutputReconciler` 仍是唯一被验证过的发布链路。

**Gate（全部满足才评估下线 reconciler）**：

1. `deliver_artifact` 累计 ≥ 20 次成功交付；
2. 观测窗口跨 ≥ 2 周，且不存在「只有 reconciler 能发现」的产物；
3. 官方交付路径与 reconciler 的 `agent_assets` 幂等键行为一致（同内容不重复）。

在此之前保留双轨，不做下线。

### 9.2 `ToolCallContentMiddleware`：删除 gate

AgentScope 2.0.4 仍在 `ReActAgent:3632` 与 `ToolExecutor:233` 以
`ToolValidator.validateInput(use.getContent(), schema)` 作为校验入口；`content` 为 `null` 时
`normalizeOptionalNullFields` 原样返回 `null`，必填 schema 必然校验失败。上游未修复，因此保留。

现已补可观测性：每次真正补齐 content 会打 INFO 日志
（`[tool-audit] restored missing tool-call content ...`）并累加 Micrometer 计数
`agent_tool_call_content_rewrite_total`。

**Gate（全部满足才考虑删除）**：上游不再以 `ToolUseBlock.content` 作为唯一校验输入；
且该计数在完整发布周期内保持为 0。

### 9.3 框架工具审计补齐

`AgentScopeToolPipeline` 只包装自研工具，Harness 直接注册的 8 个框架工具
（`read_file`/`write_file`/`edit_file`/`grep_files`/`glob_files`/`list_files`/`execute`/`deliver_artifact`）
曾绕过 `agent_tool_invocations`。现由 `FrameworkToolAuditMiddleware` 按工具名白名单旁路补齐：
不改变工具行为、失败只记 debug、与管线审计不重复计数（名字已由框架独占 + 同名作用域守卫）。

验证 SQL：

```sql
select tool_name, status, count(*) from agent_tool_invocations
 where source = 'AGENT_SCOPE' and tool_name in
       ('read_file','write_file','edit_file','grep_files','glob_files',
        'list_files','execute','deliver_artifact')
 group by tool_name, status order by tool_name;
```
