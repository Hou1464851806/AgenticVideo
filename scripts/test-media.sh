#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .runtime
./backend/mvnw -B -ntp -f backend/pom.xml verify dependency:build-classpath -DincludeScope=test -Dmdep.outputFile="$PWD/.runtime/test-classpath"
docker build --target media-runtime -t agenticvideo-m1-media -f backend/Dockerfile .
docker run --rm --entrypoint /bin/bash -u "$(id -u):$(id -g)" -v "$PWD:/workspace" -w /workspace agenticvideo-m1-media scripts/media-fixtures.sh
docker run --rm --entrypoint java -v "$PWD:$PWD" -v "$HOME/.m2:$HOME/.m2:ro" -w "$PWD/backend" agenticvideo-m1-media -cp "$PWD/backend/target/classes:$PWD/backend/target/test-classes:$(cat .runtime/test-classpath)" com.agenticvideo.MediaExperiment "$PWD"
