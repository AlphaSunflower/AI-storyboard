# Version And Compatibility

## Locked baseline

- Spring AI: `2.0.0`.
- Spring Boot: `4.0.x` for this project baseline. Spring AI 2.x targets the Boot 4.x line; do not silently downgrade a Boot 4 project to Boot 3.
- Java: use the exact JDK level required by the selected Spring Boot 4.x release; verify with the project build files before pinning it.
- Coordinates and starter names: verify against the 2.0.0 reference or Initializr. Do not copy 1.x dependency names without checking.

## Dependency rules

Prefer a Spring Boot starter for the selected model or vector store. Keep the Spring AI BOM/dependency management consistent across modules. Do not mix release trains in one application.

Maven pattern (verify the exact published version before copying):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>2.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Add one provider starter such as `spring-ai-starter-model-openai`, then use the provider's documented properties. Keep `spring-boot-starter-parent` or the project's Boot 4.0 dependency management as the only Boot platform source.

Common property-key forms to search for (provider-specific options still require verification): `spring.ai.openai.api-key`, `spring.ai.openai.chat.options.model`, `spring.ai.anthropic.api-key`, `spring.ai.ollama.base-url`, `spring.ai.vectorstore.*`, and `spring.ai.mcp.*`.

Gradle pattern:

```groovy
dependencies {
    implementation platform("org.springframework.ai:spring-ai-bom:2.0.0")
    implementation "org.springframework.ai:spring-ai-starter-model-openai"
}
```

## API migration guard

Spring AI 2.0 documentation may differ materially from 1.x. Before using a remembered API, verify the package, method signature, starter, and configuration key in the 2.0.0 Javadoc/reference. Mark any compatibility assumption in the answer.

## Upgrade workflow

1. Record current Spring Boot, Spring AI, Java, and provider SDK versions.
2. Read the official upgrade notes.
3. Compile and run focused tests after dependency changes.
4. Check streaming, tool schemas, memory persistence, vector metadata filters, MCP transports, and observability spans.

## Boot 4.0 migration checks

- Confirm every Spring AI starter resolves with Spring Framework 7 / Boot 4.0 dependencies.
- Recheck namespace and auto-configuration changes in the Boot 4.0 migration guide before adapting older code.
- Run `mvn dependency:tree` or `./gradlew dependencies` and ensure there is no Spring Boot 3.x or Spring AI 1.x artifact.

Sources: see `official-source-index.md`.
