#!/bin/sh
set -eu
mc alias set local http://silo:9000 "$SILO_ROOT_USER" "$SILO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing local/agenticvideo >/dev/null
mc anonymous set none local/agenticvideo >/dev/null
mc admin user add local "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null
mc admin policy create local agenticvideo /config/silo-policy.json >/dev/null
mc admin policy attach local agenticvideo --user "$S3_ACCESS_KEY" >/dev/null
printf 'Private bucket and application policy ready\n'
