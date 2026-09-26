# MoonAgent AgentScope Full Migration

## Global Constraints

- Target runtime is one AgentScope Harness runtime named `MoonAgentRuntime`.
- Remove the second orchestration path; legacy data remains queryable and read-only.
- Nacos is the versioned source for MoonAgent skills, the global prompt, and MCP metadata.
- Keep classpath assets as an outage fallback.
- Existing REST reads remain compatible; old write endpoints return `410 Gone`.
- New turn streaming uses sequenced AgentScope event envelopes.
- No destructive database migration.
- Canvas Bridge is not registered in shared Nacos until it has a routable address.
- AgentSpec and AgentScope Service are out of scope for this release.

## Task 1: Dead-code and stale-asset cleanup

- Remove the unused graph-core dependency, `AgentNodeClients`, its test, the `test` skill/tool, and the unrelated project-local `holo-card-studio` skill/lock entry.
- Archive the obsolete PlanGraph design document and add one authoritative AgentScope architecture note.
- Remove the duplicate `common-core` dependency declaration and keep the build warning-free.
- Verification: MoonAgent compile, focused unit tests, `git diff --check`.

## Task 2: Versioned Nacos asset pipeline

- Add AgentScope Nacos skill/prompt dependencies and configuration properties.
- Build a resilient `AiService`/skill repository setup with classpath fallback and configurable namespace/label.
- Add an idempotent publish/verify script for standard skill ZIPs, the global prompt, and MCP metadata.
- Verification: unit tests for repository fallback/version selection and script syntax checks.

## Task 3: Production skill consolidation

- Replace production skills with `storyboard`, `image`, `video`, `canvas-design`, `canvas-run`, and `general`.
- Use standard Agent Skills frontmatter with Moon-specific values under `metadata.moon`.
- Remove obsolete `aisplit`, `pic`, `delete`, `canvas-delete`, `other`, and `test` skill packages.
- Verification: skill package validation and migration tests.

## Task 4: AgentScope runtime and turn APIs

- Introduce the single `MoonAgentRuntime`, permission policy, turn event store, and runtime service.
- Route streamed turns and resumes directly through AgentScope, including Canvas tools.
- Preserve conversation ownership, transaction, message, asset, checkpoint, and concurrency behavior.
- Add `agent_turns` and `agent_turn_events`, the cutover migration, and legacy archive handling.
- Verification: backend unit/integration tests and migration tests.

## Task 5: Frontend AgentScope protocol

- Replace the multi-orchestrator SSE reducer with the sequenced AgentScope event envelope.
- Preserve chat, assets, audit, generation records, and HITL interactions.
- Replace the dynamic plan panel with an AgentScope plan/subagent timeline.
- Verification: Vitest and production build.

## Task 6: Compatibility and end-to-end verification

- Return `410 Gone` from obsolete write endpoints and document replacement URLs.
- Run backend, frontend, and Canvas verification suites.
- Produce the manual verification checklist and record any blocked external checks.

