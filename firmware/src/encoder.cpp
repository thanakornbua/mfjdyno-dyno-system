#include "encoder.h"
#include <Arduino.h>

// Quadrature decode lookup: index = (prev_ab << 2) | curr_ab
// A is bit-1, B is bit-0.  Forward: 00→10→11→01→00 = +1 each step.
static const int8_t QEM[16] = {
     0, -1, +1,  0,
    +1,  0,  0, -1,
    -1,  0,  0, +1,
     0, +1, -1,  0
};

static volatile int32_t s_count    = 0;
static volatile uint8_t s_ab_state = 0;

static uint8_t  s_pin_a = 34;
static uint8_t  s_pin_b = 35;

static EncoderState s_state{};
static bool         s_initialized    = false;
static uint16_t     s_ppr            = 1024;
static uint32_t     s_last_tick_ms   = 0;
static int32_t      s_count_snapshot = 0;

void IRAM_ATTR encoder_isr() {
    uint8_t a    = (uint8_t)digitalRead(s_pin_a);
    uint8_t b    = (uint8_t)digitalRead(s_pin_b);
    uint8_t curr = (a << 1) | b;
    uint8_t idx  = (s_ab_state << 2) | curr;
    s_count   += QEM[idx];
    s_ab_state = curr;
}

void Encoder::init(const DynoConfig& cfg) {
    s_pin_a = cfg.encoder_pin_a;
    s_pin_b = cfg.encoder_pin_b;
    s_ppr   = (cfg.encoder_ppr > 0) ? cfg.encoder_ppr : 1;

    s_count    = 0;
    s_state    = {};
    s_last_tick_ms   = millis();
    s_count_snapshot = 0;

    pinMode(cfg.encoder_pin_a, INPUT);
    pinMode(cfg.encoder_pin_b, INPUT);

    // Seed state machine from actual pin levels to avoid a spurious count
    uint8_t a  = (uint8_t)digitalRead(cfg.encoder_pin_a);
    uint8_t b  = (uint8_t)digitalRead(cfg.encoder_pin_b);
    s_ab_state = (a << 1) | b;

    attachInterrupt(digitalPinToInterrupt(cfg.encoder_pin_a), encoder_isr, CHANGE);
    attachInterrupt(digitalPinToInterrupt(cfg.encoder_pin_b), encoder_isr, CHANGE);

    s_initialized = true;
}

void Encoder::deinit() {
    if (!s_initialized) return;
    detachInterrupt(digitalPinToInterrupt(s_pin_a));
    detachInterrupt(digitalPinToInterrupt(s_pin_b));
    s_initialized = false;
}

bool Encoder::reconfigure(const DynoConfig& cfg) {
    deinit();
    init(cfg);
    return true;
}

bool Encoder::is_init() { return s_initialized; }

void Encoder::tick() {
    noInterrupts();
    int32_t count_now = s_count;
    interrupts();

    uint32_t now_ms = millis();
    uint32_t dt_ms  = now_ms - s_last_tick_ms;
    int32_t  delta  = count_now - s_count_snapshot;

    s_count_snapshot = count_now;
    s_last_tick_ms   = now_ms;

    int16_t delta16 = (delta < -32768) ? (int16_t)-32768
                    : (delta >  32767) ? (int16_t) 32767
                    : (int16_t)delta;

    s_state.count_total = count_now;
    s_state.delta       = delta16;

    if (dt_ms > 0 && delta != 0) {
        // CPR = PPR * 4 for full quadrature (CHANGE on both A and B)
        float cpr = (float)s_ppr * 4.0f;
        float rpm = ((float)(delta < 0 ? -delta : delta) / cpr) * 60000.0f / (float)dt_ms;
        s_state.rpm_x10 = (int32_t)(rpm * 10.0f);
        s_state.valid   = true;
    } else {
        s_state.rpm_x10 = 0;
        s_state.valid   = false;
    }
}

const EncoderState& Encoder::get_state()    { return s_state; }
int16_t Encoder::get_count()               { return s_state.delta; }
int32_t Encoder::get_count_total()         { return s_state.count_total; }
int16_t Encoder::get_last_delta()          { return s_state.delta; }
int32_t Encoder::get_rpm_x10()             { return s_state.rpm_x10; }
float   Encoder::get_rpm()                 { return (float)s_state.rpm_x10 / 10.0f; }
bool    Encoder::is_valid()                { return s_state.valid; }
