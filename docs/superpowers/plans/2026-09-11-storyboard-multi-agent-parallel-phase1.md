# Storyboard Multi-Agent Parallel Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将分镜生成改为“串行快模型骨架 + 并行多智能体批次生成”，失败只影响对应批次。

**Architecture:** 保留现有 HITL、checkpoint 和写库接口。新增显式 `skeleton-model` 和 `max-parallel-agents` 配置；骨架规划调用固定使用快模型；`StoryboardBatchGenerator` 提供有序并发 Fan-Out/Gather；每个批次作为独立智能体调用并保有自己的重试。

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.0, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-11-storyboard-multi-agent-parallel-design.md`

## Global Constraints

- 骨架阶段必须串行，并且只能使用显式配置的 `skeleton-model`。
- `skeleton-model` 为空时不得静默回退到网关默认文本模型。
- 默认 `max-parallel-agents=4`，实际批次并发数不得超过该值或批次数量。
- 结果必须按 `sceneNumber` 对应的批次顺序确定性合并，不能按完成时间合并。
- 单个批次失败不能取消或丢弃其他已经完成的批次；重试只发生在对应批次。
- 现有 `generate(...)` 顺序接口保持兼容。

---

### Task 1: Add explicit skeleton and parallelism configuration

**Files:**
- Modify: `agent-core/src/main/java/com/moon/moonagent/ai/AgentAiConfigProperties.java`
- Modify: `MoonAgent/src/main/resources/application.yml`
- Test: `MoonAgent/src/test/java/com/moon/moonagent/ai/AgentAiConfigPropertiesTest.java`

**Interfaces:**
- Produces: `String getSkeletonModel()`
- Produces: `int getMaxParallelAgents()`

- [x] **Step 1: Write the failing defaults test**

```java
@Test
void defaultSkeletonModelIsExplicitFastModel() {
    assertThat(new AgentAiConfigProperties().getSkeletonModel()).isEqualTo("mimo-v2.5-pro");
}

@Test
void defaultMaxParallelAgentsIsFour() {
    assertThat(new AgentAiConfigProperties().getMaxParallelAgents()).isEqualTo(4);
}
```

- [x] **Step 2: Run the test to verify it fails**

Run:

```powershell
mvn -q "-Dtest=AgentAiConfigPropertiesTest" test
```

Expected: compilation failure for missing `getSkeletonModel()` and `getMaxParallelAgents()`.

- [x] **Step 3: Add fields and YAML values**

```java
private String skeletonModel = "mimo-v2.5-pro";
private int maxParallelAgents = 4;
```

```yaml
skeleton-model: ${AGENT_SKELETON_MODEL:mimo-v2.5-pro}
max-parallel-agents: ${AGENT_MAX_PARALLEL_AGENTS:4}
```

- [x] **Step 4: Run the test**

Run:

```powershell
mvn -q "-Dtest=AgentAiConfigPropertiesTest" test
```

Expected: PASS.

---

### Task 2: Add parallel ordered batch execution

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/storyboard/StoryboardBatchGenerator.java`
- Test: `MoonAgent/src/test/java/com/moon/moonagent/ai/agent/storyboard/StoryboardBatchGeneratorTest.java`

**Interfaces:**
- Produces: `StoryboardBatchGenerator.generateParallel(Plan, Function<String,List<Map<String,Object>>>, BatchCaller, int)`
- Preserves: `StoryboardBatchGenerator.generate(Plan, Function, BatchCaller)`

- [x] **Step 1: Write failing concurrency and ordering tests**

```java
@Test
void generateParallel_startsAllBatchesConcurrently() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    var result = StoryboardBatchGenerator.generateParallel(
            new StoryboardBatchGenerator.Plan(10, 5),
            content -> List.of(Map.of("value", content)),
            range -> {
                started.countDown();
                if (!started.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("batch did not run concurrently");
                }
                release.countDown();
                release.await(2, TimeUnit.SECONDS);
                return new StoryboardBatchGenerator.CallOutput(range.from() + "-" + range.to(), null);
            },
            2);

    assertThat(result.scenes()).extracting(s -> s.get("value"))
            .containsExactly("1-5", "6-10");
}
```

```java
@Test
void generateParallel_mergesInRangeOrderWhenCompletionReverses() {
    var result = StoryboardBatchGenerator.generateParallel(
            new StoryboardBatchGenerator.Plan(10, 5),
            content -> List.of(Map.of("value", content)),
            range -> {
                if (range.from() == 1) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return new StoryboardBatchGenerator.CallOutput(range.from() + "-" + range.to(), null);
            },
            2);

    assertThat(result.scenes()).extracting(s -> s.get("value"))
            .containsExactly("1-5", "6-10");
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run:

```powershell
mvn -q "-Dtest=StoryboardBatchGeneratorTest" test
```

Expected: compilation failure for missing `generateParallel`.

- [x] **Step 3: Implement ordered Fan-Out/Gather**

Add a private `runBatch` helper shared by sequential and parallel paths. Use
`Executors.newFixedThreadPool(Math.min(maxConcurrency, plan.ranges().size()))`,
submit one task per range, collect all outcomes, then merge the batch results
in `plan.ranges()` order. Throw only after all submitted tasks have completed.

- [x] **Step 4: Run the tests**

Run:

```powershell
mvn -q "-Dtest=StoryboardBatchGeneratorTest" test
```

Expected: PASS, including existing sequential tests.

---

### Task 3: Route skeleton to the fast model and scene batches through parallel execution

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/impl/ScriptGenerationServiceImpl.java`
- Modify: `MoonAgent/src/test/java/com/moon/moonagent/ai/impl/ScriptGenerationServiceImplTest.java`

**Interfaces:**
- Consumes: `AgentAiConfigProperties.getSkeletonModel()`
- Consumes: `AgentAiConfigProperties.getMaxParallelAgents()`
- Consumes: `StoryboardBatchGenerator.generateParallel(...)`

- [x] **Step 1: Add a failing skeleton-model test**

Add `AgentAiConfigProperties agentConfig` to the constructor fixture, set
`agentConfig.setSkeletonModel("fast-skeleton")`, invoke a new package-private
`resolveSkeletonModel()`, and assert the result is `fast-skeleton`.

Add a second test with a blank model and assert it throws
`IllegalStateException` instead of calling the gateway default.

- [x] **Step 2: Run the test to verify it fails**

Run:

```powershell
mvn -q "-Dtest=ScriptGenerationServiceImplTest" test
```

Expected: compilation failure for missing `resolveSkeletonModel()` or constructor mismatch.

- [x] **Step 3: Inject `AgentAiConfigProperties` and route the plan call**

Add the dependency to the constructor. Replace the planning call:

```java
planCall = callLLM(resolveSkeletonModel(), buildPlanningSystemPrompt(),
        buildPlanningPrompt(scriptText, understanding, assetSheet),
        "skeleton", UUID.randomUUID().toString());
```

Keep the scene batch calls on the existing scene model.

- [x] **Step 4: Replace sequential batch generation**

```java
StoryboardBatchGenerator.Result result = StoryboardBatchGenerator.generateParallel(
        finalPlan, StoryboardJsonParser::parseScenes, range -> {
            String callId = UUID.randomUUID().toString();
            LlmCall batchCall = callLLM(model, finalSystemPrompt,
                    buildBatchUserPrompt(scriptText, finalUnderstanding, finalAssetSheet,
                            range, finalPlan.totalScenes()),
                    "agent-scenes-" + range.from() + "-" + range.to(), callId);
            String details = batchCall.reasoning() == null ? null : batchCall.reasoning().details();
            return new StoryboardBatchGenerator.CallOutput(batchCall.content(), details);
        }, agentConfig.getMaxParallelAgents());
```

- [x] **Step 5: Run focused and full tests**

Run:

```powershell
mvn -q "-Dtest=ScriptGenerationServiceImplTest,StoryboardBatchGeneratorTest" test
mvn -q test
```

Expected: PASS.

---

### Task 4: Build and runtime verification

**Files:**
- Package: `MoonAgent/target/moon-agent-0.0.1-SNAPSHOT.jar`
- Logs: `MoonAgent/logs/moon-agent-multi-agent.out.log`

**Interfaces:**
- Produces: running MoonAgent on port 8084 with the new jar.

- [x] **Step 1: Install shared config module**

Run:

```powershell
mvn -q -DskipTests install
```

Working directory: `agent-core`.

- [x] **Step 2: Package MoonAgent**

Run:

```powershell
mvn -q -DskipTests package
```

Working directory: `MoonAgent`.

- [x] **Step 3: Restart MoonAgent**

Stop the process listening on 8084, start the packaged jar with profile `local`,
and redirect stdout/stderr to `MoonAgent/logs/moon-agent-multi-agent.out.log`
and `MoonAgent/logs/moon-agent-multi-agent.err.log`.

- [x] **Step 4: Verify health and startup**

Run:

```powershell
Invoke-RestMethod http://localhost:8084/actuator/health
```

Expected: `status=UP`.

- [x] **Step 5: Verify parallel and skeleton settings**

Confirm startup uses the new timestamped log, then run one storyboard generation
and inspect:

```text
stage=skeleton, model=<configured fast model>
stage=agent-scenes-*
```

All batch calls must carry the same parent `X-Call-Id` family and overlap in the
gateway call log.
