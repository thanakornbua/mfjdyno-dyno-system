# Shared Rust Crates

## Overview

The Rust workspace contains shared crates that define the backend's public domain data and device protocol. Treat these crates as contract modules: changes often affect backend storage, WebSocket payloads, Java DTOs, ESP32 firmware, and tests.

Workspace members:

- `crates/dyno-types`
- `crates/dyno-protocol`
- `crates/dyno-core`
- `crates/dyno-sim`

This file covers `dyno-types`, `dyno-protocol`, and `dyno-sim`. `dyno-core` is covered in [backend.md](backend.md).

## `dyno-types`

Path: `crates/dyno-types`

Responsibility:

- Shared serializable domain types.
- Frontend-facing live telemetry shape.
- Stable run/fault/alert enums.

Modules:

- `live_frame.rs`
  - Defines `LiveFrame`, the primary WebSocket payload data object.
  - Fields include timestamps, engine/roller RPM, speed, power, torque, correction factor, AFR/lambda, CAN status, ambient values, ESP32 status, run state, faults, and alerts.

- `run_state.rs`
  - Defines `RunState`.
  - Expected states include `Idle`, `Armed`, `Recording`, `Stopping`, and fault-like states where applicable.
  - Serialized names are consumed by Java UI logic, so renaming is a contract change.

- `esp32_status.rs`
  - Defines `Esp32TelemetryStatus`.
  - Carries interpreted acquisition status from ESP32 signal flags and raw counters.

- `fault.rs`
  - Defines `FaultCode`.
  - Backend fusion maps firmware/protocol fault bits into these codes.

- `alert.rs`
  - Defines `AlertLevel` and `LiveAlerts`.
  - Used by UI presenters to choose status tones.

- `run_summary.rs`
  - Defines backend-side run summary data.
  - Storage converts stored runs into summaries for query/use cases.

Important contract:

- `LiveFrame` is sent as JSON inside the WebSocket envelope `{ "type": "live_frame", "data": ... }`.
- Java `FrameMessage` must remain compatible with the serialized field names.
- Optional measurements are represented with `Option` in Rust and nullable `Double`/boxed values in Java.

Change guidance:

- Adding an optional field is usually compatible.
- Removing/renaming fields is breaking for Java clients and fixtures.
- Enum renames are breaking because the frontend compares strings such as `RECORDING`.
- Update `crates/dyno-types/src/tests.rs` when changing serialization.

## `dyno-protocol`

Path: `crates/dyno-protocol`

Responsibility:

- Defines the binary command/telemetry protocol shared with the ESP32 firmware.
- Provides frame structures, packet encoders/decoders, command payload builders, response parsers, device config structures, and protocol errors.

Modules:

- `frame.rs`
  - Defines `DynoFrameV1`, the normalized acquisition frame consumed by fusion.
  - Defines decoded frame support and CRC helpers.
  - Field meanings must match firmware `TelemetryFrame`.

- `codec.rs`
  - Streaming frame decoder for telemetry bytes.
  - Tracks decode status and framing/CRC correctness.

- `transport.rs`
  - Command/response packet decoder.
  - Converts raw wire bytes into typed `WirePacket` values.

- `packet.rs`
  - Defines packet type IDs and conversion logic.

- `command.rs`
  - Builds command packets such as ping, get/set/apply config, and device info requests.

- `response.rs`
  - Parses ACK, error, config, and device-info responses.
  - Defines protocol error code mapping.

- `config.rs`
  - Defines `DynoConfig`, the backend-side copy of the ESP32 DAQ configuration structure.
  - Defines `EngineEdgeMode`.
  - Must stay binary-compatible with firmware `DynoConfig`.

- `device_info.rs`
  - Defines `DeviceInfo`, firmware version and hardware identity payload.

- `error.rs`
  - Defines `ProtocolError`.

Important protocol constants:

- Command magic: firmware uses `0xD5 0x2B`.
- Telemetry magic: firmware uses `0x5A 0xA5`.
- Protocol version is currently `0x01`.
- Firmware version constants currently appear in `firmware/src/protocol.h`.

Change guidance:

- Any binary layout change must be coordinated across:
  - `dyno-protocol`
  - `firmware/src/protocol.h`
  - `firmware/src/config_store.h`
  - backend ESP32 config sync
  - docs/examples
  - protocol tests
- Keep `static_assert` sizes in firmware and Rust tests aligned.
- Do not reorder binary struct fields unless all decoders/encoders are updated together.

## `dyno-sim`

Path: `crates/dyno-sim`

Responsibility:

- Simulation support that depends on `dyno-types` and `dyno-core`.
- It is separate from the main backend service so simulated data paths can evolve without bloating the production binary entry point.

Current related runtime path:

- `crates/dyno-core/src/replay.rs` provides replay-mode live frames.
- `DYNO_SOURCE_MODE=replay` starts replay instead of hardware acquisition.

Engineering use cases:

- UI development without hardware.
- Backend WebSocket/client smoke testing.
- Golden fixtures for live frame shape.

## Cross-Crate Data Flow

Normal live mode:

1. Firmware sends telemetry.
2. Backend parses it into `dyno_protocol::DynoFrameV1`.
3. Fusion converts `DynoFrameV1` plus CAN/ambient/calibration into `dyno_types::LiveFrame`.
4. WebSocket serializes `LiveFrame` as JSON for Java.
5. Storage stores selected `LiveFrame` fields into SQLite.

ESP32 config path:

1. Desired config JSON is deserialized into `dyno_protocol::DynoConfig`.
2. Backend validates it in `esp32_config.rs`.
3. Backend sends command packets built by `dyno-protocol`.
4. Firmware applies/stages config and replies with protocol responses.

## Compatibility Rules

- `dyno-types` controls JSON compatibility.
- `dyno-protocol` controls binary compatibility.
- `dyno-core` controls runtime behavior and persistence.
- Java DTOs mirror `dyno-types` and `dyno-core::api` DTOs.
- Firmware structs mirror selected `dyno-protocol` structs.

Before changing shared crates, answer:

1. Is this JSON-visible to the Java UI?
2. Is this stored in SQLite?
3. Is this binary-visible to the ESP32 firmware?
4. Is this represented in deployment config or examples?
5. Are fixtures or contract tests affected?
