---
name: spring-ai-2-0-agent
description: Use when designing, implementing, debugging, or reviewing Spring AI 2.0.0 applications in Spring Boot 4.0 projects, including ChatClient, model providers, tool calling, Advisors, memory, RAG, vector stores, MCP, multimodal models, evaluation, observability, and Boot 4.0 compatibility.
---

# Spring AI 2.0 Agent Engineering

Use this skill to build Java agent systems on Spring AI 2.0.0. Prefer official APIs and examples, keep provider-specific behavior explicit, and never silently mix Spring AI 1.x guidance into a 2.0 task.

**Evidence-first rule:** Never guess a dependency, class, method, annotation, property, transport, or behavior. If the local references do not prove it, stop implementation at that point, identify the exact official page/Javadoc needed, and ask for permission to fetch it or state the unresolved gap. Do not present plausible code as verified code.

## Version Guardrails

- Target Spring AI `2.0.0` and Spring Boot `4.0.x` unless the user explicitly selects another line.
- Treat the official reference and Javadoc as authoritative. Read `references/version-and-compatibility.md` first.
- When a symbol, starter, property, or transport is uncertain, consult `references/official-source-index.md` and state the uncertainty.

## Route The Task

Load only the references needed for the request:

| Request | Read |
| --- | --- |
| First app, dependencies, configuration | `getting-started.md`, `version-and-compatibility.md` |
| Chat, streaming, provider options | `chat-and-models.md` |
| Tools, memory, RAG, structured output, evaluation, agent workflow | `agent-patterns.md` |
| Loops, human approval, handoff, checkpoints, recovery, multi-agent orchestration | `orchestration-patterns.md` |
| Ingestion, embeddings, vector stores, metadata filters | `data-and-retrieval.md` |
| MCP, image, audio, moderation | `mcp-and-multimodal.md` |
| Production hardening, testing, observability, deployment | `production-operations.md` |
| Version or API verification | `official-source-index.md`, then the relevant topic |

For cross-cutting agent work, read the version file plus `agent-patterns.md` and `orchestration-patterns.md`, then add data, MCP, or operations references as required.

## Default Agent Architecture

When the user asks for an agent system without prescribing an architecture, design an application-level planner/executor/reviewer workflow:

1. Use `ChatClient` as the model-facing boundary.
2. Represent business actions as typed tools with explicit authorization and validation.
3. Use Advisors for reusable prompt, memory, retrieval, and interception concerns.
4. Use structured outputs for planner and reviewer decisions; keep execution side effects outside model-generated text.
5. Use RAG and `VectorStore` only where external knowledge is needed.
6. Use MCP when tools/resources/prompts must be shared across processes or clients.

Spring AI supplies these primitives; multi-agent orchestration, state machines, retries, budgets, and human approval remain application responsibilities. Do not claim a native multi-agent API unless an official 2.0 source proves it.

## Implementation Contract

For implementation requests, provide:

- Maven dependencies and Gradle equivalents where relevant.
- `application.yml` or `application.properties` with secrets represented by environment variables.
- Minimal Java code that compiles against the selected Spring AI 2.0.0 starter on Spring Boot 4.0.x.
- Tool input/output types, validation, timeout/retry policy, and authorization boundaries.
- Tests for model calls, tool behavior, retrieval filters, workflow transitions, and failure paths.
- Run commands and a short verification checklist.

Separate portable Spring AI APIs from provider-specific options. Call out trade-offs, unsupported features, and upgrade risks instead of guessing.

Every implementation answer must include an **evidence ledger** with: claim/API, source file or official URL, version, and verification status (`verified`, `application pattern`, or `unresolved`). Do not mark application-level orchestration as a Spring AI API.

## Source And Sync Policy

`references/official-source-index.md` is the locked source map. The scripts are maintenance tools, not runtime dependencies:

```powershell
.\scripts\sync_spring_ai_docs.ps1 -Manifest .\references\official-source-index.md -Output .\work\spring-ai-2.0-snapshot -Version 2.0.0 -DryRun
.\scripts\validate_source_index.ps1 -SkillPath .
```

Sync writes candidate files and metadata only; manually review diffs before replacing references.
