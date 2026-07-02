# ESP32 Desired Config Startup Behavior

`dynod` reads the desired ESP32 DAQ configuration from `DYNO_ESP32_CONFIG_PATH`
during live-mode startup. The default path is `esp32-device-config.json`.

## Missing Desired Config

If the desired config file is missing, `dynod` now creates a conservative
default file that matches the existing ESP32 `DynoConfig` JSON schema exactly,
then continues startup with that generated config.

The backend logs this at WARN level:

```text
esp32-device-config.json not found at <path> — generated default config; REVIEW BEFORE RUNNING A REAL PULL
```

The generated JSON contains only the fields already defined by the ESP32 config
schema:

```json
{
  "engine_pulse_pin": 4,
  "engine_pulses_per_rev": 1.0,
  "engine_edge_mode": "rising",
  "encoder_pin_a": 5,
  "encoder_ppr": 60,
  "can_rx_pin": 21,
  "can_tx_pin": 22,
  "can_bitrate": 500000,
  "uart_tx_pin": 17,
  "uart_rx_pin": 16,
  "uart_baud": 921600,
  "telemetry_rate_hz": 20
}
```

Review this file before running a real pull. It is intended to keep production
startup from failing on first boot or after an incomplete deploy, not to replace
site-specific hardware configuration.

## Corrupt Desired Config

If `DYNO_ESP32_CONFIG_PATH` exists but cannot be parsed as valid JSON, startup
still fails loudly. The backend does not overwrite corrupt existing files,
because they may contain recoverable operator or calibration intent.

## Other Startup Paths

This behavior is scoped only to the desired-config load path. It does not change
`DYNO_SOURCE_MODE`, startup health checks, serial readiness handling, or
last-applied config state handling.
