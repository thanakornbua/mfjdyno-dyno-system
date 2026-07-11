// firmware-test.ino — ESP32 Dyno DAQ  (single-file, Arduino-compatible)
//
// Final hardware split
//   ESP32:
//     Encoder A    -> GPIO34
//     Encoder B    -> GPIO35
//     Encoder Z    -> GPIO32
//     Ignition     -> GPIO27  (LM393 digital output)
//     BME280 SDA   -> GPIO21
//     BME280 SCL   -> GPIO22
//   Pi:
//     CAN bus is handled on the Pi via USB CAN adapter
//     Single USB cable to the devkit's onboard USB-UART bridge (UART0 /
//     `Serial`) carries telemetry, the binary config-sync protocol, AND
//     firmware flashing — no external UART adapter or GPIO wiring needed.
//
// Flash with arduino-cli:
//   arduino-cli core install esp32:esp32
//   arduino-cli compile --fqbn esp32:esp32:esp32 firmware-test
//   arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 firmware-test

#include <Arduino.h>
#include <Wire.h>

// ================================================================
//  Pin / parameter constants
// ================================================================
static constexpr uint8_t  PIN_ENC_A   = 34;
static constexpr uint8_t  PIN_ENC_B   = 35;
static constexpr uint8_t  PIN_ENC_Z   = 32;
static constexpr uint8_t  PIN_IGN     = 27;
static constexpr uint8_t  PIN_SDA     = 21;
static constexpr uint8_t  PIN_SCL     = 22;
// Telemetry + config-sync protocol run on UART0 (`Serial`), the same pins as
// the devkit's onboard USB-UART bridge, so a single USB cable carries data,
// config sync, and flashing. Reported here only for the config-sync
// DEVICE_INFO/CONFIG_DATA payload; UART0 is not separately initialized.
static constexpr uint8_t  PIN_U0_RX   = 3;
static constexpr uint8_t  PIN_U0_TX   = 1;

// Set to 1 to enable verbose per-sample debug printing on the same stream as
// telemetry. Leave at 0 in normal operation — the JSON line already uses
// most of the 115200 baud budget at the configured telemetry rate, and any
// non-JSON line sharing the stream is downstream-safe but wasted bandwidth.
#define DYNO_DEBUG_LOG 0

static constexpr uint16_t ENC_PPR     = 1024;     // encoder lines per revolution
static constexpr uint16_t ENC_CPR     = ENC_PPR * 4;
static constexpr uint32_t ENC_ISR_DEBOUNCE_US = 75;
static constexpr int32_t  ENC_DELTA_DEADBAND = 5;
static constexpr uint32_t TELEM_MS    = 50;       // 20 Hz output rate
static constexpr uint8_t  PULSES_PER_REV = 1;             // ignition pulses per revolution
static constexpr uint32_t MIN_INTERVAL_US = 1500;         // reject shorter intervals as noise
static constexpr uint32_t ENGINE_TIMEOUT_US = 500000;     // 500 ms no valid pulse -> RPM = 0
static constexpr float    MAX_VALID_RPM = 20000.0f;
static constexpr float    MAX_RPM_JUMP_PER_SAMPLE = 2500.0f;
static constexpr uint8_t  REQUIRED_VALID_PULSES = 3;
static constexpr float    RPM_EMA_ALPHA = 0.25f;
static constexpr uint8_t  IGN_QUEUE_SIZE = 64;

static constexpr uint16_t PROTO_MAGIC = 0x4459;
static constexpr uint8_t  PROTO_VERSION = 1;
static constexpr uint8_t  FRAME_SIZE = 40;
static constexpr uint8_t  CRC_OFFSET = FRAME_SIZE - 2;
static constexpr uint8_t  PKT_CONFIG_GET = 0x10;
static constexpr uint8_t  PKT_CONFIG_SET = 0x11;
static constexpr uint8_t  PKT_CONFIG_APPLY = 0x12;
static constexpr uint8_t  PKT_PING = 0x13;
static constexpr uint8_t  PKT_DEVICE_INFO_GET = 0x14;
static constexpr uint8_t  PKT_ACK = 0x20;
static constexpr uint8_t  PKT_ERROR = 0x21;
static constexpr uint8_t  PKT_CONFIG_DATA = 0x22;
static constexpr uint8_t  PKT_DEVICE_INFO_DATA = 0x23;
static constexpr uint8_t  ERR_UNSUPPORTED_COMMAND = 3;

// ================================================================
//  BME280  —  inline driver (no external library required)
//  Bosch datasheet BST-BME280-DS002, sections 4.2 and 8
// ================================================================

struct Bme280Calib {
    uint16_t T1;
    int16_t  T2;
    int16_t  T3;
    uint16_t P1;
    int16_t  P2;
    int16_t  P3;
    int16_t  P4;
    int16_t  P5;
    int16_t  P6;
    int16_t  P7;
    int16_t  P8;
    int16_t  P9;
    uint8_t  H1;
    uint8_t  H3;
    int16_t  H2;
    int16_t  H4;
    int16_t  H5;
    int8_t   H6;
};

static uint8_t     bme_addr   = 0;
static Bme280Calib bme_cal{};
static bool        bme_ok     = false;
static float       bme_temp   = 0.0f;
static float       bme_hum    = 0.0f;
static float       bme_pres   = 0.0f;
static bool        bme_valid  = false;
static int32_t     bme_t_fine = 0;

static uint8_t bme_rd8(uint8_t reg) {
    Wire.beginTransmission(bme_addr);
    Wire.write(reg);
    Wire.endTransmission(false);
    Wire.requestFrom(bme_addr, (uint8_t)1);
    return Wire.available() ? Wire.read() : 0;
}

static void bme_rd_buf(uint8_t reg, uint8_t *buf, uint8_t len) {
    Wire.beginTransmission(bme_addr);
    Wire.write(reg);
    Wire.endTransmission(false);
    Wire.requestFrom(bme_addr, len);
    for (uint8_t i = 0; i < len; ++i) {
        buf[i] = Wire.available() ? Wire.read() : 0;
    }
}

static void bme_wr8(uint8_t reg, uint8_t val) {
    Wire.beginTransmission(bme_addr);
    Wire.write(reg);
    Wire.write(val);
    Wire.endTransmission();
}

static bool bme_init() {
    Wire.begin(PIN_SDA, PIN_SCL);

    bme_addr = 0x76;
    if (bme_rd8(0xD0) != 0x60) {
        bme_addr = 0x77;
        if (bme_rd8(0xD0) != 0x60) {
            return false;
        }
    }

    bme_wr8(0xE0, 0xB6);
    delay(10);

    uint8_t c[24];
    bme_rd_buf(0x88, c, 24);
    bme_cal.T1 = ((uint16_t)c[1] << 8) | c[0];
    bme_cal.T2 = (int16_t)(((uint16_t)c[3] << 8) | c[2]);
    bme_cal.T3 = (int16_t)(((uint16_t)c[5] << 8) | c[4]);
    bme_cal.P1 = ((uint16_t)c[7] << 8) | c[6];
    bme_cal.P2 = (int16_t)(((uint16_t)c[9] << 8) | c[8]);
    bme_cal.P3 = (int16_t)(((uint16_t)c[11] << 8) | c[10]);
    bme_cal.P4 = (int16_t)(((uint16_t)c[13] << 8) | c[12]);
    bme_cal.P5 = (int16_t)(((uint16_t)c[15] << 8) | c[14]);
    bme_cal.P6 = (int16_t)(((uint16_t)c[17] << 8) | c[16]);
    bme_cal.P7 = (int16_t)(((uint16_t)c[19] << 8) | c[18]);
    bme_cal.P8 = (int16_t)(((uint16_t)c[21] << 8) | c[20]);
    bme_cal.P9 = (int16_t)(((uint16_t)c[23] << 8) | c[22]);

    bme_cal.H1 = bme_rd8(0xA1);
    uint8_t h[7];
    bme_rd_buf(0xE1, h, 7);
    bme_cal.H2 = (int16_t)(((uint16_t)h[1] << 8) | h[0]);
    bme_cal.H3 = h[2];
    bme_cal.H4 = (int16_t)(((int16_t)(int8_t)h[3] << 4) | (h[4] & 0x0F));
    bme_cal.H5 = (int16_t)(((int16_t)(int8_t)h[5] << 4) | (h[4] >> 4));
    bme_cal.H6 = (int8_t)h[6];

    bme_wr8(0xF2, 0x01);  // humidity oversampling x1
    bme_wr8(0xF4, 0x27);  // temp x1, pressure x1, normal mode
    bme_wr8(0xF5, 0xA0);  // 250 ms standby, no IIR filter

    return true;
}

static float bme_comp_temp(int32_t adc_T) {
    double v1 = (adc_T / 16384.0 - bme_cal.T1 / 1024.0) * bme_cal.T2;
    double v2 = (adc_T / 131072.0 - bme_cal.T1 / 8192.0);
    v2 = v2 * v2 * bme_cal.T3;
    bme_t_fine = (int32_t)(v1 + v2);
    return (float)((v1 + v2) / 5120.0);
}

static float bme_comp_pres(int32_t adc_P) {
    double v1 = bme_t_fine / 2.0 - 64000.0;
    double v2 = v1 * v1 * bme_cal.P6 / 32768.0;
    v2 = v2 + v1 * bme_cal.P5 * 2.0;
    v2 = v2 / 4.0 + (double)bme_cal.P4 * 65536.0;
    v1 = (bme_cal.P3 * v1 * v1 / 524288.0 + bme_cal.P2 * v1) / 524288.0;
    v1 = (1.0 + v1 / 32768.0) * bme_cal.P1;
    if (v1 == 0.0) {
        return 0.0f;
    }
    double p = 1048576.0 - adc_P;
    p = (p - v2 / 4096.0) * 6250.0 / v1;
    v1 = bme_cal.P9 * p * p / 2147483648.0;
    v2 = p * bme_cal.P8 / 32768.0;
    p += (v1 + v2 + bme_cal.P7) / 16.0;
    return (float)(p / 100.0);
}

static float bme_comp_hum(int32_t adc_H) {
    double h = bme_t_fine - 76800.0;
    h = (adc_H - (bme_cal.H4 * 64.0 + bme_cal.H5 / 16384.0 * h))
        * (bme_cal.H2 / 65536.0
           * (1.0 + bme_cal.H6 / 67108864.0 * h
              * (1.0 + bme_cal.H3 / 67108864.0 * h)));
    h *= (1.0 - bme_cal.H1 * h / 524288.0);
    if (h > 100.0) {
        h = 100.0;
    }
    if (h < 0.0) {
        h = 0.0;
    }
    return (float)h;
}

static void bme_read() {
    if (!bme_ok) {
        bme_valid = false;
        bme_temp = 0.0f;
        bme_hum = 0.0f;
        bme_pres = 0.0f;
        return;
    }

    uint8_t d[8];
    bme_rd_buf(0xF7, d, 8);
    int32_t adc_P = ((int32_t)d[0] << 12) | ((int32_t)d[1] << 4) | (d[2] >> 4);
    int32_t adc_T = ((int32_t)d[3] << 12) | ((int32_t)d[4] << 4) | (d[5] >> 4);
    int32_t adc_H = ((int32_t)d[6] << 8) | d[7];

    bme_temp = bme_comp_temp(adc_T);
    bme_pres = bme_comp_pres(adc_P);
    bme_hum = bme_comp_hum(adc_H);
    bme_valid = true;
}

// ================================================================
//  Encoder  —  full quadrature on A/B, Z index monitored separately
// ================================================================

static const int8_t QEM[16] = {
     0, -1, +1,  0,
    +1,  0,  0, -1,
    -1,  0,  0, +1,
     0, +1, -1,  0
};

static volatile int32_t enc_count      = 0;
static volatile uint8_t enc_ab_state   = 0;
static volatile uint32_t enc_z_pulses  = 0;
static volatile uint32_t enc_last_edge_us = 0;

static int32_t  enc_snap       = 0;
static int32_t  enc_total      = 0;
static int32_t  enc_delta      = 0;
static uint32_t enc_last_ms    = 0;
static float    enc_rpm        = 0.0f;
static bool     enc_valid      = false;
static bool     encoder_ready  = false;

void IRAM_ATTR encoder_ab_isr() {
    uint32_t now_us = micros();
    if (enc_last_edge_us != 0 && (now_us - enc_last_edge_us) < ENC_ISR_DEBOUNCE_US) {
        return;
    }
    enc_last_edge_us = now_us;

    uint8_t a = (uint8_t)digitalRead(PIN_ENC_A);
    uint8_t b = (uint8_t)digitalRead(PIN_ENC_B);
    uint8_t curr = (a << 1) | b;
    enc_count += QEM[(enc_ab_state << 2) | curr];
    enc_ab_state = curr;
}

void IRAM_ATTR encoder_z_isr() {
    ++enc_z_pulses;
}

static bool encoder_init() {
    pinMode(PIN_ENC_A, INPUT);
    pinMode(PIN_ENC_B, INPUT);
    pinMode(PIN_ENC_Z, INPUT);

    enc_ab_state = ((uint8_t)digitalRead(PIN_ENC_A) << 1) | (uint8_t)digitalRead(PIN_ENC_B);
    enc_last_ms = millis();

    attachInterrupt(digitalPinToInterrupt(PIN_ENC_A), encoder_ab_isr, CHANGE);
    attachInterrupt(digitalPinToInterrupt(PIN_ENC_B), encoder_ab_isr, CHANGE);
    attachInterrupt(digitalPinToInterrupt(PIN_ENC_Z), encoder_z_isr, RISING);

    encoder_ready = true;
    enc_valid = true;
    return true;
}

static void encoder_tick() {
    noInterrupts();
    int32_t cnt = enc_count;
    interrupts();

    uint32_t now_ms = millis();
    uint32_t dt_ms = now_ms - enc_last_ms;
    int32_t delta = cnt - enc_snap;

    if (delta > -ENC_DELTA_DEADBAND && delta < ENC_DELTA_DEADBAND) {
        delta = 0;
    }

    enc_snap = cnt;
    enc_total = cnt;
    enc_delta = delta;
    enc_last_ms = now_ms;

    if (dt_ms > 0 && delta != 0) {
        uint32_t step_count = (delta < 0) ? (uint32_t)(-delta) : (uint32_t)delta;
        enc_rpm = (float)step_count * 60000.0f / ((float)ENC_CPR * (float)dt_ms);
    } else {
        enc_rpm = 0.0f;
    }

    enc_valid = encoder_ready;
}

// ================================================================
//  Ignition pulse  —  GPIO27, FALLING, LM393
// ================================================================

static volatile uint32_t ign_last_edge_us = 0;
static volatile uint32_t ign_interval_queue[IGN_QUEUE_SIZE] = {};
static volatile uint8_t  ign_queue_head = 0;
static volatile uint8_t  ign_queue_tail = 0;

static uint32_t ign_last_accepted_us          = 0;
static uint32_t ign_debug_period_us           = 0;
static float    ign_debug_instant_rpm         = 0.0f;
static float    ign_filtered_rpm              = 0.0f;
static float    ign_previous_accepted_rpm     = 0.0f;
static float    ign_recent_accepted_rpms[3]   = {0.0f, 0.0f, 0.0f};
static uint8_t  ign_recent_rpm_count          = 0;
static uint8_t  ign_recent_rpm_index          = 0;
static uint8_t  ign_consecutive_valid_pulses  = 0;
static float    ign_rpm                       = 0.0f;
static bool     ign_valid                     = false;

static uint32_t accepted_pulses       = 0;
static uint32_t rejected_fast_pulses  = 0;
static uint32_t rejected_rpm_clamp    = 0;
static uint32_t rejected_jump         = 0;
static uint32_t timeout_count         = 0;

static float rpm_abs_diff(float a, float b) {
    return (a >= b) ? (a - b) : (b - a);
}

static float ignition_median3(const float *values, uint8_t count) {
    if (count == 0) {
        return 0.0f;
    }
    if (count == 1) {
        return values[0];
    }
    if (count == 2) {
        return 0.5f * (values[0] + values[1]);
    }

    float a = values[0];
    float b = values[1];
    float c = values[2];
    if (a > b) {
        float t = a;
        a = b;
        b = t;
    }
    if (b > c) {
        float t = b;
        b = c;
        c = t;
    }
    if (a > b) {
        float t = a;
        a = b;
        b = t;
    }
    return b;
}

static void ignition_reset_filter_state() {
    ign_filtered_rpm = 0.0f;
    ign_previous_accepted_rpm = 0.0f;
    ign_recent_rpm_count = 0;
    ign_recent_rpm_index = 0;
    ign_consecutive_valid_pulses = 0;
}

static void ignition_accept_rpm(float instant_rpm) {
    ign_recent_accepted_rpms[ign_recent_rpm_index] = instant_rpm;
    ign_recent_rpm_index = (uint8_t)((ign_recent_rpm_index + 1) % 3);
    if (ign_recent_rpm_count < 3) {
        ++ign_recent_rpm_count;
    }

    float median_rpm = ignition_median3(ign_recent_accepted_rpms, ign_recent_rpm_count);
    if (ign_filtered_rpm <= 0.0f) {
        ign_filtered_rpm = median_rpm;
    } else {
        ign_filtered_rpm += RPM_EMA_ALPHA * (median_rpm - ign_filtered_rpm);
    }

    ign_previous_accepted_rpm = instant_rpm;
    ign_last_accepted_us = micros();
    ++accepted_pulses;

    if (ign_consecutive_valid_pulses < 255) {
        ++ign_consecutive_valid_pulses;
    }
    if (!ign_valid && ign_consecutive_valid_pulses >= REQUIRED_VALID_PULSES) {
        ign_valid = true;
    }

    ign_rpm = ign_valid ? ign_filtered_rpm : 0.0f;
}

void IRAM_ATTR ignition_isr() {
    uint32_t now = micros();
    uint32_t last = ign_last_edge_us;
    ign_last_edge_us = now;

    if (last == 0) {
        return;
    }

    uint32_t interval = now - last;
    uint8_t next_head = (uint8_t)((ign_queue_head + 1) % IGN_QUEUE_SIZE);
    if (next_head == ign_queue_tail) {
        ign_queue_tail = (uint8_t)((ign_queue_tail + 1) % IGN_QUEUE_SIZE);
    }
    ign_interval_queue[ign_queue_head] = interval;
    ign_queue_head = next_head;
}

static void ignition_init() {
    // INPUT_PULLUP holds the pin HIGH when the ignition sensor is disconnected,
    // so a floating input can't pick up mains hum and generate phantom FALLING
    // edges (which showed up as a steady ~6000 RPM with nothing connected).
    // Assumes an idle-HIGH / active-LOW ignition signal (open-collector LM393),
    // matching the FALLING-edge trigger below. If the signal is idle-LOW /
    // active-HIGH instead, switch to INPUT_PULLDOWN and a RISING trigger.
    pinMode(PIN_IGN, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(PIN_IGN), ignition_isr, FALLING);
}

static void ignition_update() {
    while (true) {
        uint32_t period_us = 0;

        noInterrupts();
        if (ign_queue_tail != ign_queue_head) {
            period_us = ign_interval_queue[ign_queue_tail];
            ign_queue_tail = (uint8_t)((ign_queue_tail + 1) % IGN_QUEUE_SIZE);
        }
        interrupts();

        if (period_us == 0) {
            break;
        }

        float instant_rpm = 60000000.0f / ((float)period_us * (float)PULSES_PER_REV);
        ign_debug_period_us = period_us;
        ign_debug_instant_rpm = instant_rpm;

        if (period_us < MIN_INTERVAL_US) {
            ++rejected_fast_pulses;
            ign_consecutive_valid_pulses = 0;
            continue;
        }

        if (instant_rpm <= 0.0f || instant_rpm > MAX_VALID_RPM) {
            ++rejected_rpm_clamp;
            ign_consecutive_valid_pulses = 0;
            continue;
        }

        if (ign_previous_accepted_rpm > 0.0f &&
            rpm_abs_diff(instant_rpm, ign_previous_accepted_rpm) > MAX_RPM_JUMP_PER_SAMPLE) {
            ++rejected_jump;
            ign_consecutive_valid_pulses = 0;
            continue;
        }

        ignition_accept_rpm(instant_rpm);
    }

    uint32_t now_us = micros();
    if (ign_last_accepted_us == 0 || (now_us - ign_last_accepted_us) >= ENGINE_TIMEOUT_US) {
        if (ign_valid || ign_rpm > 0.0f || ign_filtered_rpm > 0.0f) {
            ++timeout_count;
        }
        ign_rpm = 0.0f;
        ign_valid = false;
        ign_last_accepted_us = 0;
        ignition_reset_filter_state();
    }
}

// ================================================================
//  Rust startup config sync compatibility protocol on Serial (UART0)
// ================================================================

static uint8_t proto_rx[FRAME_SIZE] = {};
static uint8_t proto_rx_len = 0;

static uint16_t crc16_ccitt_false(const uint8_t *data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; ++i) {
        crc ^= (uint16_t)data[i] << 8;
        for (uint8_t bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

static void wr_u16(uint8_t *packet, uint8_t offset, uint16_t value) {
    packet[offset] = (uint8_t)(value & 0xFF);
    packet[offset + 1] = (uint8_t)(value >> 8);
}

static void wr_u32(uint8_t *packet, uint8_t offset, uint32_t value) {
    packet[offset] = (uint8_t)(value & 0xFF);
    packet[offset + 1] = (uint8_t)((value >> 8) & 0xFF);
    packet[offset + 2] = (uint8_t)((value >> 16) & 0xFF);
    packet[offset + 3] = (uint8_t)((value >> 24) & 0xFF);
}

static uint16_t rd_u16(const uint8_t *packet, uint8_t offset) {
    return (uint16_t)packet[offset] | ((uint16_t)packet[offset + 1] << 8);
}

static uint32_t rd_u32(const uint8_t *packet, uint8_t offset) {
    return (uint32_t)packet[offset]
        | ((uint32_t)packet[offset + 1] << 8)
        | ((uint32_t)packet[offset + 2] << 16)
        | ((uint32_t)packet[offset + 3] << 24);
}

static void wr_f32(uint8_t *packet, uint8_t offset, float value) {
    uint8_t bytes[4];
    memcpy(bytes, &value, sizeof(bytes));
    packet[offset] = bytes[0];
    packet[offset + 1] = bytes[1];
    packet[offset + 2] = bytes[2];
    packet[offset + 3] = bytes[3];
}

static void finish_response(uint8_t *packet) {
    uint16_t crc = crc16_ccitt_false(packet, CRC_OFFSET);
    wr_u16(packet, CRC_OFFSET, crc);
    Serial.write(packet, FRAME_SIZE);
}

static void init_response(uint8_t *packet, uint8_t packet_type, uint32_t seq) {
    memset(packet, 0, FRAME_SIZE);
    wr_u16(packet, 0, PROTO_MAGIC);
    packet[2] = PROTO_VERSION;
    packet[3] = packet_type;
    wr_u32(packet, 4, seq);
}

static void encode_fixed_config(uint8_t *packet) {
    packet[8] = PIN_IGN;
    wr_f32(packet, 9, (float)PULSES_PER_REV);
    packet[13] = 1;       // falling edge
    packet[14] = PIN_ENC_A;
    wr_u16(packet, 15, ENC_PPR);
    packet[17] = 0;       // CAN RX unused on ESP32
    packet[18] = 0;       // CAN TX unused on ESP32
    wr_u32(packet, 19, 500000);
    packet[23] = PIN_U0_TX;
    packet[24] = PIN_U0_RX;
    wr_u32(packet, 25, 115200);
    wr_u16(packet, 29, (uint16_t)(1000 / TELEM_MS));
}

static void send_ack(uint32_t seq, uint8_t request_type, const char *message) {
    uint8_t packet[FRAME_SIZE];
    init_response(packet, PKT_ACK, seq);
    packet[8] = request_type;
    packet[9] = 0;
    if (message != nullptr) {
        strncpy((char *)&packet[12], message, 26);
    }
    finish_response(packet);
}

static void send_error(uint32_t seq, uint8_t request_type, uint8_t error_code, const char *message) {
    uint8_t packet[FRAME_SIZE];
    init_response(packet, PKT_ERROR, seq);
    packet[8] = request_type;
    packet[9] = error_code;
    if (message != nullptr) {
        strncpy((char *)&packet[12], message, 26);
    }
    finish_response(packet);
}

static void send_config_data(uint32_t seq) {
    uint8_t packet[FRAME_SIZE];
    init_response(packet, PKT_CONFIG_DATA, seq);
    encode_fixed_config(packet);
    finish_response(packet);
}

static void send_device_info_data(uint32_t seq) {
    uint8_t packet[FRAME_SIZE];
    init_response(packet, PKT_DEVICE_INFO_DATA, seq);
    wr_u32(packet, 8, 1);
    packet[12] = PROTO_VERSION;
    packet[13] = 0;
    packet[14] = 1;
    packet[15] = 0;
    wr_u32(packet, 16, 0x00000001);
    strncpy((char *)&packet[20], "esp32-json-daq", 16);
    finish_response(packet);
}

static void handle_protocol_packet(const uint8_t *packet) {
    uint8_t request_type = packet[3];
    uint32_t seq = rd_u32(packet, 4);

    switch (request_type) {
        case PKT_DEVICE_INFO_GET:
            send_device_info_data(seq);
            break;
        case PKT_CONFIG_GET:
            send_config_data(seq);
            break;
        case PKT_CONFIG_SET:
            send_ack(seq, request_type, "accepted");
            break;
        case PKT_CONFIG_APPLY:
            send_ack(seq, request_type, "applied");
            break;
        case PKT_PING:
            send_ack(seq, request_type, "pong");
            break;
        default:
            send_error(seq, request_type, ERR_UNSUPPORTED_COMMAND, "unsupported");
            break;
    }
}

static void poll_protocol_commands() {
    while (Serial.available() > 0) {
        uint8_t b = (uint8_t)Serial.read();

        if (proto_rx_len == 0) {
            if (b != 0x59) {
                continue;
            }
            proto_rx[proto_rx_len++] = b;
            continue;
        }
        if (proto_rx_len == 1) {
            if (b != 0x44) {
                proto_rx_len = (b == 0x59) ? 1 : 0;
                proto_rx[0] = b;
                continue;
            }
            proto_rx[proto_rx_len++] = b;
            continue;
        }

        proto_rx[proto_rx_len++] = b;
        if (proto_rx_len < FRAME_SIZE) {
            continue;
        }

        uint16_t expected_crc = crc16_ccitt_false(proto_rx, CRC_OFFSET);
        uint16_t packet_crc = rd_u16(proto_rx, CRC_OFFSET);
        if (proto_rx[2] == PROTO_VERSION && expected_crc == packet_crc) {
            handle_protocol_packet(proto_rx);
        }
        proto_rx_len = 0;
    }
}

// ================================================================
//  setup()
// ================================================================

void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println();
    Serial.println("=== dyno-test boot ===");
    Serial.println("[boot] ESP32 dyno telemetry node");
    Serial.println("[boot] CAN removed from ESP32; Pi owns CAN via USB adapter");
    Serial.println("[boot] USB debug on Serial @ 115200");
    Serial.println("[boot] Runtime telemetry on Serial @ 115200");

    encoder_init();
    Serial.printf("[encoder]  OK   A=GPIO%u  B=GPIO%u  Z=GPIO%u  PPR=%u  CPR=%u\n",
                  PIN_ENC_A, PIN_ENC_B, PIN_ENC_Z, ENC_PPR, ENC_CPR);

    ignition_init();
    Serial.printf("[ignition] OK   pin=GPIO%u  edge=FALLING  min_interval=%u us  timeout=%u us  max_rpm=%.0f  jump=%.0f  req_valid=%u  ppr=%u\n",
                  PIN_IGN,
                  (unsigned)MIN_INTERVAL_US,
                  (unsigned)ENGINE_TIMEOUT_US,
                  MAX_VALID_RPM,
                  MAX_RPM_JUMP_PER_SAMPLE,
                  REQUIRED_VALID_PULSES,
                  PULSES_PER_REV);

    bme_ok = bme_init();
    Serial.printf("[BME280]   %s  SDA=GPIO%u  SCL=GPIO%u  addr=0x%02X\n",
                  bme_ok ? "OK  " : "FAIL",
                  PIN_SDA, PIN_SCL, bme_addr);

    Serial.println("[telemetry] fields=seq,ts_us,engine_rpm,roller_rpm,encoder_count,encoder_delta,temp_c,humidity,pressure,afr,lambda,engine_valid,encoder_valid,bme_valid,can_valid");
    Serial.println("=== ready ===");
}

// ================================================================
//  loop()
// ================================================================

static uint32_t last_telem_ms     = 0;
static uint32_t last_ign_debug_ms = 0;
static uint32_t seq               = 0;

void loop() {
    poll_protocol_commands();

    uint32_t now = millis();
    if ((now - last_telem_ms) < TELEM_MS) {
        return;
    }
    last_telem_ms = now;

    ignition_update();
    encoder_tick();
    bme_read();

#if DYNO_DEBUG_LOG
    if (enc_delta != 0) {
        Serial.printf("[encoder] d=%ld c=%ld rpm=%.1f\n", (long)enc_delta, (long)enc_total, enc_rpm);
    }

    if ((now - last_ign_debug_ms) >= 1000) {
        last_ign_debug_ms = now;
        Serial.printf("[ignition] period_us=%lu instant_rpm=%.1f filtered_rpm=%.1f engine_valid=%s rejected_fast=%lu rejected_clamp=%lu rejected_jump=%lu accepted=%lu timeout=%lu\n",
                      (unsigned long)ign_debug_period_us,
                      ign_debug_instant_rpm,
                      ign_filtered_rpm,
                      ign_valid ? "true" : "false",
                      (unsigned long)rejected_fast_pulses,
                      (unsigned long)rejected_rpm_clamp,
                      (unsigned long)rejected_jump,
                      (unsigned long)accepted_pulses,
                      (unsigned long)timeout_count);
    }
#endif

    char buf[384];
    snprintf(
        buf,
        sizeof(buf),
        "{"
        "\"seq\":%lu,"
        "\"ts_us\":%lu,"
        "\"engine_rpm\":%.1f,"
        "\"roller_rpm\":%.1f,"
        "\"encoder_count\":%ld,"
        "\"encoder_delta\":%ld,"
        "\"temp_c\":%.2f,"
        "\"humidity\":%.1f,"
        "\"pressure\":%.2f,"
        "\"afr\":0.0,"
        "\"lambda\":0.0,"
        "\"engine_valid\":%s,"
        "\"encoder_valid\":%s,"
        "\"bme_valid\":%s,"
        "\"can_valid\":false"
        "}",
        (unsigned long)seq++,
        (unsigned long)micros(),
        ign_rpm,
        enc_rpm,
        (long)enc_total,
        (long)enc_delta,
        bme_temp,
        bme_hum,
        bme_pres,
        ign_valid ? "true" : "false",
        enc_valid ? "true" : "false",
        bme_valid ? "true" : "false"
    );

    Serial.println(buf);
}
