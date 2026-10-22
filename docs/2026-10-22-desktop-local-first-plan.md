# Moon 桌面端（本地优先）方案

- 版本：v1.0
- 日期：2026-10-22
- 状态：已定稿，进入 P0
- 分支：单 `master` + `desktop/` 目录；开发在短期特性分支 `codex/desktop-local-first`

## 1. 背景与决策

现有系统是纯 Web：项目、分镜、画布、会话、沙箱、产物全部在云端。用户资产（PPT、文档、素材）
每次处理都要先上传、再下载，路径长、隐私差、离线不可用。

决策：把**执行面与数据面整体下沉到桌面**，云端只保留身份、模型、共享素材三件事。

已锁定的决策：

| 决策项 | 结论 |
|---|---|
| 分支策略 | 单 `master`，新增 `desktop/` 目录隔离桌面专属代码；不开长期桌面主分支 |
| 智能体内核 | 本地（同一份 MoonAgent 代码，`--spring.profiles.active=local`） |
| 画布 | 本地；后端先以**双 sidecar** 运行，前端**同源 iframe** |
| 登录 | JWT + refresh token，**不做设备/机械码绑定**，设备数不限 |
| 记忆 / 设置 | 本地 |
| 交付 | 沙箱产出 → Electron 主进程复制到用户工作区 |
| 项目库 | 云端，显式上传 |

## 2. 目标与范围

### 2.1 范围内

- 桌面客户端（Electron）与本地内核、本地画布后端、本地数据库
- 本地工作区授权、沙箱隔离、产物交付
- 项目库上传（单向上行）
- 离线可用的本地编辑能力
- 本地备份与项目包导出/导入

### 2.2 明确不做（v1）

- 团队协作、云端业务库
- 多端同步、设备绑定
- 云端画布
- 离线模型推理
- Windows 10 正式支持（v1 要求 Win11）
- 素材版本管理（项目库一个文件就是一行资产）

## 3. 总体架构

```
┌──────────────────────────── Electron ────────────────────────────┐
│ 主进程：拉起 sidecar / 建 MXC 容器 / 托盘 / 自动更新 / 容器外交付  │
│ 渲染进程：http://127.0.0.1:<port>/  ← 同源，不用 file://          │
└──────────────┬───────────────────────────────────────────────────┘
               │ 127.0.0.1，X-Moon-Token
┌──────────────▼────────── MXC process container ──────────────────┐
│  MoonKernel :8084            CanvasServer :3000                  │
│   ├── 智能体循环 / 工具 / HITL   ├── 画布 CRUD / 图命令 / revision │
│   ├── sandboxes\{cid}\          ├── 读同一份 canvas_* 表           │
│   └── 只读 skills\ runtime\     └── 静态托管 /canvas/             │
│              └────── 同一个本地 Postgres 实例 ──────┘             │
└──────────────┬───────────────────────────────────────────────────┘
               │ HTTPS（仅三类）
               ▼
        登录/租户 · LLM 网关 · 项目库上传
```

职责边界：

- 渲染进程不直接碰文件系统
- 内核**不能写用户工作区**（只读挂载）
- 交付由容器外的 Electron 主进程执行

## 4. 仓库结构

```
master
├── MoonAgent/              共享（新增 local profile 与替换 Bean）
├── XiaoYueCanvas/          共享（桌面端作为独立 sidecar 打包）
├── AIStoryboardClient/     共享（新增同源适配、本地 API 客户端）
├── XiaoYueCanvasAgent/     共享（画布前端，新增构建与托管配置）
├── agent-core/ CommonCore/ …  共享
└── desktop/                新增，仅桌面
     ├── shell/             Electron 主进程、preload、托盘、更新、交付复制
     ├── packaging/         jlink 脚本、安装器、MXC 策略 JSON
     ├── runtime/           便携 Python 清单、技能清单、版本锁定
     └── kernel-boot/       P5 才启用（画布合并为单进程时的启动类）
```

规则：

1. 业务代码只进共享模块，靠 profile 切换行为
2. `desktop/` 只放云端不加载的东西
3. 不开长期桌面分支；发布冻结用 `release/desktop-x.y` 短期分支

## 5. 磁盘布局

```
%LOCALAPPDATA%\Moon\
├── app\                Electron + 前端资源              ~150MB   跟版本
├── runtime\
│   ├── jre\            jlink 裁剪运行时                 ~80MB    跟版本
│   ├── python\         便携 CPython                     ~35MB    跟版本
│   └── site-packages\  pptx/skia/uharfbuzz/Pillow       ~110MB   跟版本
├── skills\ppt-master\  全局一份，只读                    ~25MB    跟版本
├── data\postgres\      本地库 + 二进制                   ~120MB   永久
├── media\{projectId}\  生成的图/视频                     配额提示  永久（项目资产）
├── cache\              缩略图/预览/远端视频临时          上限 2GB  可随时清
├── sandboxes\{cid}\    work/output/vendor/tmp           临时      归档 1 天后删
├── backup\             每日 pg_dump                     保留 7 份
├── config\             偏好、端口与令牌文件              永久
├── logs\               滚动 7 天
└── run\                kernel.port / kernel.token（ACL 限当前用户）
```

安装包压缩后约 220MB，安装后约 480MB。用户无需安装 Java 或 Python。

## 6. 数据模型

全部在本地 Postgres（zonky 分发的真实 PG 二进制），表名尽量沿用云端以减少 mapper 改动。

| 组 | 表 |
|---|---|
| 项目 | `projects` `scenes` `generation_records` `media_assets` |
| 画布 | `canvas_documents` `canvas_nodes` `canvas_edges` `canvas_runs` `canvas_operations` |
| 智能体 | `agent_conversations` `agent_messages` `agent_turns` `agent_turn_events` `agent_checkpoints` `agent_memories` `agent_global_memories` |
| 设置与本地 | `user_settings` `workspace_grants` `file_registry` `delivery_records` `outbox` |

### 6.1 为“以后做协作”预留的三条纪律

现在做几乎零成本，以后补要全表迁移：

1. 主键一律 **UUID**，不用自增
2. 业务表带 `updated_at timestamptz` + `deleted_at timestamptz`（软删）
3. 删除/改名写 `outbox`，现在不发，以后补发

### 6.2 Schema 管理

- 从云端 `V1..V55` 生成 `schema-local.sql` 基线
- 之后用 Flyway 管增量，用户侧永远不执行 SQL

## 7. 文件边界：沙箱与工作区

- 沙箱：`sandboxes\{conversationId}\{work,output,vendor,tmp}`，Agent 唯一可写处
- 工作区：用户选择的目录，对内核**只读挂载**（可浏览、可导入，不可写）
- 交付：turn 结束后比对 `output\` 前后差异得到交付清单，由 Electron 主进程复制进工作区

```
用户点"打开" → 主进程 copy(output\X.pptx → 工作区\X.pptx)
  ├── 目标已存在 → 默认另存为 "X (2).pptx"，同时提示"覆盖原文件"
  └── 完成 → 卡片状态变"已交付"
```

收益：从根上消除误覆盖与 Office 文件锁问题；归档删沙箱时工作区文件不受影响。

### 7.1 交付清单的判定

不用目录约定，用 **turn 前后快照差异**：

1. turn 开始时记录 `output\` 下 `(path, mtime, size)` 集合
2. turn 结束时重新扫描，新增或变更的文件 = 本轮交付物
3. 交付物带 `sha256`，供“存入项目库”幂等去重

## 8. 安全与隔离

| 项 | 做法 |
|---|---|
| 进程隔离 | MXC process container（AppContainer），策略在容器外 |
| 可写白名单 | `sandboxes\` + `data\` + `logs\` + `run\` |
| 只读白名单 | 工作区、`runtime\`、`skills\`、`media\`、`app\` |
| 网络 | 仅 loopback（UI 端口、PG 端口）+ 网关域名 |
| 本机鉴权 | 内核只绑 127.0.0.1，随机端口 + 随机令牌写 `run\`（ACL 限当前用户） |
| 模型可见路径 | 只给工作区**相对路径**，绝对路径留在本地映射表，不上送网关 |
| 登录凭据 | refresh token 用 DPAPI（Electron `safeStorage`）加密落盘；access token 只放内存 |
| 离线 | 首次必须联网登录；之后断网不校验登录，仅本地功能可用 |

MXC 仅 Win11 可用；Win10 不在 v1 支持范围。

## 9. 同源托管

```
http://127.0.0.1:<port>/              客户端 SPA
http://127.0.0.1:<port>/canvas/       画布 SPA（iframe 同源）
http://127.0.0.1:<port>/api/**        内核 API（含 SSE）
http://127.0.0.1:<port>/api/files/**  本地媒体
http://127.0.0.1:<port>/api/library/** 云端素材代理（带本地缓存）
```

由 **MoonKernel 统一托管静态资源**（`moon.web.client-dir` / `moon.web.canvas-dir` 指向
Electron resources 中的构建产物），不额外起 Node 静态服务器，避免 SSE 经过代理。

要点：

- 画布 iframe 不再用 URL 传 token，改为 `moon:init` / `moon:ready` 握手
- `/canvas/*` 单独加 COOP/COEP（`@ffmpeg/ffmpeg` 需要 SharedArrayBuffer），不影响客户端
- 画布 IndexedDB 的 key 加前缀，避免与客户端同源撞库
- 媒体地址全部相对路径，`OutboundUrlGuard` 的白名单问题自然消失

## 10. 关键流程

### 10.1 首启

安装 → 启动（闪屏）→ 联网登录 → 选工作区（生成只读 grant）→ 初始化 Postgres（initdb 3–8s）
→ 校验 runtime / skills 版本 → 就绪。

### 10.2 一次 PPT 任务

选文件 → 复制进 `work\` → 沙箱生成 → 比对 `output\` 得交付清单 → 卡片显示
→ 用户点开 → 交付到工作区 → 可选“存入项目库”（UI 直传，sha256 幂等）。

### 10.3 一次画布编辑

打开 `/canvas/` → 读本地 `canvas_*` → 拖节点 → 写本地库 → revision 自增
→ 前端轮询本机（代码不改）。断网照常。

### 10.4 归档清理（1 天）

打 `archived_at` → 启动时若距上次清理 >24h 执行 → 删事件/checkpoint/turns/会话行 +
`sandboxes\{cid}\` → `VACUUM (ANALYZE)`。

**不删**：工作区文件、`media\`、已入库素材、`skills\`、`logs\`。

### 10.5 备份

- 每日 `pg_dump` 到 `backup\`，保留 7 份
- 另有“导出项目包”：`manifest.json` + 表行 + 引用素材，用于换机迁移

## 11. 离线能力矩阵

| 功能 | 离线 |
|---|---|
| 打开项目、编辑分镜、拖画布、执行本地图 | ✅ |
| 查看历史生成记录与图片 | ✅ |
| 与智能体对话、生成图/视频 | ❌ |
| 上传项目库 | ❌（进 outbox，联网补发） |

## 12. 里程碑

| 阶段 | 周期 | 交付 | 验收 |
|---|---|---|---|
| P0 骨架 | 4 周 | `desktop/` 目录、jlink 运行时、Electron 拉起 MoonKernel、zonky、同源托管、Flyway 基线 | 干净 Win11 双击安装、登录、一轮对话跑通 |
| P1 本地数据 | 3 周 | 项目/分镜/生成记录表 + 客户端改读本地 + 每日备份 | 断网建项目、编辑分镜、重启数据还在 |
| P2 画布 | 4 周 | CanvasServer sidecar + 画布前端打包 + `/canvas/` 同源 + 本地媒体 | 断网拖画布、保存、重开不变形 |
| P3 沙箱与交付 | 4 周 | `LocalWorkspaceClient`、MXC 策略、技能全局只读、便携 Python、交付清单、容器外交付 | 生成 pptx → 交付 → Office 打开正常；越权写 Documents 被拒 |
| P4 入云 | 3 周 | 项目库上传、outbox 重试、素材代理缓存、项目包导出/导入 | 断网上传排队、联网补发；导出包可在另一台机器导入 |
| P5 硬化 | 3 周 | 崩溃恢复、自动更新、签名、内存/启动优化 | 强杀重启可继续；安装包 <250MB |

两条主线（内核/沙箱、画布/数据）可并行，2 人约 21 周。

P0 阶段**不启用 MXC**（先跑通链路），P3 再接入。

## 13. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 双 sidecar 内存（约 600MB + Electron） | 8GB 机器吃力 | 声明 16GB 推荐；P5 评估合并单进程 |
| 画布前端打包后的 origin/API 假设 | P2 卡住 | P0 先跑一次 `/canvas/` 同源冒烟 |
| 便携 Python 的 skia/harfbuzz | 装不上/体积失控 | P0 做 spike，失败回退本地 Docker |
| 技能包假设 POSIX shell（bash/soffice） | P3 功能不全 | P3 逐条核对技能脚本，必要时提供 Git Bash 兜底 |
| 本地数据单点丢失 | 用户丢项目 | P1 起每日备份 + P4 项目包导出 |
| 云端与 local profile 漂移 | 红线回归 | CI 双模式跑事件序号/HITL/时间字段测试 |
| MXC 仅 Win11 | 装机面缩小 | v1 明确要求 Win11 |

## 附录 A：P0 任务清单

### A.1 仓库与构建

| # | 任务 | 产出 |
|---|---|---|
| 1 | 新建 `desktop/` 骨架 | `desktop/shell`、`packaging`、`runtime`、`kernel-boot` 目录与 README |
| 2 | Electron + Vite 构建链 | `desktop/shell/electron.vite.config.ts`，产物进 `resources\web\client` |
| 3 | 画布前端构建接入 | `XiaoYueCanvasAgent` 产物进 `resources\web\canvas` |
| 4 | jlink 运行时脚本 | `desktop/packaging/jlink.ps1`，产出 `runtime\jre` |
| 5 | 安装器 | electron-builder 配置，首次启动写 `config\device.json` |
| 6 | CI 双模式 | master 跑云端测试 + local profile 冒烟 |

### A.2 本地内核（local profile）

| # | 任务 | 产出 |
|---|---|---|
| 7 | `moon.mode=local` 开关 | `LocalModeCondition`，与现有 `@ConditionalOnProperty` 风格一致 |
| 8 | 本地 Postgres 引导 | `LocalPostgresBootstrap`：启动/关闭 zonky、数据目录、端口、单实例锁 |
| 9 | Flyway 基线 | `db/local/V1__baseline.sql`（见附录 D），`V2__seed.sql` 可选 |
| 10 | Redis 替换 | 2 处 `RedisTemplate` 换 Caffeine + 本地锁 |
| 11 | Nacos 关闭 | local profile 强制 classpath 技能/提示词 |
| 12 | 静态资源托管 | `LocalWebConfig`：`/` → client-dir，`/canvas/**` → canvas-dir（带 COOP/COEP） |
| 13 | 端口与令牌 | 启动时选随机端口，写 `run\kernel.port`、`run\kernel.token` |
| 14 | 本机鉴权过滤器 | 校验 `X-Moon-Token`，仅放行 127.0.0.1 |

### A.3 沙箱抽象（为 P3 铺路）

| # | 任务 | 产出 |
|---|---|---|
| 15 | 抽出 `WorkspaceClient` 接口 | 从 `MoonSandboxClient` 提取 11 个方法（附录 C） |
| 16 | `MoonSandbox` 依赖接口而非实现 | 字段类型改为 `WorkspaceClient` |
| 17 | `normalizeCommand` 下沉 | 移入 HTTP 客户端，本地模式跳过（见 C.3） |

### A.4 客户端适配

| # | 任务 | 产出 |
|---|---|---|
| 18 | 运行时 API 基址 | `config.ts` 增加 `window.__MOON_BOOT__` 注入 |
| 19 | 双 axios 实例 | `cloudClient`（/api）与内核同源客户端合并说明 |
| 20 | 画布握手 | `ChatAppsHost` 增加 `moon:init`/`moon:ready`，去掉 URL token |

## 附录 B：MXC 策略 JSON 草案

> 字段名以 `microsoft/mxc` 仓库 `schemas/` 为准，此处为语义草案。

```json
{
  "containment": { "backend": "process" },
  "process": {
    "command": "%LOCALAPPDATA%\\Moon\\runtime\\jre\\bin\\java.exe",
    "args": ["-Xmx2g", "-jar", "%LOCALAPPDATA%\\Moon\\app\\MoonKernel.jar",
             "--spring.profiles.active=local"],
    "workingDirectory": "%LOCALAPPDATA%\\Moon\\app",
    "environment": { "MOON_HOME": "%LOCALAPPDATA%\\Moon" }
  },
  "filesystem": {
    "readWrite": [
      "%LOCALAPPDATA%\\Moon\\sandboxes",
      "%LOCALAPPDATA%\\Moon\\data",
      "%LOCALAPPDATA%\\Moon\\logs",
      "%LOCALAPPDATA%\\Moon\\run"
    ],
    "readOnly": [
      "%LOCALAPPDATA%\\Moon\\runtime",
      "%LOCALAPPDATA%\\Moon\\skills",
      "%LOCALAPPDATA%\\Moon\\media",
      "%LOCALAPPDATA%\\Moon\\app",
      "${WORKSPACE_ROOT}"
    ],
    "deny": ["%USERPROFILE%\\Documents", "%USERPROFILE%\\Downloads"]
  },
  "network": {
    "allowLoopback": true,
    "allowOutbound": ["${GATEWAY_HOST}"]
  },
  "ui": { "allowDesktop": false }
}
```

注意两点：

1. `deny` 与用户选择的工作区可能冲突（用户可能把工作区选在 Desktop/Documents 下）。
   生成策略时必须**动态移除**包含 `WORKSPACE_ROOT` 的 deny 项，只保留真正的越权拦截。
2. 先用 MXC 的 Learning 模式跑一周收集 Activity Report，再收敛成最小策略。

## 附录 C：`WorkspaceClient` 与本地实现

### C.1 接口

```java
public interface WorkspaceClient {
    boolean enabled();

    SandboxWriteResult write(String conversationId, String path, String content, String mode);
    SandboxReadResult read(String conversationId, String path, int offset, int maxChars);
    List<SandboxFileEntry> list(String conversationId, String path);
    void upload(String conversationId, String path, byte[] content);
    byte[] download(String conversationId, String path);

    SandboxExecutionResult execute(String conversationId, String script,
                                   List<String> args, int timeoutSeconds);
    SandboxShellResult shell(String conversationId, String command, int timeoutSeconds);

    SandboxSkillResult materializeSkill(String conversationId, String skillName);

    byte[] exportArchive(String conversationId);
    void importArchive(String conversationId, byte[] archive);
    void cleanup(String conversationId);
}
```

- `MoonSandboxClient`（HTTP）实现它，行为不变
- 新增 `LocalWorkspaceClient`：直接用 `java.nio.file` 操作
  `%LOCALAPPDATA%\Moon\sandboxes\{cid}\`
- `MoonSandbox extends AbstractBaseSandbox` 保持不动，只把依赖类型换成接口

### C.2 `LocalWorkspaceClient` 实现要点

| 能力 | 本地实现 |
|---|---|
| write | 临时文件 + `ATOMIC_MOVE`；同会话文件锁 |
| read | `offset`/`maxChars` 语义与现有 HTTP 版一致 |
| list | 过滤 `~$*`（Office 锁文件）、`desktop.ini`、`.moon-*` |
| execute | 启动 `runtime\python\python.exe`，`PYTHONNOUSERSITE=1`，`PYTHONPATH=site-packages` |
| shell | `cmd /c`（默认）；白名单外的可执行文件走 HITL |
| materializeSkill | 不再复制，返回全局 `skills\{name}` 的只读路径 |
| export/importArchive | `zip` 打包/解包 `sandboxes\{cid}\` |
| cleanup | 递归删除会话目录 |

### C.3 必须处理的三个已有假设

1. **`normalizeCommand` 会帮倒忙**

   [MoonSandbox.java](../MoonAgent/src/main/java/com/moon/moonagent/ai/agent/agentscope/sandbox/MoonSandbox.java)
   里的 `normalizeCommand` 是为了把宿主 Windows 生成的 `cd /d "dir" && cmd` 改写成 POSIX
   `cd 'dir' && cmd`，因为 runner 是 Linux。本地模式下宿主就是 Windows，再改写会得到
   `cd 'D:\...'` 这种跑不通的命令。**本地模式必须跳过该改写。**

2. **路径分隔符**

   `relativePath()` 只按 `/` 比较 `root + "/"`。Windows 工作区根是 `D:\...`，
   模型也可能返回 `\`，两侧都要先归一化再比较。

3. **`supportsFileTransfer` 的根判断**

   同样只处理 `/` 前缀，需同步调整。

## 附录 D：Flyway 基线表清单

`V1__baseline.sql` 需覆盖（列定义从云端 DDL 生成，保证与 mapper 一致）：

**项目域**：`projects`、`scenes`、`scene_assets`（如保留）、`generation_records`、`media_assets`

**画布域**：`canvas_documents`、`canvas_nodes`、`canvas_edges`、`canvas_runs`、`canvas_operations`、`canvas_assets`（兼容期保留）

**智能体域**：`agent_conversations`、`agent_messages`、`agent_turns`、`agent_turn_events`、
`agent_checkpoints`、`agent_assets`、`agent_memories`、`agent_global_memories`、
`agent_memory_settings`、`agentscope_sessions`

**本地域**：`user_settings`、`workspace_grants`、`file_registry`、`delivery_records`、`outbox`

**约束**：

- 所有主键 UUID，带 `created_at` / `updated_at` / `deleted_at`
- `agent_turn_events` 保留 `(turn_id, seq)` 唯一约束（红线要求）
- 时间列统一 `timestamptz`，实体侧用 `OffsetDateTime`

## 待定

1. 老用户数据迁移：是否提供“从云端拉取项目 → 写本地库”，建议 P4 做
2. Windows 10 是否支持：不承诺则 MXC 全量可用
