#pragma once
#include "config_store.h"
#include <stdint.h>

// ============================================================
//  Encoder — roller speed via ESP32 PCNT
//
//  Counting mode: single-channel, both edges (CHANGE equivalent).
//    CPR (counts per revolution) = encoder_ppr × 2.
//    encoder_pin_b from DynoConfig is deliberately unused; PCNT
//    is wired to encoder_pin_a only.  The pin_b field is retained
//    in DynoConfig for protocol compatibility but has no hardware
//    effect in this module.
//
//  Overflow protection:
//    The PCNT hardware counter is 16-bit (−32768 … 32767).
//    Watch-points at both limits trigger an IRAM callback that
//    increments/decrements a 32-bit overflow accumulator.
//    tick() reads both values atomically (portDISABLE_INTERRUPTS)
//    and computes a 32-bit true count, so RPM is correct even when
//    multiple hardware wraps occur between ticks.
//
//  Glitch filter:
//    PCNT hardware glitch filter rejects pulses narrower than
//    GLITCH_FILTER_NS (1 µs).  Passes all pulse rates up to
//    ~500 kHz — well above any practical roller encoder frequency.
//
//  API contract:
//    1. Call tick() once per telemetry frame from task context.
//       This is the only function that transitions internal state.
//    2. All getter functions are pure reads of the last snapshot.
//       They are safe to call any number of times without side effects.
//    3. None of the API functions are ISR-safe.
// ============================================================

struct EncoderState {
    int32_t  count_total;  // cumulative signed 32-bit count since last init/reconfigure
    int16_t  delta;        // count delta in the most recent tick() interval
                           //   saturates to ±32767 if RPM and PPR are extreme
    int32_t  rpm_x10;      // roller RPM × 10, integer; 0 when stopped or invalid
    bool     valid;        // true: roller moving within STOPPED_TIMEOUT_MS
};

namespace Encoder {

    // ---- Lifecycle ----
    void init(const DynoConfig& cfg);
    void deinit();

    // Hot reconfigure: applies new pin/ppr settings atomically.
    // Internally calls deinit() then init(). Count accumulator and
    // RPM snapshot reset to zero.
    // Returns true if the new configuration initialised successfully.
    bool reconfigure(const DynoConfig& cfg);

    bool is_init();

    // ---- Main processing function ----
    // Must be called from task context (not ISR) once per telemetry frame.
    // Reads the PCNT hardware counter + overflow accumulator atomically,
    // computes the count delta and RPM × 10, then writes the snapshot.
    void tick();

    // ---- Snapshot access ----
    // All pure reads; no side effects; valid only after at least one tick() call.
    const EncoderState& get_state();

    int32_t get_count_total();  // state().count_total
    int16_t get_last_delta();   // state().delta
    int32_t get_rpm_x10();      // state().rpm_x10 — preferred integer path
    float   get_rpm();          // (float)get_rpm_x10() / 10.0f — backward compat
    bool    is_valid();         // state().valid
}
