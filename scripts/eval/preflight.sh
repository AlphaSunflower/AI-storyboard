#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ "${1:-}" != "--skip-unit" ]]; then
  echo "==> MoonAgent tests"
  mvn -pl MoonAgent -am test
  echo "==> Frontend tests + build"
  (
    cd AIStoryboardClient
    npm test -- --run
    npm run build
  )
fi

if [[ -z "${MOON_EMAIL:-}" || -z "${MOON_PASSWORD:-}" || -z "${MOON_PROJECT_ID:-}" ]]; then
  echo "SKIP eval: 需要 MOON_EMAIL / MOON_PASSWORD / MOON_PROJECT_ID"
  exit 0
fi

echo "==> MoonAgent real-turn eval"
python3 scripts/eval/run_eval.py --cases scripts/eval/cases.jsonl
