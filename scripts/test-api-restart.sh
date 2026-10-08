#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ENV_FILE:=.runtime/m1-ci.env}"
: "${COMPOSE_PROJECT_NAME:=agenticvideo-m1-test}"
case "$COMPOSE_PROJECT_NAME" in agenticvideo-m1-*) ;; *) echo 'Use an isolated M1 test project' >&2; exit 1;; esac
compose=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT_NAME" -f infra/compose.yaml)
api=$("${compose[@]}" ps -q api)
frontend=$("${compose[@]}" ps -q frontend)
[[ -n "$api" && -n "$frontend" ]]
network=$(docker inspect --format '{{range $name,$settings := .NetworkSettings.Networks}}{{$name}}{{end}}' "$api")
old_ip=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$api")
image=$(docker inspect --format '{{.Config.Image}}' "$frontend")
blocker="$COMPOSE_PROJECT_NAME-dns-regression"
restore() {
  docker rm -f "$blocker" >/dev/null 2>&1 || true
  "${compose[@]}" up -d --no-deps --wait api >/dev/null
}
trap restore EXIT
# Only the generated, stateless test API is recreated; no database/storage volumes are removed.
"${compose[@]}" stop api
"${compose[@]}" rm -f api
# Occupy its old address so this test cannot accidentally pass with the same IP.
docker run -d --name "$blocker" --network "$network" --ip "$old_ip" --entrypoint sh "$image" -c 'sleep 120' >/dev/null
"${compose[@]}" up -d --no-deps --wait api
new_api=$("${compose[@]}" ps -q api)
new_ip=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$new_api")
[[ "$new_ip" != "$old_ip" && "$("${compose[@]}" ps -q frontend)" == "$frontend" ]]
for attempt in {1..15}; do
  if docker exec "$frontend" wget -q -O /dev/null http://127.0.0.1/api/v1/health; then
    echo 'Frontend recovered after API address changed; frontend was not restarted.'
    exit 0
  fi
  sleep 1
done
echo 'Frontend failed to resolve the recreated API' >&2
exit 1
