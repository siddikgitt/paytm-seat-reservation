#!/usr/bin/env bash
# One-command on-sale stampede: ./burst.sh <BASE_URL>
# Uses a local JDK 21+ if present, otherwise runs the same single-file program inside a JDK container.
# Tunables (env): ADMIN_KEY CONCURRENCY STAMPEDE HOT_USERS USERS ROWS COLS RETRY_PCT
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_OPTS="-Xss512k -XX:+UseParallelGC"

java_major() {
  java -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]}'
}

if command -v java >/dev/null 2>&1 && [ "$(java_major)" -ge 21 ] 2>/dev/null; then
  exec java $JAVA_OPTS "$DIR/scripts/Burst.java" "$BASE_URL"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "need either JDK 21+ or docker to run the burst" >&2
  exit 2
fi

# Inside a container, "localhost" is the container itself; point it at the docker host instead.
TARGET="$(echo "$BASE_URL" | sed -E 's#//(localhost|127\.0\.0\.1)([:/]|$)#//host.docker.internal\2#')"
exec docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -e ADMIN_KEY -e CONCURRENCY -e STAMPEDE -e HOT_USERS -e USERS -e ROWS -e COLS -e RETRY_PCT \
  -v "$DIR/scripts:/scripts:ro" \
  eclipse-temurin:21-jdk \
  java $JAVA_OPTS /scripts/Burst.java "$TARGET"
