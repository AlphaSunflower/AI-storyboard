# Chat And Models

## Portable boundary

Use `ChatModel` for provider-neutral model access and `ChatClient` for fluent application interaction. Keep model-specific settings in the provider options object and document the portability impact.

Typical concerns include system/user messages, message roles, synchronous calls, streaming, response metadata, token usage, model selection, temperature, and structured response formats. Verify exact 2.0.0 signatures in Javadoc.

Common configuration shape:

```yaml
spring:
  ai:
    <provider>:
      api-key: ${AI_API_KEY}
      chat.options.model: ${AI_MODEL}
```

The concrete prefix may be `openai`, `anthropic`, `azure.openai`, `bedrock`, `google.genai`, `ollama`, or another documented provider. Keep provider configuration in its own profile when supporting more than one model.

## Provider selection

Spring AI publishes starters and implementations for major providers, including OpenAI, Anthropic, Microsoft, Amazon Bedrock, Google, Ollama, Mistral, DeepSeek, and others. Select one provider starter first; add fallbacks only with an explicit routing policy.

## Reliable interaction

- Bound request time and maximum output size.
- Capture provider request IDs and usage metadata without logging secrets or sensitive prompts.
- Treat empty or malformed model output as a typed failure.
- For streaming, define cancellation, backpressure, and partial-response behavior.
- Use structured output when downstream code needs a schema; validate it before side effects.
