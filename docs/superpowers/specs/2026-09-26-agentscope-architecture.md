# MoonAgent AgentScope Architecture

Date: 2026-09-26

Status: implemented architecture for the AgentScope full migration

This note supersedes the archived PlanGraph design and is the single
authoritative MoonAgent orchestration architecture. The legacy PlanGraph,
`UnifiedAgentLoop`, `DynamicTaskEngine`, Specialist, IntentHandler, and
Capability routing paths have been removed from production code.

## Core Decision

MoonAgent has one orchestration runtime, `MoonAgentRuntime`, built on the
AgentScope Harness. The runtime owns turn execution, model/tool calls, HITL
suspension and resume, subagent delegation, and runtime event production.

The following are out of scope for this release:

- a separate AgentSpec execution model;
- AgentScope Service as an additional runtime or deployment unit.

## Runtime Boundary

The existing REST/API layer remains the owner of authentication, conversation
ownership, transaction boundaries, and request validation. It submits an
authenticated turn to `MoonAgentRuntime` and does not duplicate graph or agent
routing.

The runtime is responsible for:

- assembling model, prompt, skill, tool, memory, and permission context;
- running the AgentScope ReAct loop and delegated subagents;
- emitting ordered turn events for streamed responses, tool activity, plans,
  human-input requests, errors, and completion;
- parking a turn at a durable checkpoint and resuming it with the same
  conversation ownership and concurrency guarantees as the original request.

HITL is part of the turn protocol. A pause records the pending interaction and
its checkpoint; a resume continues the same logical turn rather than starting a
second orchestration path.

## Assets And Configuration

Nacos is the versioned source of truth for:

- AgentScope skill packages;
- the global system prompt;
- MCP server/tool metadata.

Skill packages use standard Agent Skills frontmatter, with Moon-specific values
under `metadata.moon`. Publication must be idempotent and verify the namespace,
label/version, package hash, and expected asset set before runtime cutover.

Classpath assets remain the outage fallback. Runtime repository lookup must
prefer a validated Nacos version and fall back predictably to the packaged
version when Nacos is unavailable. Runtime selection must never silently mix
skills from different versions.

The Canvas Bridge is not published to shared Nacos until it has a routable
address. Local/classpath metadata remains valid until that prerequisite is met.

## Persistence And Events

AgentScope turns are persisted separately from legacy runtime state:

- `agent_turns` stores the logical turn, ownership, status, and checkpoint
  linkage;
- `agent_turn_events` stores sequenced runtime events needed for replay,
  reconnect, audit, and deterministic client rendering.

Event sequence numbers are scoped to a turn and are stable across stream
reconnect. Persisting an event is part of turn execution, not a best-effort log.

The migration is additive. Existing conversations, messages, checkpoints,
generation records, and assets remain readable. Legacy state is archived or
treated as read-only; no destructive schema migration is permitted.

## Permissions And Tool Execution

All tool exposure and execution pass through the runtime permission policy.
The policy decides whether a tool is available in the current role, turn,
conversation, and workflow stage. Skills and prompts may describe tool usage,
but they do not grant authority.

Tool calls retain the existing audit, idempotency, progress, retry, and media
preflight behavior. Long-running image/video work continues through the media
task services and reports progress back as turn events.

## Compatibility Cutover

The migration is ordered so that the new path lands before old endpoints are
disabled:

1. version and verify Nacos/classpath assets;
2. introduce the single runtime and turn/event persistence;
3. route streamed turns and resumes through AgentScope;
4. move the frontend to the sequenced AgentScope event envelope;
5. return `410 Gone` from obsolete write endpoints only after their replacement
   contract is live.

Existing read APIs remain compatible. The frontend consumes the sequenced
AgentScope event envelope directly; no translation layer to the retired
`SseEvent` protocol remains.

## Non-Negotiable Invariants

- One writable orchestration runtime.
- One versioned source for each skill/prompt/MCP asset, with explicit fallback.
- One turn identity across initial execution, HITL pause, reconnect, and resume.
- Ordered, durable turn events for every client-visible runtime transition.
- Existing user ownership and conversation isolation rules are unchanged.
- Legacy data is preserved and never rewritten by the migration.

## Generic Creative Agent Layer (2026-09-29)

The runtime now also owns a domain-neutral creative intelligence layer:

- product-owned `project_creative_contexts` memory scoped by project id;
- deterministic HARD/SOFT constraint extraction and BALANCED/STRICT modes;
- AgentScope Plan Mode and task list for complex or high-cost turns;
- the fixed creative team `creative-planner`, `reference-curator`,
  `prompt-director`, `visual-critic`, and `continuity-editor`;
- persisted media critiques and a bounded image repair loop;
- persisted timeline manifests that feed ordered long-video assembly;
- role-based gateway model selection, OpenTelemetry and PostgreSQL
  `DistributedStore`.

This layer is intentionally domain neutral. Architecture, CAD/BIM and audio
features must be introduced later as separate skills and domain packages,
without adding domain fields to the generic project memory contract.
