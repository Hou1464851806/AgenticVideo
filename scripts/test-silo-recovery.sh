#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
: "${ENV_FILE:=.runtime/test.env}" "${COMPOSE_PROJECT_NAME:=agenticvideo-m1-test}"
case "$COMPOSE_PROJECT_NAME" in agenticvideo-m1-*) ;; *) echo 'Use a dedicated test project' >&2; exit 1;; esac
set -a
source "$ENV_FILE"
set +a
compose=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT_NAME" -f infra/compose.yaml)
original=$("${compose[@]}" ps -q silo)
[[ -n "$original" ]] || { echo 'Test Silo must be running' >&2; exit 1; }
volume="${COMPOSE_PROJECT_NAME}_silo"
[[ $(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "$volume") == "$COMPOSE_PROJECT_NAME" ]]
bytes=$(docker exec "$original" du -sb /data | cut -f1)
[[ "$bytes" -le 134217728 ]] || { echo 'Recovery fixture exceeds the 128 MiB test budget' >&2; exit 1; }
id=$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')
restored="${COMPOSE_PROJECT_NAME}-restore-${id}"
restore_volume="${restored}-data"
image=$(docker inspect --format '{{.Config.Image}}' "$original")
tool_image=$(docker inspect --format '{{.Image}}' "$("${compose[@]}" ps -q api)")
mkdir -p .runtime
snapshot=".runtime/silo-${id}.tar"
config=".runtime/silo-${id}.env"
export RECOVERY_CONFIG="$config"
python3 - <<'PY'
import os
from pathlib import Path
Path(os.environ['RECOVERY_CONFIG']).write_text('MINIO_ROOT_USER='+os.environ['SILO_ROOT_USER']+'\nMINIO_ROOT_PASSWORD='+os.environ['SILO_ROOT_PASSWORD']+'\n')
PY
volume_created=false
container_created=false
finish() {
  if "$container_created"; then docker rm -f "$restored" >/dev/null; fi
  if "$volume_created"; then docker volume rm "$restore_volume" >/dev/null; fi
  rm -f "$config"
  "${compose[@]}" start silo api worker >/dev/null
}
trap finish EXIT
inventory() {
  python3 - <<'PY' | docker exec -i "$1" sh > "$2"
import os, shlex
q=shlex.quote
print('set -eu')
print('mc alias set recovery http://localhost:9000 '+q(os.environ['S3_ACCESS_KEY'])+' '+q(os.environ['S3_SECRET_KEY'])+' >/dev/null')
print('mc ls --recursive --json recovery/agenticvideo')
print('mc alias rm recovery >/dev/null')
PY
}
content_hashes() {
  RECOVERY_INVENTORY="$2" RECOVERY_ID="$id" python3 - <<'HASH' | docker exec -i "$1" sh > "$3"
import json, os, shlex
from pathlib import Path
q=shlex.quote
rows=[json.loads(line) for line in Path(os.environ['RECOVERY_INVENTORY']).read_text().splitlines()]
keys=sorted(r['key'] for r in rows if r.get('type')=='file')
temporary='/tmp/m1-recovery-'+os.environ['RECOVERY_ID']
print('set -eu')
print('mc alias set recovery http://localhost:9000 '+q(os.environ['S3_ACCESS_KEY'])+' '+q(os.environ['S3_SECRET_KEY'])+' >/dev/null')
for key in keys:
    print('mc cat '+q('recovery/agenticvideo/'+key)+' > '+q(temporary))
    print('sha256sum '+q(temporary))
print('rm -f '+q(temporary))
print('mc alias rm recovery >/dev/null')
HASH
}
"${compose[@]}" stop api worker >/dev/null
inventory "$original" ".runtime/silo-${id}-original.jsonl"
content_hashes "$original" ".runtime/silo-${id}-original.jsonl" ".runtime/silo-${id}-original.hashes"
"${compose[@]}" stop silo >/dev/null
docker run --rm --user 0 --entrypoint tar -v "${volume}:/data:ro" "$tool_image" -C /data -cf - . > "$snapshot"
docker volume create "$restore_volume" >/dev/null
volume_created=true
docker run --rm -i --user 0 --entrypoint tar -v "${restore_volume}:/data" "$tool_image" -C /data -xf - < "$snapshot"
docker run -d --name "$restored" --env-file "$config" -v "${restore_volume}:/data" "$image" server /data >/dev/null
container_created=true
ready=false
for attempt in {1..30}; do
  if docker exec "$restored" curl --fail --silent http://localhost:9000/minio/health/ready >/dev/null; then ready=true; break; fi
  sleep 1
done
"$ready" || { echo 'Restored Silo did not become ready' >&2; exit 1; }
inventory "$restored" ".runtime/silo-${id}-restored.jsonl"
content_hashes "$restored" ".runtime/silo-${id}-restored.jsonl" ".runtime/silo-${id}-restored.hashes"
RECOVERY_ID="$id" python3 - <<'PY'
import json, os
from pathlib import Path
base=Path('.runtime')
def entries(kind):
    rows=[json.loads(line) for line in (base/('silo-'+os.environ['RECOVERY_ID']+'-'+kind+'.jsonl')).read_text().splitlines()]
    return sorted((r['key'],r['size'],r.get('etag')) for r in rows if r.get('type')=='file')
a,b=entries('original'),entries('restored')
assert a and a==b, 'Restored private object inventory differs'
original_hashes=(base/('silo-'+os.environ['RECOVERY_ID']+'-original.hashes')).read_text().splitlines()
restored_hashes=(base/('silo-'+os.environ['RECOVERY_ID']+'-restored.hashes')).read_text().splitlines()
assert len(original_hashes)==len(a) and original_hashes==restored_hashes, 'Restored object content differs'
print('Silo restored to a new volume; application identity reads matching object keys, sizes, ETags and content SHA-256: '+str(len(a))+' objects')
PY
