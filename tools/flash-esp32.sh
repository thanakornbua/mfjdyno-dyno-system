#!/usr/bin/env sh
# Flash the ESP32 over its onboard USB port (telemetry, config sync, and
# flashing all share this one cable). Pass the port as $1, or rely on
# dynod's persisted/configured port.
#
# This goes through dynod's /api/system/flash-esp endpoint rather than
# calling arduino-cli directly: dynod's live telemetry reader normally
# holds the serial port open, so a direct `arduino-cli upload` fails with
# "port is busy" while dynod is running. The API asks dynod to release the
# port before flashing and reopen it afterward, so this always works
# without needing to stop the service by hand.
set -eu

API_BASE="${DYNO_CONTROL_API_BASE_URL:-http://127.0.0.1:9001}"
PORT="${1:-}"

if ! curl -fsS -m 5 "${API_BASE}/healthz" >/dev/null 2>&1; then
  echo "flash-esp32.sh: could not reach dynod at ${API_BASE} (is the service running?)" >&2
  exit 1
fi

if [ -n "$PORT" ]; then
  case "$PORT" in
    /dev/*) ;;
    *)
      echo "flash-esp32.sh: invalid port '$PORT' (expected /dev/*)" >&2
      exit 1
      ;;
  esac
  BODY="$(printf '{"flash_serial_port":"%s"}' "$PORT")"
else
  BODY="{}"
fi

START_RESPONSE="$(curl -fsS -m 10 -X POST "${API_BASE}/api/system/flash-esp" \
  -H 'Content-Type: application/json' \
  -d "$BODY")"
echo "flash-esp32.sh: $START_RESPONSE"

echo "flash-esp32.sh: waiting for flash to complete..."
while :; do
  STATUS_JSON="$(curl -fsS -m 5 "${API_BASE}/api/system/flash-esp/status")"
  STATE="$(printf '%s' "$STATUS_JSON" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')"
  case "$STATE" in
    success)
      echo "flash-esp32.sh: flash succeeded"
      exit 0
      ;;
    error)
      echo "flash-esp32.sh: flash failed" >&2
      echo "$STATUS_JSON" >&2
      exit 1
      ;;
    running | "")
      sleep 2
      ;;
    *)
      echo "flash-esp32.sh: unexpected status: $STATUS_JSON" >&2
      exit 1
      ;;
  esac
done
