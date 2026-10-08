#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${COMPOSE_PROJECT_NAME:=agenticvideo-m1-test}"
case "$COMPOSE_PROJECT_NAME" in agenticvideo-m1-*) ;; *) echo 'Use a dedicated agenticvideo-m1-* test project' >&2; exit 1;; esac
export COMPOSE_PROJECT_NAME
: "${ENV_FILE:=.runtime/m1-ci.env}"
mkdir -p .runtime
if [[ ! -f "$ENV_FILE" ]]; then
  ENV_FILE="$ENV_FILE" python3 - <<'PY'
import os, secrets
from pathlib import Path
p=Path(os.environ['ENV_FILE'])
if p.parent != Path('.runtime'):
    raise SystemExit('Generated test configuration must be inside .runtime')
p.write_text('\n'.join(k+'='+v for k,v in {
 'DATABASE_PASSWORD':secrets.token_hex(24),'SILO_ROOT_USER':'m1admin',
 'SILO_ROOT_PASSWORD':secrets.token_hex(24),'S3_ACCESS_KEY':'m1application',
 'S3_SECRET_KEY':secrets.token_hex(24)}.items())+'\n')
p.chmod(0o600)
PY
fi
set -a
source "$ENV_FILE"
set +a
compose=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT_NAME" -f infra/compose.yaml)
"${compose[@]}" config --quiet
"${compose[@]}" up -d --build --wait
export DATABASE_URL="jdbc:postgresql://localhost:${DATABASE_PORT:-54328}/agenticvideo"
export S3_ENDPOINT="http://localhost:${SILO_PORT:-19000}" S3_PUBLIC_ENDPOINT="http://localhost:${SILO_PORT:-19000}"
export PG_INTEGRATION=true M1_INTEGRATION=true SILO_APP_CONTRACT=true M1_CLEANUP_INTEGRATION=true M1_COMMIT_INTEGRATION=true
"${compose[@]}" run --rm --no-deps --entrypoint /bin/bash -u "$(id -u):$(id -g)" -v "${PWD}:/workspace" -w /workspace worker scripts/media-fixtures.sh
backend/mvnw -B -ntp -f backend/pom.xml clean verify
M1_E2E=true E2E_BASE_URL="http://localhost:${FRONTEND_PORT:-5173}" pnpm --dir frontend exec playwright test uploads.spec.ts workspace.spec.ts
python3 scripts/verify-docs.py
git diff --check
printf 'M1 integration checks passed; dedicated test volumes retained.\n'
