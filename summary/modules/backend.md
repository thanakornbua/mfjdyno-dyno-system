# Backend Module

## Responsibility

The backend is the Rust service that turns dyno acquisition data into durable run history and live UI telemetry. It owns hardware ingestion, physical calculations, runtime run state, calibration, persistence, HTTP APIs, and WebSocket broadcast.

The backend binary is `dynod`, built from `crates/dyno-core`.

## Entry Points

- `crates/dyno-core/src/main.rs`
  - Initializes tracing.
  - Loads `Config::from_env()` (fallible: propagates data-directory and port-bind failures so the process exits non-zero instead of running half-alive).
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

- `DYNO_DATA_DIR`: fixed per-machine data directory (see `crates/dyno-core/src/paths.rs`). Precedence: this override → `/var/lib/dyno` if usable → `$XDG_DATA_HOME/dyno` → `~/.local/share/dyno`. Created at startup; failure to create/write any candidate is fatal.
- `DYNO_SERIAL_PORT`: ESP32 UART path, default `auto` (detects the devkit's onboard USB-UART bridge — CP210x, CH340, or Espressif USB-CDC — under `/dev/serial/by-id`, falls back to `/dev/ttyUSB0`). This single cable also handles firmware flashing; see `serial_gate.rs`.
- `DYNO_SERIAL_BAUD`: UART baud, default `115200`.
- `DYNO_CAN_IFACE`: SocketCAN interface, default `auto` (first CAN-type interface in `/sys/class/net`, falls back to `can0`).
- `DYNO_PROFILE`: runtime profile label, default `production`.
- `DYNO_MODBUS_AFR_ENABLED`: legacy path flag, normally `false`.
- `DYNO_WS_BIND`: WebSocket bind address, default `0.0.0.0:9000`.
- `DYNO_API_BIND`: HTTP bind address, default `0.0.0.0:9001`.
- `DYNO_STORAGE_DB_PATH` or `DYNO_DB_PATH`: SQLite database path, default `dyno.db` anchored inside `data_dir`. An explicit value (relative or absolute) is used verbatim instead of being anchored.
- `DYNO_ESP32_CONFIG_PATH`: desired ESP32 device config JSON, default anchored inside `data_dir` the same way.
- `DYNO_ESP32_APPLIED_CONFIG_PATH`: persisted last-applied ESP32 config state, default anchored inside `data_dir` the same way.
- `DYNO_SOURCE_MODE`: `live`, `replay`, `sim`, or `simulation`.
- `DYNO_CORRECTION_MODE`: correction model selector.
- `DYNO_ARM_RPM`, `DYNO_RECORD_RPM`: run state/recording thresholds.
- `DYNO_STOP_RPM`: deprecated compatibility setting; parsed but no longer stops runs.
- `DYNO_SYSTEM_PASSWORD`: seeds the system password on a fresh database only. If unset, no password exists until an operator sets one via `POST /api/system/setup-password`.

Engineering notes:

- `Config` is intentionally plain and cloned into tasks as needed.
- `Config::from_env()` is fallible (`anyhow::Result<Self>`) because it resolves and creates `data_dir` as a side effect; startup fails fast if no candidate directory is writable.
- Tests use direct `Config` construction (with a `data_dir` field) for deterministic setup; env-driven tests must pin `DYNO_DATA_DIR` to a throwaway temp directory.
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
- Update run state from operator start/stop intent plus the recording RPM threshold.

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

- A run row opens on the first `Recording` frame after operator start.
- `Recording` and legacy `Stopping` frames are appended.
- `Armed` frames during an active run are treated as a pause: they keep the run open but are not persisted.
- `Idle` or `Fault` closes the active run. Operator stop records and flushes a synthetic idle frame before the API response returns.

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
- System password: `/api/system/password`, `/api/system/verify-password`, `/api/system/setup-status`, `/api/system/setup-password` — `setup-status`/`setup-password` implement the first-boot flow (see "First boot" in `docs/OPERATIONS.md`); `verify-password`, `change-password`, and calibration lock/unlock return `409 {"error":"setup_required"}` instead of `401` while no password has been set yet.
- System devices: `GET /api/system/serial-devices` (enumerated ports + persisted read/flash selection), `POST /api/system/devices` (persist `read_serial_port`/`flash_serial_port`, validated as `/dev/*` paths). The persisted read port overrides autodetection in `app.rs` (precedence: explicit `DYNO_SERIAL_PORT` env > persisted > autodetect).
- ESP flashing: `POST /api/system/flash-esp` (single-flight; rejects while recording; runs on a background task), `GET /api/system/flash-esp/status` (`idle|running|success|error` + captured log). Implemented in `flash.rs`; see below. Before starting a job the handler runs the dependency check (on `spawn_blocking`) and returns `400 {"error": "cannot flash: the flash toolchain is incomplete. ..."}` (with remediation text) when any check with `blocks_flashing: true` is an explicit `missing`; `unknown` does not block.
- Dependency check: `GET /api/system/dependencies` → `{ "dependencies": [...] }`, one entry per `deps::check_dependencies` result (`{ name, category, required, status, detail, remediation, blocks_flashing }`, `status` one of `ok|missing|unknown`). Runs on `spawn_blocking` (the check probes the filesystem and may wait on `arduino-cli core list`). Implemented in `deps.rs`; see below.

`ApiTask::spawn` binds its `TcpListener` before spawning the server task and returns `anyhow::Result<Self>`, so a port already in use (e.g. a second `dynod` instance) is a startup error rather than a silently-logged no-op. `WsTask::spawn` (`ws.rs`) follows the same pattern.

Engineering notes:

- Keep API layer thin. Storage access belongs in `storage.rs`.
- DTO names are explicit and should stay stable for the Java clients.
- If changing routes or response fields, update Java DTO/client tests in the same change.

## ESP Firmware Flashing

File: `crates/dyno-core/src/flash.rs`

Responsibility:

- Run `arduino-cli compile` then `upload -p <flash_port>` against a configurable sketch/FQBN
  (defaults match `tools/flash-esp32.sh`; overridable via `DYNO_FLASH_TOOL`/`DYNO_FLASH_FQBN`/
  `DYNO_FLASH_SKETCH`).
- Command execution is behind the `CommandRunner` trait so tests inject a fake and assert argv
  without a real toolchain; `SystemCommandRunner` is the production impl.
- The firmware sketch is embedded in the binary via `include_str!` and staged to a temp dir when
  the configured `DYNO_FLASH_SKETCH` is not present on disk, so a binary-only install can flash
  without shipping firmware sources. An on-disk sketch (dev checkout / explicit path) still wins.
- `FlashJob` holds single-flight status (`try_begin` guards concurrent flashes); `FlashStatus`
  is polled by the API. Preflight fails fast with an actionable message when the tool or sketch
  is missing (no hang); the log is size-capped.

The API runs `run_flash` on `spawn_blocking` (arduino-cli is blocking/long-running) inside a
spawned task, then writes an `esp_flash_finished` audit record.

## Dependency Check

File: `crates/dyno-core/src/deps.rs`

Consolidates dependency/package checks that were previously scattered across `flash.rs`
(arduino-cli preflight) and `detect.rs` (device enumeration) into one report:
`check_dependencies(&Config) -> Vec<DependencyCheck>`.

Checks: `arduino_cli` on `PATH` (reuses `flash::SystemCommandRunner::tool_available`, does not
reimplement PATH scanning; `flash-toolchain`, optional), `arduino_esp32_core` (best-effort
`arduino-cli core list` with a 5s timeout so it can never hang; `unknown` if arduino-cli is
missing or unresponsive), `firmware_sketch` (always `ok` — on-disk sketch or the binary-embedded
fallback), `serial_device` (via `detect::list_serial_devices()`; required in `live` source mode,
optional in `replay`), `can_interface` (best-effort presence under `/sys/class/net`; `unknown` is
expected/fine when no CAN AFR source is wired). Each check carries `blocks_flashing`
(true for `arduino_cli` and `arduino_esp32_core`) — the single source of truth for which
dependencies gate the flash endpoint and the wizard's flash button.

`main.rs` runs this once at startup (after device autodetection) and logs one line per
dependency — `warn` only for a missing *required* dependency, `info` otherwise. Missing optional
dependencies never fail startup. The same report is exposed live via
`GET /api/system/dependencies` and is what step 4 of the JavaFX first-boot wizard renders.

## Serial Device Enumeration

File: `crates/dyno-core/src/detect.rs`

Alongside boot-time autodetection, `list_serial_devices()` enumerates `/dev/serial/by-id` plus
raw `/dev/ttyUSB*`/`/dev/ttyACM*` nodes (de-duplicated by resolved path, ESP32 guesses first)
for the first-boot device picker.

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
- `WsTask::spawn` binds before spawning and returns `anyhow::Result<Self>`; a bind failure is a startup error, not a background log line.

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
- `ws.rs`: envelope shape, bind-fails-fast behavior.
- `config.rs`: environment defaults and parsing, data-directory anchoring.
- `paths.rs`: data directory resolution precedence and anchoring helpers.
- `flash.rs`: compile+upload argv, single-flight guard, preflight failures.
- `detect.rs`: serial device enumeration + de-dup + ESP-guess ordering.
- `deps.rs`: per-check status/required logic (fixture-based, no real toolchain/hardware).

## Backend Change Checklist

When changing backend behavior:

1. Identify whether the change affects a public DTO or route.
2. Update Java DTO/client code if API or WebSocket fields change.
3. Update firmware/protocol docs if wire telemetry changes.
4. Add tests in the module that owns the behavior.
5. Check production env defaults and operations docs for new settings.
6. Keep generated build outputs out of the intended source change unless explicitly requested.
