# Backend Module

## Responsibility

The backend is the Rust service that turns dyno acquisition data into durable run history and live UI telemetry. It owns hardware ingestion, physical calculations, runtime run state, calibration, persistence, HTTP APIs, and WebSocket broadcast.

The backend binary is `dynod`, built from `crates/dyno-core`.

## Entry Points

- `crates/dyno-core/src/main.rs`
  - Initializes tracing.
  - Loads `Config::from_env()`.
  - Starts the top-level `App`.
  - Waits for `SIGTERM`, `SIGINT`, or `ctrl_c`.

- `crates/dyno-core/src/lib.rs`
  - Exposes the backend modules to the binary and tests.

- `crates/dyno-core/src/app.rs`
  - Composes all subsystems.
  - Decides live vs replay mode.
  - Owns task handles so dropping `App` aborts background work and closes resources.

## Runtime Configuration

File: `crates/dyno-core/src/config.rs`

Configuration is environment-driven. The backend does not require a config file for normal startup.

Important variables:

- `DYNO_SERIAL_PORT`: ESP32 UART path, default `/dev/ttyUSB0`.
- `DYNO_SERIAL_BAUD`: UART baud, default `115200`.
- `DYNO_CAN_IFACE`: SocketCAN interface, default `can0`.
- `DYNO_PROFILE`: runtime profile label, default `production`.
- `DYNO_MODBUS_AFR_ENABLED`: legacy path flag, normally `false`.
- `DYNO_WS_BIND`: WebSocket bind address, default `0.0.0.0:9000`.
- `DYNO_API_BIND`: HTTP bind address, default `0.0.0.0:9001`.
- `DYNO_STORAGE_DB_PATH` or `DYNO_DB_PATH`: SQLite database path, default `dyno.db`.
- `DYNO_ESP32_CONFIG_PATH`: desired ESP32 device config JSON.
- `DYNO_ESP32_APPLIED_CONFIG_PATH`: persisted last-applied ESP32 config state.
- `DYNO_SOURCE_MODE`: `live`, `replay`, `sim`, or `simulation`.
- `DYNO_CORRECTION_MODE`: correction model selector.
- `DYNO_ARM_RPM`, `DYNO_RECORD_RPM`, `DYNO_STOP_RPM`: run state thresholds.

Engineering notes:

- `Config` is intentionally plain and cloned into tasks as needed.
- Tests use direct `Config` construction for deterministic setup.
- Add new runtime settings in `Config`, `Config::from_env`, `Display`, and config tests together.

## Application Composition

File: `crates/dyno-core/src/app.rs`

Startup order:

1. Run startup health checks.
2. Open storage and ensure an active calibration profile exists.
3. Validate active calibration.
4. Create watch channels for calibration and live frames.
5. Start WebSocket broadcast.
6. Start storage recorder task.
7. Start HTTP API.
8. Start acquisition pipeline based on `source_mode`.

Live mode starts:

- ESP32 config synchronization.
- Serial task for ESP32 JSON telemetry.
- CAN task for AFR/lambda.
- Fusion task for `LiveFrame` generation.

Replay mode starts:

- Replay task only.
- UART, CAN, BME280, and live fusion are bypassed.

Important ownership rule:

- `App` owns task handles. Dropping `App` aborts tasks and releases serial/CAN/listener resources.

## Serial Ingestion

Files:

- `crates/dyno-core/src/serial.rs`
- `crates/dyno-core/src/serial_link.rs`
- `crates/dyno-core/src/esp32_json.rs`

Current live ingestion path:

1. `SerialTask` opens the configured UART.
2. It reads newline-delimited ESP32 JSON telemetry.
3. `parse_json_telemetry_line` parses each line.
4. `telemetry_to_frame` maps JSON telemetry into `dyno_protocol::DynoFrameV1`.
5. `telemetry_ambient_or_stub` maps valid ESP32 BME readings into `AmbientSample`.
6. Latest frame and ambient sample are published through `watch` channels.

Reliability behavior:

- Failed open retries every 5 seconds.
- Repeated read timeouts, EOF, parse failures, or I/O errors close and reopen the port.
- The task yields every 32 decoded frames to avoid monopolizing the Tokio scheduler.
- If all receivers are dropped, the task exits cleanly.

`serial_link.rs` is the command/response link used for ESP32 configuration sync. It opens `tokio_serial` ports and handles protocol command traffic.

## ESP32 Configuration Sync

File: `crates/dyno-core/src/esp32_config.rs`

Responsibility:

- Load desired ESP32 DAQ config from JSON.
- Validate backend-side safety constraints.
- Query device info/current config over the serial command protocol.
- Apply staged config when required.
- Persist last-applied state to avoid unnecessary writes.

Important types:

- `Esp32ConfigManager`: orchestrates sync.
- `PersistedEsp32ConfigState`: last-known applied config snapshot.
- `Esp32ConfigSyncStatus`: result category.
- `Esp32ConfigError`: retryable vs fatal sync failures.

Engineering notes:

- Startup sync is skipped only for retryable serial readiness errors.
- Dangerous live config changes are guarded before application.
- When changing ESP32 config schema, update `dyno-protocol`, firmware `DynoConfig`, validation, JSON examples, and tests together.

## CAN AFR Acquisition

File: `crates/dyno-core/src/can.rs`

Responsibility:

- Open SocketCAN interface.
- Read AEM UEGO CAN frames.
- Decode AFR/lambda/voltage/status into `CanSample`.
- Publish the latest sample through a `watch` channel.

Important behavior:

- `CanSample::missing()` represents unavailable CAN transport.
- `CanSample::stale()` and status text are used when no recent data is available.
- Decoding is specific to the expected AEM UEGO frame shape.

Failure impact:

- CAN failures should not kill `dynod`.
- Fusion still emits live frames with CAN/AFR status fields indicating degraded data.

## Ambient Data

Files:

- `crates/dyno-core/src/bme280.rs`
- `crates/dyno-core/src/esp32_json.rs`

There are two ambient sources in the codebase:

- ESP32 JSON telemetry can include BME280 readings.
- Pi-side BME280 support exists in `bme280.rs`.

`AmbientSample` includes temperature, humidity, pressure, and validity metadata. Fusion sanitizes ambient values before correction-factor calculation.

Engineering notes:

- Invalid or missing ambient data must degrade to safe defaults, not crash the backend.
- Correction-factor quality depends on ambient validity.

## Fusion and Physics

Files:

- `crates/dyno-core/src/fusion.rs`
- `crates/dyno-core/src/physics.rs`
- `crates/dyno-core/src/correction.rs`

Fusion converts raw acquisition values into UI-ready `LiveFrame` snapshots.

Inputs:

- `DynoFrameV1` from ESP32 serial parsing.
- `AmbientSample` from ESP32 or BME280.
- `CanSample` from SocketCAN.
- Active `CalibrationProfile`.
- `RunControl` runtime state.

Outputs:

- `dyno_types::LiveFrame` over a `watch::Sender`.

Fusion responsibilities:

- Convert engine period to engine RPM.
- Convert encoder deltas to roller RPM.
- Calculate speed from roller RPM and roller diameter.
- Calculate angular acceleration from prior roller angular velocity.
- Calculate inertial power and torque.
- Apply correction factor.
- Map ESP32 signal/fault flags into `Esp32TelemetryStatus` and `FaultCode`.
- Derive lambda/O2 alert levels.
- Update run state based on operator start/stop plus RPM thresholds.

Physics helper responsibilities:

- Keep unit conversions isolated and testable.
- Return `Option` for invalid or not-yet-computable values.
- Avoid creating non-finite power/torque values.

Correction responsibilities:

- Parse correction mode.
- Calculate dry-air pressure and vapor pressure.
- Return both factor and quality.

Engineering notes:

- `FusionTask` stores only prior angular velocity and timestamp for inertial calculations.
- Calibration changes are read live from a watch channel.
- Preserve the distinction between `engine_rpm` and `roller_rpm`; they are independent signal paths.

## Run Control

File: `crates/dyno-core/src/run_control.rs`

Responsibility:

- Store operator intent for current run.
- Normalize license plate.
- Build run labels.
- Track configured, started, and recording flags.

Public operations:

- `configure(license_plate)`
- `start()`
- `stop()`
- `snapshot()`
- `update_runtime_state(run_state)`

Important behavior:

- `start()` only marks operator intent; fusion determines when `Recording` is actually reached.
- `stop()` clears started/recording intent.
- API responses expose `configured`, `started`, `recording`, `run_label`, and `license_plate`.

## Storage

File: `crates/dyno-core/src/storage.rs`

Storage uses SQLite through `rusqlite`.

Tables:

- `runs`: one row per recorded dyno run.
- `frames`: frame series for a run.
- `calibration_profiles`: saved calibration profiles.
- `calibration_profile_events`: audit/event history for profile changes.

Architecture:

- `Storage` is an async handle.
- SQLite work happens on a single blocking worker thread.
- Callers send commands through a bounded Tokio MPSC queue.
- `StorageTask` watches live frames and forwards frames to storage.

Recording behavior:

- A run starts when a live frame enters a recording state.
- Frames are appended while recording/stopping.
- A run closes when an idle frame follows an active run.

Query behavior:

- Recent runs are ordered newest first.
- Peak power/torque are calculated in SQL queries over stored frames.
- Frame queries return time-ordered series for charting and export.

Calibration behavior:

- A default bootstrap profile is created if none exists.
- Only one profile can be active due to a partial unique index.
- Create/update/duplicate/activate operations write profile events.

Engineering notes:

- Keep schema changes backward-compatible through migrations.
- Add query tests for any storage behavior that affects API contracts.
- Do not put direct SQLite access in API or fusion modules.

## HTTP API

File: `crates/dyno-core/src/api.rs`

Framework: Axum.

Responsibilities:

- Define route table.
- Define API DTOs.
- Translate storage/run-control/calibration operations into JSON responses.
- Map errors into stable HTTP status and `{ "error": "..." }` bodies.

Route groups:

- Health: `/healthz`
- Run control: `/api/run/*`
- Development seeding: `/api/dev/seed-run`
- Calibration: `/api/calibration*`
- Run history: `/api/runs*`

Engineering notes:

- Keep API layer thin. Storage access belongs in `storage.rs`.
- DTO names are explicit and should stay stable for the Java clients.
- If changing routes or response fields, update Java DTO/client tests in the same change.

## WebSocket Broadcast

File: `crates/dyno-core/src/ws.rs`

Responsibility:

- Bind the configured TCP address.
- Accept WebSocket clients.
- Send the current latest `LiveFrame` immediately.
- Send each subsequent latest `LiveFrame`.

Message shape:

```json
{
  "type": "live_frame",
  "data": { "...": "LiveFrame fields" }
}
```

Important behavior:

- Uses a `watch::Receiver`; slow clients skip stale frames.
- Each client gets its own spawned connection task.
- Suspicious values are logged before send.
- The server is a raw WebSocket listener on the bind address, not an Axum route.

## Health

File: `crates/dyno-core/src/health.rs`

Startup checks include:

- Database parent path creation/writability.
- Live serial path availability.
- Optional Pi-side BME280 I2C device availability.

Impact:

- Database path errors are fatal.
- Missing serial/I2C can be degraded, depending on mode/config.
- `/healthz` returns the cached startup result.

## Replay

File: `crates/dyno-core/src/replay.rs`

Replay produces synthetic `LiveFrame` values for UI/testing without live hardware.

Use cases:

- UI development.
- Demo environments.
- Backend smoke tests.

It still publishes through the same live frame channel as live mode, so WebSocket/storage consumers do not need special cases.

## State Machine

File: `crates/dyno-core/src/state.rs`

This is a small generic backend state holder. Current run-state logic primarily lives in `fusion.rs` and `run_control.rs`, but `StateMachine` remains part of `App`.

## Backend Test Map

High-value test targets:

- `api.rs`: route contracts and error mapping.
- `storage.rs`: schema, run lifecycle, calibration events, queries.
- `fusion.rs`: physical calculations, run-state transitions, fault/alert mapping.
- `physics.rs`: unit math and invalid-input handling.
- `correction.rs`: correction factors and quality.
- `serial.rs` / `esp32_json.rs`: telemetry parsing and mapping.
- `ws.rs`: envelope shape.
- `config.rs`: environment defaults and parsing.

## Backend Change Checklist

When changing backend behavior:

1. Identify whether the change affects a public DTO or route.
2. Update Java DTO/client code if API or WebSocket fields change.
3. Update firmware/protocol docs if wire telemetry changes.
4. Add tests in the module that owns the behavior.
5. Check production env defaults and operations docs for new settings.
6. Keep generated build outputs out of the intended source change unless explicitly requested.
