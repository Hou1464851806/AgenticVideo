#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
: "${ENV_FILE:=.runtime/test.env}" "${COMPOSE_PROJECT_NAME:=agenticvideo-m1-test}"
case "$COMPOSE_PROJECT_NAME" in agenticvideo-m1-*) ;; *) echo 'Recovery checks require a dedicated test project' >&2; exit 1;; esac
compose=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT_NAME" -f infra/compose.yaml)
database="agenticvideo_m1_restore_$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
mkdir -p .runtime
snapshot=".runtime/${database}.dump"
created=false
finish() {
  if "$created"; then "${compose[@]}" exec -T postgres dropdb -U agenticvideo "$database"; fi
  "${compose[@]}" start api worker >/dev/null
}
trap finish EXIT
"${compose[@]}" stop api worker >/dev/null
"${compose[@]}" exec -T postgres pg_dump -U agenticvideo -d agenticvideo -Fc -n business_schema > "$snapshot"
"${compose[@]}" exec -T postgres createdb -U agenticvideo "$database"
created=true
"${compose[@]}" exec -T postgres pg_restore --exit-on-error -U agenticvideo -d "$database" < "$snapshot"
query="SET search_path TO business_schema; SELECT json_build_object('projects',(SELECT count(*) FROM projects),'assets',(SELECT count(*) FROM assets),'revisions',(SELECT count(*) FROM edit_plan_revisions),'plans',(SELECT md5(coalesce(string_agg(plan_hash,',' ORDER BY plan_id,revision),'')) FROM edit_plan_revisions),'outputs',(SELECT md5(coalesce(string_agg(output_hash,',' ORDER BY id),'')) FROM render_jobs));"
original=$("${compose[@]}" exec -T postgres psql -qAt -U agenticvideo -d agenticvideo -c "$query")
restored=$("${compose[@]}" exec -T postgres psql -qAt -U agenticvideo -d "$database" -c "$query")
[[ "$original" == "$restored" ]] || { echo 'Restored records differ' >&2; exit 1; }
printf 'PostgreSQL snapshot restored with matching project, asset, revision and output records.\n'
# Dump contains test password hashes and sessions; retain locally with mode 0600, never publish it.
