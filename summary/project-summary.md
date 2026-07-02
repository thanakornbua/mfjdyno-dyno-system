# Project Summary

## Overview

`dyno-system` is a chassis dyno acquisition and operator-console system. It combines:

- A Rust backend service (`dynod`) that ingests live telemetry, computes fused dyno measurements, stores runs, exposes history/calibration/run-control APIs, and broadcasts live frames.
- A Java/JavaFX operator console (`dyno-ui`) that displays live telemetry, controls run flow, manages calibration profiles, compares historical runs, and exports run data.
- ESP32 Arduino firmware that acquires raw dyno signals and emits JSON telemetry over serial.
- Deployment assets for Raspberry Pi style production installs, including systemd units, environment files, and launch scripts.

The main runtime flow is:

1. ESP32 firmware reads engine/roller/ambient acquisition signals and reports telemetry over UART.
2. `dynod` reads ESP32 serial data, SocketCAN AFR data, and ambient data, then fuses them into `LiveFrame` snapshots.
3. `dynod` broadcasts live frames over WebSocket and records run frames into SQLite.
4. The JavaFX console consumes WebSocket live telemetry and calls HTTP API endpoints for history, calibration, health, export, and run-control operations.

## Backend

The backend is a Rust workspace rooted at `Cargo.toml`.

Main crates:

- `crates/dyno-core`: backend application and `dynod` binary.
- `crates/dyno-types`: shared serializable DTOs and domain types such as `LiveFrame`, run state, alerts, faults, and run summaries.
- `crates/dyno-protocol`: protocol and packet/frame handling shared with acquisition logic.
- `crates/dyno-sim`: simulation/replay support.

Backend entry point:

- `crates/dyno-core/src/main.rs`
- Binary name: `dynod`

Major backend modules:

- `app.rs`: starts and owns backend subsystems.
- `config.rs`: loads runtime config from `DYNO_*` environment variables.
- `serial.rs` / `serial_link.rs`: ESP32 UART ingestion and command link.
- `can.rs`: SocketCAN/AEM UEGO AFR acquisition.
- `fusion.rs`: combines telemetry sources into `LiveFrame` values.
- `ws.rs`: WebSocket broadcast server for live frames.
- `api.rs`: Axum HTTP JSON API.
- `storage.rs`: SQLite persistence for runs, frames, and calibration profiles.
- `calibration.rs`: calibration validation and profile data.
- `run_control.rs`: run configuration/start/stop state.
- `health.rs`: startup health checks.
- `replay.rs`: replay/simulation source mode.

Runtime modes:

- `live`: reads ESP32 UART, SocketCAN, ambient data, and runs fusion.
- `replay`: bypasses hardware acquisition and emits replay/simulated frames.

Default backend ports:

- HTTP API: `0.0.0.0:9001`
- WebSocket: `0.0.0.0:9000`

Storage:

- SQLite database, default `dyno.db`.
- Production docs use `/var/lib/dyno/dyno.db` or `/var/lib/dyno/runs-rust.db`.
- Tables include `runs`, `frames`, `calibration_profiles`, and `calibration_profile_events`.

## Frontend

The frontend/operator console is a Java 21 JavaFX desktop application under `java/`.

Build configuration:

- Gradle project: `java/build.gradle`
- Root project name: `dyno-ui`
- Main class: `com.dyno.fx.OperatorConsoleApp`

Primary frontend areas:

- `com.dyno.fx`: JavaFX screens, dialogs, shell, stage setup, theme, and UI state.
- `com.dyno.ws`: WebSocket client for live telemetry.
- `com.dyno.history`: HTTP client and DTOs for historical run queries and comparison.
- `com.dyno.calibration`: calibration API client, DTOs, and validation.
- `com.dyno.control`: run-control API client and DTOs.
- `com.dyno.health`: startup health API client and operator health mapping.
- `com.dyno.presenter`: chart, gauge, telemetry, comparison, and view-model mapping.
- `com.dyno.export`: CSV, JSON, PNG, and PDF export support.
- `com.dyno.config`: backend endpoint resolution.

Default frontend endpoints:

- API base URL: `http://127.0.0.1:9001`
- WebSocket URI: `ws://127.0.0.1:9000`
- Control API base URL: defaults to the same API base URL.

Important frontend environment variables:

- `DYNO_UI_API_BASE_URL`
- `DYNO_UI_WS_URI`
- `DYNO_CONTROL_API_BASE_URL`
- `DYNO_BACKEND_HOST`
- `DYNO_BACKEND_HTTP_PORT`
- `DYNO_BACKEND_WS_PORT`
- `DYNO_UI_MODE`
- `DYNO_UI_FULLSCREEN`
- `DYNO_UI_WS_DEBUG`

Gradle run tasks:

- `gradle run`
- `gradle runOperatorConsoleFx`
- `gradle runOperatorConsoleFxWindowed`
- `gradle runOperatorConsoleFxFullscreen`

## API Pathway

### Live Telemetry Path

Live telemetry uses a raw WebSocket listener, not an HTTP route path.

1. Backend binds `DYNO_WS_BIND`, default `0.0.0.0:9000`.
2. Frontend connects to `DYNO_UI_WS_URI`, default `ws://127.0.0.1:9000`.
3. Backend sends the latest `LiveFrame` immediately after connection.
4. Backend sends each subsequent latest frame from a `watch::Receiver`.
5. Slow clients skip stale frames because the channel stores only the latest value.

WebSocket message envelope:

```json
{
  "type": "live_frame",
  "data": {
    "ts_ms": 0,
    "engine_rpm": null,
    "roller_rpm": null,
    "speed_kmh": null,
    "power_hp": null,
    "torque_nm": null,
    "correction_factor": 1.0,
    "afr": null,
    "lambda": null,
    "ambient_temp_c": null,
    "humidity_pct": null,
    "pressure_hpa": null,
    "run_state": "Idle"
  }
}
```

The actual `LiveFrame` also includes CAN status, ESP32 acquisition status, faults, and alerts.

### HTTP API Path

HTTP API base URL defaults to `http://127.0.0.1:9001`.

Health:

- `GET /healthz`: cached startup health summary.

Run control:

- `POST /api/run/configure`: configure current run metadata, currently license plate focused.
- `POST /api/run/start`: start the current run.
- `POST /api/run/stop`: stop the current run and record/flush an idle frame when needed so history is immediately queryable.
- `GET /api/run/status`: read run-control status.

Development:

- `POST /api/dev/seed-run`: seed a synthetic development run when dev API is enabled.

Calibration:

- `GET /api/calibration`: get active calibration profile.
- `GET /api/calibration/profiles`: list calibration profiles.
- `POST /api/calibration/profiles`: create calibration profile.
- `PUT /api/calibration/profiles/:id`: update calibration profile.
- `POST /api/calibration/profiles/:id/duplicate`: duplicate calibration profile.
- `GET /api/calibration/profiles/:id/events`: list calibration profile events.
- `POST /api/calibration/activate`: activate a calibration profile.

Run history:

- `GET /api/runs`: list recent runs.
- `POST /api/runs/compare`: fetch multiple runs and frame series for comparison.
- `GET /api/runs/:id`: fetch one run.
- `DELETE /api/runs/:id`: delete one run.
- `GET /api/runs/:id/frames`: fetch stored frame series for one run.

### Hardware-to-UI Data Path

1. ESP32 firmware emits JSON telemetry over serial.
2. Backend serial task decodes ESP32 frames.
3. Backend CAN task reads AFR/lambda from SocketCAN.
4. Backend ambient/BME280 path supplies temperature, humidity, and pressure when enabled.
5. Fusion task computes speed, power, torque, correction factor, run state, faults, and alerts.
6. WebSocket task broadcasts `LiveFrame` envelopes to the console.
7. Storage task records run frames to SQLite.
8. JavaFX console renders live gauges/charts and uses HTTP endpoints for stored data.

## Other Details

Firmware:

- Location: `firmware/`
- Framework: PlatformIO with Arduino ESP32.
- Board: `esp32dev`.
- Upload port: `/dev/ttyUSB1`.
- Monitor speed: `115200`.
- Dependencies include Adafruit BME280 and Adafruit Unified Sensor libraries.

Deployment:

- Operations guide: `docs/OPERATIONS.md`
- Backend systemd unit: `deploy/systemd/dynod.service`
- CAN setup unit: `deploy/systemd/dyno-canable.service`
- Operator console systemd unit: `deploy/systemd/dyno-operator-console.service`
- Backend env examples: `deploy/env/dyno.env`, `deploy/env/dynod.env.example`
- Operator console env example: `deploy/env/dyno-operator-console.env.example`
- Console launcher: `deploy/bin/run-dyno-operator-console.sh`

Common production layout:

- Backend binary: `/usr/local/bin/dynod`
- Backend env file: `/etc/dyno/dynod.env`
- Backend state directory: `/var/lib/dyno`
- Operator console launcher: `/usr/local/bin/dyno-operator-console`
- Operator console install root: `/opt/dyno-operator-console`
- Operator console env file: `/etc/dyno/operator-console.env`

Testing and generated artifacts:

- Rust tests live beside Rust modules under `crates/*/src`.
- Java tests live under `java/src/test/java`.
- `java/build/` contains generated classes, reports, install distributions, and test output.
- The current working tree contains many pre-existing uncommitted generated/build changes; this summary only adds files under `summary/`.

Key commands:

```sh
cargo test
cargo run -p dyno-core
cd java && gradle test
cd java && gradle runOperatorConsoleFx
tools/flash-esp32.sh
```
