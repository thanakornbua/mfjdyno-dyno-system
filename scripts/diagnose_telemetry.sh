#!/usr/bin/env bash
# Diagnose why live telemetry (ESP32 -> serial -> fusion -> websocket) shows
# stale/null values (LiveFrame::idle() default: ts_ms=0, engine_rpm=null, run_state=idle).
#
# Run on the production host as any user with sudo/journalctl access:
#   sudo bash scripts/diagnose_telemetry.sh
#
# Safe: read-only. Does not restart services or touch config.

set -uo pipefail

HEALTH_URL="${DYNO_HEALTH_URL:-http://127.0.0.1:8080/healthz}"
RUN_STATUS_URL="${DYNO_RUN_STATUS_URL:-http://127.0.0.1:8080/api/run/status}"

hr() { printf '\n=== %s ===\n' "$1"; }

hr "1. dynod service status"
systemctl status dynod.service --no-pager -l 2>&1 || echo "systemctl status failed (not running / no permission?)"

hr "2. dynod effective environment (source mode, serial port/baud)"
if [ -r /etc/dyno/dyno.env ]; then
  echo "--- /etc/dyno/dyno.env ---"
  grep -E 'DYNO_SOURCE_MODE|DYNO_SERIAL_PORT|DYNO_SERIAL_BAUD|RUST_LOG' /etc/dyno/dyno.env || echo "(none of the expected keys set)"
else
  echo "/etc/dyno/dyno.env not readable or missing"
fi
if [ -r /etc/dyno/dynod.env ]; then
  echo "--- /etc/dyno/dynod.env ---"
  grep -E 'DYNO_SOURCE_MODE|DYNO_SERIAL_PORT|DYNO_SERIAL_BAUD|RUST_LOG' /etc/dyno/dynod.env || echo "(none of the expected keys set)"
else
  echo "/etc/dyno/dynod.env not readable or missing"
fi
echo "--- Actual env of running dynod process (source of truth; storage-persisted"
echo "    serial-port override can shadow the env file, so this may still differ) ---"
DYNOD_PID="$(pgrep -x dynod | head -1 || true)"
if [ -n "$DYNOD_PID" ]; then
  tr '\0' '\n' < "/proc/$DYNOD_PID/environ" 2>/dev/null | grep -E '^DYNO_|^RUST_LOG' || echo "(couldn't read /proc/$DYNOD_PID/environ — try sudo)"
else
  echo "dynod process not found running"
fi

hr "3. Serial device presence"
ls -l /dev/ttyUSB* /dev/ttyACM* 2>&1 || true
echo "--- Who holds the port open (needs lsof) ---"
command -v lsof >/dev/null && lsof /dev/ttyUSB0 /dev/ttyACM0 2>&1 || echo "lsof not installed, skipping"
echo "--- dmesg tail for USB serial attach/detach events ---"
dmesg 2>&1 | grep -iE 'ttyUSB|ttyACM|cp210x|ch341|ftdi' | tail -20 || echo "(no dmesg access or no matches)"

hr "4. Health endpoint ($HEALTH_URL)"
curl -sS -m 5 "$HEALTH_URL" | (command -v jq >/dev/null && jq . || cat) || echo "curl to healthz failed"

hr "5. Run status endpoint ($RUN_STATUS_URL)"
curl -sS -m 5 "$RUN_STATUS_URL" | (command -v jq >/dev/null && jq . || cat) || echo "curl to run/status failed"

hr "6. Fusion/serial log lines (last 15 min via journalctl)"
echo "--- fusion task spawned / produced live frame ---"
journalctl -u dynod --since "15 min ago" --no-pager 2>&1 | grep -iE 'fusion task spawned|fusion: produced live frame' | tail -20 || echo "no journalctl access or no matches"
echo "--- serial errors/malformed lines ---"
journalctl -u dynod --since "15 min ago" --no-pager 2>&1 | grep -iE 'serial:|skipping malformed|MAX_CONSECUTIVE_FAILURES|failed to open' | tail -20 || echo "no matches"
echo "--- api listening line (confirms bind addr) ---"
journalctl -u dynod --since "15 min ago" --no-pager 2>&1 | grep -iE 'api: listening on' | tail -5 || echo "no matches"

hr "7. Live websocket sample (5s, requires python3 + websockets pkg)"
python3 - <<'PYEOF' 2>&1
import asyncio, sys
try:
    import websockets
except ImportError:
    print("python 'websockets' package not installed, skipping (pip install websockets)")
    sys.exit(0)

async def main():
    uri = "ws://127.0.0.1:9000"
    try:
        async with websockets.connect(uri, open_timeout=5) as ws:
            for _ in range(3):
                msg = await asyncio.wait_for(ws.recv(), timeout=5)
                print(msg)
    except Exception as e:
        print(f"websocket connect/recv failed: {e}")

asyncio.run(main())
PYEOF

hr "Done"
echo "Send the full output back. Key things to look at:"
echo "  - section 2: is DYNO_SOURCE_MODE actually 'live' in the RUNNING process env (not just the env file)?"
echo "  - section 3: does /dev/ttyUSB0 (or ACM0) exist, and is something else already holding it open?"
echo "  - section 6: does 'fusion task spawned' appear but 'produced live frame' never does -> confirms fusion never got real data"
echo "  - section 6: any 'serial: skipping malformed' or 'failed to open' lines"
echo "  - section 7: does the raw ws sample match what you already saw, or does it show real values?"
