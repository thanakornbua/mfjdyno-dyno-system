# Dyno System Engineering Docs

This directory contains onboarding-level summaries for the major modules in this repository.

Start here:

- [Project summary](project-summary.md): high-level overview, backend/frontend split, API pathway, and deployment notes.
- [Backend module](modules/backend.md): Rust `dynod` service, subsystem ownership, storage, fusion, run control, health, and operational concerns.
- [Shared Rust crates](modules/shared-rust-crates.md): `dyno-types`, `dyno-protocol`, and `dyno-sim`.
- [Frontend module](modules/frontend.md): JavaFX operator console packages, state/presenter flow, API clients, export path, and tests.
- [Firmware module](modules/firmware.md): ESP32 acquisition firmware, runtime config, telemetry, command protocol, and hardware responsibilities.
- [API contracts](modules/api-contracts.md): HTTP and WebSocket contracts, payload ownership, and compatibility rules.
- [Deployment module](modules/deployment.md): production layout, systemd units, environment files, and operations workflow.
- [End-to-end workflows](modules/workflows.md): live telemetry, run recording, calibration, export, and failure-mode workflows.

Use these files as engineering orientation. Source code remains the source of truth for exact behavior, but these docs describe where behavior lives and how modules fit together.
