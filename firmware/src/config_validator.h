#pragma once
#include "config_store.h"
#include <stdint.h>

// ============================================================
//  Config validation — field-level error reporting and
//  peripheral reinit cost estimation.
//
//  Validation is pure (no hardware interaction, no globals).
//  It runs on a proposed DynoConfig before staging or applying.
//
//  ESP32 GPIO constraints enforced here:
//    Rejected for all uses:   6–11  (internal flash/SPI)
//    Input-only (no output):  34–39 (can be RX/sense but not TX/drive)
//    Valid range:             0–39
//    Strapping pins (warned in docs but not rejected):  0, 2, 5, 12, 15
//
//  Reinit cost:
//    REINIT_ENGINE  — MCPWM capture channel must be torn down and rebuilt
//    REINIT_ENCODER — PCNT unit must be torn down and rebuilt
//    REINIT_CAN     — TWAI driver must be uninstalled and reinstalled
//    REINIT_UART    — Serial2 must be ended/begun (see UART safety note below)
//    (no bit)       — telemetry_rate_hz and CAN frame ID/offset are live-adjustable
//
//  UART safety:
//    When REINIT_UART is set in the commit reinit_mask, the ACK for
//    CONFIG_APPLY is sent on the OLD baud rate before Serial2 is
//    reinitialized.  The Rust backend must listen for the ACK on the
//    old baud rate and only then switch to the new baud rate.
// ============================================================

// ---- Detailed error codes ----------------------------------------
enum class ConfigError : uint8_t {
    NONE              = 0x00,

    // Frame-level
    BAD_FRAME_LEN     = 0x01,   // payload length != sizeof(DynoConfig)
    BAD_CRC           = 0x02,   // CRC16 mismatch on incoming frame
    BAD_VERSION       = 0x03,   // protocol version unsupported

    // GPIO errors — field_id identifies the offending pin field
    INVALID_GPIO      = 0x10,   // GPIO number > 39
    FLASH_GPIO        = 0x11,   // GPIO 6-11 reserved for internal flash/SPI
    INPUT_ONLY_AS_OUT = 0x12,   // GPIO 34-39 used where output drive is required
    GPIO_CONFLICT     = 0x13,   // same GPIO number assigned to two subsystems

    // Value range errors
    INVALID_PPR_ENG   = 0x20,   // engine_pulses_per_rev == 0 or > 120
    INVALID_PPR_ENC   = 0x21,   // encoder_ppr == 0 or > 10000
    INVALID_CAN_BRATE = 0x22,   // CAN bitrate not in supported TWAI set
    INVALID_UART_BAUD = 0x23,   // uart_baud < 9600 or > 3 000 000
    INVALID_TELEM_HZ  = 0x24,   // telemetry_rate_hz == 0 or > 200
    INVALID_EDGE_MODE = 0x25,   // engine_edge_mode > BOTH(2)

    // Apply-time errors
    NO_STAGED_CONFIG  = 0x30,   // CONFIG_APPLY received with no staged config pending
    REINIT_FAILED_ENG = 0x31,   // engine peripheral reinit failed post-commit
    REINIT_FAILED_ENC = 0x32,   // encoder peripheral reinit failed post-commit
    REINIT_FAILED_CAN = 0x33,   // CAN peripheral reinit failed post-commit
    NVS_SAVE_FAILED   = 0x34,   // NVS write failure (config was applied in RAM)
};

// ---- Which config field triggered the error ---------------------
enum class ConfigField : uint8_t {
    NONE              = 0,
    ENGINE_PULSE_PIN  = 1,
    ENGINE_PPR        = 2,
    ENGINE_EDGE_MODE  = 3,
    ENCODER_PIN_A     = 4,
    ENCODER_PIN_B     = 5,
    ENCODER_PPR       = 6,
    CAN_RX_PIN        = 7,
    CAN_TX_PIN        = 8,
    CAN_BITRATE       = 9,
    UART_TX_PIN       = 10,
    UART_RX_PIN       = 11,
    UART_BAUD         = 12,
    TELEM_RATE_HZ     = 13,
};

// ---- Result returned by validate() ------------------------------
struct ValidationResult {
    ConfigError error;        // NONE when fully valid
    ConfigField field;        // field that failed (NONE for frame-level errors)
    uint8_t     reinit_mask;  // REINIT_* bits this change would trigger
                              // (set even on success, for preview before staging)
};

namespace ConfigValidator {
    // Validate proposed against current live config.
    //   On success  → result.error == ConfigError::NONE
    //                 result.reinit_mask populated with REINIT_* bits
    //   On failure  → result.error != NONE, result.field set if applicable
    ValidationResult validate(const DynoConfig& proposed,
                              const DynoConfig& current);

    // Compute the reinit bitmask for a current → proposed transition.
    // Assumes both configs are individually valid.
    uint8_t compute_reinit_mask(const DynoConfig& current,
                                const DynoConfig& proposed);
}
