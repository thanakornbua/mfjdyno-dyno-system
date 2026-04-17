# Dyno Operations

## File layout

- Backend binary: `/usr/local/bin/dynod`
- Backend env file: `/etc/dyno/dynod.env`
- Backend state directory: `/var/lib/dyno`
- Backend database: `/var/lib/dyno/dyno.db`
- Operator console launcher: `/usr/local/bin/dyno-operator-console`
- Operator console install root: `/opt/dyno-operator-console`
- Operator console env file: `/etc/dyno/operator-console.env`

## Backend environment

- `DYNO_DB_PATH`: SQLite database path. Use `/var/lib/dyno/dyno.db` in production.
- `DYNO_SOURCE_MODE`: `live` or `replay`.
- `DYNO_SERIAL_PORT`: UART device path for live ingest, usually `/dev/serial0`.
- `DYNO_SERIAL_BAUD`: UART baud rate, usually `921600`.
- `DYNO_BME280_ENABLED`: `true` or `false`.
- `DYNO_WS_BIND`: websocket bind address, usually `0.0.0.0:9000`.
- `DYNO_API_BIND`: HTTP API bind address, usually `0.0.0.0:9001`.
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
2. Copy [dynod.service](/home/thanakornb/dyno-backend/deploy/systemd/dynod.service) to `/etc/systemd/system/dynod.service`.
3. Copy [dynod.env.example](/home/thanakornb/dyno-backend/deploy/env/dynod.env.example) to `/etc/dyno/dynod.env` and edit it.
4. Install the Java operator console distribution under `/opt/dyno-operator-console`.
5. Copy [run-dyno-operator-console.sh](/home/thanakornb/dyno-backend/deploy/bin/run-dyno-operator-console.sh) to `/usr/local/bin/dyno-operator-console` and make it executable.
6. Copy [dyno-operator-console.env.example](/home/thanakornb/dyno-backend/deploy/env/dyno-operator-console.env.example) to `/etc/dyno/operator-console.env` and edit it.
7. Optional: copy [dyno-operator-console.service](/home/thanakornb/dyno-backend/deploy/systemd/dyno-operator-console.service) to `/etc/systemd/system/dyno-operator-console.service` if the target host runs the UI under systemd.

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

## Live mode example

`/etc/dyno/dynod.env`

```dotenv
RUST_LOG=info
DYNO_DB_PATH=/var/lib/dyno/dyno.db
DYNO_SOURCE_MODE=live
DYNO_SERIAL_PORT=/dev/serial0
DYNO_SERIAL_BAUD=921600
DYNO_BME280_ENABLED=true
DYNO_WS_BIND=0.0.0.0:9000
DYNO_API_BIND=0.0.0.0:9001
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
