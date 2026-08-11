# Agent Patterns

## ChatClient and Advisors

Build a single `ChatClient` boundary, then compose reusable `Advisor` instances for memory, retrieval, logging, safety, and prompt transformations. Keep business side effects in services and tools rather than Advisors.

```java
@Service
class AssistantService {
    private final ChatClient chatClient;

    AssistantService(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem("Answer only from approved context.").build();
    }

    String answer(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

For JSON/POJO responses, use the documented `entity(Class<T>)` or structured-output converter for the selected 2.0.0 API, then validate the returned object before use.

## Tool calling

Expose narrow, typed tools. Validate all model-supplied arguments, enforce authorization using the real caller identity, apply idempotency to writes, and return stable error objects. A tool result is untrusted input until validated.

```java
@Component
class OrderTools {
    @Tool(description = "Look up an order by its public identifier")
    OrderStatus lookupOrder(String orderId) { /* authorize and query */ }
}
```

Register tools through the documented 2.0.0 tool callback/annotation mechanism. Confirm exact annotation and registration signatures before coding.

Tool-call loop guidance: let the model request a tool, execute it in application code, return a typed result, and allow the model to continue only within a bounded iteration count. Do not execute arbitrary method names or accept authorization decisions from the model.

## Memory and RAG

Use conversation memory for dialogue state and RAG for external knowledge. Persist only the minimum required context. Apply tenant/user filters before retrieval and include source metadata in the final answer when citations matter.

Typical RAG composition is a retrieval `Advisor` attached to the `ChatClient`; the exact advisor class and constructor vary by 2.0.0 module. Verify the class in the official RAG reference before copying code.

## Structured output and evaluation

Represent planner, executor, and reviewer decisions as validated POJOs or records. Reject schema violations before executing tools. Evaluate relevance, groundedness, refusal behavior, and tool correctness with deterministic fixtures and model-evaluation utilities where appropriate.

Spring AI 2.0's evaluation reference documents `EvaluationRequest`, `EvaluationResponse`, `RelevancyEvaluator`, and `FactCheckingEvaluator`. Use `RelevancyEvaluator` for user/question/context alignment and `FactCheckingEvaluator` for claim support. Keep evaluator-model selection, prompts, thresholds, and false-positive handling explicit; an evaluator result is evidence for a policy decision, not an automatic production truth.

## Planner/executor/reviewer workflow

- Planner selects a bounded plan and required tools.
- Executor performs one authorized step at a time and records typed results.
- Reviewer checks policy, evidence, and completion; it may request a retry or human approval.
- Application code owns state, transitions, budgets, retries, idempotency, and audit logs.

Do not describe this as a Spring AI native multi-agent runtime.

## API-to-task map

| Need | Spring AI 2.0 area | Application responsibility |
| --- | --- | --- |
| Model conversation | `ChatClient`, `ChatModel` | Prompt policy, endpoint contract |
| Business action | Tools / `ToolCallback` | Auth, validation, idempotency |
| Cross-cutting prompt/retrieval | Advisors | Ordering and failure policy |
| Dialogue state | Chat memory | Persistence, retention, tenant isolation |
| Knowledge grounding | RAG / `VectorStore` | Ingestion, ACL filters, citations |
| Typed decisions | Structured output | Schema validation and fallback |
| External tool protocol | MCP | Transport auth and server trust |

Use the official examples repository to locate runnable variants for each row before inventing wiring.

## Large tool catalogs

For systems with many tools or MCP servers, verify the 2.0.0 Tool Search Tool documentation before enabling it. The documented pattern uses `ToolSearchToolCallingAdvisor`, a `ToolIndex`, and a bounded result count so the model discovers tools on demand rather than receiving every schema in every prompt. For fewer than roughly ten small tools, direct registration is usually easier to test.
