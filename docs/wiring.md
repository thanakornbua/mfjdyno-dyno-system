# Sensor wiring — ESP32 dyno DAQ

Pin assignments are the source of truth in
[`firmware/firmware-test/firmware-test.ino`](../firmware/firmware-test/firmware-test.ino)
(constants `PIN_*`). This document mirrors them; if the firmware changes, update
this table.

> **All ESP32 GPIO are 3.3 V and NOT 5 V tolerant.** Level-shift any 5 V+
> sensor output before it reaches a pin.

## Overview

```
                          ┌──────────────────────┐
  Roller encoder          │        ESP32         │
  (quadrature, 1024 PPR)  │                      │
    A  ───────────────────┤ GPIO34  (input-only) │
    B  ───────────────────┤ GPIO35  (input-only) │
    Z  ───────────────────┤ GPIO32               │
    VCC ── supply*        │                      │
    GND ── GND            │                      │
                          │                      │
  Ignition pickup         │                      │
  (LM393 comparator)      │                      │
    DO ───────────────────┤ GPIO27               │
    VCC ── 3.3V           │                      │
    GND ── GND            │                      │
                          │                      │
  BME280 (ambient)        │                      │
    SDA ──────────────────┤ GPIO21               │
    SCL ──────────────────┤ GPIO22               │
    VCC ── 3.3V           │                      │
    GND ── GND            │      USB port        │
                          └──────────┬───────────┘
                                     │ single USB cable (UART0, 115200)
                              ┌──────┴──────┐    telemetry + config-sync
                              │  Pi / PC    │    + firmware flashing
                              │  (dynod)    │
                              └─────────────┘

  CAN AFR (wideband) ── NOT on the ESP32 — USB CAN adapter on the Pi (can0)
```

## Pin table

| Sensor | Signal | ESP32 pin | Firmware constant | Trigger / mode |
|---|---|---|---|---|
| Roller encoder | A | GPIO34 | `PIN_ENC_A` | interrupt, CHANGE (4× quadrature) |
| Roller encoder | B | GPIO35 | `PIN_ENC_B` | interrupt, CHANGE |
| Roller encoder | Z (index) | GPIO32 | `PIN_ENC_Z` | interrupt, RISING |
| Ignition pickup | DO | GPIO27 | `PIN_IGN` | interrupt, FALLING, `INPUT_PULLUP` |
| BME280 | SDA | GPIO21 | `PIN_SDA` | I²C, addr 0x76 |
| BME280 | SCL | GPIO22 | `PIN_SCL` | I²C |
| UART0 (reserved) | RX / TX | GPIO3 / GPIO1 | `PIN_U0_RX` / `PIN_U0_TX` | owned by onboard USB bridge — do not wire |

Firmware parameters that pair with this wiring: `ENC_PPR = 1024` (CPR 4096),
`PULSES_PER_REV = 1` ignition pulse per engine revolution, telemetry at 20 Hz.

## Per-sensor notes

### Roller encoder → GPIO34 (A), GPIO35 (B), GPIO32 (Z)

- **GPIO34/35 are input-only and have no internal pull-ups/downs.** The
  firmware configures plain `INPUT`, so the encoder output must be push-pull
  (totem-pole). For open-collector / NPN encoders, add **external ~4.7 kΩ
  pull-ups to 3.3 V** on A, B, and Z — a floating line counts phantom edges
  and corrupts roller RPM, speed, power, and torque.
- Most industrial 1024 PPR encoders run 5–24 V with ≥5 V outputs: **level-shift
  A/B/Z to 3.3 V** (divider or shifter board) and power the encoder from its
  rated supply, not the ESP32 3.3 V rail.

### Ignition pickup (LM393 module) → GPIO27

- Falling-edge triggered; one pulse per engine revolution (`PULSES_PER_REV = 1`).
- **Power the module from 3.3 V**, not 5 V — LM393 boards pull DO up to VCC.
- The pin uses `INPUT_PULLUP`, so a disconnected sensor idles HIGH and reads a
  quiet 0 RPM instead of amplifying mains hum into phantom RPM. This assumes
  the module output is idle-HIGH / pulse-LOW (standard open-collector). If
  your signal is idle-LOW / pulse-HIGH, switch the firmware to
  `INPUT_PULLDOWN` + RISING.
- Feed it from an inductive clamp or the coil's low-tension side — never the
  HT lead directly. Route away from the coil/plug lead; shielded or twisted
  pair with ground.

### BME280 → GPIO21 (SDA), GPIO22 (SCL)

- I²C address **0x76** (check the address jumper if `bme_valid` stays false —
  some boards default to 0x77).
- 3.3 V module; breakout boards typically include I²C pull-ups.

### Serial link (Pi ↔ ESP32)

- One USB cable to the devkit's onboard USB-UART bridge (UART0, 115200 baud)
  carries JSON telemetry, the binary config-sync protocol, and firmware
  flashing. Nothing may be wired to GPIO1/GPIO3.

### CAN AFR (wideband)

- Deliberately **not** connected to the ESP32. The wideband controller's
  CAN H/L go to a USB-CAN adapter (CANable / gs_usb) on the Pi, which exposes
  `can0` to dynod.

## Grounding

Encoder, LM393, BME280, and the ESP32 must share a common ground.
**Star-ground at the ESP32** rather than daisy-chaining through the chassis —
ground loops next to an ignition system are the primary path for the EMI/mains
noise that produces phantom readings.
