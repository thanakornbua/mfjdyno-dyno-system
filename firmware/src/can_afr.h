#pragma once
#include "config_store.h"
#include <stdint.h>

// ============================================================
//  CanStatus — bus and data health, carried in TelemetryFrame
// ============================================================
enum class CanStatus : uint8_t {
    OK       = 0x00,   // driver running, AFR frame received within timeout
    NO_DATA  = 0x01,   // driver running, no matching frame received yet
    STALE    = 0x02,   // last matching frame was > AFR_TIMEOUT_MS ago
    BUS_OFF  = 0x03,   // TWAI entered bus-off error state (auto-recovery in progress)
    NOT_INIT = 0xFF,   // TWAI driver not installed
};

// ============================================================
//  CanAfr — wideband AFR via ESP32 TWAI (CAN 2.0B)
//
//  Transport layer (this module):
//    Receives raw CAN frames non-blockingly.
//    Drains the full RX queue each poll() call.
//    Auto-recovers from TWAI_STATE_BUS_OFF.
//
//  Decoder (user-configurable in can_afr.cpp):
//    Filters on a configurable arbitration ID (11-bit or 29-bit).
//    Extracts a uint8_t or uint16_t word at a configurable byte offset.
//    Applies integer scale/offset to produce AFR × 100.
//    Derives lambda × 1000 from a configurable stoichiometric AFR.
//
//  Live-adjustable fields (no REINIT_CAN required):
//    can_afr_frame_id, can_afr_byte_offset — re-read on every poll() call
//    from ConfigStore::get(), so changes take effect within one loop tick.
//
//  Fields requiring REINIT_CAN (deinit → init):
//    can_rx_pin, can_tx_pin, can_bitrate
//
//  API contract:
//    Call poll() once per loop() iteration (not from ISR).
//    All getter functions are pure reads of the last decoded snapshot.
// ============================================================

namespace CanAfr {

    // ---- Lifecycle ----
    void init(const DynoConfig& cfg);
    void deinit();

    // Hot reconfigure: deinit() + init() with new hardware config.
    // Resets decoded state; stale timeout asserts immediately.
    // Returns true if the new configuration initialised successfully.
    bool reconfigure(const DynoConfig& cfg);

    bool is_init();

    // ---- Main receive function ----
    // Call from loop() — non-blocking.
    // Drains the TWAI RX queue (up to RX_DRAIN_MAX frames).
    // Handles bus-off detection and recovery signalling.
    // Re-reads live-adjustable config fields (frame ID, byte offset).
    void poll();

    // ---- Snapshot access ----
    // All pure reads; valid after at least one matching frame has arrived.

    // AFR × 100; e.g. 14.70 → 1470.  Returns 0 when invalid/stale.
    uint16_t  get_afr_x100();

    // Lambda × 1000; e.g. λ=1.000 → 1000.  Returns 0 when invalid/stale.
    uint16_t  get_lambda_x1000();

    // Float AFR for backward compatibility.  get_afr_x100() / 100.0f.
    float     get_afr();

    // Wideband sensor voltage (byte4 * 0.1 V).  Returns 0 when invalid/stale.
    float     get_voltage();

    bool      is_valid();     // true: fresh frame within AFR_TIMEOUT_MS
    CanStatus get_status();   // detailed bus + data health
}
