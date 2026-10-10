#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

env_file="${ENV_FILE:-.env}"
repo_root="$(cd ../../.. && pwd)"
jar_path="${MOON_SANDBOX_JAR:-$repo_root/MoonSandbox/target/moon-sandbox.jar}"

if [ ! -f "$jar_path" ]; then
    echo "missing MoonSandbox jar: $jar_path" >&2
    echo "build it first with: mvn -pl MoonSandbox -am package -DskipTests" >&2
    exit 1
fi

docker compose --env-file "$env_file" config -q
docker compose --env-file "$env_file" up -d --build
docker compose --env-file "$env_file" ps
