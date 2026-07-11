# Deployment Module

## Responsibility

Deployment assets define how the backend, CAN setup, and Java operator console are installed and run on a production host. The current operations documentation targets a Raspberry Pi style deployment with systemd, SocketCAN, ESP32 UART, SQLite state, and a local JavaFX desktop session.

Primary operations guide:

- `docs/OPERATIONS.md`

Deployment files:

- `deploy/systemd/dynod.service`
- `deploy/systemd/dyno-canable.service`
- `deploy/systemd/dyno-operator-console.service`
- `deploy/env/dyno.env`
- `deploy/env/dynod.env.example`
- `deploy/env/dyno-operator-console.env.example`
- `deploy/bin/run-dyno-operator-console.sh`

## Production Layout

Common installed paths:

- Backend binary: `/usr/local/bin/dynod`
- Backend env file: `/etc/dyno/dynod.env`
- Backend state directory: `/var/lib/dyno`
- Backend database: `/var/lib/dyno/dyno.db`
- Operator console launcher: `/usr/local/bin/dyno-operator-console`
- Operator console install root: `/opt/dyno-operator-console`
- Operator console env file: `/etc/dyno/operator-console.env`

The repository also contains example and local env files under `deploy/env`.

## Backend Service

Unit:

- `deploy/systemd/dynod.service`

Binary:

- `dynod`, built from `crates/dyno-core`.

Main responsibilities:

- Start the Rust backend.
- Load production environment.
- Bind HTTP API and WebSocket ports.
- Open SQLite database.
- Connect to ESP32/CAN/I2C as configured.

If the API or WebSocket port is already bound (e.g. a second `dynod` instance, or one started manually while the systemd unit is also running), startup now fails fast with a non-zero exit and a log message naming the likely cause, instead of continuing half-alive with those ports unbound.

Important backend environment variables:

- `RUST_LOG`: log level, commonly `info`.
- `DYNO_DATA_DIR`: fixed per-machine data directory the backend self-provisions at startup (default `/var/lib/dyno` if writable, else an XDG fallback). `DYNO_STORAGE_DB_PATH`/`DYNO_DB_PATH` and the ESP32 config paths are anchored inside it unless set explicitly.
- `DYNO_STORAGE_DB_PATH` or `DYNO_DB_PATH`: SQLite path.
- `DYNO_SYSTEM_PASSWORD`: seeds the system password on a brand-new database only; otherwise the JavaFX console prompts the operator to create one on first boot (`GET /api/system/setup-status`, `POST /api/system/setup-password`).
- `DYNO_FLASH_TOOL` / `DYNO_FLASH_FQBN` / `DYNO_FLASH_SKETCH`: in-app ESP flashing (defaults `arduino-cli` / `esp32:esp32:esp32` / `firmware/firmware-test`). The first-boot wizard can flash firmware. The firmware sketch is **embedded in the `dynod` binary** and staged to a temp dir when not present on disk, so a minimal binary-only install can still flash — only `arduino-cli` + the esp32 core are required on the host. If `arduino-cli` is missing the flash step reports a clear error while the rest of setup still completes. The operator-selected read serial port is persisted and overrides autodetection (`DYNO_SERIAL_PORT` env still wins).
- Dependency check: at every startup `dynod` runs `deps::check_dependencies` and logs one line per dependency (arduino-cli, esp32 core, firmware sketch, serial device, CAN interface) — see "Dependency check" in `docs/OPERATIONS.md` for the full required-vs-optional list. Only a missing `serial_device` in `DYNO_SOURCE_MODE=live` is treated as required; everything else is optional and never fails startup. The same report is queryable via `GET /api/system/dependencies` and shown in step 4 of the first-boot wizard.
- `DYNO_SOURCE_MODE`: `live` or `replay`.
- `DYNO_PROFILE`: normally `production`.
- `DYNO_SERIAL_PORT`: ESP32 UART path (`auto` by default — detects the devkit's onboard USB-UART bridge: CP210x, CH340, or Espressif USB-CDC). A single cable to this port also handles flashing.
- `DYNO_SERIAL_BAUD`: ESP32 UART baud.
- `DYNO_CAN_IFACE`: SocketCAN interface (`auto` by default — first CAN-type interface).
- `DYNO_MODBUS_AFR_ENABLED`: normally `false`.
- `DYNO_BME280_ENABLED`: `true` or `false`.
- `DYNO_WS_BIND`: WebSocket bind.
- `DYNO_API_BIND`: HTTP API bind.
- `DYNO_ESP32_CONFIG_PATH`: desired ESP32 config JSON.
- `DYNO_ESP32_APPLIED_CONFIG_PATH`: last-applied config state.

Common commands:

```sh
sudo systemctl enable --now dynod
sudo systemctl restart dynod
journalctl -u dynod -f
curl http://127.0.0.1:9001/healthz
```

## CAN Service

Unit:

- `deploy/systemd/dyno-canable.service`

Purpose:

- Bring up CAN support before or alongside backend live acquisition.
- Production autodetects the SocketCAN interface (falling back to `can0`) unless pinned with `DYNO_CAN_IFACE`.

Dependency:

- `can-utils` is useful for diagnostics and setup.

Common commands:

```sh
sudo apt install can-utils
sudo systemctl enable --now dyno-canable.service
ip link show can0
```

Engineering notes:

- If CAN interface naming changes, update backend env and operations docs.
- Backend CAN failure should degrade AFR status but should not prevent all backend operation.

## Operator Console Service

Unit:

- `deploy/systemd/dyno-operator-console.service`

Launcher:

- `deploy/bin/run-dyno-operator-console.sh`

Installed launcher path:

- `/usr/local/bin/dyno-operator-console`

Install root:

- `/opt/dyno-operator-console`

Main environment variables:

- `DYNO_UI_API_BASE_URL`: backend HTTP API base URL.
- `DYNO_UI_WS_URI`: backend WebSocket URI.
- `DYNO_CONTROL_API_BASE_URL`: optional run-control API override.
- `DYNO_UI_MODE`: `windowed`, `maximized`, or `fullscreen`.
- `DYNO_UI_FULLSCREEN`: legacy fullscreen flag.
- `DYNO_UI_MAXIMIZE_TO_FULLSCREEN`: maximized/fullscreen behavior.
- `DISPLAY`: required for systemd-launched JavaFX in a desktop session.
- `XAUTHORITY`: required for systemd-launched JavaFX in a desktop session.
- `DYNO_OPERATOR_CONSOLE_HOME`: optional launcher install root override.

Common commands:

```sh
/usr/local/bin/dyno-operator-console
sudo systemctl enable --now dyno-operator-console
journalctl -u dyno-operator-console -f
```

Engineering notes:

- The console starts even when the backend is unavailable.
- WebSocket reconnects automatically.
- History/calibration requests fail at request time until backend returns.
- Under systemd, JavaFX display variables are often the first failure point.

## Example Backend Env

Live mode example:

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

Replay mode example:

```dotenv
RUST_LOG=info
DYNO_DB_PATH=/var/lib/dyno/dyno.db
DYNO_SOURCE_MODE=replay
DYNO_BME280_ENABLED=false
DYNO_WS_BIND=0.0.0.0:9000
DYNO_API_BIND=0.0.0.0:9001
```

## Installation Flow

Backend:

1. Build `dynod`.
2. Install binary to `/usr/local/bin/dynod`.
3. Create `/etc/dyno` and `/var/lib/dyno` with correct ownership.
4. Install backend env file.
5. Install and enable CAN unit.
6. Install and enable backend unit.
7. Check `/healthz`.

Operator console:

1. Build Java distribution.
2. Install distribution under `/opt/dyno-operator-console`.
3. Install launcher as `/usr/local/bin/dyno-operator-console`.
4. Install console env file.
5. Run manually inside desktop session first.
6. If needed, install and enable systemd console service.

## Startup Health

Backend startup checks are exposed at:

```sh
curl http://127.0.0.1:9001/healthz
```

Expected interpretation:

- `database_path=error`: backend cannot create/write database path; service should fail.
- `serial_port=degraded`: serial device missing or unavailable; live task will keep retrying.
- `bme280_i2c=degraded`: optional I2C device missing; ambient data falls back.

## Troubleshooting Map

Backend not running:

- Check `systemctl status dynod`.
- Check `journalctl -u dynod -f`.
- Check env path and binary path.
- Check database directory permissions.
- Check for a "failed to bind ... already running?" error — a second `dynod` instance (manual run vs. the systemd unit) is a common cause.

Operator console shows the first-boot password dialog unexpectedly:

- Expected the first time a fresh database is used; create a password and continue.
- If it reappears on an established install, the `system_password` setting was cleared (see the "Password reset" procedure in `docs/OPERATIONS.md`) or a new/wrong database is being used — check `DYNO_DATA_DIR`/`DYNO_DB_PATH`.

No live telemetry:

- Check WebSocket bind and UI `DYNO_UI_WS_URI`.
- Check ESP32 serial path and permissions.
- Check backend logs for serial open/retry messages.
- Read raw serial output with miniterm or `cat`.

No AFR/lambda:

- Check `can0` exists.
- Check CAN bitrate/interface service.
- Check backend CAN status fields in live frame.
- Use `candump can0` if available.

Console will not start under systemd:

- Check `DISPLAY`.
- Check `XAUTHORITY`.
- Start launcher manually inside the desktop session.
- Tail `dyno-operator-console` logs.

History/calibration fail but live UI works:

- Check HTTP API URL, not WebSocket URL.
- Run `curl http://127.0.0.1:9001/healthz`.
- Confirm firewall/bind address if UI runs on a different host.

## Deployment Change Checklist

When deployment behavior changes:

1. Update systemd unit or launcher script.
2. Update env examples.
3. Update `docs/OPERATIONS.md`.
4. Update this summary file.
5. Confirm default ports still match Java `EndpointConfig`.
6. Confirm production paths still match service users and permissions.
