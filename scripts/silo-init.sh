#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ENV_FILE:=infra/.env}"
set -a
source "$ENV_FILE"
set +a
: "${S3_ACCESS_KEY:?}" "${S3_SECRET_KEY:?}"
# Credentials go through stdin, never command-line arguments or logs.
python3 - <<'PY' | docker compose --env-file "$ENV_FILE" -f infra/compose.yaml exec -T silo sh
import os, shlex
q=shlex.quote
print('set -eu')
print('mc alias set local http://localhost:9000 '+q(os.environ['SILO_ROOT_USER'])+' '+q(os.environ['SILO_ROOT_PASSWORD'])+' >/dev/null')
print('mc mb --ignore-existing local/agenticvideo >/dev/null')
print('mc admin user add local '+q(os.environ['S3_ACCESS_KEY'])+' '+q(os.environ['S3_SECRET_KEY'])+' >/dev/null')
print("cat > /tmp/agenticvideo-policy.json <<'POLICY'")
print('{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket","s3:ListBucketMultipartUploads"],"Resource":["arn:aws:s3:::agenticvideo"]},{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["arn:aws:s3:::agenticvideo/*"]}]}')
print('POLICY')
print('mc admin policy create local agenticvideo /tmp/agenticvideo-policy.json >/dev/null')
print('mc admin policy attach local agenticvideo --user '+q(os.environ['S3_ACCESS_KEY'])+' >/dev/null')
print('rm /tmp/agenticvideo-policy.json')
PY
