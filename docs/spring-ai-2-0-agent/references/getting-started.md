# Getting Started

## Minimal flow

1. Create a Spring Boot 4.0.x application with Spring Initializr.
2. Add the model provider starter and `spring-boot-starter-web` when exposing HTTP endpoints.
3. Set the provider key through an environment variable and a Spring property.
4. Inject `ChatClient.Builder`, build a client, and call `prompt(...).call().content()`.

Example configuration (provider names vary):

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: ${OPENAI_CHAT_MODEL:gpt-4o-mini}
```

Use the exact provider prefix and nested option names from the 2.0.0 provider page; do not infer them from another provider.

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder.defaultSystem("Be concise and cite uncertainty.").build();
}

String answer = chatClient.prompt()
    .user("Explain the order processing policy")
    .call()
    .content();
```

Use streaming only when the endpoint and client contract support incremental output. Keep provider keys out of source control and prefer environment-backed configuration.

## First verification

- Confirm the application starts with the intended Boot/Spring AI versions.
- Exercise one synchronous request and one provider error path.
- Add a test with a mocked `ChatModel` or test model before integrating external credentials.

Official implementation examples are indexed in `official-source-index.md`; use them to verify starter names and Boot 4.0 wiring.
