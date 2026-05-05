#include "telemetry.h"
#include "protocol.h"
#include "engine_pulse.h"
#include "encoder.h"
#include "can_afr.h"
#include "bme280_sensor.h"
#include <Arduino.h>

static uint8_t  s_fault_flags = 0;
static uint32_t s_seq         = 0;

namespace Telemetry {

void init(const DynoConfig& /*cfg*/) {
    s_fault_flags = 0;
    s_seq         = 0;
}

void set_fault(uint8_t fault_bit)   { s_fault_flags |=  fault_bit; }
void clear_fault(uint8_t fault_bit) { s_fault_flags &= ~fault_bit; }

void send() {
    EnginePulse::update();
    Encoder::tick();
    BmeSensor::read();

    char buf[256];
    snprintf(buf, sizeof(buf),
        "{\"seq\":%lu,\"engine_rpm\":%.1f,\"roller_rpm\":%.1f,"
        "\"encoder_count\":%ld,\"lambda\":%.4f,\"afr\":%.2f,"
        "\"temp_c\":%.2f,\"humidity\":%.1f,\"pressure\":%.2f,"
        "\"engine_valid\":%s,\"can_valid\":%s,\"bme_valid\":%s}\n",
        (unsigned long)s_seq++,
        EnginePulse::get_rpm(),
        Encoder::get_rpm(),
        (long)Encoder::get_count_total(),
        CanAfr::get_lambda_x1000() / 1000.0f,
        CanAfr::get_afr(),
        BmeSensor::get_temp_c(),
        BmeSensor::get_humidity(),
        BmeSensor::get_pressure_hpa(),
        EnginePulse::is_valid() ? "true" : "false",
        CanAfr::is_valid()      ? "true" : "false",
        BmeSensor::is_valid()   ? "true" : "false"
    );
    Serial2.print(buf);
}

} // namespace Telemetry
