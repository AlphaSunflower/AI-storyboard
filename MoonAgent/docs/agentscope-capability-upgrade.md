# Moon 智能体能力补齐：DSH 对照 + AgentScope 2.0.4 可用面

> 目的：把「DeepSeek Harness（dsh）有哪些横向能力值得借鉴」翻译成 MoonAgent 的可执行清单。
> 结论先说：**DSH 值得借鉴的横向能力，绝大多数 AgentScope 2.0.4 已经内建**——
> 其中一部分是 MoonAgent 自己关掉的开关，另一部分是从未接线的 API。
> 真正必须自研或买外部工具的只有三类：插件/HMR 组合系统、加固沙箱运行时、对外 SDK。
>
> 证据基线：`io.agentscope:agentscope-core:2.0.4` / `agentscope-harness:2.0.4`
> （本地仓库 `E:\Development\apache-maven-3.9.15\mvn_repo\io\agentscope`），
> 类与签名由 `javap` 直接读出；MoonAgent 侧由 `MoonAgentRuntime.java`、
> `application.yml`、`AgentScopeToolkitFactory.java` 等确认。**未跑测试，未做运行时验证。**

---

## 1. 一张总表

| DSH 的横向能力 | AgentScope 2.0.4 对应物（类） | Moon 现状 | 动作 |
|---|---|---|---|
| 工具注册表 / 插件化工具 | `Toolkit.registerTool(Object)` + `@Tool` / `@ToolParam` 注解 + `ToolGroup`/`ToolGroupManager` | 在用 `ToolGroup`（按 skill 激活），但注册是**手写枚举** | 改注解 + 反射注册 |
| 声明式工具开关与 MCP 声明 | `ToolsConfig{strictAllow,defaultToolsEnabled,allow,deny,mcpServers}` + `ToolsConfigLoader` + `McpClientManager` | **主动关闭**（`disableToolsConfig()`） | 打开，把 allow/deny/MCP 落到配置 |
| 子智能体（可动态生成） | `SubagentDeclaration` + `AgentSpecLoader`（文件驱动）+ `SubagentSpecGenerator` + `DynamicSubagentsMiddleware`/`AgentSpawnTool` | 5 个**硬编码**只读子智能体；`disableDynamicSubagents()` | 声明文件化 + 评估开启动态 |
| 多智能体编排（任务板/认领/广播） | `TeamClient`（createTask/claim/assign/complete/broadcast/spawnMember/approvePlan）+ `teamsMode(TeamClient, TeamContext)` + `TeamTool` + `TeamWakeups` | **完全未用** | 中优先，替代自研编排 |
| Skill 生态（自创/策展/晋升门禁） | `SkillCurator`(+`SkillCuratorConfig`)、`SkillPromoter`+`SkillPromotionGate`（`LocalApprovalGate`/`NotifyAndWaitGate`/`RejectAllGate`）、`SkillSecurityScanner`、`SkillUsageStore`、`ProposeSkillTool`、`SkillManageTool`、`MarketplaceStager`、`WorkspaceSkillRepository` | 6 个固定 skill + Nacos 仓库，无自创/策展 | 打开策展，替代"扩 skill 要改代码" |
| 会话转录 / 长期记忆 / 会话搜索 | `TranscriptStore`(`FilesystemTranscriptStore`/`ObjectStoreTranscriptStore`)、`MemoryConfig`、`MemoryConsolidator`、`SessionTree`、`SessionSearchTool`、`MemoryGet/Save/SearchTool` | **主动关闭**（`disableTranscript()`/`disableMemoryTools()`/`disableMemoryHooks()`） | 打开 Transcript（可落 MinIO）；记忆需评估 |
| RAG / 知识注入 | `GenericRAGHook` + `Knowledge` + `KnowledgeRetrievalTools` + `RetrieveConfig` | 未用（DSH 也没有） | 可选增益，用于参考资产检索 |
| 沙箱生命周期 / 租约 / 快照 | `SandboxManager.acquire/release/persistState`、`SandboxLease`、`SandboxExecutionGuard.tryEnter`、`SessionSandboxStateStore`、`SandboxSnapshotSpec`(`Local`/`Remote`/`Noop`)、`SandboxLifecycleMiddleware`、`RoutedSandboxFilesystem`、`WorkspaceSpec`(+`BindMountEntry`/`GitRepoEntry`) | 自研 `MoonSandbox extends AbstractBaseSandbox` + client adapter，用了 `IsolationScope.SESSION`，但**未用 Manager/Lease/快照** | 接线，支撑"每会话隔离/恢复" |
| 加固沙箱运行时（真正的隔离） | **没有实现**（只有抽象） | 单容器 + 共享密钥 | 必须外部工具（见 §4） |
| 优雅停机 / 协作取消 | `GracefulShutdownManager`、`GracefulShutdownConfig(Duration, PartialReasoningPolicy)`、`ShutdownStateSaver`、`InterruptControl`/`RequestStopEvent`、builder `stopOnReject(boolean)` | 未用（`turn_failed` 40904 自研） | 高优先，护住事件序号红线 |
| 状态并发冲突 | `ConflictPolicy{OVERWRITE,FAIL,APPEND_MERGE}`、`VersionedState`、`ConcurrentSessionModificationException`、`ListHashUtil` | 未用 | 多实例安全的前提 |
| 分布式周期任务 | `StoreBackedPeriodicGate` / `LocalPeriodicGate` | 未用（自研 `@Scheduled` 恢复/清理） | 多实例安全 |
| 追踪 | `OtelTracingMiddleware`（已用）、`AgentTraceMiddleware`、`enableAgentTracingLog(true)`（JSONL trace）、`TracerRegistry` | OTel 已开，JSONL trace 未开 | 低成本可开 |
| 模型层 | `ModelRegistry`、`ModelProviderSupport`、`JdkHttpTransport`/`OkHttpTransport`/WebSocket、`ProxyConfig`、`ModelContextWindows`、`CachePolicy`、`FailoverListener`、`fallbackModel`、`ExecutionConfig`（模型级/工具级）、`ToolChoice`、`StructuredOutputReminder` | 用了 openai 扩展 + fallback | 按需 |
| 内置工具（可直接用） | `TodoTools`、`ShellCommandTool`+`CommandValidator`(Unix/Windows)、`ReadFileTool`/`WriteFileTool`、`MetaToolFactory`、`WaitAsyncResultsTool` | 部分自研 | 按需替换 |
| 插件/HMR 组合系统（Cordis 等价物） | **没有** | — | 见 §3，用 MCP + 配置约定代替 |
| 对外嵌入 SDK / ACP | 只有 `subagent.task.RemoteSubagentTransport`/`AgentProtocolTaskClient`（远程 agent 协议），**无公开 SDK** | 自研 REST + SSE | 自研或后续 |

---

## 2. 立刻可动的开关（Moon 主动关掉的）

证据：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/agentscope/MoonAgentRuntime.java:246-266`

| 开关 | 关闭的能力 | 打开的前提 |
|---|---|---|
| `.disableToolsConfig()` | 配置驱动工具 allow/deny + MCP 声明 | 需要先把 `ToolsConfig` 的 allow 列表与现有 13 个工具组对齐，否则会出现"工具突然全消失" |
| `.disableDynamicSubagents()` | 模型按需创建子智能体 | 必须同时给 `maxIters`/工具面/权限收紧，否则等于放开代码执行面 |
| `.disableTranscript()` | 会话转录（可 `ObjectStoreTranscriptStore` 落 MinIO） | 需评估存储量与脱敏；与自研 `agent_turn_events` 有职责重叠 |
| `.disableWorkspaceContext()` / `.disableAtPathExpansion()` | 工作区上下文、`@path` 展开 | 与沙箱路径模型耦合，改动要过红线测试 |
| `.disableMemoryTools()` / `.disableMemoryHooks()` | 记忆读写、自动 consolidate | 跨会话记忆会改变产品语义，需产品决策 |
| `.disableDefaultWorkspaceSkills()` + `SkillFilter.only(6 个)` | 动态/工作区 skill | 与 Nacos 资产发布流程冲突，建议先走 SkillCurator 而非直接放开 |
| `.disableWebTools()` | 框架自带 web 工具 | Moon 自研 `web_search`/`web_fetch`（DeepSeek 原生搜索 + 服务端抓取），保留现状 |

> 注意：这些开关当初大概率是为了避免与自研事件流/技能集打架。**逐个开、逐个测**，
> 不要一次全开；每个开关都要覆盖 `CLAUDE.md` 红线里的 seq 连续性与 HITL 终态用例。

---

## 3. AgentScope 没有、必须自研或外部解决的

1. **插件/HMR 组合系统（Cordis 等价）**：AgentScope 无 profile/bundle/patch 分层，无热重载。
   现实替代：`ToolsConfig`（工具面）+ MCP（能力面）+ Spring Bean/`@Tool`（代码面）+
   Skill 仓库（提示词面）。**不要试图在 AgentScope 上造 Cordis。**
2. **加固沙箱运行时**：框架只给抽象（`SandboxClient`/`SandboxManager`/`IsolationScope`），
   不给隔离内核。现在是单容器 + 共享密钥（空值即绕过），`ffmpeg` 还跑在宿主机。
   必须靠 Docker 之外的运行时与内核特性（见 §4）。
3. **工作流脚本引擎（DSH `workflow`）**：AgentScope 只有团队任务板 + 异步任务注册表，
   没有"脚本编排 + phase + pipeline"那一层。若确需，自研或直接用团队任务板近似。
4. **对外嵌入协议/SDK**：无 JSON-RPC/ACP 对等物；现有 REST+SSE 继续自持。
5. **质量门禁**：覆盖率、变异、属性化测试全是外部工具（见 §4）。

---

## 4. 外部工具清单

版本核实方式：直接抓 `repo1.maven.org/maven2/.../maven-metadata.xml`（当前索引，2026 日期）
或官方文档；**不使用 `search.maven.org` 的 JSON API**（索引停在 2025 年中，会给出过时的"最新版本"）。

### 4.0 决定性约束：真隔离必须 Linux 宿主

- gVisor 安装文档要求 **Linux 5.6+**（x86_64/ARM64）；Firecracker 依赖 **KVM**、只支持 Linux 宿主/guest；
  Kata 走硬件虚拟化（hypervisor）。Docker Desktop on Windows 把 Linux 容器跑在托管 WSL2 VM 里，
  在其中注册自定义 OCI runtime **不是受支持且可持久的路径**（此项未核实）。
- 结论：**"Windows 开发机上做真隔离"不成立**。唯一利好细节：gVisor 默认的 `systrap` 平台
  **不需要 `/dev/kvm`**（只有可选的 `kvm` 平台需要），所以没有嵌套虚拟化的 Linux VM 也能跑 gVisor。

### 4.1 沙箱隔离（当前：单容器 + 共享密钥，compose 无 seccomp/AppArmor profile）

| 选项 | 买到什么 | 成本 | 许可 |
|---|---|---|---|
| **[gVisor `runsc`](https://gvisor.dev/docs/user_guide/quick_start/docker/)** 经 daemon.json 注册 runtime | 用户态内核，抹掉宿主内核 syscall 面；**首选默认** | 低：装 + 重启 daemon + `--runtime=runsc`；仅 Linux | Apache-2.0（未核实） |
| **[K8s Agent Sandbox](https://agent-sandbox.sigs.k8s.io/)**（kubernetes-sigs） | CRD + 热池 + SDK，**gVisor/Kata 可插拔后端**，每会话隔离是一等 API | 高：需要 Kubernetes | **Apache-2.0 ✔**（GitHub API 核实，4.2k star，Go） |
| **[microsandbox](https://microsandbox.dev/)** | 每沙箱独立内核的 microVM；出口白名单/可关；宿主侧凭据占位替换 | 中；项目年轻 | Apache-2.0 ✔ |
| **自定义 seccomp + [moby/profiles](https://raw.githubusercontent.com/moby/profiles/main/README.md)** | 拦住 gVisor 会拦的 syscall 类别，最便宜的加固 | 极低 | Apache-2.0 ✔ |
| **[Firecracker](https://firecracker-microvm.github.io/)** / firecracker-containerd | 硬件级隔离，<125ms 启动，`jailer` | 高（自建基建） | Apache-2.0 ✔ |
| **bubblewrap / nsjail / Landlock** | OS 级文件+网络沙箱，**正是 Anthropic Claude Code 的做法**（[已开源说明](https://www.anthropic.com/engineering/claude-code-sandboxing)） | 中；**Landlock 官方只有 Rust/Go 绑定，Java 要手写 JNI/JNA**（[landlock.io](https://landlock.io/)） | MIT/Apache-2.0 |
| **每会话编排**：`com.github.docker-java:docker-java:3.7.1` 或 Testcontainers 当运行时 | 把共享容器改成一会话一容器 | 中高（要改 runner 设计） | Apache-2.0 |

**最低可行**：Windows 上先做"seccomp profile + `ffmpeg` 迁入沙箱 + 每会话 namespace"；
生产环境用 Linux 上一会话一个 `runsc` 容器（`systrap`，无需 KVM）。**不要在 Docker Desktop 上试 gVisor/Kata。**

### 4.2 出口控制（真正拦得住的那种）

Anthropic 的表述是最好的共识：**"有效的沙箱需要文件系统与网络双重隔离"**，实现方式是
**"连到沙箱外代理服务器的 unix domain socket"**——因为沙箱内的代理环境变量只是建议性的。
现在的 Squid 白名单形状是对的，但进程可以用裸 socket、IP 字面量、DoH/DNS 隧道绕过环境变量代理。

| 选项 | 买到什么 | 成本 | 许可 |
|---|---|---|---|
| **无默认路由 + 显式白名单 netns** | 绕过变成不可能；单一最强控制 | Linux 低成本，Windows 无 | n/a |
| **Cilium NetworkPolicy egress（DNS 感知）** | 声明式 L3/L7 出口白名单 | 高（K8s + CNI） | Apache-2.0 |
| **mitmproxy transparent 模式** | 抓不配合的进程；带 DNS server 模式。**仅 Linux/macOS**；容器抓包"必须用 host 网络"；**WSL 不支持（eBPF 关闭）** | 中 | MIT |
| **Squid 白名单（现状）** | 只有配上"无默认路由"才成立 | 已付 | GPL-2.0 |
| **iptables/nftables 出口白名单** | 内核级、按容器生效 | Linux 低成本 | GPL-2.0 |

**最低可行**：保留 Squid，但补一层无默认路由 netns 让代理成为**唯一通路**，并在 DNS 层也过滤。

### 4.3 工具发现 / 插件化

| 选项 | 买到什么 | 成本 | 许可 |
|---|---|---|---|
| **Spring Bean 发现 + 自定义 `@AgentTool`**（或直接用 AgentScope `@Tool`） | 约 50 行删掉硬编码枚举，保留 Spring DI | 极低 | Apache-2.0 |
| **MCP**：[`spring-ai-starter-mcp-server:2.0.1`](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-mcp-server/maven-metadata.xml) | 真正的扩展缝：独立部署、跨语言；与现有 Spring AI BOM 对齐（2.0.1 稳定，2.1.0-M1 里程碑） | 中 | Apache-2.0 |
| `ServiceLoader` / SPI | 零依赖发现，但无 DI / 无热重载 | 低 | JDK |
| [`PF4J 3.16.0`](https://repo1.maven.org/maven2/org/pf4j/pf4j/maven-metadata.xml) | 真正的插件生命周期、外部 JAR、版本 | 中 | Apache-2.0 |
| [`Spring Modulith 2.1.1`](https://docs.spring.io/spring-modulith/reference/verification.html) | `ApplicationModules.verify()` 强制无环 + 仅 API 包可访问 | 低 | Apache-2.0 |
| OSGi | 过度设计 | 极高 | Apache-2.0 |

**最低可行**：先用注解 + Spring `List<AgentTool>` 收集，MCP 作为对外扩展缝。

### 4.4 测试与质量门禁（现状：无任何插件，Mockito 单测为主，CI 缺 MoonSandbox）

| 选项 | 买到什么 | 成本 | 许可 |
|---|---|---|---|
| **[JaCoCo 0.8.15](https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html)** `check` goal | 真正的覆盖率门禁，绑到 `verify`，`haltOnFailure=true` | 极低 | EPL-2.0 |
| **[ArchUnit 1.5.1](https://repo1.maven.org/maven2/com/tngtech/archunit/archunit-junit5/maven-metadata.xml)** | 把 Controller→Service→Mapper 分层写成测试 | 低 | Apache-2.0 |
| **[Testcontainers 2.0.5](https://repo1.maven.org/maven2/org/testcontainers/testcontainers/maven-metadata.xml)** | 真 Postgres/Redis/MinIO 集成测试 | 中 | MIT |
| **[jqwik 1.10.2](https://repo1.maven.org/maven2/net/jqwik/jqwik/maven-metadata.xml)** | 对 SSE `seq` 连续性做属性测试——正是红线 #1 | 低 | EPL-2.0 |
| **[PIT 1.30.1](https://repo1.maven.org/maven2/org/pitest/pitest-maven/maven-metadata.xml)** | 暴露"什么都没断言的 Mockito 测试" | 中（仅定时跑） | Apache-2.0 |
| **[SpotBugs 4.10.5.0](https://repo1.maven.org/maven2/com/github/spotbugs/spotbugs-maven-plugin/maven-metadata.xml)** + [findsecbugs 1.14.0](https://repo1.maven.org/maven2/com/h3xstream/findsecbugs/findsecbugs-plugin/maven-metadata.xml) | 安全相关静态分析 | 低 | LGPL-2.1 / Apache-2.0 |
| **[OWASP Dependency-Check 13.0.0](https://repo1.maven.org/maven2/org/owasp/dependency-check-maven/maven-metadata.xml)** / Trivy | 依赖与镜像 CVE（DC 现在需要 NVD API key） | 低 | Apache-2.0 |
| Pact / Spring Cloud Contract / REST Assured | 钉住 MoonAgent→MoonSandbox 的 HTTP 契约 | 中 | Apache-2.0 |
| Gatling | SSE 流压测（**k6 是 AGPL-3.0 ✔**，商业闭源场景要注意） | 中 | Apache-2.0 |

JaCoCo 门禁参数（已核实参数名）：

```xml
<goal>check</goal>
<rules><rule><element>BUNDLE</element><limits><limit>
  <counter>INSTRUCTION</counter><value>COVEREDRATIO</value><minimum>0.80</minimum>
</limit></limits></rule></rules>
```

**最低可行**：先把 MoonSandbox 加进 CI 矩阵，再加 JaCoCo 门禁 + ArchUnit + jqwik 的 `seq` 属性测试
——这四项直接解决"只有 Mockito"和"CI 漏模块"。

### 4.5 可观测性 / 运维 / 并发

| 选项 | 买到什么 | 成本 | 许可 |
|---|---|---|---|
| **[Micrometer Tracing 1.7.1](https://repo1.maven.org/maven2/io/micrometer/micrometer-tracing-bridge-otel/maven-metadata.xml)**（`micrometer-tracing-bridge-otel`，版本由 Boot BOM 管理）+ **[OTel Java agent 2.32.0](https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/maven-metadata.xml)** | 按 turn 打标的 trace（tag `turnId`/`conversationId`），agent 自动插桩沙箱 HTTP 调用 | 低 | Apache-2.0 |
| **[ShedLock 7.10.1](https://repo1.maven.org/maven2/net/javacrumbs/shedlock/shedlock-spring/maven-metadata.xml)** | 多实例下安全的清理/过期任务（Postgres/Redis 锁） | 极低 | Apache-2.0 |
| **[Resilience4j `resilience4j-spring-boot4:2.4.0`](https://repo1.maven.org/maven2/io/github/resilience4j/resilience4j-spring-boot4/maven-metadata.xml)** | 沙箱调用的熔断/舱壁/超时——**Spring Boot 4 starter 已存在** | 低 | Apache-2.0 |
| **[logstash-logback-encoder 9.0](https://repo1.maven.org/maven2/net/logstash/logback/logstash-logback-encoder/maven-metadata.xml)** | 结构化 JSON 日志，`turnId` 进 MDC | 低 | Apache-2.0 |
| Actuator `HealthIndicator` | 就绪探针真的在沙箱里跑一条脚本 | 低 | Apache-2.0 |
| Toxiproxy / Chaos Mesh | 故障注入；锁正确性测试 | 中 | MIT / Apache-2.0 |

**最低可行**：OTel agent + ShedLock + 深度沙箱健康检查。
关于硬编码的 4/4/2 并发上限：**挪到配置，并把 per-user 并发从单进程计数改成 Redis 计数**。

### 4.6 行业对"最低可行沙箱"的共识

Modal 文档称其 Sandbox 跑在 **gVisor**（"强隔离，适合多数负载"）或**独立内核的 VM runtime**
上——共享内核容器**不达标**。Firecracker 站点列出 E2B、Kata、Fly.io、firecracker-containerd 为用户；
microsandbox 主打"一个负载一个 microVM"对标 Docker 的共享内核。Anthropic 要求文件系统**与**网络双重隔离、
凭据绝不进沙箱、由代理持有真实 git token。**OpenAI code-interpreter 的内部机制无法核实**
（文档 403），Vercel Sandbox / Docker Sandboxes 内部同样未核实，因此不做断言。
收敛结论：每会话隔离 / 自有内核或 gVisor / 只读根 / 非 root / 丢能力 / seccomp / 无默认路由 /
出口白名单 / 资源上限 / 短生命周期 / 凭据留在宿主侧。
**用共享容器跑不可信的多租户代码，低于行业底线。**

> 仍标记"未核实"的项：gVisor 许可页文本、Docker Desktop 自定义 runtime 支持、
> OpenAI/Vercel/Docker Sandboxes 的隔离内部实现、Java Landlock 绑定是否完全不存在
> （仅确认 landlock.io 只列了 Rust/Go 绑定）。

---

## 5. 建议落地顺序

> 排序原则：**先做 Windows 上就能做、且能立刻降低最高危风险的**；
> 需要 Linux 宿主的隔离升级单独作为一条线，不要和功能迭代混在一起。

**第 1 步 —— 沙箱卫生（Windows 兼容，今天就能做）**
- 加自定义 seccomp profile；**修掉共享密钥为空即绕过的默认值**（缺失就拒绝启动）；
- **把 `ffmpeg` 合并迁进沙箱**（现在跑在 MoonAgent 宿主上，绕过整个隔离模型）；
- 补"无默认路由"的 netns，让 Squid 成为唯一通路，并在 DNS 层过滤。
- 验收：越权/逃逸用例、出口白名单用例（含"忽略 `HTTP_PROXY` 直连"的负例）、产物发布幂等用例。

**第 2 步 —— 真隔离（需要 Linux 宿主，独立立项）**
- 生产：一会话一个 `runsc` 容器（gVisor `systrap`，无需 KVM）；
  或迁到 Kubernetes 后用 **Agent Sandbox**（gVisor/Kata 可插拔 + 热池）。
- 同时接 AgentScope `SandboxManager`/`SandboxLease`/`SandboxSnapshotSpec` 让隔离与恢复走框架。
- **不要**在 Docker Desktop on Windows 上尝试 gVisor/Kata。

**第 3 步 —— 修 CI + 加门禁（直接治"只有 Mockito"和"CI 漏模块"）**
- CI 矩阵补 MoonSandbox；加 JaCoCo `check` 阈值；
- ArchUnit 把分层规范变成可执行测试；jqwik 对 SSE `seq` 连续性写属性测试。
- 验收：覆盖率门禁在 CI 生效；`seq` 乱序/断链用例可复现失败。

**第 4 步 —— 集成真实性**
- Testcontainers（Postgres/Redis/MinIO）+ Pact 或 Spring Cloud Contract
  覆盖 MoonAgent→MoonSandbox（含 `X-Internal-Secret` 头）。

**第 5 步 —— 运维与并发**
- OTel Java agent + Micrometer Tracing、ShedLock（清理/过期任务）、
  Resilience4j 包沙箱 HTTP 客户端、深度沙箱 `HealthIndicator`；
  并发上限挪进配置，per-user 计数改走 Redis。

**并行可做（不依赖沙箱那条线）——护红线与收敛工具面**
- 接 `GracefulShutdownManager` + `InterruptControl` + `stopOnReject(true)`；
  验收：停机/中断下 `agent_turn_events` 不断号，`reject` 仍为终态。
- 工具改 `@Tool`/`@ToolParam` + `registerTool(Object)`，权限从注解推导；
  打开 `ToolsConfig`。验收：工具清单快照测试 + HITL ASK 判定逐条对齐。
- 打开 `SkillCurator` + `LocalApprovalGate`；子智能体改 `AgentSpecLoader` 文件声明。
  验收：新增 skill/子智能体零 Java 改动，Nacos 发布流程不回归。

---

## 6. 需要先确认的风险

- **基建方向是前置决策**：真隔离要么"Linux 单机 + 一会话一 `runsc`"，要么"Kubernetes + Agent Sandbox"。
  若当前生产仍是 Windows 单机，先定这个，再谈第 2 步；它决定 MoonSandbox 是改造还是迁移。
- AgentScope 2.0.4 的 `harness` API 属于较新面，升级到 2.x 后续版本是否会破坏
  上述签名，**未核实**。
- 打开 memory/transcript/dynamic subagents 对 `AgentTurnEventSequencer`
  契约与 HITL 卡片的实际影响，**只能在真实运行中验证**。
- 本清单基于字节码与配置的静态审计，**未运行任何测试**；工具版本来自
  `repo1.maven.org` 元数据与官方文档，**未做实际构建验证**。
- 环境限制（影响后续调研）：本会话 `web_search` 不可用（端点配置错误返回 404），
  `github.com` 与 `platform.openai.com` 抓取失败；`raw.githubusercontent.com`、
  `api.github.com`、`repo1.maven.org`、官方文档站可用。
