#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# The fault fixtures intentionally name this isolated project in their ownership assertions.
: "${ENV_FILE:=.runtime/empty.env}"
mkdir -p .runtime
if [[ ! -f "$ENV_FILE" ]]; then
  ENV_FILE="$ENV_FILE" python3 - <<'PY'
import os, secrets
from pathlib import Path
p=Path(os.environ['ENV_FILE'])
if p.parent != Path('.runtime'):
    raise SystemExit('Generated fault configuration must be inside .runtime')
p.write_text('\n'.join(k+'='+v for k,v in {
 'DATABASE_PASSWORD':secrets.token_hex(24),'SILO_ROOT_USER':'m1faultadmin',
 'SILO_ROOT_PASSWORD':secrets.token_hex(24),'S3_ACCESS_KEY':'m1faultapplication',
 'S3_SECRET_KEY':secrets.token_hex(24),'DATABASE_PORT':'54329','SILO_PORT':'19002',
 'SILO_CONSOLE_PORT':'19003','API_PORT':'8081','FRONTEND_PORT':'5174',
 'BROWSER_ORIGIN':'http://localhost:5174'}.items())+'\n')
p.chmod(0o600)
PY
fi
# Java fault fixtures use this exact path. Reject overrides rather than touching unrelated stacks.
[[ "$ENV_FILE" == .runtime/empty.env ]] || { echo 'Fault fixtures require .runtime/empty.env' >&2; exit 1; }
set -a
source "$ENV_FILE"
set +a
compose=(docker compose --env-file "$ENV_FILE" -p agenticvideo-m1-empty -f infra/compose.yaml)
"${compose[@]}" config --quiet
"${compose[@]}" up -d --build --wait
export DATABASE_URL="jdbc:postgresql://localhost:${DATABASE_PORT:-54329}/agenticvideo"
export S3_ENDPOINT="http://localhost:${SILO_PORT:-19002}" S3_PUBLIC_ENDPOINT="http://localhost:${SILO_PORT:-19002}"
"${compose[@]}" run --rm --no-deps --entrypoint /bin/bash -u "$(id -u):$(id -g)" -v "${PWD}:/workspace" -w /workspace worker scripts/media-fixtures.sh
export M1_FAULT_INTEGRATION=true M1_SERVICE_FAULT_INTEGRATION=true M1_RESOURCE_FAULT_INTEGRATION=true M1_MEDIA_BOUNDARY_INTEGRATION=true
backend/mvnw -B -ntp -f backend/pom.xml verify -Dtest=WorkerFaultIntegrationTest,ServiceFaultIntegrationTest,ResourceFaultIntegrationTest,MediaBoundaryIntegrationTest
restore_worker() {
  unset RENDER_TIMEOUT_SECONDS
  "${compose[@]}" up -d --wait worker
}
trap restore_worker EXIT
RENDER_TIMEOUT_SECONDS=2 "${compose[@]}" up -d --wait worker
M1_TIMEOUT_INTEGRATION=true backend/mvnw -B -ntp -f backend/pom.xml verify -Dtest=RenderTimeoutIntegrationTest
printf 'Isolated fault checks passed; services restored and test volumes retained.\n'
