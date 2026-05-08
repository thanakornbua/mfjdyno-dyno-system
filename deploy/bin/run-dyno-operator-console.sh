#!/usr/bin/env bash
set -euo pipefail

APP_HOME="${DYNO_OPERATOR_CONSOLE_HOME:-/opt/dyno-operator-console}"
LIB_DIR="${APP_HOME}/lib"
JAVA_BIN="${JAVA_BIN:-java}"

if [[ ! -d "${LIB_DIR}" ]]; then
  echo "dyno-operator-console: missing lib directory at ${LIB_DIR}" >&2
  exit 1
fi

mapfile -t ALL_JARS < <(find "${LIB_DIR}" -maxdepth 1 -type f -name '*.jar' | sort)
if [[ "${#ALL_JARS[@]}" -eq 0 ]]; then
  echo "dyno-operator-console: no jars found in ${LIB_DIR}" >&2
  exit 1
fi

JAVA_FX_JARS=()
APP_JARS=()
for jar in "${ALL_JARS[@]}"; do
  name="$(basename "${jar}")"
  if [[ "${name}" == javafx-* ]]; then
    JAVA_FX_JARS+=("${jar}")
  else
    APP_JARS+=("${jar}")
  fi
done

if [[ "${#JAVA_FX_JARS[@]}" -eq 0 ]]; then
  echo "dyno-operator-console: JavaFX runtime jars were not found in ${LIB_DIR}" >&2
  exit 1
fi

MODULE_PATH="$(IFS=:; echo "${JAVA_FX_JARS[*]}")"
CLASSPATH="$(IFS=:; echo "${APP_JARS[*]}")"

exec "${JAVA_BIN}" \
  -Ddyno.api.base_url="${DYNO_UI_API_BASE_URL:-http://127.0.0.1:9001}" \
  -Ddyno.ws.uri="${DYNO_UI_WS_URI:-ws://127.0.0.1:9000}" \
  -Ddyno.control.api.base_url="${DYNO_CONTROL_API_BASE_URL:-http://127.0.0.1:9001}" \
  -DDYNO_UI_MODE="${DYNO_UI_MODE:-maximized}" \
  -DDYNO_UI_FULLSCREEN="${DYNO_UI_FULLSCREEN:-false}" \
  -DDYNO_UI_MAXIMIZE_TO_FULLSCREEN="${DYNO_UI_MAXIMIZE_TO_FULLSCREEN:-true}" \
  --module-path "${MODULE_PATH}" \
  --add-modules javafx.controls \
  -cp "${CLASSPATH}" \
  com.dyno.fx.OperatorConsoleApp
