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

Important backend environment variables:

- `RUST_LOG`: log level, commonly `info`.
- `DYNO_STORAGE_DB_PATH` or `DYNO_DB_PATH`: SQLite path.
- `DYNO_SOURCE_MODE`: `live` or `replay`.
- `DYNO_PROFILE`: normally `production`.
- `DYNO_SERIAL_PORT`: ESP32 UART path.
- `DYNO_SERIAL_BAUD`: ESP32 UART baud.
- `DYNO_CAN_IFACE`: SocketCAN interface.
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
- Production expects SocketCAN interface `can0` unless overridden with `DYNO_CAN_IFACE`.

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
DYNO_SERIAL_PORT=/dev/ttyUSB0
DYNO_SERIAL_BAUD=115200
DYNO_CAN_IFACE=can0
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
