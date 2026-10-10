#!/usr/bin/env bash
# Static validation for the sandbox Compose project. This does not build or
# start containers.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null 2>&1; then
    echo "docker CLI is required" >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose v2 is required" >&2
    exit 1
fi

compose_json="$(docker compose --env-file .env.example config --format json)"

python_bin="${PYTHON_BIN:-}"
if [ -z "$python_bin" ] && command -v python3 >/dev/null 2>&1; then
    python_bin=python3
elif [ -z "$python_bin" ] && command -v python >/dev/null 2>&1; then
    python_bin=python
fi
if [ -z "$python_bin" ]; then
    echo "python3 or python is required to inspect rendered Compose JSON" >&2
    exit 1
fi

printf '%s' "$compose_json" | "$python_bin" -c '
import json
import sys

config = json.load(sys.stdin)
services = config["services"]
networks = config["networks"]
volumes = config["volumes"]
runner = services["sandbox-runner"]
proxy = services["sandbox-egress-proxy"]


def network_names(service):
    value = service.get("networks", [])
    return set(value.keys() if isinstance(value, dict) else value)


def env_value(service, key):
    env = service.get("environment", {})
    if isinstance(env, list):
        return next((item.split("=", 1)[1] for item in env
                     if item.startswith(key + "=")), None)
    return env.get(key)


def positive_number(value):
    if isinstance(value, (int, float)):
        return value > 0
    try:
        return float(str(value).strip()) > 0
    except (TypeError, ValueError):
        return False


assert network_names(runner) == {"sandbox-internal", "sandbox-edge"}, "runner must bridge internal and host networks"
assert network_names(proxy) == {"sandbox-internal", "sandbox-egress"}, "proxy must bridge internal and external networks"
assert networks["sandbox-internal"]["internal"] is True, "sandbox-internal must be internal"
assert network_names(runner), "runner networks must be configured"
assert runner.get("ports"), "runner must publish its API port"
assert runner["read_only"] is True, "runner rootfs must be read-only"
assert runner["user"] == "10001:10001", "runner must run as the non-root 10001 identity"
assert "/workspaces" in [mount["target"] for mount in runner.get("volumes", [])], "runner must mount /workspaces"
assert positive_number(runner.get("pids_limit")), "runner must set pids_limit"
assert positive_number(runner.get("mem_limit")), "runner must set mem_limit"
assert positive_number(runner.get("cpus")), "runner must set cpus"
assert runner.get("restart") == "unless-stopped", "runner must use restart policy unless-stopped"
assert runner.get("healthcheck"), "runner must define a healthcheck"
assert any(item.split(":", 1)[0] == "/tmp" for item in runner.get("tmpfs", [])), "runner must provide tmpfs /tmp"
assert "sandbox-workspaces" in volumes, "workspaces named volume must exist"
assert env_value(proxy, "SANDBOX_ALLOWED_DOMAINS"), "proxy allowlist environment variable must be configured"
assert env_value(runner, "HTTP_PROXY") == "http://sandbox-egress-proxy:3128", "runner must use the egress proxy"
assert env_value(runner, "HTTPS_PROXY") == "http://sandbox-egress-proxy:3128", "runner must use the egress proxy"
print("compose static checks passed")
'
