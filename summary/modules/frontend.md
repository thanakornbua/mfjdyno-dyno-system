# Frontend Module

## Responsibility

The frontend is the Java 21 JavaFX operator console. It gives the operator a live dyno view, run controls, calibration management, run comparison, and export/print workflows.

Path: `java/`

Main class: `com.dyno.fx.OperatorConsoleApp`

Build file: `java/build.gradle`

## Launch and Build

Gradle tasks:

- `gradle run`
- `gradle runOperatorConsoleFx`
- `gradle runOperatorConsoleFxWindowed`
- `gradle runOperatorConsoleFxFullscreen`
- `gradle test`

Runtime entry:

- `java/src/main/java/com/dyno/fx/OperatorConsoleApp.java`
  - JavaFX `Application`.
  - Creates `OperatorConsoleStage`.

Shell/orchestration:

- `java/src/main/java/com/dyno/fx/OperatorConsoleStage.java`
  - Main composition root for the operator console.
  - Creates API clients, live telemetry state, WebSocket client, presenters, shell view, health polling, and run-control actions.
  - Owns background executor services for API calls and health polling.

## Endpoint Configuration

File: `java/src/main/java/com/dyno/config/EndpointConfig.java`

Defaults:

- API base URL: `http://127.0.0.1:9001`
- WebSocket URI: `ws://127.0.0.1:9000`
- Control API base URL: same as API base URL unless overridden.

Resolution order:

- API:
  - JVM property `dyno.api.base_url`
  - `DYNO_UI_API_BASE_URL`
  - `DYNO_BACKEND_HOST` plus `DYNO_BACKEND_HTTP_PORT`
  - default

- WebSocket:
  - JVM property `dyno.ws.uri`
  - `DYNO_UI_WS_URI`
  - `DYNO_BACKEND_HOST` plus `DYNO_BACKEND_WS_PORT`
  - default

- Control API:
  - JVM property `dyno.control.api.base_url`
  - JVM property `DYNO_CONTROL_API_BASE_URL`
  - environment `DYNO_CONTROL_API_BASE_URL`
  - API base URL

Debug:

- `DYNO_UI_WS_DEBUG` or JVM property `dyno.ws.debug` enables additional WebSocket frame logging.

Engineering notes:

- Prefer `EndpointConfig` over hardcoded URLs.
- Keep systemd env examples aligned with new endpoint variables.

## Live Telemetry Path

Files:

- `com.dyno.ws.DynoWebSocketClient`
- `com.dyno.model.FrameMessage`
- `com.dyno.state.LiveTelemetryState`
- `com.dyno.state.LiveTelemetrySnapshot`
- `com.dyno.state.ConnectionPhase`

Flow:

1. `OperatorConsoleStage` creates `LiveTelemetryState`.
2. `DynoWebSocketClient` connects to `EndpointConfig.wsUri()`.
3. Backend sends JSON envelopes with `type=live_frame`.
4. Client unwraps `data`, `payload`, or `frame` wrappers defensively.
5. Client maps the live frame object into `FrameMessage`.
6. `LiveTelemetryState.updateFrame` publishes a new immutable snapshot.
7. Presenters listen for snapshot changes and build render models.

Connection behavior:

- Connect timeout is 5 seconds.
- Reconnect backoff doubles up to 30 seconds.
- UI starts even if backend is down.
- `LiveTelemetryState.updateConnection` records phase and operator-facing message.

Important parser behavior:

- `DynoWebSocketClient` accepts current backend envelope `{ "type": "live_frame", "data": ... }`.
- It also tolerates nested `payload`/`frame` wrappers for compatibility.
- It detects frame objects by fields such as `engine_rpm`, `roller_rpm`, `power_hp`, `lambda`, or `run_state`.

## State and Presenter Layer

Packages:

- `com.dyno.state`
- `com.dyno.presenter`
- `com.dyno.model`

Purpose:

- Keep JavaFX views mostly passive.
- Convert backend DTOs into display-ready strings, tones, chart series, and button states.
- Keep chart accumulation and run identity logic testable outside the UI.

Key classes:

- `LiveTelemetryState`
  - Holds connection phase, latest `FrameMessage`, recording flag, and run chart points.
  - Publishes JavaBeans property changes.
  - Clears chart points when entering recording.
  - Appends chart points only when engine RPM/power/torque are present, non-negative, and RPM is monotonic.

- `TelemetryPresenter`
  - Builds `OperatorViewModel` for gauges, tiles, banner, state text, peaks, and secondary metrics.
  - Tracks peak power/torque during recording.
  - Calculates display lambda from backend lambda or AFR fallback.

- `LiveDynoChartPresenter`
  - Builds chart-specific model.
  - Handles run control state, axis selection, dataset lifecycle, disconnected-during-run state, and chart series.

- `CompareDisplayMapper`
  - Converts compare API responses into display state for compare views.

- `RunIdentityState`
  - Tracks license plate, run number, and labels used by presenter/UI workflows.

- `GaugeThresholdProfile`
  - Encapsulates tone thresholds for gauges.

Engineering notes:

- Put formatting, tone selection, and chart rules in presenters, not views.
- Use boxed/nullable DTO values because backend measurements are optional.
- Keep presenter tests focused on behavior, not JavaFX rendering.

## JavaFX Views

Package: `com.dyno.fx`

Main view classes:

- `OperatorConsoleStage`
  - Application composition and control workflow.

- `LiveRunShellView`
  - Main dashboard/run shell.
  - Renders telemetry, chart, compare panels, and header.

- `HeaderBarView`
  - Status, run labels, toolbar actions, connection/operator health, and banner.

- `LiveDynoChartView`
  - Chart rendering for live and compare data.

- `GaugeCardView`, `MetricTileView`, `SecondaryMetricView`
  - Reusable metric display components.

- `RunConfigureDialog`
  - Run start configuration, including license plate and chart axis selection.

- `CalibrationDialog`
  - Calibration profile management UI.

- `CompareSelectView`, `CompareDataView`
  - Historical run selection and comparison display.

- `ExportDialog`, `ExportRunPickerView`
  - Export/print workflows.

- `FxTheme`, `UiText`, `UiLaunchConfig`, `RunControlUiState`
  - Theme, text, launch mode, and run-control UI state.

Lifecycle:

1. `OperatorConsoleStage.show` builds the scene.
2. Initial models are rendered before backend connection.
3. Width/height listeners apply responsive layout.
4. WebSocket starts after `stage.show()`.
5. Health polling starts after WebSocket startup.
6. Close request stops WebSocket and executors.

Threading:

- UI updates must run on the JavaFX Application Thread via `Platform.runLater`.
- Network/API work runs on background executors.
- Chart snapshots for PNG/PDF export must be captured on the FX thread before background export work.

## API Client Packages

### History

Package: `com.dyno.history`

Client: `HistoryApiClient`

Endpoints:

- `GET /api/runs`
- `GET /api/runs/{id}`
- `GET /api/runs/{id}/frames`
- `POST /api/runs/compare`
- `DELETE /api/runs/{id}`

DTOs:

- `RunHistorySummaryDto`
- `RunHistoryDetailDto`
- `RunHistoryFrameDto`
- `RunHistoryFrameSeriesDto`
- `CompareRunsRequestDto`
- `CompareRunsResponseDto`
- `ComparedRunDto`
- `DeleteRunResponseDto`

Behavior:

- Request timeout is 5 seconds.
- Closed keep-alive connections are retried once.
- Error bodies are parsed for `{ "error": "..." }`.

### Calibration

Package: `com.dyno.calibration`

Client: `CalibrationApiClient`

Endpoints:

- `GET /api/calibration`
- `GET /api/calibration/profiles`
- `POST /api/calibration/profiles`
- `PUT /api/calibration/profiles/{id}`
- `POST /api/calibration/profiles/{id}/duplicate`
- `POST /api/calibration/activate`
- `GET /api/calibration/profiles/{id}/events`

DTOs:

- `CalibrationProfileDto`
- `CalibrationResponseDto`
- `CalibrationValidationDto`
- `CalibrationUpsertRequestDto`
- `ActivateCalibrationRequestDto`
- `DuplicateCalibrationProfileRequestDto`
- `CalibrationProfileEventDto`

Validation:

- `CalibrationDraftValidator` mirrors frontend-side constraints so invalid forms can be caught before submitting.
- Backend validation remains authoritative.

### Run Control

Package: `com.dyno.control`

Client: `RunControlApiClient`

Endpoints:

- `POST /api/run/configure`
- `POST /api/run/start`
- `POST /api/run/stop`
- `GET /api/run/status`

DTOs:

- `RunConfigureRequest`
- `RunControlResponse`

Behavior:

- Request timeout is 4 seconds.
- Parse failures are converted into failure responses instead of exceptions escaping into UI logic.
- `OperatorConsoleStage` uses configure then start as one user action.

### Health

Package: `com.dyno.health`

Client: `HealthApiClient`

Endpoint:

- `GET /healthz`

Classes:

- `StartupHealthDto`
- `StartupCheckDto`
- `OperatorStatusMapper`
- `OperatorStatusModel`

Purpose:

- Poll backend startup health.
- Convert backend health checks into concise operator status messages and tones.

## Export Module

Package: `com.dyno.export`

Responsibility:

- Export a single run or compare view to PDF, PNG, CSV, and JSON.

Key classes:

- `ExportService`
  - Orchestrates multi-format export.
  - Creates output directories.
  - Collects per-format successes and errors.

- `DynoPdfExporter`
  - Produces run and compare PDF reports.
  - Includes Thai/English report labels and chart rendering.

- `DynoCsvExporter`
  - Writes frame series CSV.

- `DynoJsonExporter`
  - Writes single-run or compare JSON.

- `DynoPngExporter`
  - Writes JavaFX chart snapshots.

- `FontProvider`
  - Locates/loads Sarabun-compatible font for Thai PDF output.

Export behavior:

- Single-run CSV/JSON/PDF use the selected run detail and full frame list.
- Compare CSV writes one CSV per run.
- Compare JSON/PDF includes all compared runs.
- PNG requires a JavaFX `WritableImage` snapshot captured before export.

## Legacy Swing Package

Package: `com.dyno.view`

This package contains Swing UI components such as `OperatorConsoleFrame`, `LiveTelemetryWindow`, `DynoChartPanel`, and visual helper `OperatorUi`.

Current primary app path is JavaFX under `com.dyno.fx`. Treat Swing classes as legacy or fallback UI unless a task explicitly targets them.

## Operator Config Extension

Path: `operator-console/src/main/java/com/dyno/operator/config`

Responsibility:

- ESP32 DAQ config editing support.
- HTTP client and DTOs for device configuration workflows.

Packages:

- `client`
  - `Esp32DaqConfigClient`
  - `HttpEsp32DaqConfigClient`
  - `JsonCodec`

- `model`
  - `Esp32DaqConfigDto`
  - `Esp32DaqConfigResponseDto`
  - `Esp32DaqConfigUpdateRequestDto`
  - `Esp32DaqConfigValidationDto`
  - `EngineEdgeMode`

- `view`
  - `Esp32DaqConfigView`

- `viewmodel`
  - `Esp32DaqConfigViewModel`
  - `Esp32DaqConfigValidator`

Engineering notes:

- Keep this aligned with backend ESP32 config schema and firmware `DynoConfig`.
- If the config API is integrated into the main JavaFX shell, avoid duplicating DTO field rules.

## Frontend Test Map

Existing tests cover API contracts, mapping, WebSocket parsing, chart presenter behavior, and config resolution.

High-value test targets:

- `DynoWebSocketClientTest`: WebSocket envelope parsing.
- `LiveFrameContractTest`: backend/frontend live frame field compatibility.
- `HistoryApiContractTest`: history DTO compatibility.
- `CalibrationApiContractTest`: calibration DTO compatibility.
- `HealthApiContractTest`: health DTO compatibility.
- `RunControlResponseTest`: run-control response parsing/defaults.
- `LiveDynoChartPresenterTest`: chart lifecycle.
- `CompareDisplayMapperTest`: compare mapping.
- `OperatorStatusMapperTest`: health-to-status mapping.
- `EndpointConfigTest`: endpoint resolution.

## Frontend Change Checklist

When changing frontend behavior:

1. Identify whether the change belongs in DTO, state, presenter, or view.
2. Keep network calls off the JavaFX Application Thread.
3. Update DTOs and contract tests when backend API fields change.
4. Update presenter tests for display logic.
5. Keep endpoint config centralized in `EndpointConfig`.
6. If export changes, verify PDF/PNG/CSV/JSON paths separately.
