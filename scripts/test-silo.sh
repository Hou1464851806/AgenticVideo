#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ENV_FILE:=.runtime/test.env}"
set -a
source "$ENV_FILE"
set +a
export S3_ACCESS_KEY="$SILO_ROOT_USER" S3_SECRET_KEY="$SILO_ROOT_PASSWORD"
export S3_ENDPOINT=http://localhost:19000 SILO_CONTRACT=true
export SILO_BROWSER_FIXTURE="$PWD/.runtime/silo-browser.json"
./backend/mvnw -B -ntp -f backend/pom.xml test -Dtest=StorageContractTest,BrowserStorageFixtureTest
E2E_BASE_URL="http://localhost:${FRONTEND_PORT:-5173}" pnpm --dir frontend test:e2e
# Only the fixed isolated browser-contract bucket is owned by this test.
python3 - <<'PY' | docker compose --env-file "$ENV_FILE" -f infra/compose.yaml -p agenticvideo-m1-test exec -T silo sh
import os, shlex
q=shlex.quote
print('set -eu')
print('mc alias set contract http://localhost:9000 '+q(os.environ['SILO_ROOT_USER'])+' '+q(os.environ['SILO_ROOT_PASSWORD'])+' >/dev/null')
print('mc rm --incomplete --recursive --force contract/m1-browser-contract >/dev/null')
print('mc rm --recursive --force contract/m1-browser-contract >/dev/null')
print('mc rb contract/m1-browser-contract >/dev/null')
PY
rm "$SILO_BROWSER_FIXTURE"
