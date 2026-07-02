# End-to-End Workflows

## Overview

This file explains how the main product workflows cross module boundaries. Use it when you need to trace a feature from hardware through backend, storage, API, and UI.

## Live Telemetry Workflow

Goal:

- Show live dyno measurements in the operator console.

Path:

1. ESP32 firmware reads hardware signals.
2. Firmware sends telemetry over serial.
3. Backend `SerialTask` reads JSON telemetry.
4. Backend maps telemetry into `DynoFrameV1`.
5. Backend CAN task supplies AFR/lambda.
6. Backend ambient path supplies temperature, humidity, and pressure.
7. Fusion computes `LiveFrame`.
8. WebSocket broadcasts `{ "type": "live_frame", "data": LiveFrame }`.
9. Java `DynoWebSocketClient` parses the frame.
10. `LiveTelemetryState` publishes a snapshot.
11. Presenters build display models.
12. JavaFX views render gauges, tiles, charts, state badges, and banners.

Key backend files:

- `firmware/src/main.cpp`
- `firmware/src/telemetry.cpp`
- `crates/dyno-core/src/serial.rs`
- `crates/dyno-core/src/esp32_json.rs`
- `crates/dyno-core/src/can.rs`
- `crates/dyno-core/src/fusion.rs`
- `crates/dyno-core/src/ws.rs`
- `crates/dyno-types/src/live_frame.rs`

Key frontend files:

- `java/src/main/java/com/dyno/ws/DynoWebSocketClient.java`
- `java/src/main/java/com/dyno/model/FrameMessage.java`
- `java/src/main/java/com/dyno/state/LiveTelemetryState.java`
- `java/src/main/java/com/dyno/presenter/TelemetryPresenter.java`
- `java/src/main/java/com/dyno/presenter/LiveDynoChartPresenter.java`
- `java/src/main/java/com/dyno/fx/LiveRunShellView.java`

Failure points:

- Serial port unavailable or wrong baud.
- Firmware emits invalid or unexpected telemetry.
- CAN unavailable.
- Backend WebSocket bind blocked.
- UI points to wrong WebSocket URI.

## Run Recording Workflow

Goal:

- Store a dyno run and make it available for history, comparison, and export.

Path:

1. Operator opens run mode and starts a run.
2. UI calls `POST /api/run/configure`.
3. UI calls `POST /api/run/start`.
4. Backend `RunControl` marks the run configured/started.
5. Fusion observes operator start intent and the recording RPM threshold.
6. Fusion changes runtime state to `Armed` below threshold or `Recording` at/above threshold.
7. `StorageTask` watches live frames.
8. Storage opens a run when recording starts.
9. Storage appends frames during `Recording`/legacy `Stopping`.
10. If RPM dips back to `Armed`, storage pauses collection but keeps the active run open.
11. Storage closes the run on operator stop (`Idle`) or `Fault`.
12. UI calls history APIs to list/fetch stored run data.

Key backend files:

- `crates/dyno-core/src/api.rs`
- `crates/dyno-core/src/run_control.rs`
- `crates/dyno-core/src/fusion.rs`
- `crates/dyno-core/src/storage.rs`

Key frontend files:

- `java/src/main/java/com/dyno/control/RunControlApiClient.java`
- `java/src/main/java/com/dyno/fx/RunConfigureDialog.java`
- `java/src/main/java/com/dyno/fx/RunControlUiState.java`
- `java/src/main/java/com/dyno/fx/OperatorConsoleStage.java`
- `java/src/main/java/com/dyno/presenter/LiveDynoChartPresenter.java`

Important details:

- `start()` means operator intent, not guaranteed physical recording.
- Recording begins only when fusion sees engine RPM at/above `DYNO_RECORD_RPM`.
- RPM below `DYNO_RECORD_RPM` pauses collection; it does not split or end the run.
- `stop()` records and flushes an idle frame when needed to force run closure before the response returns.
- Stored run peaks are calculated from stored frame data.

## Run History and Compare Workflow

Goal:

- List past runs, inspect one run, and compare multiple runs.

Path:

1. UI requests recent run list with `GET /api/runs`.
2. Backend queries SQLite `runs` and aggregate frame peaks.
3. UI displays selectable runs.
4. For a single run, UI calls `GET /api/runs/:id` and `GET /api/runs/:id/frames`.
5. For compare, UI calls `POST /api/runs/compare` with selected IDs.
6. Backend returns run detail plus frame series for each selected run.
7. `CompareDisplayMapper` converts responses into chart/table display state.

Key backend files:

- `crates/dyno-core/src/api.rs`
- `crates/dyno-core/src/storage.rs`

Key frontend files:

- `java/src/main/java/com/dyno/history/HistoryApiClient.java`
- `java/src/main/java/com/dyno/fx/CompareSelectView.java`
- `java/src/main/java/com/dyno/fx/CompareDataView.java`
- `java/src/main/java/com/dyno/presenter/CompareDisplayMapper.java`
- `java/src/main/java/com/dyno/presenter/CompareDisplayState.java`

Important details:

- Compare response includes both run metadata and full frame series.
- Java display code should tolerate missing optional measurements.
- Delete uses `DELETE /api/runs/:id` and cascades frame deletion through storage logic/schema.

## Calibration Workflow

Goal:

- Manage physical dyno parameters used by live calculations.

Path:

1. Backend storage ensures an active default profile exists at startup.
2. Backend validates the active profile before allowing startup to complete.
3. UI opens calibration dialog.
4. UI calls `GET /api/calibration` and `GET /api/calibration/profiles`.
5. Operator creates, edits, duplicates, or activates a profile.
6. Backend validates request values.
7. Storage writes profile and event history.
8. If the profile becomes active, backend publishes it through calibration watch channel.
9. Fusion reads the current active calibration and applies it to subsequent frames.

Key backend files:

- `crates/dyno-core/src/calibration.rs`
- `crates/dyno-core/src/storage.rs`
- `crates/dyno-core/src/api.rs`
- `crates/dyno-core/src/fusion.rs`

Key frontend files:

- `java/src/main/java/com/dyno/calibration/CalibrationApiClient.java`
- `java/src/main/java/com/dyno/calibration/CalibrationDraftValidator.java`
- `java/src/main/java/com/dyno/fx/CalibrationDialog.java`

Important details:

- Backend validation is authoritative.
- Frontend validation is for faster feedback.
- Active calibration changes do not require backend restart.
- Calibration parameters affect speed, power, torque, and correction behavior.

## ESP32 Config Sync Workflow

Goal:

- Keep the ESP32 DAQ runtime configuration aligned with backend-desired config.

Path:

1. Backend loads desired config JSON from `DYNO_ESP32_CONFIG_PATH`.
2. Backend reads last applied config state from `DYNO_ESP32_APPLIED_CONFIG_PATH`.
3. Backend opens serial command link.
4. Backend requests device info/current config.
5. Backend validates desired config.
6. If needed, backend sends config set/apply command packets.
7. Firmware stages and commits the config.
8. Firmware returns ACK or config error.
9. Backend writes last-applied state.
10. Live telemetry startup continues.

Key backend files:

- `crates/dyno-core/src/esp32_config.rs`
- `crates/dyno-core/src/serial_link.rs`
- `crates/dyno-protocol/src/command.rs`
- `crates/dyno-protocol/src/response.rs`
- `crates/dyno-protocol/src/config.rs`

Key firmware files:

- `firmware/src/protocol.cpp`
- `firmware/src/config_store.cpp`
- `firmware/src/config_validator.cpp`
- `firmware/src/main.cpp`

Important details:

- Retryable serial readiness errors can skip config sync during startup.
- Non-retryable config errors stop startup.
- UART reinit is handled carefully because ACK must be sent before changing baud.

## Export Workflow

Goal:

- Export run or compare data as PDF, PNG, CSV, and JSON.

Single run path:

1. UI selects one run.
2. UI fetches run detail and frames.
3. UI captures chart snapshot if PNG/PDF chart output is needed.
4. `ExportService.exportSingleRun` writes selected formats.
5. UI reports exported paths and per-format errors.

Compare path:

1. UI compares selected runs.
2. UI keeps last compare response.
3. UI captures compare chart snapshot if needed.
4. `ExportService.exportCompare` writes selected formats.
5. CSV writes one file per run; JSON/PDF use combined compare data.

Key frontend files:

- `java/src/main/java/com/dyno/export/ExportService.java`
- `java/src/main/java/com/dyno/export/DynoPdfExporter.java`
- `java/src/main/java/com/dyno/export/DynoCsvExporter.java`
- `java/src/main/java/com/dyno/export/DynoJsonExporter.java`
- `java/src/main/java/com/dyno/export/DynoPngExporter.java`
- `java/src/main/java/com/dyno/fx/ExportDialog.java`
- `java/src/main/java/com/dyno/fx/ExportRunPickerView.java`

Important details:

- JavaFX snapshots must be captured on the FX Application Thread.
- File writing should run in a background thread.
- PDF has Thai/English labels and depends on font discovery.

## Health and Recovery Workflow

Goal:

- Keep operators informed and allow recovery without restarting the UI.

Path:

1. Backend collects startup health once at startup.
2. Backend exposes cached result via `/healthz`.
3. Java UI polls health endpoint.
4. `OperatorStatusMapper` converts checks into operator-facing status.
5. WebSocket client independently reconnects for live telemetry.

Common degraded states:

- Serial missing: backend logs retries; UI may show disconnected/no live data.
- BME280 missing: backend uses fallback/sanitized ambient data.
- CAN missing/stale: live frame contains CAN/AFR status fields.
- Backend offline: WebSocket reconnects; HTTP actions fail on demand.

## Safe Change Strategy

For cross-cutting changes:

1. Start from the owner module.
2. Identify downstream contracts.
3. Update tests at each boundary.
4. Keep DTO changes additive when possible.
5. Verify with replay mode before live hardware when practical.
6. Verify with live hardware before calling a hardware/protocol change complete.
