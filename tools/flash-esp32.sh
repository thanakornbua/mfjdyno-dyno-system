#!/usr/bin/env sh
# Flash the ESP32 over its onboard USB port (telemetry, config sync, and
# flashing all share this one cable). Pass the port as $1, or rely on the
# default below.
set -eu

PORT="${1:-/dev/ttyUSB0}"

arduino-cli compile --fqbn esp32:esp32:esp32 firmware/firmware-test
arduino-cli upload -p "$PORT" --fqbn esp32:esp32:esp32 firmware/firmware-test
