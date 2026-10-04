#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v javac >/dev/null 2>&1 && javac -version 2>&1 | awk '{split($2,v,"."); exit !(v[1]>=21)}'; then
  OUTPUT="$(mktemp -d)"
  trap 'rm -rf "$OUTPUT"' EXIT
  javac -d "$OUTPUT" "$DIR/scripts/Burst.java" "$DIR/scripts/BurstChecksTest.java"
  STRICT=false java -cp "$OUTPUT" BurstChecksTest
  STRICT=true CONCURRENCY=1 STAMPEDE=1 RETRIES_429=9 java -cp "$OUTPUT" BurstChecksTest
else
  docker run --rm -v "$DIR/scripts:/scripts:ro" eclipse-temurin:21-jdk sh -c \
    'javac -d /tmp /scripts/Burst.java /scripts/BurstChecksTest.java && STRICT=false java -cp /tmp BurstChecksTest && STRICT=true CONCURRENCY=1 STAMPEDE=1 RETRIES_429=9 java -cp /tmp BurstChecksTest'
fi
