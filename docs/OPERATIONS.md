# Dyno Operations

## File layout

- Backend binary: `/usr/local/bin/dynod`
- Backend env file: `/etc/dyno/dynod.env`
- Backend state directory: `/var/lib/dyno`
- Backend database: `/var/lib/dyno/dyno.db`
- ESP32 desired config behavior: [ESP32_CONFIG_SYNC.md](/home/thanakornb/dyno-system/docs/ESP32_CONFIG_SYNC.md)
- Operator console launcher: `/usr/local/bin/dyno-operator-console`
- Operator console install root: `/opt/dyno-operator-console`
- Operator console env file: `/etc/dyno/operator-console.env`

## First boot

`dynod` is self-provisioning: it does not depend on the directory it is launched from.

- On startup it resolves a fixed **data directory** and creates it if missing: `DYNO_DATA_DIR` if set, else `/var/lib/dyno` if usable, else `$XDG_DATA_HOME/dyno` (falling back to `~/.local/share/dyno`). The resolved path is logged as `data_dir` in the startup "Effective configuration" block.
- `DYNO_STORAGE_DB_PATH`/`DYNO_DB_PATH`, `DYNO_ESP32_CONFIG_PATH`, and `DYNO_ESP32_APPLIED_CONFIG_PATH` are anchored inside the data directory unless you set them to an explicit path yourself.
- No system password is baked in. `GET /api/system/setup-status` returns `{"password_set": false}` until an operator sets one. The JavaFX console detects this on launch (and when Machine Configuration is opened) and shows a **first-time setup wizard**.
- For unattended installs, preseed the password by setting `DYNO_SYSTEM_PASSWORD` in the environment before the very first launch of a new database; the first-boot wizard is skipped once a password already exists.
- If a second `dynod` instance is started against the same ports, it now fails fast with a clear "already running?" error instead of starting up half-alive with WS/API unbound.

### First-time setup wizard

The JavaFX console runs a four-step wizard on first boot:

1. **Create system password** — `POST /api/system/setup-password`.
2. **Select ESP device** — the operator picks, from a dropdown of detected serial ports (`GET /api/system/serial-devices`), a single port for the ESP32. Saved via `POST /api/system/devices`. **A single USB cable to the ESP32 devkit's onboard USB port carries telemetry, config sync, and firmware flashing** — the wizard submits the same path as both the read and flash port. (Both settings still exist on the backend for back-compat with older two-cable installs; see "Single-cable wiring" below.) The read port is persisted (`read_serial_port` setting) and overrides autodetection at startup — precedence is: explicit `DYNO_SERIAL_PORT` env (non-`auto`) > persisted read port > autodetect.
3. **Dependency check (informational)** — `GET /api/system/dependencies` lists every dependency the backend checked at startup (see "Dependency check" below) with an ok/missing/unknown indicator and remediation text. Missing optional dependencies never block advancing. This step deliberately sits **before** flashing so the operator sees toolchain status first; if `arduino-cli` or the esp32 core is missing, the next step disables flashing.
4. **Flash ESP firmware (optional)** — `POST /api/system/flash-esp` runs `arduino-cli compile` then `upload` against the chosen flash port (equivalent to `tools/flash-esp32.sh`); progress is polled from `GET /api/system/flash-esp/status`. The operator can skip this step. Flashing automatically suspends the live telemetry reader for the duration of the upload (expect a ~5–10 s telemetry gap around a flash) and resumes it afterward.

The firmware sketch is **embedded in the `dynod` binary**, so no firmware sources need to be shipped to the machine — if `DYNO_FLASH_SKETCH` doesn't exist on disk, the built-in firmware is staged to a temp directory and flashed automatically. In-app flashing therefore only requires `arduino-cli` on `PATH` and the esp32 core installed. Configure via `DYNO_FLASH_TOOL` (default `arduino-cli`), `DYNO_FLASH_FQBN` (default `esp32:esp32:esp32`), and `DYNO_FLASH_SKETCH` (default `firmware/firmware-test` — used when present, e.g. a dev checkout, otherwise the embedded firmware is used). `POST /api/system/flash-esp` runs the dependency check before starting: if any dependency with `blocks_flashing: true` (arduino-cli, esp32 core) is an explicit `missing`, it refuses with `400 {"error": "cannot flash: the flash toolchain is incomplete. ..."}` (the body includes the remediation text) rather than starting a doomed job; an `unknown` status does not block. If no `flash_serial_port` is configured, flashing falls back to the persisted read port, then to the runtime-resolved serial port — so a single-cable setup needs no separate flash-port configuration at all.

### Single-cable wiring

The ESP32 is wired for **one USB cable** between the Pi and the devkit's onboard USB port: telemetry (newline-delimited JSON), the binary config-sync protocol, and firmware flashing all share it (UART0 / `Serial` on the ESP32 side). There is no external UART adapter and no GPIO wiring to the ESP32's Serial2 pins.

Two behaviors follow directly from sharing one physical UART0 port between the backend and the bootloader:

- **Opening the port resets the ESP32.** The tty driver asserts DTR/RTS on open, which triggers the devkit's auto-reset circuit. Every `dynod` (re)start and every reconnect after a serial hiccup causes a brief (~1–2 s) telemetry gap and resets onboard counters (e.g. encoder totals). The reader tolerates the resulting boot-ROM noise and re-syncs cleanly; this is expected behavior, not a fault.
- **Flashing needs exclusive access to the same port the live reader holds.** `POST /api/system/flash-esp` asks the reader to release the port before invoking `arduino-cli upload`, and lets it resume afterward (see `crates/dyno-core/src/serial_gate.rs`). If the reader doesn't confirm release within 5 s, the flash request is rejected with `409 Conflict` instead of racing esptool for the port.

Migrating an existing two-cable installation: unplug the external UART adapter, connect the devkit's onboard USB port to the Pi, and either delete `esp32-device-config.json` (so it regenerates with the new UART0 defaults) or edit its `uart_tx_pin`/`uart_rx_pin`/`uart_baud` fields to match the firmware's fixed report (pins 1/3, 115200 baud) — a mismatch here fails startup config sync with `DangerousLiveChange`.

### Dependency check

`crates/dyno-core/src/deps.rs` consolidates the dependency/package checks that used to be scattered across `flash.rs` (arduino-cli preflight) and `detect.rs` (device enumeration) into a single `check_dependencies(&Config) -> Vec<DependencyCheck>`. Each result is `{ name, category, required, status, detail, remediation, blocks_flashing }`, with `status` one of `ok | missing | unknown`. `blocks_flashing` marks the dependencies whose absence makes an ESP flash attempt pointless — the flash endpoint gate and the setup wizard key off this flag rather than dependency names. Example entry:

```json
{
  "name": "arduino_cli",
  "category": "flash-toolchain",
  "required": false,
  "status": "missing",
  "detail": "'arduino-cli' was not found on PATH; ESP32 firmware flashing is unavailable",
  "remediation": "Install arduino-cli (https://arduino.github.io/arduino-cli) ...",
  "blocks_flashing": true
}
```

Checks performed:

| name | category | required | blocks_flashing | notes |
| --- | --- | --- | --- | --- |
| `arduino_cli` | `flash-toolchain` | no | yes | on `PATH`? Reuses the same PATH-scan helper as the flash preflight. |
| `arduino_esp32_core` | `flash-toolchain` | no | yes | best-effort `arduino-cli core list` with a 5s timeout; `unknown` if arduino-cli is absent or the command doesn't respond in time. |
| `firmware_sketch` | `flash-toolchain` | no | no | always `ok` — either the configured sketch is present on disk, or the embedded fallback (built into the `dynod` binary) is used. |
| `serial_device` | `device` | yes in `live` source mode, no in `replay` | no | via `detect::list_serial_devices()`. |
| `can_interface` | `device` | no | no | best-effort presence check under `/sys/class/net`; `unknown` is expected and fine on deployments with no CAN AFR source. |

At startup, `dynod` runs this check once (after device autodetection) and logs one summary line per dependency (`info` for ok/optional-missing, `warn` for a missing *required* dependency). Missing optional dependencies never fail startup. The same report is available live via `GET /api/system/dependencies` → `{ "dependencies": [...] }`, and is what step 4 of the first-time setup wizard displays.

## Backend environment

- `DYNO_DATA_DIR`: fixed per-machine data directory for the database and ESP32 config files. See "First boot" above.
- `DYNO_DB_PATH`: SQLite database path. Defaults to `dyno.db` inside the data directory; set an absolute path to override.
- `DYNO_SOURCE_MODE`: `live` or `replay`.
- `DYNO_PROFILE`: use `production` for ESP32 JSON UART + SocketCAN AEM UEGO + no Modbus AFR.
- `DYNO_SERIAL_PORT`: UART device path for live JSON ingest. Default `auto` scans `/dev/serial/by-id` for the ESP32's onboard USB-UART bridge (CP210x, CH340, or a native USB-CDC/Espressif bridge), falling back to `/dev/ttyUSB0`; set an explicit path to pin it. An explicit value here also overrides any read port chosen in the first-time setup wizard.
- `DYNO_SERIAL_BAUD`: UART baud rate, usually `115200`.
- `DYNO_FLASH_TOOL`, `DYNO_FLASH_FQBN`, `DYNO_FLASH_SKETCH`: in-app ESP flashing settings (defaults `arduino-cli`, `esp32:esp32:esp32`, `firmware/firmware-test`). See "First-time setup wizard" above.
- `DYNO_CAN_IFACE`: SocketCAN interface for AEM UEGO AFR. Default `auto` picks the first CAN-type interface under `/sys/class/net`, falling back to `can0`; set an explicit interface to pin it.
- `DYNO_MODBUS_AFR_ENABLED`: legacy AFR path flag. Keep `false` in production.
- `DYNO_BME280_ENABLED`: `true` or `false`.
- `DYNO_WS_BIND`: websocket bind address, usually `0.0.0.0:9000`.
- `DYNO_API_BIND`: HTTP API bind address, usually `0.0.0.0:9001`.
- `DYNO_RECORD_RPM`: engine RPM at or above which an operator-started run records frames.
- `DYNO_STOP_RPM`: deprecated compatibility setting; parsed but no longer used to stop runs.
- `RUST_LOG`: backend log level, usually `info`.

## Operator console environment

- `DYNO_UI_API_BASE_URL`: backend HTTP API base URL.
- `DYNO_UI_WS_URI`: backend websocket URL.
- `DYNO_CONTROL_API_BASE_URL`: optional external run-control API.
- `DYNO_UI_MODE`: `windowed`, `maximized`, or `fullscreen`.
- `DYNO_UI_FULLSCREEN`: legacy fullscreen flag.
- `DYNO_UI_MAXIMIZE_TO_FULLSCREEN`: `true` keeps maximized windows visually close to fullscreen.
- `DISPLAY` and `XAUTHORITY`: required when the JavaFX console is started from systemd in a graphical desktop session.
- `DYNO_OPERATOR_CONSOLE_HOME`: optional install root override for the launcher script.

## Install layout

1. Install `dynod` to `/usr/local/bin/dynod`.
2. Install CAN support: `sudo apt install can-utils`.
3. Copy [dyno-canable.service](/home/thanakornb/dyno-system/deploy/systemd/dyno-canable.service) to `/etc/systemd/system/dyno-canable.service`.
4. Copy [dynod.service](/home/thanakornb/dyno-system/deploy/systemd/dynod.service) to `/etc/systemd/system/dynod.service`.
5. Copy [dyno.env](/home/thanakornb/dyno-system/deploy/env/dyno.env) to `/etc/dyno/dyno.env` and edit it.
6. Enable CAN and backend services:

```sh
sudo systemctl enable --now dyno-canable.service
sudo systemctl enable --now dynod.service
```

7. If the target names the backend service `dyno-backend.service`, install the same backend unit under that name and enable it with `sudo systemctl enable --now dyno-backend.service`.

8. Copy [dynod.env.example](/home/thanakornb/dyno-system/deploy/env/dynod.env.example) to `/etc/dyno/dynod.env` only for host-specific overrides.
9. Install the Java operator console distribution under `/opt/dyno-operator-console`.
10. Copy [run-dyno-operator-console.sh](/home/thanakornb/dyno-system/deploy/bin/run-dyno-operator-console.sh) to `/usr/local/bin/dyno-operator-console` and make it executable.
11. Copy [dyno-operator-console.env.example](/home/thanakornb/dyno-system/deploy/env/dyno-operator-console.env.example) to `/etc/dyno/operator-console.env` and edit it.
12. Optional: copy [dyno-operator-console.service](/home/thanakornb/dyno-system/deploy/systemd/dyno-operator-console.service) to `/etc/systemd/system/dyno-operator-console.service` if the target host runs the UI under systemd.

## Common commands

- Enable and start backend: `sudo systemctl enable --now dynod`
- Restart backend: `sudo systemctl restart dynod`
- Tail backend logs: `journalctl -u dynod -f`
- Check backend startup health: `curl http://127.0.0.1:9001/healthz`
- Start operator console manually: `/usr/local/bin/dyno-operator-console`
- Enable and start operator console service: `sudo systemctl enable --now dyno-operator-console`
- Tail operator console logs: `journalctl -u dyno-operator-console -f`

## Startup behavior

- `dynod` runs startup checks for the database path, live serial path, and optional BME280 I2C device.
- A bad database path is fatal and the service exits with a clear log message.
- A missing live serial device is degraded, not fatal. The serial task keeps retrying until the device appears.
- A missing `/dev/i2c-1` is degraded, not fatal. Ambient reads fall back to stub values.
- `GET /healthz` returns the cached startup check summary for quick operator inspection.
- The operator console starts even if the backend is unavailable. The websocket client keeps reconnecting automatically, while history/calibration requests fail on demand until the backend returns.

## Run lifecycle

- Runs are operator-bounded: `POST /api/run/start` begins operator intent, and `POST /api/run/stop` ends the run.
- Fusion emits `Recording` only while operator intent is started and engine RPM is at or above `DYNO_RECORD_RPM`.
- When RPM dips below `DYNO_RECORD_RPM` during a started run, state returns to `Armed`/paused. Gauges and live telemetry continue to stream, but those paused frames are not persisted.
- Storage keeps the active run open through paused `Armed` frames and appends again when RPM rises back to recording threshold.
- `POST /api/run/stop` records a synthetic idle frame and flushes storage before returning, so the completed run is queryable when the response arrives.

## Live mode example

`/etc/dyno/dynod.env`

```dotenv
RUST_LOG=info
DYNO_STORAGE_DB_PATH=/var/lib/dyno/runs-rust.db
DYNO_PROFILE=production
DYNO_SOURCE_MODE=live
DYNO_SERIAL_PORT=auto
DYNO_SERIAL_BAUD=115200
DYNO_CAN_IFACE=auto
DYNO_MODBUS_AFR_ENABLED=false
DYNO_BME280_ENABLED=true
DYNO_WS_BIND=0.0.0.0:9000
DYNO_API_BIND=0.0.0.0:9001
```

## ESP32 JSON telemetry

In live JSON mode, ESP32 `bme_valid=true` ambient values feed `ambient_temp_c`, `humidity_pct`, and `pressure_hpa`; invalid BME frames fall back to backend stub ambient values.

Flash the Arduino sketch:

```sh
tools/flash-esp32.sh
```

Read raw newline-delimited JSON telemetry:

```sh
python3 -m serial.tools.miniterm /dev/ttyUSB0 115200
```

or:

```sh
stty -F /dev/ttyUSB0 115200 raw -echo && cat /dev/ttyUSB0
```

Run the backend against the ESP32 telemetry port:

```sh
DYNO_SERIAL_PORT=/dev/ttyUSB0 DYNO_SERIAL_BAUD=115200 cargo run -p dyno-core
```

## Replay mode example

`/etc/dyno/dynod.env`

```dotenv
RUST_LOG=info
DYNO_DB_PATH=/var/lib/dyno/dyno.db
DYNO_SOURCE_MODE=replay
DYNO_BME280_ENABLED=false
DYNO_WS_BIND=0.0.0.0:9000
DYNO_API_BIND=0.0.0.0:9001
```

## Troubleshooting

- `healthz` shows `error` for `database_path`: the service user cannot create or write the database directory. Fix ownership or update `DYNO_DB_PATH`.
- `healthz` shows `degraded` for `serial_port`: verify `DYNO_SERIAL_PORT`, cabling, and device permissions. In live mode the backend keeps retrying.
- `healthz` shows `degraded` for `bme280_i2c`: confirm `DYNO_BME280_ENABLED`, I2C overlay/device availability, and `/dev/i2c-1` permissions.
- The operator console cannot connect: confirm `DYNO_UI_API_BASE_URL` and `DYNO_UI_WS_URI` match the backend bind addresses and host firewall rules.
- The operator console starts but control actions fail: confirm `DYNO_CONTROL_API_BASE_URL` points to the run-control service. That API is separate from `dynod`.
- The console service exits immediately under systemd: set `DISPLAY` and `XAUTHORITY` in `/etc/dyno/operator-console.env`, or launch the console manually inside the desktop session.

## Security and maintenance notes

- The HTTP API (:9001) and WebSocket (:9000) have **no authentication**. The default env files bind `0.0.0.0`, exposing run history, run control, calibration, and run deletion to the whole LAN. On installs where the operator console runs on the same machine as `dynod`, bind to loopback instead: `DYNO_WS_BIND=127.0.0.1:9000`, `DYNO_API_BIND=127.0.0.1:9001`. Otherwise keep the dyno network isolated/firewalled.
- `POST /api/dev/seed-run` is disabled unless `DYNO_ENABLE_DEV_API=true` (or a debug build). Do not enable it in production env files.
- The system password (calibration unlock and Machine Configuration) is stored in the `settings` table under the `system_password` key. There is no built-in default — see "First boot" above for how it gets set the first time. Changing it later goes through `POST /api/system/password` (requires the current password) rather than a direct DB edit.
- The `frames` table grows without bound (one row per telemetry frame per recorded run, ~100 Hz). On a busy shop machine, periodically delete old runs from the operator console (deletes cascade to frames) or via `DELETE /api/runs/:id`, then reclaim space with `sqlite3 /var/lib/dyno/dyno.db 'VACUUM;'` while `dynod` is stopped.

## Password reset

If the system password is lost:

1. Stop the backend: `sudo systemctl stop dynod`.
2. Clear the stored password: `sqlite3 /var/lib/dyno/dyno.db "DELETE FROM settings WHERE key='system_password';"` (adjust the path if `DYNO_DATA_DIR`/`DYNO_DB_PATH` point elsewhere).
3. Restart the backend: `sudo systemctl start dynod`.
4. Open the operator console; the first-time setup dialog reappears so a new password can be created.

A full wipe (all runs, calibration profiles, and the password) is the same idea but deletes the whole data directory instead of one setting: stop `dynod`, `rm -rf /var/lib/dyno` (or wherever `data_dir` resolved to — check the startup log), then restart. The backend recreates everything from scratch on next boot.
