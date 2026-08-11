# Production Operations

## Reliability

- Configure connection, model, tool, and retrieval timeouts.
- Retry only transient failures with bounded exponential backoff and jitter.
- Make writes idempotent and attach correlation IDs to workflow runs.
- Define cancellation and partial-result behavior for streaming.

## Security

Keep credentials in environment variables or a secret manager. Redact prompts, tool arguments, documents, and model responses according to data classification. Enforce authorization in tools and retrieval filters, not in prompts alone. Treat retrieved documents and model output as untrusted.

## Observability

Enable Spring AI/Spring Boot observability for model, tool, retrieval, and workflow spans. Record latency, error class, token usage, provider/model, and retrieval counts while avoiding raw sensitive content by default.

Use the Spring Boot 4.0 Actuator/observation setup already present in the host application, then enable the Spring AI observations documented for the selected modules. Keep high-cardinality prompt text out of metric tags.

## Testing

Use mocked models for unit tests, deterministic fixtures for tools and vector retrieval, contract tests for provider integrations, and end-to-end tests for planner/executor/reviewer transitions. Test malformed structured output, denied tools, empty retrieval, timeouts, rate limits, and provider outages.

## Deployment

Pin compatible dependency versions, configure connection pools and rate limits, externalize prompts where governance requires it, and document provider quotas/cost controls. Verify startup, health checks, migrations/indexes, and graceful shutdown.
