#include "config_validator.h"
#include <string.h>

// ============================================================
//  GPIO constraint tables
// ============================================================

static constexpr uint8_t GPIO_MAX     = 39u;
// Flash/SPI bus — ESP32 uses these for the internal QSPI flash.
// Assigning them to any peripheral will hang or corrupt flash access.
static constexpr uint8_t FLASH_PIN_LO = 6u;
static constexpr uint8_t FLASH_PIN_HI = 11u;
// Input-only pads — GPIO 34-39 have no output driver.
static constexpr uint8_t INONLY_LO    = 34u;
static constexpr uint8_t INONLY_HI    = 39u;

static inline bool is_valid_gpio(uint8_t p) {
    return p <= GPIO_MAX;
}
static inline bool is_flash_gpio(uint8_t p) {
    return p >= FLASH_PIN_LO && p <= FLASH_PIN_HI;
}
static inline bool is_input_only(uint8_t p) {
    return p >= INONLY_LO && p <= INONLY_HI;
}

// ---- CAN bitrate whitelist (TWAI_TIMING_CONFIG_* supported values) ----
static constexpr uint32_t VALID_CAN_BITRATES[] = {
    25000u, 50000u, 100000u, 125000u,
    250000u, 500000u, 800000u, 1000000u,
};
static bool is_valid_can_bitrate(uint32_t br) {
    for (uint32_t v : VALID_CAN_BITRATES) {
        if (v == br) return true;
    }
    return false;
}

// ---- Validate a single input-capable GPIO ----------------------
// Used for: engine_pulse_pin, encoder_pin_a/b, can_rx_pin, uart_rx_pin
static ConfigError check_input_gpio(uint8_t pin) {
    if (!is_valid_gpio(pin)) return ConfigError::INVALID_GPIO;
    if (is_flash_gpio(pin))  return ConfigError::FLASH_GPIO;
    return ConfigError::NONE;
}

// ---- Validate an output-capable GPIO ---------------------------
// Used for: can_tx_pin, uart_tx_pin
static ConfigError check_output_gpio(uint8_t pin) {
    if (!is_valid_gpio(pin)) return ConfigError::INVALID_GPIO;
    if (is_flash_gpio(pin))  return ConfigError::FLASH_GPIO;
    if (is_input_only(pin))  return ConfigError::INPUT_ONLY_AS_OUT;
    return ConfigError::NONE;
}

// ---- Field-level validation ------------------------------------
// Returns the first field that fails, or {NONE,NONE} on success.
static ValidationResult validate_fields(const DynoConfig& c) {
    // Shorthand: return error result for a field
#define FAIL(err, fld) return { ConfigError::err, ConfigField::fld, 0 }

    // Engine pulse
    {
        ConfigError e = check_input_gpio(c.engine_pulse_pin);
        if (e == ConfigError::FLASH_GPIO)  FAIL(FLASH_GPIO,       ENGINE_PULSE_PIN);
        if (e != ConfigError::NONE)        FAIL(INVALID_GPIO,     ENGINE_PULSE_PIN);
    }
    if (c.engine_pulses_per_rev < 1 || c.engine_pulses_per_rev > 120)
        FAIL(INVALID_PPR_ENG, ENGINE_PPR);
    if (static_cast<uint8_t>(c.engine_edge_mode) > static_cast<uint8_t>(EdgeMode::BOTH))
        FAIL(INVALID_EDGE_MODE, ENGINE_EDGE_MODE);

    // Encoder
    {
        ConfigError e = check_input_gpio(c.encoder_pin_a);
        if (e == ConfigError::FLASH_GPIO)  FAIL(FLASH_GPIO,   ENCODER_PIN_A);
        if (e != ConfigError::NONE)        FAIL(INVALID_GPIO, ENCODER_PIN_A);
    }
    {
        ConfigError e = check_input_gpio(c.encoder_pin_b);
        if (e == ConfigError::FLASH_GPIO)  FAIL(FLASH_GPIO,   ENCODER_PIN_B);
        if (e != ConfigError::NONE)        FAIL(INVALID_GPIO, ENCODER_PIN_B);
    }
    if (c.encoder_ppr < 1 || c.encoder_ppr > 10000)
        FAIL(INVALID_PPR_ENC, ENCODER_PPR);

    // CAN / TWAI
    {
        ConfigError e = check_input_gpio(c.can_rx_pin);
        if (e == ConfigError::FLASH_GPIO)  FAIL(FLASH_GPIO,   CAN_RX_PIN);
        if (e != ConfigError::NONE)        FAIL(INVALID_GPIO, CAN_RX_PIN);
    }
    {
        ConfigError e = check_output_gpio(c.can_tx_pin);
        if (e == ConfigError::FLASH_GPIO)    FAIL(FLASH_GPIO,        CAN_TX_PIN);
        if (e == ConfigError::INPUT_ONLY_AS_OUT) FAIL(INPUT_ONLY_AS_OUT, CAN_TX_PIN);
        if (e != ConfigError::NONE)          FAIL(INVALID_GPIO,      CAN_TX_PIN);
    }
    if (!is_valid_can_bitrate(c.can_bitrate))
        FAIL(INVALID_CAN_BRATE, CAN_BITRATE);

    // UART
    {
        ConfigError e = check_output_gpio(c.uart_tx_pin);
        if (e == ConfigError::FLASH_GPIO)    FAIL(FLASH_GPIO,        UART_TX_PIN);
        if (e == ConfigError::INPUT_ONLY_AS_OUT) FAIL(INPUT_ONLY_AS_OUT, UART_TX_PIN);
        if (e != ConfigError::NONE)          FAIL(INVALID_GPIO,      UART_TX_PIN);
    }
    {
        ConfigError e = check_input_gpio(c.uart_rx_pin);
        if (e == ConfigError::FLASH_GPIO)  FAIL(FLASH_GPIO,   UART_RX_PIN);
        if (e != ConfigError::NONE)        FAIL(INVALID_GPIO, UART_RX_PIN);
    }
    if (c.uart_baud < 9600u || c.uart_baud > 3000000u)
        FAIL(INVALID_UART_BAUD, UART_BAUD);

    // Telemetry rate
    if (c.telemetry_rate_hz < 1 || c.telemetry_rate_hz > 200)
        FAIL(INVALID_TELEM_HZ, TELEM_RATE_HZ);

#undef FAIL
    return { ConfigError::NONE, ConfigField::NONE, 0 };
}

// ---- GPIO conflict detection -----------------------------------
// Returns the second field in the first conflicting pair found.
static ValidationResult check_gpio_conflicts(const DynoConfig& c) {
    struct PinEntry { uint8_t pin; ConfigField field; };
    const PinEntry pins[] = {
        { c.engine_pulse_pin, ConfigField::ENGINE_PULSE_PIN },
        { c.encoder_pin_a,    ConfigField::ENCODER_PIN_A    },
        { c.encoder_pin_b,    ConfigField::ENCODER_PIN_B    },
        { c.can_rx_pin,       ConfigField::CAN_RX_PIN       },
        { c.can_tx_pin,       ConfigField::CAN_TX_PIN       },
        { c.uart_tx_pin,      ConfigField::UART_TX_PIN      },
        { c.uart_rx_pin,      ConfigField::UART_RX_PIN      },
    };
    constexpr int N = static_cast<int>(sizeof(pins) / sizeof(pins[0]));
    for (int i = 0; i < N; i++) {
        for (int j = i + 1; j < N; j++) {
            if (pins[i].pin == pins[j].pin) {
                // Report the second pin as the conflicting one
                return { ConfigError::GPIO_CONFLICT, pins[j].field, 0 };
            }
        }
    }
    return { ConfigError::NONE, ConfigField::NONE, 0 };
}

// ============================================================
//  Public API
// ============================================================

uint8_t ConfigValidator::compute_reinit_mask(const DynoConfig& cur,
                                             const DynoConfig& next) {
    uint8_t mask = 0;

    // Engine: pin, edge mode, or pulses_per_rev changed
    if (cur.engine_pulse_pin      != next.engine_pulse_pin      ||
        cur.engine_edge_mode      != next.engine_edge_mode      ||
        cur.engine_pulses_per_rev != next.engine_pulses_per_rev) {
        mask |= REINIT_ENGINE;
    }

    // Encoder: either quadrature pin changed
    // encoder_ppr is a pure calculation constant — no peripheral restart needed,
    // but we reinit for simplicity so the new ppr is reflected immediately.
    if (cur.encoder_pin_a != next.encoder_pin_a ||
        cur.encoder_pin_b != next.encoder_pin_b ||
        cur.encoder_ppr   != next.encoder_ppr) {
        mask |= REINIT_ENCODER;
    }

    // CAN / TWAI: any hardware-level parameter changed
    // can_afr_frame_id and can_afr_byte_offset are soft-config — polled live
    if (cur.can_rx_pin  != next.can_rx_pin  ||
        cur.can_tx_pin  != next.can_tx_pin  ||
        cur.can_bitrate != next.can_bitrate) {
        mask |= REINIT_CAN;
    }

    // UART: Serial2 must be ended/begun on pin or baud change
    if (cur.uart_baud   != next.uart_baud   ||
        cur.uart_tx_pin != next.uart_tx_pin ||
        cur.uart_rx_pin != next.uart_rx_pin) {
        mask |= REINIT_UART;
    }

    // telemetry_rate_hz: consumed from ConfigStore::get() each loop tick — no restart
    // can_afr_frame_id / byte_offset: read live in CanAfr::poll() — no restart

    return mask;
}

ValidationResult ConfigValidator::validate(const DynoConfig& proposed,
                                           const DynoConfig& current) {
    // 1. Field range checks
    ValidationResult r = validate_fields(proposed);
    if (r.error != ConfigError::NONE) return r;

    // 2. Cross-field GPIO conflict check
    r = check_gpio_conflicts(proposed);
    if (r.error != ConfigError::NONE) return r;

    // 3. Compute reinit cost for the caller
    r.reinit_mask = compute_reinit_mask(current, proposed);
    return r;   // r.error == NONE here
}
