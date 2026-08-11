# MCP And Multimodal

## MCP

Use Spring AI MCP starters and Java annotations when an application consumes or exposes MCP servers. Verify the exact 2.0.0 client/server APIs and transport configuration. Supported transport families include STDIO, SSE, and Streamable HTTP.

The official 2.0.0 overview identifies MCP Boot Starters and MCP Java Annotations as the Spring integration points. The MCP client handles protocol/capability negotiation, tool discovery and execution, resources, prompts, and transport; the server exposes tools/resources/prompts. Choose the starter page matching the role and transport instead of guessing one artifact.

MCP implementation checklist:

1. Select client or server starter and transport.
2. Declare only the tools/resources/prompts required by the application.
3. Add connection authentication, timeouts, tool allow-lists, and audit logging.
4. Adapt MCP tool callbacks to the same authorization and idempotency rules as local tools.

Treat remote MCP tools as external capabilities: authenticate connections, restrict tool exposure, validate arguments/results, apply timeouts, and audit calls. Do not assume an MCP server is trusted because its schema is typed.

## Multimodal model APIs

Use the provider-neutral abstractions where available for text-to-image, audio transcription, text-to-speech, and moderation. Keep provider-specific limits, MIME types, size limits, and streaming support explicit. Store media outside prompts when possible and enforce content/security policies before model calls.
