# API Contracts

## Overview

The system exposes two runtime interfaces to the operator console:

- HTTP JSON API on `DYNO_API_BIND`, default `0.0.0.0:9001`.
- Raw WebSocket live telemetry on `DYNO_WS_BIND`, default `0.0.0.0:9000`.

The Java console defaults to:

- `http://127.0.0.1:9001`
- `ws://127.0.0.1:9000`

HTTP routes are implemented in `crates/dyno-core/src/api.rs`.

WebSocket broadcast is implemented in `crates/dyno-core/src/ws.rs`.

Java clients live under:

- `java/src/main/java/com/dyno/history`
- `java/src/main/java/com/dyno/calibration`
- `java/src/main/java/com/dyno/control`
- `java/src/main/java/com/dyno/health`
- `java/src/main/java/com/dyno/ws`

## Compatibility Rules

API changes should preserve these rules:

- Additive optional fields are generally safe.
- Removing fields is breaking.
- Renaming fields is breaking.
- Changing enum/string values is breaking.
- Changing route paths or HTTP verbs is breaking.
- Changing WebSocket envelope shape is breaking unless the Java parser is updated first.

Any API change should update:

- Backend DTOs/tests.
- Java DTOs/client tests.
- `summary/modules/api-contracts.md`.
- Any deployment/operator docs if endpoints or env variables change.

## Error Model

Backend HTTP errors use JSON bodies:

```json
{
  "error": "message"
}
```

Backend error categories:

- Not found: `404`
- Bad request/validation: `400`
- Internal errors: `500`

Java API clients parse the `error` field when available and surface it to UI logic.

## Health API

### `GET /healthz`

Purpose:

- Return cached startup health checks.
- Used by the UI for backend/operator status.
- Useful for manual production diagnostics.

Response shape:

```json
{
  "status": "ok|degraded|error",
  "source_mode": "live|replay",
  "checks": [
    {
      "name": "database_path",
      "status": "ok|degraded|error",
      "summary": "human-readable summary",
      "detail": "optional detail",
      "checked_at_ms": 0
    }
  ]
}
```

Owner:

- Backend health: `crates/dyno-core/src/health.rs`
- API route: `crates/dyno-core/src/api.rs`
- Java client: `com.dyno.health.HealthApiClient`
- UI mapping: `com.dyno.health.OperatorStatusMapper`

## Run Control API

Run control captures operator intent. It does not directly guarantee that a run is recording; fusion records only when start intent is active and RPM is at/above `DYNO_RECORD_RPM`. Below-threshold frames are reported as `Armed`/paused and do not end the operator-bounded run.

### `POST /api/run/configure`

Request:

```json
{
  "license_plate": "ABC123",
  "run_mode": null,
  "notes": null
}
```

Current backend behavior:

- Uses `license_plate`.
- `run_mode` and `notes` are accepted in the DTO but are not central to current behavior.

Response:

```json
{
  "success": true,
  "message": "Run configured",
  "configured": true,
  "started": false,
  "recording": false,
  "run_label": "ABC123 RUN 1",
  "license_plate": "ABC123"
}
```

### `POST /api/run/start`

Starts operator intent for the configured run.

Response fields are the same as configure.

Expected messages:

- `Run started`
- `Run already started`

### `POST /api/run/stop`

Stops operator intent. If a run was active, backend records and flushes an idle frame so the stored run is closed and queryable before the response returns.

Expected messages:

- `Run stopped`
- `Run already stopped`

### `GET /api/run/status`

Returns current run-control snapshot.

Owners:

- Backend state: `crates/dyno-core/src/run_control.rs`
- Backend routes: `crates/dyno-core/src/api.rs`
- Java client: `com.dyno.control.RunControlApiClient`
- Java UI state: `com.dyno.fx.RunControlUiState`

## Development API

### `POST /api/dev/seed-run`

Purpose:

- Insert synthetic run frames for local development.

Guard:

- Enabled in debug builds or when `DYNO_ENABLE_DEV_API=true`.

Response:

```json
{
  "success": true,
  "message": "Seeded development run",
  "run_id": 1
}
```

Do not rely on this endpoint in production workflows.

## Calibration API

Calibration profiles control physical dyno parameters used by fusion.

Core fields:

- `profile_id`
- `name`
- `created_at_ms`
- `updated_at_ms`
- `is_active`
- `roller_diameter_m`
- `encoder_pulses_per_rev`
- `roller_inertia_kg_m2`
- `sample_window_ms`
- `engine_pulses_per_rev_hint`
- `engine_rpm_scale`
- `notes`

### `GET /api/calibration`

Returns active profile, validation result, and optional activation marker.

Response:

```json
{
  "profile": {
    "profile_id": 1,
    "name": "Default bootstrap profile",
    "created_at_ms": 0,
    "updated_at_ms": 0,
    "is_active": true,
    "roller_diameter_m": 0.318,
    "encoder_pulses_per_rev": 60.0,
    "roller_inertia_kg_m2": 3.5,
    "sample_window_ms": 100,
    "engine_pulses_per_rev_hint": null,
    "engine_rpm_scale": null,
    "notes": null
  },
  "validation": {
    "is_valid": true,
    "errors": [],
    "warnings": []
  }
}
```

### `GET /api/calibration/profiles`

Returns all profiles.

### `POST /api/calibration/profiles`

Creates a profile.

Request:

```json
{
  "name": "Track setup",
  "roller_diameter_m": 0.318,
  "encoder_pulses_per_rev": 60.0,
  "roller_inertia_kg_m2": 3.5,
  "sample_window_ms": 100,
  "engine_pulses_per_rev_hint": null,
  "engine_rpm_scale": null,
  "notes": null,
  "activate_after_save": false
}
```

### `PUT /api/calibration/profiles/:id`

Updates an existing profile using the same request shape as create.

### `POST /api/calibration/profiles/:id/duplicate`

Request:

```json
{
  "name": "Track setup copy",
  "activate_after_save": false
}
```

### `GET /api/calibration/profiles/:id/events`

Returns profile audit/event history.

Event fields:

- `event_id`
- `profile_id`
- `event_type`
- `created_at_ms`
- `summary`
- `previous_values_json`
- `new_values_json`

### `POST /api/calibration/activate`

Request:

```json
{
  "profile_id": 1
}
```

Important runtime behavior:

- Activating a calibration profile publishes it through a watch channel.
- Fusion reads the active calibration on each frame.
- Changes affect subsequent live calculations without restarting the backend.

Owners:

- Validation/domain: `crates/dyno-core/src/calibration.rs`
- Persistence/events: `crates/dyno-core/src/storage.rs`
- API routes: `crates/dyno-core/src/api.rs`
- Java client/UI: `com.dyno.calibration`, `com.dyno.fx.CalibrationDialog`

## Run History API

Run history is backed by SQLite tables `runs` and `frames`.

### `GET /api/runs`

Returns recent run summaries, currently limited by backend to 20.

Summary fields:

- `run_id`
- `started_at_ms`
- `ended_at_ms`
- `date`
- `source_mode`
- `correction_mode`
- `peak_power_hp`
- `peak_power_rpm`
- `peak_torque_nm`
- `peak_torque_rpm`

### `GET /api/runs/:id`

Returns run detail.

Detail includes summary fields plus:

- `calibration_profile_id`
- `calibration_profile_name`
- `roller_diameter_m`
- `encoder_pulses_per_rev`
- `roller_inertia_kg_m2`
- `sample_window_ms`

### `GET /api/runs/:id/frames`

Returns frame series:

```json
{
  "run_id": 1,
  "frames": [
    {
      "run_id": 1,
      "ts_ms": 0,
      "engine_rpm": 4500.0,
      "roller_rpm": 1200.0,
      "speed_kmh": 95.4,
      "power_hp": 87.3,
      "torque_nm": 135.0,
      "afr": 13.8,
      "lambda": 0.939,
      "ambient_temp_c": 24.5,
      "humidity_pct": 55.0,
      "pressure_hpa": 1013.25,
      "correction_factor": 1.02,
      "run_state": "Recording"
    }
  ]
}
```

### `POST /api/runs/compare`

Request:

```json
{
  "run_ids": [1, 2, 3]
}
```

Response:

```json
{
  "runs": [
    {
      "run": { "...": "RunDetailDto" },
      "frames": []
    }
  ]
}
```

### `DELETE /api/runs/:id`

Response:

```json
{
  "run_id": 1,
  "deleted": true
}
```

Owners:

- Backend persistence: `crates/dyno-core/src/storage.rs`
- Backend routes: `crates/dyno-core/src/api.rs`
- Java client: `com.dyno.history.HistoryApiClient`
- Compare UI: `com.dyno.fx.CompareSelectView`, `CompareDataView`
- Export: `com.dyno.export`

## WebSocket Live Telemetry

Bind:

- Backend: `DYNO_WS_BIND`
- UI: `DYNO_UI_WS_URI`

No URL path is required by the backend's raw WebSocket listener. Clients connect directly to the configured host/port.

Envelope:

```json
{
  "type": "live_frame",
  "data": {
    "ts_ms": 1234,
    "engine_rpm": 4500.0,
    "roller_rpm": 1200.0,
    "speed_kmh": 95.4,
    "power_hp": 87.3,
    "torque_nm": 135.0,
    "correction_factor": 1.02,
    "afr": 13.8,
    "lambda": 0.939,
    "can_present": true,
    "can_frames_seen": 1,
    "afr_valid": true,
    "can_valid": true,
    "can_status_text": "AEM UEGO active",
    "ambient_temp_c": 24.5,
    "humidity_pct": 55.0,
    "pressure_hpa": 1013.25,
    "esp32_status": {},
    "run_state": "Recording",
    "faults": [],
    "alerts": {}
  }
}
```

Initial frame:

- Backend sends the latest frame immediately after a client connects.

Update policy:

- Backend uses `watch`, so only the latest frame is retained.
- Slow clients skip stale frames rather than building an unbounded backlog.

Owners:

- Backend type: `crates/dyno-types/src/live_frame.rs`
- Backend broadcast: `crates/dyno-core/src/ws.rs`
- Java parser: `com.dyno.ws.DynoWebSocketClient`
- Java DTO: `com.dyno.model.FrameMessage`

## Contract Test Guidance

Recommended tests for API changes:

- Backend route tests for response status/body.
- Java DTO deserialization tests for every changed payload.
- WebSocket envelope fixture tests.
- Storage query tests if API values come from SQL.
- Presenter tests if UI display text or tones change.

Before merging an API change, run:

```sh
cargo test
cd java && gradle test
```
