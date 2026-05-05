#include "engine_pulse.h"
#include <Arduino.h>

static constexpr uint32_t DEBOUNCE_US      = 75u;
static constexpr uint32_t STALL_TIMEOUT_US = 500000u;  // 500 ms

static volatile uint32_t s_period_us  = 0;
static volatile uint32_t s_last_us    = 0;
static volatile uint16_t s_rejected   = 0;
static volatile bool     s_ever_fired = false;

static EnginePulseState s_state{};
static bool             s_initialized = false;
static DynoConfig       s_cfg{};

void IRAM_ATTR pulse_isr() {
    uint32_t now      = micros();
    uint32_t interval = now - s_last_us;
    if (s_ever_fired && interval < DEBOUNCE_US) {
        if (s_rejected < 65535u) s_rejected++;
        return;
    }
    s_period_us  = interval;
    s_last_us    = now;
    s_ever_fired = true;
}

void EnginePulse::init(const DynoConfig& cfg) {
    s_cfg        = cfg;
    s_period_us  = 0;
    s_last_us    = 0;
    s_rejected   = 0;
    s_ever_fired = false;
    s_state      = {};

    pinMode(cfg.engine_pulse_pin, INPUT);

    int mode;
    switch (cfg.engine_edge_mode) {
        case EdgeMode::EDGE_FALLING: mode = FALLING; break;
        case EdgeMode::EDGE_BOTH:    mode = CHANGE;  break;
        default:                     mode = RISING;  break;
    }
    attachInterrupt(digitalPinToInterrupt(cfg.engine_pulse_pin), pulse_isr, mode);
    s_initialized = true;
}

void EnginePulse::deinit() {
    if (!s_initialized) return;
    detachInterrupt(digitalPinToInterrupt(s_cfg.engine_pulse_pin));
    s_initialized = false;
}

bool EnginePulse::reconfigure(const DynoConfig& cfg) {
    deinit();
    init(cfg);
    return true;
}

bool EnginePulse::is_init() { return s_initialized; }

void EnginePulse::update() {
    noInterrupts();
    uint32_t period  = s_period_us;
    uint32_t last    = s_last_us;
    bool     ever    = s_ever_fired;
    uint16_t rej     = s_rejected;
    interrupts();

    bool timeout = ever && ((micros() - last) >= STALL_TIMEOUT_US);

    s_state.rejected_pulses = rej;
    s_state.timeout         = timeout;

    if (!ever || timeout) {
        s_state.period_us = 0;
        s_state.rpm_x10   = 0;
        s_state.valid     = false;
        return;
    }

    float ppr = (s_cfg.engine_pulses_per_rev > 0)
                    ? (float)s_cfg.engine_pulses_per_rev : 1.0f;
    float rpm = (60000000.0f / (float)period) / ppr;

    s_state.period_us = period;
    s_state.rpm_x10   = (int32_t)(rpm * 10.0f);
    s_state.valid     = true;
}

const EnginePulseState& EnginePulse::get_state()    { return s_state; }
uint32_t EnginePulse::get_period_us()               { return s_state.period_us; }
uint32_t EnginePulse::get_interval_us()             { return s_state.period_us; }
int32_t  EnginePulse::get_rpm_x10()                 { return s_state.rpm_x10; }
float    EnginePulse::get_rpm()                     { return (float)s_state.rpm_x10 / 10.0f; }
bool     EnginePulse::is_valid()                    { return s_state.valid; }
bool     EnginePulse::is_timeout()                  { return s_state.timeout; }
uint16_t EnginePulse::get_rejected_count()          { return s_state.rejected_pulses; }
