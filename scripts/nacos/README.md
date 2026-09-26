# Nacos asset pipeline

`publish_assets.py` builds deterministic Agent Skill ZIPs, validates the global
system prompt and MCP metadata, and publishes only when explicitly invoked.

Run local validation without contacting Nacos:

```powershell
python scripts/nacos/publish_assets.py verify --offline
```

Verify an already-published namespace and label:

```powershell
$env:NACOS_ADDR = "localhost:8848"
python scripts/nacos/publish_assets.py verify --namespace public --label stable
```

Publish an approved asset version:

```powershell
python scripts/nacos/publish_assets.py publish --namespace public --label stable
```

Authentication is read from `NACOS_ACCESS_TOKEN`, or from `NACOS_USERNAME` and
`NACOS_PASSWORD`. Credentials are never written to the manifest or command
output.

MCP endpoint placeholders are resolved only during publish or online verify:

- `MCP_GATEWAY_ADDRESS`
- `MCP_GATEWAY_PORT`

Canvas Bridge is intentionally absent from the shared asset manifest.
