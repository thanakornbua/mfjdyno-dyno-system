#!/usr/bin/env sh
set -eu

arduino-cli compile --fqbn esp32:esp32:esp32 firmware/firmware-test
arduino-cli upload -p /dev/ttyUSB1 --fqbn esp32:esp32:esp32 firmware/firmware-test
