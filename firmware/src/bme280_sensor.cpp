#include "bme280_sensor.h"
#include <Wire.h>
#include <Adafruit_BME280.h>
#include <Arduino.h>

static constexpr uint8_t SDA_PIN = 21;
static constexpr uint8_t SCL_PIN = 22;

static Adafruit_BME280 s_bme;
static BmeState         s_state{};
static bool             s_initialized = false;

void BmeSensor::init() {
    Wire.begin(SDA_PIN, SCL_PIN);

    bool found = s_bme.begin(0x76);
    if (!found) found = s_bme.begin(0x77);

    if (!found) {
        Serial.println("[bme] sensor not found on 0x76 or 0x77");
        s_initialized = false;
        return;
    }

    s_bme.setSampling(
        Adafruit_BME280::MODE_NORMAL,
        Adafruit_BME280::SAMPLING_X1,    // temperature
        Adafruit_BME280::SAMPLING_X1,    // pressure
        Adafruit_BME280::SAMPLING_X1,    // humidity
        Adafruit_BME280::FILTER_OFF,
        Adafruit_BME280::STANDBY_MS_250
    );

    s_initialized = true;
    Serial.println("[bme] init ok");
}

void BmeSensor::read() {
    if (!s_initialized) return;
    s_state.temp_c   = s_bme.readTemperature();
    s_state.humidity = s_bme.readHumidity();
    s_state.pressure = s_bme.readPressure() / 100.0f;  // Pa → hPa
    s_state.valid    = true;
}

bool            BmeSensor::is_init()           { return s_initialized; }
bool            BmeSensor::is_valid()          { return s_state.valid; }
float           BmeSensor::get_temp_c()        { return s_state.temp_c; }
float           BmeSensor::get_humidity()      { return s_state.humidity; }
float           BmeSensor::get_pressure_hpa()  { return s_state.pressure; }
const BmeState& BmeSensor::get_state()         { return s_state; }
