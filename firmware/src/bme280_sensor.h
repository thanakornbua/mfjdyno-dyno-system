#pragma once
#include <stdint.h>
#include <stdbool.h>

struct BmeState {
    float temp_c;
    float humidity;
    float pressure;   // hPa
    bool  valid;
};

namespace BmeSensor {
    // SDA=GPIO21, SCL=GPIO22 (fixed hardware; not in DynoConfig)
    void  init();
    void  read();

    bool  is_init();
    bool  is_valid();

    float get_temp_c();
    float get_humidity();
    float get_pressure_hpa();

    const BmeState& get_state();
}
