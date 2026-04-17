#pragma once
#include "config_store.h"
#include <stdint.h>

// ============================================================
//  Telemetry — assembles and transmits TelemetryFrame
//
//  send() is the single entry point called by main.cpp at
//  telemetry_rate_hz.  It:
//    1. Latches the encoder counter (Encoder::tick)
//    2. Reads all sensor modules
//    3. Packs the integer-scaled TelemetryFrame struct
//    4. Computes CRC16-CCITT over bytes [0 .. offsetof(crc16)-1]
//    5. Writes the 44-byte frame raw via Protocol::send_telemetry
//
//  Fault flags are accumulated through set_fault() / clear_fault().
//  main.cpp should call set_fault(FLT_*) after any failed init().
//  Faults are sticky until explicitly cleared or the module reinits.
// ============================================================

namespace Telemetry {
    void init(const DynoConfig& cfg);

    // Set / clear individual FLT_* fault bits (see protocol.h).
    // Thread-safe for main-task use only (same context as send()).
    void set_fault(uint8_t fault_bit);
    void clear_fault(uint8_t fault_bit);

    // Assemble and send one TelemetryFrame.
    // Must be called from the same task as set_fault() / clear_fault().
    void send();
}
