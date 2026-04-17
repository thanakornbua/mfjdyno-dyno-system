#include "engine_pulse.h"
#include <Arduino.h>

// ============================================================
//  Backend: MCPWM capture (default) or GPIO ISR fallback.
//
//  Both backends write the same three volatile words from ISR:
//    s_raw_delta_ticks — inter-pulse tick count (last two edges)
//    s_raw_cap_ticks   — timestamp of last accepted edge
//    s_pulse_count     — free-running edge counter (wraps OK)
//
//  All derived state (EMA, stall, snapshot) lives in update(),
//  which runs in task context once per telemetry frame.
// ============================================================

// ============================================================
//  MCPWM backend (default — no -DENGINE_PULSE_BACKEND_ISR)
//
//  Capture timer: MCPWM group 0, APB clock.
//  Typical resolution: 80 MHz → 12.5 ns/tick.
//  32-bit counter wraps every ~53 s — beyond any stall window.
// ============================================================
#ifndef ENGINE_PULSE_BACKEND_ISR

#include "driver/mcpwm.h"

static volatile uint32_t s_raw_delta_ticks = 0;
static volatile uint32_t s_raw_cap_ticks   = 0;
static volatile uint32_t s_pulse_count     = 0;

static mcpwm_cap_timer_handle_t   s_cap_timer   = nullptr;
static mcpwm_cap_channel_handle_t s_cap_channel = nullptr;
static uint32_t                   s_timer_res_hz = 80000000u;  // overwritten in init()

static bool IRAM_ATTR cap_callback(mcpwm_cap_channel_handle_t /*chan*/,
                                   const mcpwm_capture_event_data_t* edata,
                                   void* /*user_ctx*/) {
    uint32_t now       = edata->cap_value;
    s_raw_delta_ticks  = now - s_raw_cap_ticks;
    s_raw_cap_ticks    = now;
    s_pulse_count++;
    return false;  // no high-priority task to wake
}

// ============================================================
//  GPIO ISR fallback (-DENGINE_PULSE_BACKEND_ISR)
//
//  Uses attachInterruptArg() + Xtensa CCOUNT register.
//  CCOUNT increments at the CPU clock (typically 240 MHz → 4.17 ns/tick).
//  No timer hardware required.
// ============================================================
#else

#include "esp_attr.h"

static volatile uint32_t s_raw_delta_ticks = 0;
static volatile uint32_t s_raw_cap_ticks   = 0;
static volatile uint32_t s_pulse_count     = 0;

static uint32_t s_isr_pin      = 0;
static uint32_t s_timer_res_hz = 240000000u;  // overwritten to actual CPU freq in init()

static void IRAM_ATTR gpio_isr_handler(void* /*arg*/) {
    uint32_t now;
    asm volatile("rsr %0, ccount" : "=r"(now));
    s_raw_delta_ticks = now - s_raw_cap_ticks;
    s_raw_cap_ticks   = now;
    s_pulse_count++;
}

#endif  // ENGINE_PULSE_BACKEND_ISR

// ============================================================
//  Shared constants and state (task context only)
// ============================================================

static constexpr uint32_t STALL_TIMEOUT_MS = 1000u;
static constexpr uint32_t MIN_PERIOD_US    = 200u;
//  200 µs ≅ 5 000 Hz pulse rate.  At 1 p/rev that is 300 000 RPM —
//  well above any physical engine, so this is purely a noise gate.
//  The threshold is intentionally generous to allow high PPR sensors.

static uint8_t  s_pulses_per_rev   = 1;
static bool     s_initialized      = false;

// EMA state — 0 means "no sample yet, seed on first valid pulse"
static uint32_t s_ema_period_us    = 0;

// Stall tracking
static uint32_t s_prev_pulse_count = 0;
static uint32_t s_last_pulse_ms    = 0;

// Snapshot written by update(), read by all getters
static EnginePulseState s_snapshot = {};

// ============================================================
//  Internal helpers
// ============================================================

// ticks → µs without float, guarded against division by zero.
static inline uint32_t ticks_to_us(uint32_t ticks, uint32_t res_hz) {
    if (res_hz == 0u) return 0u;
    return (uint32_t)(((uint64_t)ticks * 1000000ULL) / (uint64_t)res_hz);
}

// Integer EMA: 25% weight on new sample, 75% on history.
//   ema = (3*ema + period) >> 2
// Power-of-2 denominator → single right-shift, no division needed.
static inline uint32_t ema_update(uint32_t ema, uint32_t period) {
    return (3u * ema + period) >> 2;
}

// ============================================================
//  EnginePulse namespace
// ============================================================
namespace EnginePulse {

// ---- init() ----
void init(const DynoConfig& cfg) {
    s_pulses_per_rev   = cfg.engine_pulses_per_rev;
    s_ema_period_us    = 0;
    s_prev_pulse_count = 0;
    s_last_pulse_ms    = millis();
    s_snapshot         = {};

    // Zero the ISR-written words before enabling the interrupt so
    // the first update() doesn't process stale ticks from a previous session.
    s_raw_delta_ticks = 0;
    s_raw_cap_ticks   = 0;
    s_pulse_count     = 0;

#ifndef ENGINE_PULSE_BACKEND_ISR
    // ---- MCPWM capture timer ----
    mcpwm_capture_timer_config_t timer_cfg = {};
    timer_cfg.group_id = 0;
    timer_cfg.clk_src  = MCPWM_CAPTURE_CLK_SRC_DEFAULT;

    esp_err_t err = mcpwm_new_capture_timer(&timer_cfg, &s_cap_timer);
    if (err != ESP_OK) {
        Serial.printf("[eng] MCPWM timer init failed: %s\n", esp_err_to_name(err));
        return;
    }

    // Query actual resolution so RPM math is correct regardless of prescale.
    mcpwm_capture_timer_get_resolution(s_cap_timer, &s_timer_res_hz);
    Serial.printf("[eng] MCPWM timer resolution: %u Hz\n", s_timer_res_hz);

    // ---- Capture channel ----
    mcpwm_capture_channel_config_t ch_cfg = {};
    ch_cfg.gpio_num       = cfg.engine_pulse_pin;
    ch_cfg.prescale       = 1;
    ch_cfg.flags.pos_edge = (cfg.engine_edge_mode != EdgeMode::FALLING) ? 1u : 0u;
    ch_cfg.flags.neg_edge = (cfg.engine_edge_mode != EdgeMode::RISING)  ? 1u : 0u;
    ch_cfg.flags.pull_up  = 1u;

    err = mcpwm_new_capture_channel(s_cap_timer, &ch_cfg, &s_cap_channel);
    if (err != ESP_OK) {
        Serial.printf("[eng] MCPWM channel init failed: %s\n", esp_err_to_name(err));
        mcpwm_del_capture_timer(s_cap_timer);
        s_cap_timer = nullptr;
        return;
    }

    // ---- Register callback and start ----
    mcpwm_capture_event_callbacks_t cbs = {};
    cbs.on_cap = cap_callback;
    mcpwm_capture_channel_register_event_callbacks(s_cap_channel, &cbs, nullptr);

    mcpwm_capture_channel_enable(s_cap_channel);
    mcpwm_capture_timer_enable(s_cap_timer);
    mcpwm_capture_timer_start(s_cap_timer);

#else
    // ---- GPIO ISR fallback ----
    s_isr_pin      = (uint32_t)cfg.engine_pulse_pin;
    s_timer_res_hz = (uint32_t)getCpuFrequencyMhz() * 1000000u;

    uint8_t mode;
    switch (cfg.engine_edge_mode) {
        case EdgeMode::RISING:  mode = RISING;  break;
        case EdgeMode::FALLING: mode = FALLING; break;
        default:                mode = CHANGE;  break;
    }
    attachInterruptArg((uint8_t)cfg.engine_pulse_pin, gpio_isr_handler, nullptr, mode);

    Serial.printf("[eng] GPIO ISR backend — pin %u, CPU freq %u Hz\n",
                  cfg.engine_pulse_pin, s_timer_res_hz);
#endif

    s_initialized = true;
    Serial.printf("[eng] init ok — pin %u, %u p/rev, edge %u\n",
                  cfg.engine_pulse_pin,
                  cfg.engine_pulses_per_rev,
                  (uint8_t)cfg.engine_edge_mode);
}

// ---- deinit() ----
void deinit() {
    if (!s_initialized) return;

#ifndef ENGINE_PULSE_BACKEND_ISR
    if (s_cap_channel) {
        mcpwm_capture_channel_disable(s_cap_channel);
        mcpwm_del_capture_channel(s_cap_channel);
        s_cap_channel = nullptr;
    }
    if (s_cap_timer) {
        mcpwm_capture_timer_stop(s_cap_timer);
        mcpwm_capture_timer_disable(s_cap_timer);
        mcpwm_del_capture_timer(s_cap_timer);
        s_cap_timer = nullptr;
    }
#else
    detachInterrupt((uint8_t)s_isr_pin);
#endif

    s_initialized = false;
    Serial.println("[eng] deinit");
}

// ---- reconfigure() ----
// deinit → init atomically from the caller's perspective.
// EMA is seeded fresh; rejected_pulses resets to zero.
bool reconfigure(const DynoConfig& cfg) {
    deinit();
    s_snapshot            = {};   // clear before init() so getters see clean state
    s_ema_period_us       = 0;
    init(cfg);
    return s_initialized;
}

// ---- is_init() ----
bool is_init() {
    return s_initialized;
}

// ============================================================
//  update() — the only function that transitions module state
//
//  Call once per telemetry frame from task context (not ISR).
//  Reads the ISR-written volatile words, applies:
//    1. Stall / timeout detection
//    2. Minimum interval filter (noise rejection)
//    3. EMA smoothing
//    4. RPM integer computation
//  Then writes results into s_snapshot atomically.
// ============================================================
void update() {
    if (!s_initialized) {
        s_snapshot = {};
        return;
    }

    // ---- 1. Snapshot volatile ISR data ----
    // On Xtensa LX6 each 32-bit aligned load is a single l32i instruction
    // and is therefore atomic.  Read count before delta to avoid a TOCTOU
    // where ISR fires between the two reads and increments both.
    const uint32_t count     = s_pulse_count;
    const uint32_t raw_delta = s_raw_delta_ticks;

    // ---- 2. Stall / timeout detection ----
    const uint32_t now_ms = millis();

    if (count != s_prev_pulse_count) {
        s_prev_pulse_count = count;
        s_last_pulse_ms    = now_ms;
    }

    const bool timed_out = (now_ms - s_last_pulse_ms) >= STALL_TIMEOUT_MS;

    if (timed_out || raw_delta == 0u) {
        s_snapshot.period_us = 0;
        s_snapshot.rpm_x10   = 0;
        s_snapshot.valid     = false;
        s_snapshot.timeout   = timed_out;
        // rejected_pulses is persistent — do not reset here
        return;
    }

    // ---- 3. Ticks → µs ----
    const uint32_t period_us = ticks_to_us(raw_delta, s_timer_res_hz);

    // ---- 4. Minimum interval filter ----
    if (period_us < MIN_PERIOD_US) {
        if (s_snapshot.rejected_pulses < UINT16_MAX) {
            s_snapshot.rejected_pulses++;
        }
        // Leave previous valid snapshot untouched; clear timeout flag only.
        s_snapshot.timeout = false;
        return;
    }

    // ---- 5. EMA smoothing ----
    if (s_ema_period_us == 0u) {
        // Seed on first valid sample so the EMA starts at a realistic value
        // rather than pulling toward zero for the first few pulses.
        s_ema_period_us = period_us;
    } else {
        s_ema_period_us = ema_update(s_ema_period_us, period_us);
    }

    // ---- 6. RPM × 10 (integer, no float) ----
    // Derivation:
    //   period_us is the inter-pulse time in microseconds for one "pulse event".
    //   One revolution = pulses_per_rev events → rev_time_us = period_us * ppr
    //   RPM = (60 * 1_000_000) / rev_time_us
    //   RPM×10 = (60 * 1_000_000 * 10) / rev_time_us
    //          = 600_000_000 / (period_us * ppr)
    //
    // uint64_t intermediate prevents overflow even at idle-speed RPMs where
    // period_us can be 50 000+ and ppr might be 60.
    const int32_t rpm_x10 = (int32_t)(
        600000000ULL / ((uint64_t)s_ema_period_us * (uint64_t)s_pulses_per_rev)
    );

    // ---- 7. Commit snapshot ----
    s_snapshot.period_us = s_ema_period_us;
    s_snapshot.rpm_x10   = rpm_x10;
    s_snapshot.valid     = true;
    s_snapshot.timeout   = false;
    // rejected_pulses accumulates across frames — leave as-is
}

// ============================================================
//  Snapshot accessors — pure reads, no side effects
// ============================================================

const EnginePulseState& get_state() {
    return s_snapshot;
}

uint32_t get_period_us() {
    return s_snapshot.period_us;
}

int32_t get_rpm_x10() {
    return s_snapshot.rpm_x10;
}

float get_rpm() {
    return (float)s_snapshot.rpm_x10 / 10.0f;
}

bool is_valid() {
    return s_snapshot.valid;
}

bool is_timeout() {
    return s_snapshot.timeout;
}

uint16_t get_rejected_count() {
    return s_snapshot.rejected_pulses;
}

}  // namespace EnginePulse
