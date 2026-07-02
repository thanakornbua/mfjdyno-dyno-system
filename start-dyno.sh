#!/usr/bin/env bash
#
# Start the full dyno system for development/shop use:
#   1. dynod backend (Rust) — WebSocket :9000 + HTTP API :9001
#   2. JavaFX operator console (via Gradle)
#
# Usage:
#   ./start-dyno.sh                # live mode (real ESP32 hardware)
#   ./start-dyno.sh --replay       # replay mode (synthetic data, no hardware)
#   ./start-dyno.sh --replay --dev # replay + dev API (enables /api/dev/seed-run)
#   ./start-dyno.sh --fullscreen   # fullscreen console
#   ./start-dyno.sh --backend-only # start dynod only, no UI
#
# Ctrl+C (or closing the console window) stops everything.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SOURCE_MODE="live"
DEV_API="false"
UI_TASK="runOperatorConsoleFxWindowed"
START_UI="true"

for arg in "$@"; do
  case "$arg" in
    --replay)       SOURCE_MODE="replay" ;;
    --live)         SOURCE_MODE="live" ;;
    --dev)          DEV_API="true" ;;
    --fullscreen)   UI_TASK="runOperatorConsoleFxFullscreen" ;;
    --backend-only) START_UI="false" ;;
    -h|--help)      sed -n '3,15p' "$0"; exit 0 ;;
    *) echo "start-dyno: unknown option '$arg' (try --help)" >&2; exit 1 ;;
  esac
done

export DYNO_SOURCE_MODE="${DYNO_SOURCE_MODE:-$SOURCE_MODE}"
export DYNO_ENABLE_DEV_API="${DYNO_ENABLE_DEV_API:-$DEV_API}"
export DYNO_DB_PATH="${DYNO_DB_PATH:-$ROOT/dyno.db}"
export DYNO_WS_BIND="${DYNO_WS_BIND:-127.0.0.1:9000}"
export DYNO_API_BIND="${DYNO_API_BIND:-127.0.0.1:9001}"
export DYNO_UI_API_BASE_URL="${DYNO_UI_API_BASE_URL:-http://127.0.0.1:9001}"
export DYNO_UI_WS_URI="${DYNO_UI_WS_URI:-ws://127.0.0.1:9000}"

API_HOST_PORT="${DYNO_API_BIND/0.0.0.0/127.0.0.1}"

BACKEND_PID=""
cleanup() {
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "start-dyno: stopping backend (pid $BACKEND_PID)"
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "start-dyno: building backend..."
cargo build --release -p dyno-core --manifest-path "$ROOT/Cargo.toml"

echo "start-dyno: starting dynod (mode=$DYNO_SOURCE_MODE, db=$DYNO_DB_PATH, api=$DYNO_API_BIND, ws=$DYNO_WS_BIND)"
"$ROOT/target/release/dynod" &
BACKEND_PID=$!

echo "start-dyno: waiting for backend health..."
for i in $(seq 1 30); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "start-dyno: backend exited during startup" >&2
    exit 1
  fi
  if curl -sf "http://${API_HOST_PORT}/healthz" >/dev/null 2>&1; then
    echo "start-dyno: backend is up (http://${API_HOST_PORT})"
    break
  fi
  if [[ "$i" -eq 30 ]]; then
    echo "start-dyno: backend did not become healthy within 30s" >&2
    exit 1
  fi
  sleep 1
done

if [[ "$START_UI" != "true" ]]; then
  echo "start-dyno: backend-only mode; press Ctrl+C to stop."
  wait "$BACKEND_PID"
  exit 0
fi

echo "start-dyno: launching operator console ($UI_TASK)..."
cd "$ROOT/java"
gradle "$UI_TASK"

# UI closed normally; cleanup trap stops the backend.
echo "start-dyno: operator console closed."
