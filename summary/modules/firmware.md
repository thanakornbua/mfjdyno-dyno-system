# Firmware Module

## Responsibility

The firmware runs on an ESP32 and acquires dyno-side signals. It measures engine pulse timing, roller encoder movement, AFR over CAN, and BME280 ambient readings, then publishes telemetry to the backend over serial. It also accepts a binary command protocol for ping, device info, config read, config stage, and config apply.

Path: `firmware/`

Build system: PlatformIO

Framework: Arduino ESP32

Board: `esp32dev`

## PlatformIO Configuration

File: `firmware/platformio.ini`

Important settings:

- Board: `esp32dev`
- Framework: `arduino`
- Upload port: `/dev/ttyUSB0` (single USB cable to the devkit's onboard port; also carries telemetry and config sync)
- Monitor port: `/dev/ttyUSB0`
- Upload speed: `921600`
- Monitor speed: `115200`

Dependencies:

- `adafruit/Adafruit BME280 Library`
- `adafruit/Adafruit Unified Sensor`

## Main Runtime Loop

File: `firmware/src/main.cpp`

Startup sequence:

1. Initialize config store.
2. Validate active config.
3. Initialize serial/protocol handling.
4. Initialize engine pulse capture.
5. Initialize roller encoder capture.
6. Initialize CAN AFR acquisition.
7. Initialize BME280 sensor.
8. Initialize telemetry state.

Loop responsibilities:

- Process incoming command packets.
- Apply staged configuration when requested.
- Poll/update sensors.
- Emit telemetry at the configured interval.

Important helper areas:

- `handle_reinit()`: applies reinitialization after config changes.
- `config_update()`: processes pending config workflow.
- `sensor_update()`: updates acquisition modules.
- `print_config()`: startup diagnostic output.

Engineering notes:

- UART reinitialization happens last because config ACK must be sent using the old baud first.
- Any change to config update behavior must preserve safe staged/apply semantics.

## Runtime Configuration Store

Files:

- `firmware/src/config_store.h`
- `firmware/src/config_store.cpp`
- `firmware/src/config_validator.h`
- `firmware/src/config_validator.cpp`

`DynoConfig` is the firmware-side runtime configuration structure. It includes pins, timing, UART speed, encoder settings, CAN settings, and feature flags.

Config lifecycle:

1. `ConfigStore::init()` loads config from ESP32 NVS.
2. If no valid stored config exists, defaults are used.
3. Config set command stages a new config.
4. Config apply command commits the staged config.
5. Commit returns a reinit mask indicating which subsystems need reinitialization.

Reinit mask bits:

- `REINIT_ENGINE`
- `REINIT_ENCODER`
- `REINIT_CAN`
- `REINIT_UART`

Validation responsibilities:

- Ensure GPIO values are valid.
- Reject flash-reserved pins.
- Respect input-only pin constraints.
- Validate CAN bitrates.
- Detect GPIO conflicts.
- Return `ConfigError` and `ConfigField` for command error payloads.

Contract warning:

- Firmware `DynoConfig` must stay compatible with Rust `dyno_protocol::DynoConfig`.
- If the struct layout changes, update both sides and protocol tests.

## Engine Pulse Acquisition

Files:

- `firmware/src/engine_pulse.h`
- `firmware/src/engine_pulse.cpp`

Responsibility:

- Attach interrupt to engine pulse input.
- Measure pulse period in microseconds.
- Apply debounce filtering.
- Detect engine stall/timeout.
- Expose RPM and validity state.

Key behavior:

- ISR records pulse timing.
- `EnginePulse::update()` calculates current state.
- RPM is exposed as scaled integer `rpm_x10` and float helper.
- Timeout is based on no pulse for the configured stall window.

Important constants:

- Debounce defaults around tens of microseconds.
- Stall timeout is currently 500 ms in the implementation.

Engineering notes:

- Keep ISR work minimal.
- Be careful when changing debounce because it directly affects false pulse rejection and RPM validity.

## Roller Encoder Acquisition

Files:

- `firmware/src/encoder.h`
- `firmware/src/encoder.cpp`

Responsibility:

- Track quadrature encoder A/B transitions.
- Maintain total count and per-window delta.
- Calculate roller RPM.
- Expose validity state.

Key behavior:

- Uses a quadrature encoder matrix (`QEM`) in the ISR.
- `Encoder::tick()` snapshots counts and computes delta/RPM.
- Pin and pulses-per-revolution values come from `DynoConfig`.

Engineering notes:

- Encoder deltas feed backend physics; changes affect speed, power, and torque.
- Preserve sign handling if supporting reverse roller movement.
- Avoid expensive logic in the ISR.

## CAN AFR Acquisition

Files:

- `firmware/src/can_afr.h`
- `firmware/src/can_afr.cpp`

Responsibility:

- Configure ESP32 TWAI/CAN driver.
- Receive AEM UEGO AFR frames.
- Decode raw lambda/AFR values.
- Track CAN status, voltage, and freshness.

Important behavior:

- AEM UEGO is expected to use a 29-bit extended ID.
- Lambda/AFR scaling is encoded in `can_afr.cpp`.
- Data becomes stale after the configured timeout.
- `CanStatus` reports initialization, no-data, active, stale, bus-off, and similar states.

Engineering notes:

- Backend also has SocketCAN AFR support. Firmware CAN fields still matter because telemetry status/fault flags can reflect CAN state.
- If AFR scaling changes, update backend decoding assumptions and UI labels/tests.

## BME280 Ambient Sensor

Files:

- `firmware/src/bme280_sensor.h`
- `firmware/src/bme280_sensor.cpp`

Responsibility:

- Initialize BME280 over I2C.
- Read temperature, humidity, and pressure.
- Expose validity state and values.

Pins:

- SDA: GPIO 21
- SCL: GPIO 22

Backend usage:

- Valid ESP32 BME values can feed `AmbientSample`.
- Invalid BME values fall back to backend stub/sanitized ambient behavior.

## Telemetry Producer

Files:

- `firmware/src/telemetry.h`
- `firmware/src/telemetry.cpp`
- `firmware/src/protocol.h`
- `firmware/src/protocol.cpp`

Responsibility:

- Collect current acquisition state.
- Build `TelemetryFrame`.
- Set signal flags and fault flags.
- Send telemetry through protocol framing.

Telemetry fields include:

- Sequence number.
- Timestamp.
- Engine period.
- Encoder delta/count.
- AFR/lambda.
- Ambient values.
- CAN status.
- Signal flags.
- Fault flags.

Signal flags include:

- `SIG_ENGINE_VALID`
- `SIG_ROLLER_VALID`
- `SIG_AFR_VALID`
- `SIG_CAN_ACTIVE`
- `SIG_ENGINE_STALL`
- `SIG_ROLLER_STOP`

Fault flags include:

- `FLT_ENGINE_INIT`
- `FLT_ENCODER_INIT`
- `FLT_CAN_INIT`
- `FLT_CAN_BUS_OFF`
- `FLT_CONFIG_INVALID`
- `FLT_UART_OVERRUN`

Contract warning:

- `TelemetryFrame` size is statically asserted in firmware.
- Rust `dyno_protocol::DynoFrameV1` and decoders must match the firmware frame layout.

## Command Protocol

Files:

- `firmware/src/protocol.h`
- `firmware/src/protocol.cpp`
- `crates/dyno-protocol/src/*`
- `crates/dyno-core/src/serial_link.rs`
- `crates/dyno-core/src/esp32_config.rs`

Firmware protocol responsibilities:

- Maintain RX state machine for command frames.
- Validate magic/version/length/CRC.
- Dispatch command packet types.
- Send typed response frames.

Command packet types include:

- Ping
- Config get
- Config set
- Config apply
- Device info

Response payloads include:

- Pong
- Config ACK
- Config error
- Device info
- Current config

Important constants:

- Command magic: `0xD5 0x2B`
- Telemetry magic: `0x5A 0xA5`
- Protocol version: `0x01`
- Firmware version currently defined in `protocol.h`

Engineering notes:

- Keep CRC calculation aligned with Rust protocol tests.
- Any new command requires additions to firmware dispatch, Rust command builder, Rust response parser if applicable, and backend caller logic.

## Firmware-to-Backend Mapping

Telemetry path:

1. Firmware emits acquisition telemetry.
2. Backend serial task receives telemetry.
3. Backend maps telemetry to `DynoFrameV1`.
4. Fusion maps `DynoFrameV1` to `LiveFrame`.
5. UI receives `LiveFrame` over WebSocket.

Config path:

1. Backend loads desired ESP32 config JSON.
2. Backend validates config.
3. Backend sends config set/apply commands.
4. Firmware stages and applies config.
5. Firmware returns ACK/error/device info.
6. Backend persists last-applied config state.

## Firmware Test and Bring-Up

Useful commands:

```sh
tools/flash-esp32.sh
python3 -m serial.tools.miniterm /dev/ttyUSB0 115200
stty -F /dev/ttyUSB0 115200 raw -echo && cat /dev/ttyUSB0
```

Bring-up checklist:

1. Confirm serial port path and baud.
2. Confirm firmware version/device info via backend sync logs.
3. Confirm engine pulse input toggles and valid RPM appears.
4. Confirm roller encoder delta increases during roller movement.
5. Confirm CAN status transitions to active when AFR frames arrive.
6. Confirm BME280 validity and reasonable ambient values.
7. Confirm backend WebSocket live frames contain the expected fields.

## Firmware Change Checklist

When changing firmware:

1. Identify whether the change affects binary protocol layout.
2. Update `dyno-protocol` and firmware structs together.
3. Keep `static_assert` sizes accurate.
4. Update config validator and backend validation together.
5. Preserve staged/apply behavior for live config changes.
6. Test with serial monitor before testing through the UI.
