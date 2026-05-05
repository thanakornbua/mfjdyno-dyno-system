#include "can_afr.h"
#include "config_store.h"
#include <Arduino.h>
#include "driver/twai.h"

// ============================================================
//  ╔══════════════════════════════════════════════════════════╗
//  ║           USER-CONFIGURABLE: AFR SIGNAL MAPPING         ║
//  ║                                                          ║
//  ║  Edit the constants in this section to match your        ║
//  ║  wideband controller's CAN protocol.                     ║
//  ║                                                          ║
//  ║  The arbitration ID and byte offset can also be set      ║
//  ║  at runtime via DynoConfig (can_afr_frame_id,            ║
//  ║  can_afr_byte_offset) — those override the defaults      ║
//  ║  below without a firmware flash.  The endianness,        ║
//  ║  word width, scale, and stoich must be changed here.     ║
//  ╚══════════════════════════════════════════════════════════╝
//
//  ── Frame selection ─────────────────────────────────────────
//  Default ID comes from DynoConfig::can_afr_frame_id (0x0AF).
//  Change it via CONFIG_SET/CONFIG_APPLY from the Rust backend,
//  or by editing DEFAULT_CONFIG in config_store.h.
//
//  TODO: set AFR_EXTENDED_ID = true if your controller uses
//        29-bit (extended) arbitration IDs.
static constexpr bool AFR_EXTENDED_ID = true;    // AEM UEGO uses 29-bit extended ID

//  ── Payload word width and byte order ───────────────────────
//  false = extract a single byte (uint8_t) at can_afr_byte_offset.
//  true  = extract two bytes (uint16_t) starting at can_afr_byte_offset.
//
//  TODO: set AFR_TWO_BYTE = true if your controller encodes AFR
//        as a 16-bit value (e.g. Haltech IC-7 lambda × 1000 word).
static constexpr bool AFR_TWO_BYTE  = true;   // AEM UEGO: 16-bit lambda word

//  TODO: set AFR_BIG_ENDIAN = true if the 16-bit word is sent
//        big-endian (Motorola byte order).  Ignored when AFR_TWO_BYTE = false.
static constexpr bool AFR_BIG_ENDIAN = true;     // AEM UEGO: Motorola byte order

//  ── Linear scaling ──────────────────────────────────────────
//  Formula (integer, no float in hot path):
//      afr_x100 = raw × AFR_SCALE_NUM × 100 / AFR_SCALE_DEN + AFR_OFFSET_x100
//
//  Default mapping (Innovate Motorsports LC-2 / many generic WBO2):
//      raw byte 0x00 →  0.00 AFR  (sensor offline / warming up)
//      raw byte 0x7B → 12.30 AFR
//      raw byte 0x93 → 14.70 AFR  (stoichiometric petrol)
//      raw byte 0xFF → 25.50 AFR
//
//  TODO: adjust SCALE_NUM/SCALE_DEN for your controller.
//        Examples:
//          PLX SM-AFR: raw × 0.0489 + 7.35  → SCALE_NUM=489, SCALE_DEN=10000, OFFSET_x100=735
//          AEM 30-0300: raw × 0.1           → default below
//          Haltech (16-bit lambda × 1000):   AFR_TWO_BYTE=true, SCALE_NUM=147, SCALE_DEN=100000, OFFSET_x100=0
// AEM UEGO: lambda = raw * 0.0001 → afr = lambda * 14.65
// afr_x100 = raw * 0.1465 = raw * 1465 * 100 / 1000000
static constexpr uint32_t AFR_SCALE_NUM   = 1465u;
static constexpr uint32_t AFR_SCALE_DEN   = 1000000u;
static constexpr int32_t  AFR_OFFSET_x100 = 0;

//  ── Stoichiometric AFR for lambda calculation ────────────────
//  lambda = AFR / stoich_AFR
//  lambda_x1000 = afr_x100 × 1000 / STOICH_x100
//
//  TODO: match your fuel type:
//    Petrol (gasoline):  STOICH_x100 = 1470
//    E10 (90/10):        STOICH_x100 = 1421  (approx)
//    E85:                STOICH_x100 =  906
//    Diesel:             STOICH_x100 = 1460
//    Methanol:           STOICH_x100 =  658
static constexpr uint16_t STOICH_x100 = 1465u;      // AEM UEGO stoich (14.65)

//  ── Freshness timeout ────────────────────────────────────────
//  Declare data stale after this many milliseconds with no matching frame.
//  TODO: reduce to 500 ms for high-rate controllers (> 20 Hz CAN output).
static constexpr uint32_t AFR_TIMEOUT_MS = 2000u;

//  ── Maximum frames drained per poll() call ───────────────────
//  Keeps poll() bounded.  At 100 Hz CAN and 100 Hz telemetry rate
//  the queue should rarely hold more than 1 frame; 16 is a generous
//  budget to clear any transient burst.
static constexpr uint8_t RX_DRAIN_MAX = 16u;

// ============================================================
//  ── END USER-CONFIGURABLE SECTION ──────────────────────────
// ============================================================

// ============================================================
//  Module state
// ============================================================

static bool     s_driver_installed = false;
static uint16_t s_afr_x100        = 0;
static uint16_t s_lambda_x1000    = 0;
static uint8_t  s_voltage_x10     = 0;       // byte4 * 0.1 V, scaled ×10
static bool     s_ever_received   = false;   // guards against millis()==0 at boot
static uint32_t s_last_rx_ms      = 0;

// ============================================================
//  Internal helpers — transport and decoder
// ============================================================

// ---- Bitrate → TWAI timing config ----
static bool build_timing_config(uint32_t bitrate, twai_timing_config_t* out) {
    switch (bitrate) {
        case   25000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_25KBITS();  *out = t; return true; }
        case   50000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_50KBITS();  *out = t; return true; }
        case  100000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_100KBITS(); *out = t; return true; }
        case  125000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_125KBITS(); *out = t; return true; }
        case  250000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_250KBITS(); *out = t; return true; }
        case  500000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS(); *out = t; return true; }
        case  800000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_800KBITS(); *out = t; return true; }
        case 1000000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_1MBITS();   *out = t; return true; }
        default:
            Serial.printf("[can] unsupported bitrate %u — falling back to 500k\n", bitrate);
            { twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS(); *out = t; }
            return false;
    }
}

// ---- Endian-aware raw word extraction ----
// Returns uint16_t (single byte returned as uint16_t for uniform downstream math).
static uint16_t extract_raw(const twai_message_t& msg, uint8_t byte_offset) {
    if (!AFR_TWO_BYTE) {
        return (uint16_t)msg.data[byte_offset];
    }
    // Two-byte word: check bounds (DLC must cover both bytes)
    uint8_t lo = msg.data[byte_offset];
    uint8_t hi = msg.data[byte_offset + 1u];
    if (AFR_BIG_ENDIAN) {
        // Motorola / big-endian: first byte is MSB
        return (uint16_t)((uint16_t)lo << 8) | (uint16_t)hi;
    }
    // Intel / little-endian: first byte is LSB
    return (uint16_t)((uint16_t)hi << 8) | (uint16_t)lo;
}

// ---- Integer AFR scaling ----
// afr_x100 = raw × SCALE_NUM × 100 / SCALE_DEN + OFFSET_x100
// Clamped to [0, 65535].
static uint16_t raw_to_afr_x100(uint16_t raw) {
    // Promote to int64 to handle controllers with large SCALE_NUM values
    // (e.g. PLX: 489 × 0xFF × 100 / 10000 ≈ fine in int32, but 16-bit raw × large num may overflow)
    int64_t val = (int64_t)raw * (int64_t)AFR_SCALE_NUM * 100LL
                / (int64_t)AFR_SCALE_DEN
                + (int64_t)AFR_OFFSET_x100;
    if (val <= 0)      return 0u;
    if (val > 65535LL) return 65535u;
    return (uint16_t)val;
}

// ---- Lambda from AFR × 100 ----
// lambda_x1000 = afr_x100 × 1000 / STOICH_x100
// Returns 0 for zero or invalid inputs.
static uint16_t afr_x100_to_lambda_x1000(uint16_t afr_x100) {
    if (afr_x100 == 0u || STOICH_x100 == 0u) return 0u;
    uint32_t lam = (uint32_t)afr_x100 * 1000u / (uint32_t)STOICH_x100;
    return (lam > 65535u) ? 65535u : (uint16_t)lam;
}

// ---- Process one received CAN frame ----
// Filters on the live-configurable frame ID, extracts and decodes the signal.
// live_frame_id / live_byte_offset are read from ConfigStore::get() in poll()
// and passed in so this function has no dependency on config directly.
static void process_frame(const twai_message_t& msg,
                          uint32_t live_frame_id,
                          uint8_t  live_byte_offset) {
    // ---- Frame ID filter ----
    // Reject if extended-ID flag mismatches the expected frame type
    if ((bool)msg.extd != AFR_EXTENDED_ID) return;
    if (msg.identifier != live_frame_id)   return;
    // Remote frames carry no payload
    if (msg.rtr)                           return;

    // ---- Payload bounds check ----
    uint8_t required_bytes = AFR_TWO_BYTE ? (live_byte_offset + 2u)
                                          : (live_byte_offset + 1u);
    if (msg.data_length_code < required_bytes) return;

    // ---- Decode lambda / AFR ----
    uint16_t raw        = extract_raw(msg, live_byte_offset);
    uint16_t afr_x100   = raw_to_afr_x100(raw);
    uint16_t lam_x1000  = afr_x100_to_lambda_x1000(afr_x100);

    // ---- Decode wideband sensor voltage (byte4 * 0.1 V) ----
    uint8_t voltage_x10 = (msg.data_length_code >= 5u) ? msg.data[4] : 0u;

    // ---- Commit snapshot ----
    s_afr_x100      = afr_x100;
    s_lambda_x1000  = lam_x1000;
    s_voltage_x10   = voltage_x10;
    s_last_rx_ms    = millis();
    s_ever_received = true;
}

// ============================================================
//  CanAfr namespace
// ============================================================
namespace CanAfr {

// ---- init() ----
void init(const DynoConfig& cfg) {
    s_afr_x100      = 0;
    s_lambda_x1000  = 0;
    s_voltage_x10   = 0;
    s_ever_received = false;
    s_last_rx_ms    = 0;

    twai_general_config_t g_cfg = TWAI_GENERAL_CONFIG_DEFAULT(
        (gpio_num_t)cfg.can_tx_pin,
        (gpio_num_t)cfg.can_rx_pin,
        TWAI_MODE_NORMAL
    );
    g_cfg.rx_queue_len = 16;   // buffer up to 16 frames between poll() calls

    twai_timing_config_t t_cfg;
    build_timing_config(cfg.can_bitrate, &t_cfg);

    // Accept all frames in hardware; software filter in process_frame().
    // This allows the AFR frame ID to be changed at runtime via DynoConfig
    // without reinstalling the driver.
    twai_filter_config_t f_cfg = TWAI_FILTER_CONFIG_ACCEPT_ALL();

    esp_err_t err = twai_driver_install(&g_cfg, &t_cfg, &f_cfg);
    if (err != ESP_OK) {
        Serial.printf("[can] driver install failed: %s\n", esp_err_to_name(err));
        return;
    }

    err = twai_start();
    if (err != ESP_OK) {
        Serial.printf("[can] start failed: %s\n", esp_err_to_name(err));
        twai_driver_uninstall();
        return;
    }

    s_driver_installed = true;
    Serial.printf("[can] init ok — tx=%u rx=%u %ubps id=0x%03X byte=%u %s\n",
                  cfg.can_tx_pin, cfg.can_rx_pin, cfg.can_bitrate,
                  cfg.can_afr_frame_id, cfg.can_afr_byte_offset,
                  AFR_EXTENDED_ID ? "ext" : "std");
}

// ---- deinit() ----
void deinit() {
    if (!s_driver_installed) return;
    twai_stop();
    twai_driver_uninstall();
    s_driver_installed = false;
    Serial.println("[can] deinit");
}

// ---- reconfigure() ----
bool reconfigure(const DynoConfig& cfg) {
    deinit();
    init(cfg);
    return s_driver_installed;
}

// ---- is_init() ----
bool is_init() {
    return s_driver_installed;
}

// ---- poll() ----
// Call from loop() each iteration — non-blocking.
//
// Flow:
//   1. Check hardware state — handle bus-off and recovery transitions.
//   2. Drain the TWAI RX queue (up to RX_DRAIN_MAX frames).
//   3. For each frame, call process_frame() to filter and decode.
//
// Bus-off recovery state machine:
//   BUS_OFF   → twai_initiate_recovery() → state becomes RECOVERING
//   RECOVERING → wait (nothing to do, driver handles the sequence)
//   STOPPED   → twai_start() to resume after recovery completes
//   RUNNING   → normal operation
void poll() {
    if (!s_driver_installed) return;

    // ---- 1. Bus state check ----
    twai_status_info_t hw;
    if (twai_get_status_info(&hw) == ESP_OK) {
        switch (hw.state) {
            case TWAI_STATE_BUS_OFF:
                // Initiate the 128-occurrence bus-free sequence.
                // After completion TWAI transitions to STOPPED automatically.
                Serial.println("[can] bus-off — initiating recovery");
                twai_initiate_recovery();
                return;

            case TWAI_STATE_RECOVERING:
                // Recovery in progress; don't receive or transmit.
                return;

            case TWAI_STATE_STOPPED:
                // Recovery completed; restart the driver.
                if (twai_start() == ESP_OK) {
                    Serial.println("[can] recovery complete — restarted");
                } else {
                    Serial.println("[can] restart after recovery failed");
                }
                return;

            default:
                break;  // TWAI_STATE_RUNNING — proceed normally
        }
    }

    // ---- 2. Read live-adjustable config fields ----
    // These fields do not require REINIT_CAN — they are consumed here directly
    // so any change staged and committed via CONFIG_APPLY takes effect within
    // one loop() iteration without a peripheral restart.
    const DynoConfig& cfg       = ConfigStore::get();
    const uint32_t frame_id     = cfg.can_afr_frame_id;
    const uint8_t  byte_offset  = cfg.can_afr_byte_offset;

    // ---- 3. Drain RX queue ----
    for (uint8_t i = 0; i < RX_DRAIN_MAX; i++) {
        twai_message_t msg;
        esp_err_t err = twai_receive(&msg, /*ticks_to_wait=*/0);
        if (err == ESP_ERR_TIMEOUT) break;   // queue empty — done
        if (err != ESP_OK)          break;   // unexpected error — stop draining
        process_frame(msg, frame_id, byte_offset);
    }
}

// ============================================================
//  Snapshot accessors — pure reads, no side effects
// ============================================================

uint16_t get_afr_x100() {
    if (!s_ever_received) return 0u;
    if ((millis() - s_last_rx_ms) >= AFR_TIMEOUT_MS) return 0u;
    return s_afr_x100;
}

uint16_t get_lambda_x1000() {
    if (!s_ever_received) return 0u;
    if ((millis() - s_last_rx_ms) >= AFR_TIMEOUT_MS) return 0u;
    return s_lambda_x1000;
}

float get_afr() {
    return (float)get_afr_x100() / 100.0f;
}

float get_voltage() {
    if (!s_ever_received) return 0.0f;
    if ((millis() - s_last_rx_ms) >= AFR_TIMEOUT_MS) return 0.0f;
    return (float)s_voltage_x10 / 10.0f;
}

bool is_valid() {
    return s_driver_installed &&
           s_ever_received &&
           (millis() - s_last_rx_ms) < AFR_TIMEOUT_MS;
}

CanStatus get_status() {
    if (!s_driver_installed) return CanStatus::NOT_INIT;

    twai_status_info_t hw;
    if (twai_get_status_info(&hw) == ESP_OK) {
        if (hw.state == TWAI_STATE_BUS_OFF ||
            hw.state == TWAI_STATE_RECOVERING) {
            return CanStatus::BUS_OFF;
        }
    }

    if (!s_ever_received)                               return CanStatus::NO_DATA;
    if ((millis() - s_last_rx_ms) >= AFR_TIMEOUT_MS)   return CanStatus::STALE;
    return CanStatus::OK;
}

}  // namespace CanAfr
