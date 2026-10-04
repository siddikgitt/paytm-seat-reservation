#!/usr/bin/env bash
set -euo pipefail

export PORT="${PORT:-8080}"
export APP_PORT="${APP_PORT:-18080}"

java -jar /app/app.jar --server.port="$APP_PORT" --server.address=127.0.0.1 &
app_pid=$!
haproxy -W -db -f /app/haproxy.cfg &
proxy_pid=$!

stop() {
    trap - EXIT TERM INT
    kill -TERM "$proxy_pid" "$app_pid" 2>/dev/null || true
    wait "$proxy_pid" "$app_pid" 2>/dev/null || true
}
trap stop EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

# If either process exits, terminate its sibling; never leave a live proxy
# masking a dead application (or an unreachable Java process running alone).
wait -n "$app_pid" "$proxy_pid"
