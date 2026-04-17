#pragma once
#include <stdint.h>

// ============================================================
//  DynoConfig — runtime configuration struct.
//  Packed so it can be serialised over UART byte-for-byte.
//  NVS key: "dyno" / "cfg_v1"  — bump key on layout change.
// ============================================================

enum class EdgeMode : uint8_t {
    EDGE_RISING = 0,
    EDGE_FALLING = 1,
    EDGE_BOTH   = 2
};

struct DynoConfig {
    // --- Engine pulse (MCPWM capture) ---
    uint8_t  engine_pulse_pin;        // GPIO (input-capable: 0-39 except 6-11)
    uint8_t  engine_pulses_per_rev;   // trigger-wheel teeth (1-120)
    EdgeMode engine_edge_mode;        // which edge(s) to capture

    // --- Roller encoder (PCNT quadrature) ---
    uint8_t  encoder_pin_a;           // PCNT edge input
    uint8_t  encoder_pin_b;           // PCNT level / direction input
    uint16_t encoder_ppr;             // pulses per revolution, single-edge (1-10000)

    // --- CAN / TWAI ---
    uint8_t  can_rx_pin;              // input-capable GPIO
    uint8_t  can_tx_pin;              // output-capable GPIO (not 34-39)
    uint32_t can_bitrate;             // bits/s — must be in TWAI supported set
    uint32_t can_afr_frame_id;        // 11-bit or 29-bit CAN ID — live adjustable
    uint8_t  can_afr_byte_offset;     // byte index [0-7] within payload — live adjustable

    // --- UART to Raspberry Pi (Serial2) ---
    uint8_t  uart_tx_pin;             // output-capable GPIO
    uint8_t  uart_rx_pin;             // input-capable GPIO
    uint32_t uart_baud;               // 9600-3000000

    // --- Telemetry ---
    uint8_t  telemetry_rate_hz;       // 1-200 Hz — live adjustable

    uint8_t  _pad[2];                 // keep struct size a multiple of 4
} __attribute__((packed));

// ---- Default hardware wiring ----
static constexpr DynoConfig DEFAULT_CONFIG = {
    .engine_pulse_pin      = 34,
    .engine_pulses_per_rev = 1,
    .engine_edge_mode      = EdgeMode::RISING,

    .encoder_pin_a         = 32,
    .encoder_pin_b         = 33,
    .encoder_ppr           = 600,

    .can_rx_pin            = 4,
    .can_tx_pin            = 5,
    .can_bitrate           = 500000,
    .can_afr_frame_id      = 0x0AF,
    .can_afr_byte_offset   = 0,

    .uart_tx_pin           = 17,
    .uart_rx_pin           = 16,
    .uart_baud             = 921600,

    .telemetry_rate_hz     = 100,
    ._pad                  = {0, 0},
};

// ============================================================
//  Reinit flags — set by ConfigStore::commit().
//  Consumed (cleared) by main.cpp after restarting each module.
// ============================================================
#define REINIT_ENGINE   (1u << 0)
#define REINIT_ENCODER  (1u << 1)
#define REINIT_CAN      (1u << 2)
#define REINIT_UART     (1u << 3)   // UART reinit last; ACK sent on old baud first

extern volatile uint32_t g_reinit_flags;

// ============================================================
//  ConfigStore
//
//  Two-phase write model:
//
//   1. stage(cfg)   — validate externally, then store as pending.
//                     Does not touch live config or peripherals.
//   2. commit()     — copy staged → live, compute REINIT_* bits,
//                     set g_reinit_flags, optionally persist to NVS.
//                     Returns the reinit_mask that was applied.
//
//  Single-phase helpers still available:
//   reset_to_defaults()  — immediately replaces live config, sets all reinit flags.
//   save()               — writes current live config to NVS.
// ============================================================
namespace ConfigStore {
    // Boot: load from NVS or fall back to defaults.
    void init();

    // ---- Live config access ----
    const DynoConfig& get();

    // ---- Staging ----
    // Store a pre-validated candidate config.
    // Caller is responsible for running ConfigValidator::validate() first.
    void stage(const DynoConfig& cfg);
    bool has_staged();
    const DynoConfig& get_staged();  // only valid when has_staged() == true
    void discard_staged();

    // Apply staged config to live.
    // Computes REINIT_* diff, sets g_reinit_flags, optionally saves to NVS.
    // Returns the reinit_mask.  Returns 0xFF if no staged config exists.
    uint8_t commit(bool save_to_nvs = false);

    // ---- NVS persistence ----
    bool save();

    // ---- Hard reset ----
    // Replaces live config with defaults and sets all REINIT_* flags.
    void reset_to_defaults();

    // ---- Config version counter ----
    // 1-byte monotonic counter, incremented by commit() and reset_to_defaults().
    // Wraps 0xFF → 0.  Reported in CONFIG_ACK so Rust can detect missed commits.
    uint8_t get_config_version();
}
