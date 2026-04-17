#pragma once
#include "config_store.h"
#include <stdint.h>

// ============================================================
//  EnginePulse — engine RPM from a conditioned digital pulse
//
//  Backend selection (compile-time):
//    Default (no flag):           MCPWM capture unit 0/channel 0
//                                 ESP-IDF v5 handle API.
//                                 Timer resolution: 80 MHz APB → 12.5 ns/tick.
//    -DENGINE_PULSE_BACKEND_ISR:  GPIO ISR fallback using Arduino
//                                 attachInterruptArg() + Xtensa CCOUNT register.
//                                 Timer resolution: CPU clock (240 MHz → 4.2 ns/tick).
//
//  To switch backends, add to platformio.ini:
//    build_flags = -DENGINE_PULSE_BACKEND_ISR
//
//  API contract:
//    1. Call update() once per telemetry frame from task context.
//       This is the only function that transitions internal state.
//    2. All getter functions are pure reads of the last snapshot.
//       They are safe to call any number of times, in any order,
//       without side effects.
//    3. None of the API functions are ISR-safe.
//
//  ISR rules:
//    The capture/interrupt backend only writes these volatile words:
//      s_raw_delta_ticks, s_raw_cap_ticks, s_pulse_count
//    All derived state (EMA, stall detection, snapshot) lives in task context.
//    No float, no Serial, no heap in ISR.
// ============================================================

// ---- Snapshot struct — populated atomically by update() ----
struct EnginePulseState {
    uint32_t period_us;        // EMA-smoothed inter-pulse period in µs
                               //   0 when not valid or timed out
    int32_t  rpm_x10;          // RPM × 10 computed from period_us (integer, no float)
                               //   0 when not valid or timed out
    bool     valid;            // true: fresh data, passed min-interval, within stall window
    bool     timeout;          // true: no accepted pulse within STALL_TIMEOUT_MS
                               //        false before any pulse has ever arrived
    uint16_t rejected_pulses;  // saturating count of pulses rejected by min-interval filter
                               //   reset to 0 on init/reconfigure
};

namespace EnginePulse {

    // ---- Lifecycle ----
    void init(const DynoConfig& cfg);
    void deinit();

    // Hot reconfigure: applies new pin/edge/ppr settings atomically.
    // Internally calls deinit() then init(). The EMA and rejection counter
    // reset; stall timeout asserts immediately until the first new pulse.
    // Returns true if the new configuration initialised successfully.
    bool reconfigure(const DynoConfig& cfg);

    bool is_init();

    // ---- Main processing function ----
    // Must be called from task context (not ISR) once per telemetry frame.
    // Reads ISR-populated volatile data, applies:
    //   • Minimum interval filter (noise rejection)
    //   • EMA smoothing on period_us
    //   • Stall / timeout detection
    // Then writes the result into the snapshot.
    void update();

    // ---- Snapshot access ----
    // All pure reads; no side effects; valid only after at least one update() call.
    const EnginePulseState& get_state();

    uint32_t get_period_us();       // snapshot().period_us
    int32_t  get_rpm_x10();         // snapshot().rpm_x10 — preferred integer path
    float    get_rpm();             // (float)get_rpm_x10() / 10.0f — backward compat
    bool     is_valid();            // snapshot().valid
    bool     is_timeout();          // snapshot().timeout
    uint16_t get_rejected_count();  // snapshot().rejected_pulses
}
