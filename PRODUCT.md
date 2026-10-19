# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Existing codebase, not greenfield: Spring Boot 4 / Java 25 gateway (`AILLMGateway`) with a zero-build static admin UI (`src/main/resources/static/admin-ui`, plain HTML/CSS/JS) served on port 8083. PostgreSQL + Redis back the gateway. Do not introduce a frontend framework or build step without an explicit decision.

## Users

Primary users are the platform operations team and model onboarding engineers for an internal LLM gateway. They work in the same admin console: operations checks health, logs, errors, channel status, and route availability; onboarding engineers add channels, define model parameter Schemas, bind routes, and verify the final upstream request.

## Product Purpose

The gateway centralizes model access for internal applications: one OpenAI-style API surface for chat, image, and video, with provider keys, routing, fallbacks, and response normalization kept behind the gateway. The admin console exists so operators can change channels/routes/model behavior without editing application code, and can see what will be sent upstream before spending a request.

Success means: a new model or channel can be onboarded without a code change; a route change can be reviewed and previewed before saving; failures are diagnosable from logs and health views; and the same configuration can be exported, reviewed, and re-imported as code.

## Positioning

The gateway is not a generic chat proxy. Its distinct mechanism is a unified media contract plus a model-level parameter Schema: image and video providers with different field names, value conventions, sync/async lifecycles, and response shapes are described once and normalized at the edge. Routing, fallbacks, key pools, and config-as-code sit on top of that same Schema, so the admin UI, the Agent/canvas clients, and the YAML representation agree on one source of truth.

## Operating Context

- Internal network deployment; provider keys and configuration must not leave the internal environment.
- Chinese-language admin UI for operators.
- Single-tenant control plane today. Multi-tenant isolation and billing are a stated future direction, not implemented product behavior.
- Admins work from desktop browsers; mobile web is a read/check surface, not the primary configuration surface.
- Configuration is changed through the admin UI, the admin HTTP API, or YAML import/export. YAML import is upsert-oriented and does not prune absent rows.
- Image/video work is asynchronous in many providers; the gateway exposes create, poll, content download, and cancel where the provider supports them.

## Capabilities and Constraints

Confirmed capabilities: channel CRUD, route CRUD with priority/weight/retries/fallbacks, model parameter Schema, key/parameter/upstream-model mapping, request preview, parameter default injection, image response normalization, video task polling/content, channel key pool, call logs and metrics, API keys, admin users, YAML export/validate/import.

Constraints:

- Provider protocols differ. Not every provider can be fully configured through the Schema; multipart, signed requests, binary responses, operation-style polling, and vendor-specific payloads may require a protocol adapter.
- The admin UI is plain static JavaScript; no bundler. Keep it dependency-free unless a stack change is explicitly approved.
- Spring Boot 4 uses Jackson 3 for HTTP serialization; do not return Jackson 2 `JsonNode` directly from controllers.
- Database migrations are manual in this repository; `db/migration/V*.sql` documents changes but is not auto-applied by Flyway.
- Multi-tenant boundaries, quotas, pricing, and billing are undecided and must not be invented in product copy or UI.
- The gateway is internal infrastructure; it is not a public self-serve SaaS product.

## Brand Commitments

- Product name: LLM 网关 (LLM Gateway).
- Chinese-first admin copy; technical identifiers may remain English.
- Internal, operator-facing tone: direct, specific, no marketing claims.
- Existing visual identity has been refined into a neutral stone control console with a single oxide-red accent; preserve that operator-first direction rather than reintroducing decorative editorial styling.

## Evidence on Hand

- `AILLMGateway/` gateway implementation, tests, admin UI, and SQL migrations.
- `docs/2026-10-18-LLM网关路由与渠道管理评审.md`: routing/channel review and P0 decisions.
- `docs/2026-10-18-模型配置工作台.md`: model configuration workbench, templates, request preview, Schema rules.
- `docs/2026-10-19-生图生视频接口对照.md`: provider interface comparison.
- `docs/2026-10-19-LLM网关管理后台视觉改造.md`: current admin UI design direction and verification notes.
- Real call logs and channel health data exist in the local database; no customer testimonials, public benchmarks, or pricing evidence exist. Do not fabricate them.

## Product Principles

1. **Show the request before sending it.** A configuration change is not trustworthy until the operator can see the final upstream JSON, target URL, and the transformations applied.
2. **Model capability is model-level; channel binding is route-level.** Parameter Schemas belong to the model; routes only bind a model to a channel, upstream model name, priority, weight, retries, and fallbacks.
3. **One contract, many providers.** Normalize request/response differences at the gateway; do not leak provider-specific fields into business clients.
4. **Configuration is code-reviewed infrastructure.** The UI, API, and YAML must describe the same state; exports must not contain secrets.
5. **Operator clarity beats decoration.** In the admin console, scanability, explicit states, safe defaults, and recoverable errors outrank visual expression.

## Accessibility & Inclusion

- Web admin UI, Chinese-language operators.
- Target WCAG AA for contrast, keyboard focus, labels, and error visibility in the admin UI; the current UI has been checked with automated design/contrast tooling, but no formal WCAG audit or assistive-technology test has been completed.
- Mobile web must remain usable for status/log inspection and basic configuration review.
