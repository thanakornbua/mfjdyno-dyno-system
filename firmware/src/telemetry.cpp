#include "telemetry.h"
#include "protocol.h"
#include "engine_pulse.h"
#include "encoder.h"
#include "can_afr.h"
#include <Arduino.h>
#include <stddef.h>   // offsetof
#include "esp_timer.h"

// ============================================================
//  Scaling — all sensor fields are integer-scaled by their source modules.
//
//  engine_period_us      raw µs, exact
//  engine_rpm_x10        RPM × 10  from EnginePulse::get_rpm_x10()
//  roller_rpm_x10        RPM × 10  from Encoder::get_rpm_x10()
//  encoder_delta         int16 PCNT delta from Encoder::get_last_delta()
//  encoder_count_total   int32 from Encoder::get_count_total()
//  afr_scaled_x100       AFR × 100 from CanAfr::get_afr_x100()
//  lambda_scaled_x1000   λ × 1000  from CanAfr::get_lambda_x1000()
//
//  AFR scaling and stoichiometric AFR are configured in can_afr.cpp.
// ============================================================

// ---- Module state ----
static uint8_t  s_fault_flags  = 0;
static uint16_t s_seq          = 0;

// Edge-detection state for SIG_ENGINE_STALL / SIG_ROLLER_STOP
static bool s_engine_was_valid = false;
static bool s_roller_was_valid = false;

namespace Telemetry {

void init(const DynoConfig& /*cfg*/) {
    s_fault_flags       = 0;
    s_seq               = 0;
    s_engine_was_valid  = false;
    s_roller_was_valid  = false;
}

void set_fault(uint8_t fault_bit) {
    s_fault_flags |= fault_bit;
}

void clear_fault(uint8_t fault_bit) {
    s_fault_flags &= ~fault_bit;
}

void send() {
    // ---- 1. Advance sensor state for this frame ----
    // EnginePulse::update() must run before any EnginePulse getter is called;
    // it is the only function that transitions internal state (EMA, stall, snapshot).
    EnginePulse::update();
    Encoder::tick();

    // ---- 2. Sample all sensors ----
    const bool  engine_valid = EnginePulse::is_valid();
    const bool  roller_valid = Encoder::is_valid();
    const bool  afr_valid    = CanAfr::is_valid();
    const bool  can_active   = CanAfr::is_init();

    const uint32_t period_us      = EnginePulse::get_period_us();
    const int32_t  engine_rpm_x10 = EnginePulse::get_rpm_x10();  // integer, no float
    const int32_t  roller_rpm_x10 = Encoder::get_rpm_x10();       // integer, no float
    const uint16_t afr_x100      = CanAfr::get_afr_x100();        // integer, 0 when invalid
    const uint16_t lambda_x1000  = CanAfr::get_lambda_x1000();    // integer, 0 when invalid
    const CanStatus can_st       = CanAfr::get_status();

    // ---- 3. Build signal flags ----
    uint8_t sig = 0;
    if (engine_valid) sig |= SIG_ENGINE_VALID;
    if (roller_valid) sig |= SIG_ROLLER_VALID;
    if (afr_valid)    sig |= SIG_AFR_VALID;
    if (can_active)   sig |= SIG_CAN_ACTIVE;

    // Edge: was valid last frame but not this frame → set stall/stop bits for one frame
    if (s_engine_was_valid && !engine_valid) sig |= SIG_ENGINE_STALL;
    if (s_roller_was_valid && !roller_valid) sig |= SIG_ROLLER_STOP;
    s_engine_was_valid = engine_valid;
    s_roller_was_valid = roller_valid;

    // Reflect current bus-off state into fault flags (auto-latching)
    if (can_st == CanStatus::BUS_OFF) {
        s_fault_flags |= FLT_CAN_BUS_OFF;
    }

    // ---- 4. Populate fault flags from init state ----
    // Combine latched faults with live init checks so that a successful
    // reinit clears the corresponding bit automatically.
    uint8_t live_faults = s_fault_flags;
    if (!EnginePulse::is_init()) live_faults |= FLT_ENGINE_INIT;
    if (!Encoder::is_init())     live_faults |= FLT_ENCODER_INIT;
    if (!CanAfr::is_init())      live_faults |= FLT_CAN_INIT;

    // ---- 5. Assemble frame ----
    TelemetryFrame frame = {};

    frame.magic[0]            = TELEM_MAGIC_0;
    frame.magic[1]            = TELEM_MAGIC_1;
    frame.version             = PROTO_TELEM_VERSION;
    frame.pkt_type            = static_cast<uint8_t>(PacketType::TELEM_DATA);
    frame.seq                 = s_seq++;
    frame.frame_len           = sizeof(TelemetryFrame);

    frame.timestamp_us        = (uint64_t)esp_timer_get_time();

    frame.engine_period_us    = period_us;
    frame.engine_rpm_x10      = engine_rpm_x10;

    frame.encoder_count_total = Encoder::get_count_total();
    frame.roller_rpm_x10      = roller_rpm_x10;
    frame.encoder_delta       = Encoder::get_last_delta();
    frame.signal_flags        = sig;
    frame.fault_flags         = live_faults;

    frame.afr_scaled_x100     = afr_x100;
    frame.lambda_scaled_x1000 = lambda_x1000;
    frame.can_status          = static_cast<uint8_t>(can_st);
    frame._rsv                = 0;

    // ---- 6. CRC16-CCITT over all bytes before the crc16 field ----
    frame.crc16 = crc16_compute(
        reinterpret_cast<const uint8_t*>(&frame),
        offsetof(TelemetryFrame, crc16)
    );

    // ---- 7. Send ----
    Protocol::send_telemetry(frame);
}

} // namespace Telemetry
