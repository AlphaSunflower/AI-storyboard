# Orchestration Patterns

This file describes production patterns that compose Spring AI 2.0 primitives. The patterns are application architecture, not a claim that Spring AI exposes a native workflow or multi-agent runtime.

## Evidence gate

Before writing code, classify each proposed element:

| Element | Required evidence |
| --- | --- |
| Maven/Gradle coordinate | 2.0.0 reference, BOM, or official example |
| Java class/method/annotation | 2.0.0 Javadoc or reference code block |
| `spring.ai.*` property | 2.0.0 configuration section or generated metadata |
| Loop, state machine, handoff, approval | Application design unless an official Spring AI page explicitly documents it |

If evidence is missing, stop and ask for the source or use a clearly labeled pseudocode pattern. Never fabricate an import or property.

## Bounded agent loop

Use a loop only when the model must observe tool results and decide the next step. The application owns the loop:

```text
REQUESTED -> PLAN -> EXECUTE_TOOL -> OBSERVE -> REVIEW
                         |                    |
                         +-- retry ----------+
                         +-- approval ------> WAITING_FOR_HUMAN
                         +-- failure -------> RECOVERY
REVIEW -> COMPLETED or HANDOFF or FAILED
```

Required controls:

- `maxSteps`, wall-clock deadline, token/cost budget, and per-tool timeout.
- Idempotency key for every side-effecting tool.
- Typed state persisted after each transition.
- Loop termination on repeated state, no progress, denied tool, malformed output, or budget exhaustion.
- Audit record containing prompt/model version, selected tool, arguments hash, result status, and reviewer decision.

## Human-in-the-loop

Pause before irreversible, high-risk, privileged, or ambiguous actions. Persist a `WAITING_FOR_HUMAN` checkpoint containing the proposed action, evidence, risk, expiry, and resume token. Resume only after an authenticated human decision; never treat silence or timeout as approval.

Common approval modes:

- **Approve/reject:** one action with an explicit decision.
- **Edit then approve:** human edits tool arguments before execution.
- **Escalate:** hand off to an operator or specialist agent with the complete evidence bundle.
- **Sample/review:** allow low-risk actions automatically and sample completed traces for review.

## Checkpoint and recovery

Persist workflow state at request acceptance, after planning, before each side effect, after each tool result, and before waiting for a human. On restart, resume from the last durable state and use idempotency keys to prevent duplicate writes. Make recovery transitions explicit: retry transient errors, compensate completed actions when possible, or hand off with a reason.

## Handoff and multi-agent options

Choose the least complex architecture that meets the requirement:

| Pattern | Use when | Boundary |
| --- | --- | --- |
| Single agent + tools | One domain and modest tool set | One `ChatClient`, typed tools |
| Planner/executor/reviewer | Plan quality and policy checks matter | Typed plan/result/review records |
| Supervisor + specialists | Multiple bounded domains | Supervisor routes; specialists cannot bypass policy |
| Sequential pipeline | Fixed deterministic stages | State passed as validated DTOs |
| Event-driven workers | Long-running or asynchronous work | Durable events and idempotent consumers |
| MCP federation | Tools/resources must be shared across processes | MCP client/server trust boundary |

Handoff payloads must include objective, current state, evidence/source IDs, completed actions, pending actions, budgets, deadlines, and failure context. A handoff is not a free-form prompt copy.

## Recursive advisors and tool discovery

Spring AI 2.0 documents Recursive Advisors and a Tool Search Tool pattern. Use them only after verifying the exact page and starter. Tool search is appropriate for large catalogs or MCP-heavy systems; for small catalogs, direct tool registration is simpler. Bound recursive advisor depth and detect cycles.

## Verification scenarios

Test: model requests an unauthorized tool, tool times out, model repeats the same call, human rejects an action, process restarts after a checkpoint, provider rate-limits, and reviewer finds unsupported evidence. Each case must end in a deterministic state and audit entry.
