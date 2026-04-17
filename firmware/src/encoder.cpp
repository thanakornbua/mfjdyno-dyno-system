#include "encoder.h"
#include <Arduino.h>
#include "driver/pcnt.h"
#include "freertos/FreeRTOS.h"
#include "freertos/portmacro.h"

// ============================================================
//  Design notes
//
//  Single-channel, both-edges counting (increment on rise AND fall).
//    CPR = encoder_ppr × 2.
//    Direction is not tracked — the roller only spins forward.
//
//  RPM integer math (no float):
//    delta = counts accumulated in interval_ms milliseconds
//    CPR   = ppr × EDGES_PER_PULSE (2)
//    rpm_x10 = |delta| × 600_000 / (CPR × interval_ms)
//            = |delta| × 300_000 / (ppr  × interval_ms)
//
//    Uses int64_t intermediate to avoid overflow at:
//      large delta (high PPR, fast roller, slow tick rate) and
//      small ppr × interval_ms denominator.
//
//  Overflow protection:
//    PCNT is 16-bit signed (−32768 … 32767).  Watch-points at both
//    limits call on_reach() (IRAM) which adjusts s_hw_overflow.
//    tick() atomically reads hw_count + s_hw_overflow under a
//    portDISABLE_INTERRUPTS critical section (~3 register reads)
//    to prevent a torn read between the two values.
//
//  Glitch filter:
//    PCNT hardware filter rejects pulses narrower than
//    GLITCH_FILTER_NS.  At 1000 ns this passes all pulse rates up
//    to ~500 kHz — no physical roller encoder comes close.
//    The filter requires the APB clock (80 MHz) to be active;
//    it is always on for the ESP32 in normal operation.
// ============================================================

static constexpr int     PCNT_LOW_LIMIT    = -32768;
static constexpr int     PCNT_HIGH_LIMIT   =  32767;
static constexpr int32_t PCNT_RANGE        = (int32_t)PCNT_HIGH_LIMIT
                                           - (int32_t)PCNT_LOW_LIMIT + 1;  // 65536

static constexpr uint32_t GLITCH_FILTER_NS  = 1000u;  // 1 µs: rejects EMI, passes encoder
static constexpr uint32_t STOPPED_TIMEOUT_MS = 500u;
static constexpr uint8_t  EDGES_PER_PULSE    = 2u;     // both rising and falling edges counted

// ============================================================
//  ISR-shared state (written by on_reach, read by tick)
// ============================================================

// Cumulative hardware overflow counter.
// +1 each time the PCNT counter wraps at HIGH_LIMIT (forward overflow).
// -1 each time it wraps at LOW_LIMIT  (reverse underflow, rare in practice).
static volatile int32_t s_hw_overflow = 0;

// IRAM callback — called when PCNT counter reaches a watch-point.
// No float, no Serial, no heap.
static bool IRAM_ATTR on_reach(pcnt_unit_handle_t /*unit*/,
                                const pcnt_watch_event_data_t* edata,
                                void* /*user_ctx*/) {
    if (edata->watch_point_val == PCNT_HIGH_LIMIT) {
        s_hw_overflow++;
    } else {
        s_hw_overflow--;
    }
    return false;  // no high-priority task to wake
}

// ============================================================
//  Module state (task context only)
// ============================================================

static pcnt_unit_handle_t    s_pcnt_unit = nullptr;
static pcnt_channel_handle_t s_pcnt_chan = nullptr;

static uint16_t  s_ppr            = 1;
static bool      s_initialized    = false;

// 32-bit true count from the previous tick() call.
// Computed as hw_count + s_hw_overflow * PCNT_RANGE.
static int32_t   s_prev_true_count = 0;
static uint32_t  s_prev_ms         = 0;
static uint32_t  s_last_move_ms    = 0;

// Snapshot — populated by tick(), read by all getters.
static EncoderState s_state = {};

// ============================================================
//  Lifecycle
// ============================================================

namespace Encoder {

void init(const DynoConfig& cfg) {
    s_ppr         = cfg.encoder_ppr ? cfg.encoder_ppr : 1u;  // guard div/0
    s_hw_overflow = 0;
    s_prev_true_count = 0;
    s_prev_ms     = millis();
    s_last_move_ms = millis();
    s_state       = {};

    // ---- PCNT unit ----
    pcnt_unit_config_t unit_cfg = {};
    unit_cfg.low_limit  = PCNT_LOW_LIMIT;
    unit_cfg.high_limit = PCNT_HIGH_LIMIT;
    // accum_count lets the driver maintain a 32-bit shadow internally,
    // but we implement our own overflow tracking for full control.

    esp_err_t err = pcnt_new_unit(&unit_cfg, &s_pcnt_unit);
    if (err != ESP_OK) {
        Serial.printf("[enc] pcnt_new_unit failed: %s\n", esp_err_to_name(err));
        return;
    }

    // ---- Glitch filter ----
    pcnt_glitch_filter_config_t flt_cfg = {};
    flt_cfg.max_glitch_ns = GLITCH_FILTER_NS;
    err = pcnt_unit_set_glitch_filter(s_pcnt_unit, &flt_cfg);
    if (err != ESP_OK) {
        // Non-fatal: log and continue without filter.
        Serial.printf("[enc] warn: glitch filter unavailable: %s\n", esp_err_to_name(err));
    }

    // ---- Single channel: pin_a edge, no level/direction pin ----
    pcnt_chan_config_t chan_cfg = {};
    chan_cfg.edge_gpio_num  = cfg.encoder_pin_a;
    chan_cfg.level_gpio_num = PCNT_PIN_NOT_USED;

    err = pcnt_new_channel(s_pcnt_unit, &chan_cfg, &s_pcnt_chan);
    if (err != ESP_OK) {
        Serial.printf("[enc] pcnt_new_channel failed: %s\n", esp_err_to_name(err));
        pcnt_del_unit(s_pcnt_unit);
        s_pcnt_unit = nullptr;
        return;
    }

    // Count on both rising and falling edges — CPR = ppr × 2.
    // No direction input → HOLD action on level changes (no level pin anyway).
    pcnt_channel_set_edge_action(s_pcnt_chan,
        PCNT_CHANNEL_EDGE_ACTION_INCREMENT,   // rising  edge → +1
        PCNT_CHANNEL_EDGE_ACTION_INCREMENT);  // falling edge → +1

    // ---- Watch-points for 32-bit overflow extension ----
    err = pcnt_unit_add_watch_point(s_pcnt_unit, PCNT_HIGH_LIMIT);
    if (err != ESP_OK) {
        Serial.printf("[enc] warn: high watch-point failed: %s\n", esp_err_to_name(err));
    }
    err = pcnt_unit_add_watch_point(s_pcnt_unit, PCNT_LOW_LIMIT);
    if (err != ESP_OK) {
        Serial.printf("[enc] warn: low watch-point failed: %s\n", esp_err_to_name(err));
    }

    pcnt_event_callbacks_t cbs = {};
    cbs.on_reach = on_reach;
    pcnt_unit_register_event_callbacks(s_pcnt_unit, &cbs, nullptr);

    // ---- Enable and start ----
    pcnt_unit_enable(s_pcnt_unit);
    pcnt_unit_clear_count(s_pcnt_unit);
    pcnt_unit_start(s_pcnt_unit);

    s_initialized = true;
    Serial.printf("[enc] init ok — pin_a=%u ppr=%u CPR=%u (both-edge)\n",
                  cfg.encoder_pin_a, cfg.encoder_ppr,
                  (uint32_t)cfg.encoder_ppr * EDGES_PER_PULSE);
}

void deinit() {
    if (!s_initialized) return;

    if (s_pcnt_unit) {
        pcnt_unit_stop(s_pcnt_unit);
        pcnt_unit_disable(s_pcnt_unit);
        if (s_pcnt_chan) {
            pcnt_del_channel(s_pcnt_chan);
            s_pcnt_chan = nullptr;
        }
        pcnt_del_unit(s_pcnt_unit);
        s_pcnt_unit = nullptr;
    }

    s_initialized = false;
    Serial.println("[enc] deinit");
}

bool reconfigure(const DynoConfig& cfg) {
    deinit();
    s_state            = {};
    s_prev_true_count  = 0;
    s_hw_overflow      = 0;
    init(cfg);
    return s_initialized;
}

bool is_init() {
    return s_initialized;
}

// ============================================================
//  tick() — sole state-transition function
//  Call once per telemetry frame from task context.
// ============================================================
void tick() {
    if (!s_initialized) {
        s_state = {};
        return;
    }

    // ---- 1. Atomically snapshot PCNT count + overflow accumulator ----
    // The critical section prevents a torn read between hw_count and s_hw_overflow:
    // if the PCNT counter hits HIGH_LIMIT between the two reads, the overflow
    // callback would have already fired and hw_count already reset to LOW_LIMIT,
    // so without the lock we'd compute an incorrect 32-bit value.
    int hw_count = 0;
    int32_t ovf = 0;
    portDISABLE_INTERRUPTS();
    pcnt_unit_get_count(s_pcnt_unit, &hw_count);
    ovf = s_hw_overflow;
    portENABLE_INTERRUPTS();

    // ---- 2. Compute 32-bit true count and delta ----
    int32_t true_count = (int32_t)hw_count + ovf * PCNT_RANGE;
    int32_t delta      = true_count - s_prev_true_count;
    s_prev_true_count  = true_count;

    uint32_t now_ms    = millis();
    uint32_t interval_ms = now_ms - s_prev_ms;
    s_prev_ms          = now_ms;

    // ---- 3. Accumulate total count ----
    s_state.count_total += delta;

    // Saturate delta to int16 for the telemetry wire field.
    // This only clips if the roller was stopped for many seconds and then
    // suddenly spun — the total accumulator is still int32-correct.
    s_state.delta = (int16_t)constrain(delta, (int32_t)INT16_MIN, (int32_t)INT16_MAX);

    // ---- 4. Stopped / validity detection ----
    if (delta != 0) {
        s_last_move_ms = now_ms;
    }
    s_state.valid = (now_ms - s_last_move_ms) < STOPPED_TIMEOUT_MS;

    // ---- 5. RPM × 10 (integer, no float) ----
    // Guard: skip calculation on first tick (interval undefined) or if stopped.
    if (!s_state.valid || interval_ms == 0u) {
        s_state.rpm_x10 = 0;
        return;
    }

    // rpm_x10 = |delta| × 600_000 / (CPR × interval_ms)
    //         = |delta| × 300_000 / (ppr  × interval_ms)   [CPR = ppr × 2]
    //
    // int64_t guards against overflow when:
    //   delta is large (high PPR, fast roller, slow tick rate), AND
    //   denominator is small (low ppr × short interval).
    //
    // Practical ceiling: 10 000 PPR × 3 000 RPM × (1/10 Hz tick) = 50 000 counts/tick.
    // Numerator: 50 000 × 300 000 = 15 × 10^9 — fits in int64_t, overflows int32_t.
    const int32_t abs_delta = (delta < 0) ? -delta : delta;
    s_state.rpm_x10 = (int32_t)(
        ((int64_t)abs_delta * 300000LL) /
        ((int64_t)s_ppr * (int64_t)interval_ms)
    );
}

// ============================================================
//  Snapshot accessors — pure reads, no side effects
// ============================================================

const EncoderState& get_state() {
    return s_state;
}

int32_t get_count_total() {
    return s_state.count_total;
}

int16_t get_last_delta() {
    return s_state.delta;
}

int32_t get_rpm_x10() {
    return s_state.rpm_x10;
}

float get_rpm() {
    return (float)s_state.rpm_x10 / 10.0f;
}

bool is_valid() {
    return s_state.valid;
}

}  // namespace Encoder
