#pragma once
#include <Arduino.h>
#include "config_store.h"
#include "config_validator.h"
#include "can_afr.h"

// ============================================================
//  Wire protocol — ESP32 ↔ Rust backend, UART
//
//  Two independent frame formats share the same physical UART:
//
//  ── A) COMMAND frames (bidirectional) ──────────────────────
//
//     [MAGIC:0xD5,0x2B][VER:1][PKT_TYPE:1][SEQ:2][LEN:2][PAYLOAD:LEN][CRC16:2]
//
//     Total overhead: 10 bytes.
//     CRC-16/CCITT-FALSE covers bytes VER..last-payload-byte.
//     Magic {0xD5, 0x2B} chosen to not conflict with:
//       - Telemetry magic {0x5A, 0xA5}
//
//  ── B) TELEMETRY frames (ESP32 → Rust, high-rate) ──────────
//
//     [MAGIC:0x5A,0xA5][VER:1][PKT_TYPE:1][SEQ:2][FRAME_LEN:2]
//     [...sensor payload...][CRC16:2]
//     Total: 44 bytes fixed. (see TelemetryFrame below)
//
//  Both formats use CRC-16/CCITT-FALSE (poly=0x1021, init=0xFFFF).
//  All multi-byte integers: little-endian.
//
//  UART-pin-change safety (REINIT_UART):
//    When a CONFIG_SET or CONFIG_APPLY results in REINIT_UART,
//    the CONFIG_ACK response is sent on the OLD baud rate before
//    Serial2 is restarted.  The Rust backend must:
//      1. Listen for CONFIG_ACK on the old baud rate.
//      2. Switch its UART to the new baud rate after receiving it.
//    The ESP32 waits for Serial2.flush() before reiniting.
// ============================================================

// ---- Command frame magic ----
static constexpr uint8_t CMD_MAGIC_0       = 0xD5u;
static constexpr uint8_t CMD_MAGIC_1       = 0x2Bu;
static constexpr uint8_t PROTO_CMD_VERSION = 0x01u;
static constexpr size_t  CMD_MAX_PAYLOAD   = 256u;

// ---- Telemetry frame magic (unchanged) ----
static constexpr uint8_t TELEM_MAGIC_0       = 0x5Au;
static constexpr uint8_t TELEM_MAGIC_1       = 0xA5u;
static constexpr uint8_t PROTO_TELEM_VERSION = 0x01u;

// ---- Firmware version ----
static constexpr uint8_t FW_VERSION_MAJOR = 0u;
static constexpr uint8_t FW_VERSION_MINOR = 2u;
static constexpr uint8_t FW_VERSION_PATCH = 0u;

// ============================================================
//  Command packet types
// ============================================================
enum class CmdPacketType : uint8_t {
    // Rust → ESP32
    PING         = 0x01,   // payload: empty
    CONFIG_GET   = 0x02,   // payload: empty — returns current live config
    CONFIG_SET   = 0x03,   // payload: DynoConfig — validate and stage
    CONFIG_APPLY = 0x04,   // payload: empty — commit staged + reinit peripherals
    DEVICE_INFO  = 0x05,   // payload: empty — returns DeviceInfoPayload

    // ESP32 → Rust
    PONG         = 0x81,   // payload: PongPayload
    CONFIG_ACK   = 0x82,   // payload: ConfigAckPayload
    CONFIG_ERROR = 0x83,   // payload: ConfigErrorPayload
    DEVICE_INFO_RSP = 0x84,// payload: DeviceInfoPayload
};

// ============================================================
//  Telemetry packet types (carried in TelemetryFrame::pkt_type)
// ============================================================
enum class PacketType : uint8_t {
    TELEM_DATA      = 0x01,
    TELEM_HEARTBEAT = 0x02,
    TELEM_EVENT     = 0x03,
};

// ============================================================
//  Signal flags — TelemetryFrame::signal_flags
// ============================================================
#define SIG_ENGINE_VALID    (1u << 0)
#define SIG_ROLLER_VALID    (1u << 1)
#define SIG_AFR_VALID       (1u << 2)
#define SIG_CAN_ACTIVE      (1u << 3)
#define SIG_ENGINE_STALL    (1u << 4)
#define SIG_ROLLER_STOP     (1u << 5)

// ============================================================
//  Fault flags — TelemetryFrame::fault_flags
// ============================================================
#define FLT_ENGINE_INIT     (1u << 0)
#define FLT_ENCODER_INIT    (1u << 1)
#define FLT_CAN_INIT        (1u << 2)
#define FLT_CAN_BUS_OFF     (1u << 3)
#define FLT_CONFIG_INVALID  (1u << 4)
#define FLT_UART_OVERRUN    (1u << 5)

// ============================================================
//  Command payload structs
// ============================================================

// PONG — response to PING
struct PongPayload {
    uint16_t req_seq;     // echoed from the PING frame's seq field
    uint32_t uptime_ms;   // millis() at time of response
    uint8_t  _rsv[2];
} __attribute__((packed));
static_assert(sizeof(PongPayload) == 8, "");

// CONFIG_ACK — response to CONFIG_SET, CONFIG_APPLY, CONFIG_GET
struct ConfigAckPayload {
    uint16_t req_seq;          // echoed from the triggering request
    uint8_t  config_version;   // ConfigStore::get_config_version()
    uint8_t  reinit_mask;      // REINIT_* bits: pending (after SET) or applied (after APPLY)
    // When reinit_mask includes REINIT_UART, the Rust backend must wait for
    // this ACK on the OLD baud rate and only then switch to the new baud rate.
} __attribute__((packed));
static_assert(sizeof(ConfigAckPayload) == 4, "");

// CONFIG_ERROR — response to CONFIG_SET or CONFIG_APPLY on failure
struct ConfigErrorPayload {
    uint16_t    req_seq;      // echoed from the triggering request
    uint8_t     error_code;   // ConfigError enum value
    uint8_t     field_id;     // ConfigField enum value; 0 = not field-specific
    char        message[28];  // ASCII human-readable, null-padded
} __attribute__((packed));
static_assert(sizeof(ConfigErrorPayload) == 32, "");

// DEVICE_INFO_RSP — response to DEVICE_INFO
struct DeviceInfoPayload {
    uint8_t  fw_major;
    uint8_t  fw_minor;
    uint8_t  fw_patch;
    uint8_t  proto_version;    // PROTO_CMD_VERSION
    uint32_t uptime_ms;        // millis()
    uint8_t  config_version;   // ConfigStore::get_config_version()
    uint8_t  reinit_pending;   // nonzero if a staged config is waiting for APPLY
    uint8_t  _rsv[2];
    char     build_date[12];   // __DATE__ null-padded (e.g. "Apr 17 2026\0")
    char     build_time[9];    // __TIME__ null-padded (e.g. "12:34:56\0")
    uint8_t  _rsv2[3];
} __attribute__((packed));
static_assert(sizeof(DeviceInfoPayload) == 36, "");

// ============================================================
//  Telemetry frame — 44 bytes fixed, self-framing
// ============================================================
struct TelemetryFrame {
    uint8_t  magic[2];
    uint8_t  version;
    uint8_t  pkt_type;
    uint16_t seq;
    uint16_t frame_len;
    uint64_t timestamp_us;
    uint32_t engine_period_us;
    int32_t  engine_rpm_x10;
    int32_t  encoder_count_total;
    int32_t  roller_rpm_x10;
    int16_t  encoder_delta;
    uint8_t  signal_flags;
    uint8_t  fault_flags;
    uint16_t afr_scaled_x100;
    uint16_t lambda_scaled_x1000;
    uint8_t  can_status;
    uint8_t  _rsv;
    uint16_t crc16;
} __attribute__((packed));
static_assert(sizeof(TelemetryFrame) == 44, "TelemetryFrame size mismatch");

// ============================================================
//  CRC helpers
// ============================================================
uint16_t crc16_update(uint16_t crc, const uint8_t* data, size_t len);
uint16_t crc16_compute(const uint8_t* data, size_t len);

// ============================================================
//  Protocol interface
// ============================================================
namespace Protocol {
    void init(HardwareSerial& serial);

    // Drain UART RX each loop() — parses command frames, dispatches handlers.
    void process_rx();

    // Outbound telemetry (raw 44-byte struct, CRC16 must be pre-computed by caller)
    void send_telemetry(const TelemetryFrame& frame);

    // Outbound command responses — all encode as command frames with CRC16
    void send_pong(uint16_t req_seq);
    void send_config_ack(uint16_t req_seq, uint8_t reinit_mask);
    void send_config_error(uint16_t req_seq, ConfigError err, ConfigField field,
                           const char* msg);
    void send_device_info(uint16_t req_seq);
    void send_current_config(uint16_t req_seq);
}
