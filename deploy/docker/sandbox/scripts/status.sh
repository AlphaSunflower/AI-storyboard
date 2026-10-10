#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

env_file="${ENV_FILE:-.env}"
docker compose --env-file "$env_file" ps
docker compose --env-file "$env_file" logs --tail=50 sandbox-runner sandbox-egress-proxy
