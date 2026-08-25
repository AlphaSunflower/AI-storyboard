# Multi-Agent + MCP Enhancement Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Evolve MoonAgent from single-agent-with-intent-handlers to supervisor+specialist multi-agent architecture, and add MCP server/client for cross-process tool sharing.

**Architecture:** Supervisor (PlanGraph) routes to specialist ChatClients (storyboard/image/video/review). Each specialist owns its tools and autonomous reasoning loop. MCP Server exposes agent tools to external systems. MCP Client consumes external tools (asset search, external services).

**Tech Stack:** Spring AI 2.0.0, Spring AI Alibaba Graph Core 2.0.0-M1.1, Spring Boot 4.0.0, Java 21

---

## Current State Analysis

**What exists (MoonAgent module):**
- `PlanGraph` (StateGraph) — declarative nodes + conditional edges for intent routing
- `IntentHandler` interface — 6 implementations (aisplit, pic, video, scene-review, delete, other)
- `AgentTools` — @Tool methods (writeScenes, replaceScenes, refineImage, generateVideo)
- `AgentOrchestratorSupport` — shared SSE/checkpoint/LLM utilities
- Skill-based prompt management (SKILL.md per intent, `SkillLoader`)
- HITL checkpoints with form tokens
- SSE streaming to frontend

**What's missing:**
1. Handlers are scripted flows (hardcoded LLM calls + tool invocations), not autonomous agents
2. No MCP — tools are Java-only, can't be consumed by external processes
3. No cross-agent communication — handlers can't delegate to each other
4. No agent-level memory — each request is stateless beyond conversation history
5. No tool discovery — all tools statically registered

---

## Phase 1: MCP Server — Expose Agent Tools (Foundation)

**Why first:** Zero-risk addition. Existing tools become callable by any MCP client (Claude, other agents, external systems). No changes to current flow.

### Task 1.1: Add MCP Server dependency to MoonAgent

**Objective:** Add Spring AI MCP Server Boot Starter to pom.xml

**Files:**
- Modify: `MoonAgent/pom.xml`

**Step 1:** Add dependency (verify exact 2.0.0 starter name from official-source-index.md):
```xml
<!-- MCP Server — expose agent tools to external MCP clients -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

**Step 2:** Add MCP server config to application.yml:
```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        name: moon-agent
        version: 1.0.0
        type: SYNC
        # SSE transport (WebMVC) — external clients connect via /mcp/sse
        sse-message-endpoint: /mcp/sse
        # Tool change notification
        tool-change-notification: true
```

**Step 3:** Verify: `mvn compile` passes, app starts, `/mcp/sse` endpoint responds

**Step 4:** Commit: `feat(agent): add MCP server dependency and config`

### Task 1.2: Register AgentTools as MCP ToolCallbacks

**Objective:** Make existing @Tool methods discoverable via MCP

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentTools.java`
- Create: `MoonAgent/src/main/java/com/moon/moonagent/config/McpServerConfig.java`

**Step 1:** Create McpServerConfig that explicitly registers AgentTools as tool callbacks:
```java
@Configuration
public class McpServerConfig {
    @Bean
    public List<ToolCallback> mcpToolCallbacks(AgentTools agentTools) {
        return ToolCallbacks.from(agentTools).stream().toList();
    }
}
```

Note: Verify the exact Spring AI 2.0 API for `ToolCallbacks.from()` — it may be `ToolCallbackProvider` or direct registration. Check Javadoc.

**Step 2:** Verify: MCP client (e.g., `npx @modelcontextprotocol/inspector`) connects to `/mcp/sse` and lists tools: writeScenes, replaceScenes, refineImage, generateVideo

**Step 3:** Commit: `feat(agent): register AgentTools as MCP tool callbacks`

### Task 1.3: Add MCP resource exposure (project context)

**Objective:** Expose project metadata as MCP resources so external agents can query project state

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/config/McpServerConfig.java`

**Step 1:** Add resource provider for project context:
```java
@Bean
public List<McpServerFeatures.SyncResourceProvider> mcpResources(
        AgentConversationMapper conversationMapper) {
    return List.of(
        McpServerFeatures.SyncResourceProvider.builder()
            .uri("moon://project/{projectId}")
            .name("Project context")
            .description("Current project scenes and assets")
            .handler((exchange, params) -> {
                // Return project scenes/assets as JSON resource
            })
            .build()
    );
}
```

Note: Verify exact MCP resource API in Spring AI 2.0 — `McpServerFeatures.SyncResourceProvider` may have different builder API.

**Step 2:** Commit: `feat(agent): expose project context as MCP resources`

---

## Phase 2: Specialist ChatClients — Autonomous Agents

**Why:** Currently handlers call LLM with fixed prompts. Each specialist should own a ChatClient with its tools, capable of autonomous multi-step reasoning.

### Task 2.1: Create SpecialistAgent abstraction

**Objective:** Each specialist = a ChatClient + its tools + its system prompt (from SKILL.md)

**Files:**
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/SpecialistAgent.java`
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/SpecialistResult.java`

**Step 1:** Define the interface:
```java
public interface SpecialistAgent {
    /** Intent type this specialist handles */
    String intentType();

    /**
     * Execute autonomously: ChatClient with tools, bounded loop.
     * @return structured result with message, tool outputs, and optional handoff target
     */
    SpecialistResult execute(SpecialistContext context);
}
```

**Step 2:** Define SpecialistResult (typed handoff payload):
```java
public record SpecialistResult(
    String message,                    // Final message to user
    Map<String, Object> toolOutputs,   // Tool execution results
    String handoffTarget,              // null = done, "intent-xxx" = hand off to another specialist
    String handoffReason,              // Why handing off
    Map<String, Object> handoffState   // State to pass to next specialist
) {
    public boolean needsHandoff() { return handoffTarget != null; }
}
```

**Step 3:** Define SpecialistContext:
```java
public record SpecialistContext(
    AgentConversation conversation,
    String userMessage,
    String picUrl,
    SseEmitter emitter,
    Map<String, Object> initialState,  // From previous specialist (if handoff)
    AgentSkill skill                   // SKILL.md metadata + prompts
) {}
```

**Step 2:** Commit: `feat(agent): define SpecialistAgent interface and handoff payload`

### Task 2.2: Implement AisplitSpecialist (first specialist)

**Objective:** Convert AisplitIntentHandler from scripted flow to autonomous ChatClient agent

**Files:**
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/impl/AisplitSpecialist.java`

**Step 1:** Build ChatClient with aisplit tools and skill prompts:
```java
@Component
public class AisplitSpecialist implements SpecialistAgent {
    private final ChatClient chatClient;  // Built with writeScenes, replaceScenes tools

    public AisplitSpecialist(ChatClient.Builder builder, AgentTools tools) {
        this.chatClient = builder
            .defaultSystem("你是专业分镜师...")  // From SKILL.md body
            .defaultTools(tools.writeScenes, tools.replaceScenes)  // From SKILL.md tools
            .defaultOptions(OpenAiChatOptions.builder()
                .model("text")  // Gateway routes to default text model
                .timeout(Duration.ofSeconds(120)))
            .build();
    }

    @Override
    public SpecialistResult execute(SpecialistContext ctx) {
        // ChatClient prompt → tool loop → bounded iterations → return result
        var response = chatClient.prompt()
            .user(ctx.userMessage())
            .system(s -> s.param("projectContext", getProjectContext(ctx)))
            .call();
        // Parse response, check for tool calls, return SpecialistResult
    }
}
```

**Step 2:** Verify: aisplit flow still works end-to-end via the new specialist

**Step 3:** Commit: `feat(agent): implement AisplitSpecialist with autonomous ChatClient`

### Task 2.3: Implement remaining specialists (pic, video, scene-review, other)

**Objective:** Convert remaining handlers to specialist pattern

**Files:**
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/impl/PicSpecialist.java`
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/impl/VideoSpecialist.java`
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/impl/SceneReviewSpecialist.java`
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/impl/OtherSpecialist.java`

**Step 1:** Each specialist follows the same pattern as Task 2.2, with its own:
- Tools (from SKILL.md `tools:` field)
- System prompt (from SKILL.md body)
- Model role (from SKILL.md `model_role:` → gateway routing)

**Step 2:** Verify each specialist independently

**Step 3:** Commit per specialist: `feat(agent): implement XxxSpecialist`

---

## Phase 3: Supervisor Handoff — Cross-Agent Communication

**Why:** Specialists can delegate to each other (e.g., storyboard specialist → image specialist after scenes are written)

### Task 3.1: Add handoff routing to PlanGraph

**Objective:** PlanGraph reads SpecialistResult.handoffTarget and routes to next specialist

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/PlanGraph.java`

**Step 1:** Add handoff state keys:
```java
private static final String K_HANDOFF_TARGET = "handoffTarget";
private static final String K_HANDOFF_STATE = "handoffState";
```

**Step 2:** Modify handler adapter nodes to check `SpecialistResult.needsHandoff()`:
- If handoff → set state keys → conditional edge routes to target specialist
- If done → route to END

**Step 3:** Add conditional edge from specialist nodes:
```
specialist_node → handoff_check (edge)
  ├─ handoffTarget != null → target_specialist_node
  └─ else → END
```

**Step 4:** Verify: "生成分镜然后生成图片" triggers aisplit → (handoff) → pic flow

**Step 5:** Commit: `feat(agent): add supervisor handoff routing to PlanGraph`

### Task 3.2: Implement handoff state transfer

**Objective:** Ensure state (scenes, images, context) passes correctly between specialists

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/SpecialistContext.java`

**Step 1:** SpecialistContext gains `previousResults: List<SpecialistResult>` — accumulated handoff chain

**Step 2:** Each specialist can read previous results to understand what was done

**Step 3:** Verify: after aisplit writes scenes, pic specialist sees the scenes and generates images

**Step 4:** Commit: `feat(agent): implement cross-specialist state transfer`

---

## Phase 4: MCP Client — Consume External Tools

**Why:** Agent can call external MCP servers (asset search, external image APIs, knowledge bases)

### Task 4.1: Add MCP Client dependency

**Files:**
- Modify: `MoonAgent/pom.xml`

**Step 1:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

**Step 2:** Configure MCP client connections:
```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        # Connect to external MCP servers
        connections:
          asset-server:
            url: http://localhost:8084/mcp/sse  # Asset search MCP server
            transport: SSE
          knowledge-base:
            url: http://localhost:8085/mcp/sse  # Project knowledge base
            transport: SSE
```

**Step 3:** Commit: `feat(agent): add MCP client dependency`

### Task 4.2: Register external MCP tools as AgentTools

**Objective:** External MCP tools become callable by specialists

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentTools.java`
- Create: `MoonAgent/src/main/java/com/moon/moonagent/config/McpClientConfig.java`

**Step 1:** McpClientConfig discovers tools from connected MCP servers and registers them:
```java
@Configuration
public class McpClientConfig {
    @Bean
    public ToolCallbackProvider mcpToolProvider(McpSyncClient mcpClient) {
        return new McpToolCallbackProvider(mcpClient);
    }
}
```

**Step 2:** Specialists can now use external tools alongside local tools

**Step 3:** Commit: `feat(agent): register external MCP tools via client`

### Task 4.3: Build asset-search MCP server (separate service or embedded)

**Objective:** Expose project assets as an MCP server so agents can search/reference them

**Files:**
- Create: `MoonAgent/src/main/java/com/moon/moonagent/mcp/AssetMcpServer.java` (or separate microservice)

**Step 1:** Asset search tool:
```java
@Tool(description = "Search project assets by type and keyword")
List<AssetResult> searchAssets(String projectId, String type, String keyword) {
    // Query scene_assets, agent_assets tables
}
```

**Step 2:** Verify: external MCP client can search assets

**Step 3:** Commit: `feat(agent): implement asset search MCP server`

---

## Phase 5: Agent Memory & Observability

### Task 5.1: Add per-conversation memory Advisor

**Objective:** Specialists remember context across turns without re-sending full history

**Files:**
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/memory/ConversationMemoryAdvisor.java`

**Step 1:** Use Spring AI `ChatMemory` Advisor:
```java
@Bean
public ChatMemory chatMemory(AgentMessageMapper messageMapper) {
    // Backed by agent_messages table
    return new JdbcChatMemory(messageMapper);
}
```

**Step 2:** Attach to each specialist's ChatClient as an Advisor

**Step 3:** Commit: `feat(agent): add conversation memory advisor`

### Task 5.2: Add observability (Micrometer traces for agent calls)

**Files:**
- Modify: `MoonAgent/src/main/resources/application.yml`

**Step 1:** Enable Spring AI observability:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
spring:
  ai:
    observations:
      enabled: true
```

**Step 2:** Verify: Prometheus metrics show LLM call latency, tool call counts

**Step 3:** Commit: `feat(agent): enable AI observability metrics`

---

## Files Likely to Change

| File | Change |
|------|--------|
| `MoonAgent/pom.xml` | Add MCP server/client dependencies |
| `MoonAgent/src/main/resources/application.yml` | MCP config |
| `MoonAgent/.../AgentTools.java` | MCP registration |
| `MoonAgent/.../PlanGraph.java` | Handoff routing |
| **New:** `MoonAgent/.../specialist/SpecialistAgent.java` | Interface |
| **New:** `MoonAgent/.../specialist/SpecialistResult.java` | Handoff payload |
| **New:** `MoonAgent/.../specialist/impl/*.java` | 5 specialist implementations |
| **New:** `MoonAgent/.../config/McpServerConfig.java` | MCP server beans |
| **New:** `MoonAgent/.../config/McpClientConfig.java` | MCP client beans |

---

## Verification

```bash
# Compile
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q

# MCP Server smoke test (after Phase 1)
npx @modelcontextprotocol/inspector http://localhost:8081/mcp/sse
# Should list: writeScenes, replaceScenes, refineImage, generateVideo

# Handoff test (after Phase 3)
# Send "生成一个3场景的科幻分镜，然后为每个场景生成图片"
# Expected: aisplit specialist → writes scenes → handoff → pic specialist → generates images

# MCP Client test (after Phase 4)
# External MCP server connected → specialist can search assets
```

---

## Risks & Tradeoffs

1. **Spring AI Alibaba Graph + MCP compatibility:** `spring-ai-alibaba-graph-core:2.0.0-M1.1` may not align with MCP server starter. Need to verify version compatibility — may need to upgrade graph-core.

2. **ChatClient tool loop vs scripted HITL:** Current HITL (plan → checkpoint → human → execute) is tightly coupled to handler flow. Autonomous ChatClient with tool loop may not naturally produce HITL checkpoints. **Mitigation:** Keep HITL as an Advisor that intercepts before irreversible tool calls.

3. **MCP transport choice:** SSE vs Streamable HTTP vs STDIO. SSE is simplest for HTTP-based services but Streamable HTTP is the newer standard. Start with SSE, migrate later.

4. **Specialist ChatClient cost:** Each specialist builds its own ChatClient = separate conversation context. For short interactions this is fine; for long multi-turn, memory Advisor is essential.

5. **Backward compatibility:** Existing frontend SSE protocol must not change. Specialists emit the same events through the same SseEmitter.

---

## Open Questions

1. Should `delete` intent become a specialist or stay as a simple handler? (It's trivial — probably keep as handler)
2. Should MCP server be in MoonAgent or a separate gateway service? (Embedded for now, extract later if needed)
3. How to handle specialist failure → fallback to "other" specialist? (Add error edge in PlanGraph)
