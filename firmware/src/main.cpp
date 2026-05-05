#include <Arduino.h>
#include "config_store.h"
#include "config_validator.h"
#include "protocol.h"
#include "engine_pulse.h"
#include "encoder.h"
#include "can_afr.h"
#include "bme280_sensor.h"
#include "telemetry.h"

// ============================================================
//  main.cpp — boot sequence, main loop, peripheral reinit
//
//  Module dependency graph (all read ConfigStore::get()):
//
//    ConfigStore
//      ├─► Protocol      UART2 command framing (Rust ↔ ESP32)
//      ├─► EnginePulse   MCPWM capture → RPM
//      ├─► Encoder       PCNT single-channel → roller RPM
//      ├─► CanAfr        TWAI RX → AFR / lambda
//      └─► Telemetry     assembles TelemetryFrame, calls Protocol::send_telemetry
//
//  ── FreeRTOS task split (future migration) ─────────────────
//
//  The two named functions below map cleanly to two tasks:
//
//  sensor_task  (HIGH priority, period = 1000/telem_rate_hz ms)
//  ┌─────────────────────────────────────────────────────────┐
//  │  CanAfr::poll()          drain TWAI queue               │
//  │  Telemetry::send()       EnginePulse::update            │
//  │                          Encoder::tick                  │
//  │                          pack + CRC + TX frame          │
//  └─────────────────────────────────────────────────────────┘
//
//  config_task  (NORMAL priority, period ≈ 2–5 ms)
//  ┌─────────────────────────────────────────────────────────┐
//  │  Protocol::process_rx()  drain UART2 RX, dispatch cmds  │
//  │  handle_reinit()         if g_reinit_flags != 0          │
//  └─────────────────────────────────────────────────────────┘
//
//  Shared state (single-writer, multi-reader):
//    g_reinit_flags          volatile; written by config_task,
//                            cleared and consumed by config_task
//    ConfigStore::get()      read-only reference after commit()
//    Telemetry::set/clear_fault()  called from config_task only
//
//  In the current single-threaded Arduino model the two logical
//  tasks both execute in loop() with CanAfr::poll() running on
//  every iteration to prevent TWAI RX queue overflow when the
//  CAN bus message rate exceeds the telemetry rate.
//
//  ── UART reinit ordering ───────────────────────────────────
//  Reinit order inside handle_reinit(): ENGINE → ENCODER → CAN → UART
//  UART must go last.  The CONFIG_ACK for CONFIG_APPLY is sent
//  on the OLD baud rate by protocol.cpp before commit() sets
//  g_reinit_flags.  main.cpp flushes Serial2 before the teardown
//  so the ACK reaches the Rust backend on the old baud.
// ============================================================

static uint32_t s_last_telem_ms = 0;

// ============================================================
//  Peripheral reinit
//  Called when g_reinit_flags is non-zero (set by ConfigStore::commit).
//  Flags are cleared before reinit so a crash inside deinit/init
//  does not leave the system in a permanent reinit loop.
// ============================================================
static void handle_reinit() {
    const uint32_t flags = g_reinit_flags;
    g_reinit_flags = 0;                    // clear before any possible fault path

    const DynoConfig& cfg = ConfigStore::get();

    if (flags & REINIT_ENGINE) {
        Serial.println("[main] reinit: engine pulse");
        bool ok = EnginePulse::reconfigure(cfg);
        if (ok) Telemetry::clear_fault(FLT_ENGINE_INIT);
        else    Telemetry::set_fault(FLT_ENGINE_INIT);
        Serial.printf("[main] engine pulse reinit %s\n", ok ? "ok" : "FAILED");
    }

    if (flags & REINIT_ENCODER) {
        Serial.println("[main] reinit: encoder");
        bool ok = Encoder::reconfigure(cfg);
        if (ok) Telemetry::clear_fault(FLT_ENCODER_INIT);
        else    Telemetry::set_fault(FLT_ENCODER_INIT);
        Serial.printf("[main] encoder reinit %s\n", ok ? "ok" : "FAILED");
    }

    if (flags & REINIT_CAN) {
        Serial.println("[main] reinit: CAN/TWAI");
        bool ok = CanAfr::reconfigure(cfg);
        if (ok) {
            Telemetry::clear_fault(FLT_CAN_INIT);
            Telemetry::clear_fault(FLT_CAN_BUS_OFF);  // fresh start
        } else {
            Telemetry::set_fault(FLT_CAN_INIT);
        }
        Serial.printf("[main] CAN reinit %s\n", ok ? "ok" : "FAILED");
    }

    if (flags & REINIT_UART) {
        // ── Must be last ──────────────────────────────────────────────
        // CONFIG_ACK was already sent on the old baud rate by protocol.cpp.
        // flush() blocks until the TX FIFO drains.  The brief delay(5)
        // adds margin for the far-end (Rust backend) to fully receive the
        // ACK before we tear down Serial2.  At ≥9600 baud, flush() already
        // covers the transmission time; the 5 ms is belt-and-suspenders.
        Serial.println("[main] reinit: UART — flushing before switchover");
        Serial2.flush();
        delay(5);
        Serial2.end();
        Serial2.begin(cfg.uart_baud, SERIAL_8N1, cfg.uart_rx_pin, cfg.uart_tx_pin);
        Protocol::init(Serial2);
        Serial.printf("[main] UART reinit ok — %u baud rx=%u tx=%u\n",
                      cfg.uart_baud, cfg.uart_rx_pin, cfg.uart_tx_pin);
    }
}

// ============================================================
//  Boot helper — print active configuration to USB serial
// ============================================================
static void print_config(const DynoConfig& cfg) {
    Serial.printf("  engine    pin=%-2u  ppr=%-3u  edge=%u\n",
                  cfg.engine_pulse_pin,
                  cfg.engine_pulses_per_rev,
                  static_cast<uint8_t>(cfg.engine_edge_mode));
    Serial.printf("  encoder   pin_a=%-2u  ppr=%u\n",
                  cfg.encoder_pin_a, cfg.encoder_ppr);
    Serial.printf("  CAN       rx=%-2u  tx=%-2u  %u bps  id=0x%03X  byte=%u\n",
                  cfg.can_rx_pin, cfg.can_tx_pin,
                  cfg.can_bitrate, cfg.can_afr_frame_id, cfg.can_afr_byte_offset);
    Serial.printf("  UART2     rx=%-2u  tx=%-2u  %u baud\n",
                  cfg.uart_rx_pin, cfg.uart_tx_pin, cfg.uart_baud);
    Serial.printf("  telemetry %u Hz\n", cfg.telemetry_rate_hz);
}

// ============================================================
//  setup() — runs once at power-on / reset
// ============================================================
void setup() {
    // USB serial for human-readable debug output only.
    // No telemetry or protocol frames on Serial — only on Serial2.
    Serial.begin(115200);
    delay(1000);   // allow USB CDC enumeration to settle on first boot

    Serial.println();
    Serial.println("============================================");
    Serial.printf( "  dyno DAQ  fw v%u.%u.%u  built %s %s\n",
                   FW_VERSION_MAJOR, FW_VERSION_MINOR, FW_VERSION_PATCH,
                   __DATE__, __TIME__);
    Serial.println("============================================");

    // ---- 1. Config — NVS load or defaults ----
    ConfigStore::init();
    const DynoConfig& cfg = ConfigStore::get();
    Serial.printf("[main] config v%u loaded\n", ConfigStore::get_config_version());
    print_config(cfg);

    // ---- 2. Telemetry — init first so set_fault() is valid for subsequent inits ----
    Telemetry::init(cfg);

    // ---- 3. Protocol (UART2) ----
    Serial2.begin(cfg.uart_baud, SERIAL_8N1, cfg.uart_rx_pin, cfg.uart_tx_pin);
    Protocol::init(Serial2);
    Serial.printf("[main] UART2 up — %u baud rx=%u tx=%u\n",
                  cfg.uart_baud, cfg.uart_rx_pin, cfg.uart_tx_pin);

    // ---- 4. Sensor modules (fault-tolerant: a failed module does not abort the rest) ----

    EnginePulse::init(cfg);
    if (EnginePulse::is_init()) {
        Serial.println("[main] engine pulse OK");
    } else {
        Serial.println("[main] engine pulse FAILED — FLT_ENGINE_INIT set");
        Telemetry::set_fault(FLT_ENGINE_INIT);
    }

    Encoder::init(cfg);
    if (Encoder::is_init()) {
        Serial.println("[main] encoder OK");
    } else {
        Serial.println("[main] encoder FAILED — FLT_ENCODER_INIT set");
        Telemetry::set_fault(FLT_ENCODER_INIT);
    }

    CanAfr::init(cfg);
    if (CanAfr::is_init()) {
        Serial.println("[main] CAN/TWAI OK");
    } else {
        Serial.println("[main] CAN/TWAI FAILED — FLT_CAN_INIT set");
        Telemetry::set_fault(FLT_CAN_INIT);
    }

    BmeSensor::init();
    if (!BmeSensor::is_init()) {
        Serial.println("[main] BME280 FAILED — bme_valid will be false");
    }

    s_last_telem_ms = millis();

    Serial.println("============================================");
    Serial.println("  boot complete — entering loop");
    Serial.println("============================================");
}

// ============================================================
//  config_update() — future config_task() body
//
//  Processes one batch of incoming UART command bytes and applies
//  any pending peripheral reinit.  Non-blocking; bounded by the
//  128-byte budget inside Protocol::process_rx().
//
//  UART overrun detection:
//    If Serial2 still has > 128 bytes queued after draining our
//    budget, the Pi is sending faster than we can process or a
//    malformed frame has stalled the parser.  Set FLT_UART_OVERRUN
//    so the condition is visible in telemetry and clears
//    automatically once the backlog drains.
// ============================================================
static void config_update() {
    // Reinit must run before process_rx so any new command sees the
    // updated live config (e.g. a second CONFIG_GET after CONFIG_APPLY).
    if (g_reinit_flags) {
        handle_reinit();
    }

    Protocol::process_rx();

    // Overrun heuristic: one full process_rx() budget = 128 bytes.
    // If there is still more than one budget's worth waiting, we are behind.
    if (Serial2.available() > 128) {
        Telemetry::set_fault(FLT_UART_OVERRUN);
    } else {
        Telemetry::clear_fault(FLT_UART_OVERRUN);
    }
}

// ============================================================
//  sensor_update() — future sensor_task() body
//
//  Drains the TWAI RX queue, then assembles and transmits one
//  telemetry frame.  Internally, Telemetry::send() calls:
//    EnginePulse::update()  read MCPWM/ISR volatile words, run EMA
//    Encoder::tick()        read PCNT counter, compute delta + RPM
//    (AFR snapshot already updated by CanAfr::poll() above)
//
//  In a FreeRTOS context this whole function becomes a task
//  body with vTaskDelayUntil() providing the period.
// ============================================================
static void sensor_update() {
    CanAfr::poll();      // pull latest CAN frames before we snapshot AFR
    Telemetry::send();
}

// ============================================================
//  loop() — Arduino main loop
// ============================================================
void loop() {
    // ---- Config path (runs every iteration, no strict deadline) ----
    config_update();

    // ---- Sensor path (rate-limited to telemetry_rate_hz) ----
    // Read telemetry rate from live config so a CONFIG_APPLY change
    // takes effect within the current loop iteration.
    const DynoConfig& cfg = ConfigStore::get();

    // Guard against divide-by-zero (validator enforces 1–200 Hz, but be safe).
    uint32_t interval_ms = (cfg.telemetry_rate_hz > 0u)
                            ? (1000u / cfg.telemetry_rate_hz)
                            : 10u;
    if (interval_ms == 0u) interval_ms = 1u;   // rate > 1000 Hz cannot be configured

    const uint32_t now_ms = millis();
    if ((now_ms - s_last_telem_ms) >= interval_ms) {
        s_last_telem_ms = now_ms;
        sensor_update();
    }

}
