#include "protocol.h"
#include "config_store.h"
#include "config_validator.h"
#include <string.h>
#include <Arduino.h>

static HardwareSerial* s_serial = nullptr;

// ============================================================
//  CRC-16/CCITT-FALSE  poly=0x1021, init=0xFFFF, no reflection
//  Covers VER..last-payload-byte in command frames,
//  and magic[0]..rsv in telemetry frames.
// ============================================================
uint16_t crc16_update(uint16_t crc, const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (int b = 0; b < 8; b++) {
            crc = (crc & 0x8000u) ? (uint16_t)((crc << 1) ^ 0x1021u)
                                  : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

uint16_t crc16_compute(const uint8_t* data, size_t len) {
    return crc16_update(0xFFFFu, data, len);
}

// ============================================================
//  TX helpers
// ============================================================

// Emit one complete command frame.
// CRC covers: [VER][PKT_TYPE][SEQ_LO][SEQ_HI][LEN_LO][LEN_HI][PAYLOAD...]
static void send_cmd_frame(CmdPacketType type, uint16_t seq,
                           const uint8_t* payload, uint16_t len) {
    if (!s_serial) return;

    uint8_t hdr[6] = {
        PROTO_CMD_VERSION,
        static_cast<uint8_t>(type),
        static_cast<uint8_t>(seq & 0xFFu),
        static_cast<uint8_t>((seq >> 8) & 0xFFu),
        static_cast<uint8_t>(len & 0xFFu),
        static_cast<uint8_t>((len >> 8) & 0xFFu),
    };

    uint16_t crc = crc16_update(0xFFFFu, hdr, sizeof(hdr));
    if (len > 0 && payload != nullptr) {
        crc = crc16_update(crc, payload, len);
    }

    uint8_t crc_bytes[2] = {
        static_cast<uint8_t>(crc & 0xFFu),
        static_cast<uint8_t>((crc >> 8) & 0xFFu),
    };

    s_serial->write(CMD_MAGIC_0);
    s_serial->write(CMD_MAGIC_1);
    s_serial->write(hdr, sizeof(hdr));
    if (len > 0 && payload != nullptr) {
        s_serial->write(payload, len);
    }
    s_serial->write(crc_bytes, sizeof(crc_bytes));
}

namespace Protocol {

// ---- Telemetry (raw write, no command envelope) ----
void send_telemetry(const TelemetryFrame& frame) {
    if (!s_serial) return;
    s_serial->write(reinterpret_cast<const uint8_t*>(&frame), sizeof(TelemetryFrame));
}

// ---- PONG ----
void send_pong(uint16_t req_seq) {
    PongPayload p = {};
    p.req_seq   = req_seq;
    p.uptime_ms = millis();
    send_cmd_frame(CmdPacketType::PONG,
                   req_seq,
                   reinterpret_cast<const uint8_t*>(&p), sizeof(p));
}

// ---- CONFIG_ACK ----
void send_config_ack(uint16_t req_seq, uint8_t reinit_mask) {
    ConfigAckPayload p = {};
    p.req_seq        = req_seq;
    p.config_version = ConfigStore::get_config_version();
    p.reinit_mask    = reinit_mask;
    send_cmd_frame(CmdPacketType::CONFIG_ACK,
                   req_seq,
                   reinterpret_cast<const uint8_t*>(&p), sizeof(p));
}

// ---- CONFIG_ERROR ----
void send_config_error(uint16_t req_seq, ConfigError err, ConfigField field,
                       const char* msg) {
    ConfigErrorPayload p = {};
    p.req_seq    = req_seq;
    p.error_code = static_cast<uint8_t>(err);
    p.field_id   = static_cast<uint8_t>(field);
    if (msg) {
        size_t mlen = strlen(msg);
        if (mlen >= sizeof(p.message)) mlen = sizeof(p.message) - 1u;
        memcpy(p.message, msg, mlen);
    }
    send_cmd_frame(CmdPacketType::CONFIG_ERROR,
                   req_seq,
                   reinterpret_cast<const uint8_t*>(&p), sizeof(p));
}

// ---- DEVICE_INFO_RSP ----
void send_device_info(uint16_t req_seq) {
    DeviceInfoPayload p = {};
    p.fw_major       = FW_VERSION_MAJOR;
    p.fw_minor       = FW_VERSION_MINOR;
    p.fw_patch       = FW_VERSION_PATCH;
    p.proto_version  = PROTO_CMD_VERSION;
    p.uptime_ms      = millis();
    p.config_version = ConfigStore::get_config_version();
    p.reinit_pending = ConfigStore::has_staged() ? 1u : 0u;
    // Embed build timestamp for remote debugging
    strncpy(p.build_date, __DATE__, sizeof(p.build_date) - 1);
    strncpy(p.build_time, __TIME__, sizeof(p.build_time) - 1);
    send_cmd_frame(CmdPacketType::DEVICE_INFO_RSP,
                   req_seq,
                   reinterpret_cast<const uint8_t*>(&p), sizeof(p));
}

// ---- CONFIG_ACK carrying the current live config ----
// CONFIG_GET response: send ACK then the full DynoConfig as a second frame.
// We use a dedicated RSP_CONFIG-style packet re-using CONFIG_ACK pkt_type
// but carrying the full config struct as the payload, disambiguated by size.
// The Rust side distinguishes CONFIG_ACK (4 bytes) from CONFIG_GET_RSP
// (sizeof(DynoConfig) bytes) by payload length.
void send_current_config(uint16_t req_seq) {
    // First send a standard ACK with reinit_mask=0 so Rust knows the request was accepted
    send_config_ack(req_seq, 0);
    // Then send the full config as a separate frame typed CONFIG_ACK so the parser
    // can match by seq.  Using the same seq makes correlation unambiguous.
    // TODO: define a dedicated CONFIG_GET_RSP packet type if the Rust parser
    //       cannot distinguish by length alone.
    const DynoConfig& cfg = ConfigStore::get();
    send_cmd_frame(CmdPacketType::CONFIG_ACK,
                   req_seq,
                   reinterpret_cast<const uint8_t*>(&cfg), sizeof(DynoConfig));
}

} // namespace Protocol

// ============================================================
//  RX state machine — command frames (CRC16)
// ============================================================

enum class RxState : uint8_t {
    WAIT_MAGIC_0,
    WAIT_MAGIC_1,
    WAIT_VER,
    WAIT_PKT_TYPE,
    WAIT_SEQ_LO,
    WAIT_SEQ_HI,
    WAIT_LEN_LO,
    WAIT_LEN_HI,
    READ_PAYLOAD,
    WAIT_CRC_LO,
    WAIT_CRC_HI,
};

static RxState  s_rx_state   = RxState::WAIT_MAGIC_0;
static uint8_t  s_rx_type    = 0;
static uint16_t s_rx_seq     = 0;
static uint16_t s_rx_len     = 0;
static uint16_t s_rx_pos     = 0;
static uint8_t  s_rx_buf[CMD_MAX_PAYLOAD];
static uint16_t s_rx_crc     = 0;   // running CRC over VER..payload
static uint8_t  s_rx_crc_lo  = 0;   // received CRC low byte

static void rx_reset() {
    s_rx_state = RxState::WAIT_MAGIC_0;
    s_rx_crc   = 0xFFFFu;
    s_rx_pos   = 0;
}

// Feed byte into running CRC (covers VER..payload — not magic bytes)
static inline void crc_feed(uint8_t b) {
    s_rx_crc ^= (uint16_t)b << 8;
    for (int i = 0; i < 8; i++) {
        s_rx_crc = (s_rx_crc & 0x8000u) ? (uint16_t)((s_rx_crc << 1) ^ 0x1021u)
                                         : (uint16_t)(s_rx_crc << 1);
    }
}

// ============================================================
//  Command handlers
// ============================================================

static void handle_ping(uint16_t seq) {
    Protocol::send_pong(seq);
}

static void handle_config_get(uint16_t seq) {
    Protocol::send_current_config(seq);
}

static void handle_config_set(uint16_t seq, const uint8_t* payload, uint16_t len) {
    if (len != sizeof(DynoConfig)) {
        Serial.printf("[proto] CONFIG_SET bad length %u (expected %u)\n",
                      len, (unsigned)sizeof(DynoConfig));
        Protocol::send_config_error(seq, ConfigError::BAD_FRAME_LEN,
                                    ConfigField::NONE,
                                    "payload length != sizeof(DynoConfig)");
        return;
    }

    DynoConfig proposed;
    memcpy(&proposed, payload, sizeof(DynoConfig));

    ValidationResult r = ConfigValidator::validate(proposed, ConfigStore::get());
    if (r.error != ConfigError::NONE) {
        // Build a brief message describing the error
        char msg[28] = {};
        snprintf(msg, sizeof(msg), "err=0x%02X field=%u",
                 static_cast<uint8_t>(r.error),
                 static_cast<uint8_t>(r.field));
        Serial.printf("[proto] CONFIG_SET rejected: %s\n", msg);
        Protocol::send_config_error(seq, r.error, r.field, msg);
        return;
    }

    // Validation passed — stage the config (does not touch live config or peripherals)
    ConfigStore::stage(proposed);
    Serial.printf("[proto] CONFIG_SET staged — reinit_mask=0x%02X\n", r.reinit_mask);

    // Respond with the reinit preview so Rust knows what CONFIG_APPLY will trigger.
    // If reinit_mask includes REINIT_UART, Rust must prepare to switch baud before
    // sending CONFIG_APPLY.
    Protocol::send_config_ack(seq, r.reinit_mask);
}

static void handle_config_apply(uint16_t seq) {
    if (!ConfigStore::has_staged()) {
        Protocol::send_config_error(seq, ConfigError::NO_STAGED_CONFIG,
                                    ConfigField::NONE,
                                    "no staged config; send CONFIG_SET first");
        return;
    }

    // Determine if the UART will change.  We need this before commit() clears
    // the staged config, to decide whether to set REINIT_UART last.
    const DynoConfig& cur    = ConfigStore::get();
    const DynoConfig& staged = ConfigStore::get_staged();
    bool uart_changes = (cur.uart_baud   != staged.uart_baud   ||
                         cur.uart_tx_pin != staged.uart_tx_pin ||
                         cur.uart_rx_pin != staged.uart_rx_pin);

    // Commit: applies staged → live, sets g_reinit_flags, increments config_version.
    // We pass save_to_nvs=false here; if Rust wants persistence it should send
    // a separate CONFIG_SAVE command (or we could add a flag to CONFIG_APPLY payload).
    // TODO: add optional save_to_nvs bit in a CONFIG_APPLY payload field.
    uint8_t applied_mask = ConfigStore::commit(/*save_to_nvs=*/false);

    Serial.printf("[proto] CONFIG_APPLY committed — reinit_mask=0x%02X uart_changes=%d\n",
                  applied_mask, (int)uart_changes);

    if (uart_changes) {
        // ACK MUST be sent on the old baud rate — Serial2 hasn't been reinited yet.
        // main.cpp handles REINIT_UART last in handle_reinit(), after all other
        // subsystems, so there is a window here where the ACK gets out.
        Serial.println("[proto] UART change pending — ACK sent on old baud");
    }

    // Send ACK (on current — possibly old — baud rate).
    Protocol::send_config_ack(seq, applied_mask);

    // g_reinit_flags has been set by commit().  main.cpp will pick it up next loop.
    // REINIT_UART is handled last inside handle_reinit() in main.cpp, so the ACK
    // above will be flushed before Serial2 is torn down.
}

static void handle_device_info(uint16_t seq) {
    Protocol::send_device_info(seq);
}

static void dispatch(uint8_t type, uint16_t seq,
                     const uint8_t* payload, uint16_t len) {
    switch (static_cast<CmdPacketType>(type)) {
        case CmdPacketType::PING:         handle_ping(seq);                      break;
        case CmdPacketType::CONFIG_GET:   handle_config_get(seq);                break;
        case CmdPacketType::CONFIG_SET:   handle_config_set(seq, payload, len);  break;
        case CmdPacketType::CONFIG_APPLY: handle_config_apply(seq);              break;
        case CmdPacketType::DEVICE_INFO:  handle_device_info(seq);               break;
        default:
            Serial.printf("[proto] unknown cmd 0x%02X\n", type);
            Protocol::send_config_error(seq, ConfigError::BAD_VERSION,
                                        ConfigField::NONE, "unknown packet type");
            break;
    }
}

// ============================================================
//  Public API
// ============================================================
namespace Protocol {

void init(HardwareSerial& serial) {
    s_serial = &serial;
    rx_reset();
}

void process_rx() {
    if (!s_serial) return;

    // Drain up to 128 bytes per call so we don't starve the main loop.
    int budget = 128;
    while (s_serial->available() && budget-- > 0) {
        uint8_t b = static_cast<uint8_t>(s_serial->read());

        switch (s_rx_state) {

            case RxState::WAIT_MAGIC_0:
                if (b == CMD_MAGIC_0) {
                    s_rx_crc   = 0xFFFFu;   // reset CRC; don't include magic bytes
                    s_rx_state = RxState::WAIT_MAGIC_1;
                }
                // else: scan forward; could be a telemetry byte, ignore it
                break;

            case RxState::WAIT_MAGIC_1:
                if (b == CMD_MAGIC_1) {
                    s_rx_state = RxState::WAIT_VER;
                } else if (b == CMD_MAGIC_0) {
                    // Two consecutive 0xD5 — stay in MAGIC_1 wait
                    s_rx_state = RxState::WAIT_MAGIC_1;
                } else {
                    rx_reset();
                }
                break;

            case RxState::WAIT_VER:
                if (b != PROTO_CMD_VERSION) {
                    Serial.printf("[proto] bad version 0x%02X\n", b);
                    Protocol::send_config_error(0, ConfigError::BAD_VERSION,
                                                ConfigField::NONE, "unsupported version");
                    rx_reset();
                    break;
                }
                crc_feed(b);
                s_rx_state = RxState::WAIT_PKT_TYPE;
                break;

            case RxState::WAIT_PKT_TYPE:
                s_rx_type  = b;
                crc_feed(b);
                s_rx_state = RxState::WAIT_SEQ_LO;
                break;

            case RxState::WAIT_SEQ_LO:
                s_rx_seq   = b;
                crc_feed(b);
                s_rx_state = RxState::WAIT_SEQ_HI;
                break;

            case RxState::WAIT_SEQ_HI:
                s_rx_seq  |= (uint16_t)b << 8;
                crc_feed(b);
                s_rx_state = RxState::WAIT_LEN_LO;
                break;

            case RxState::WAIT_LEN_LO:
                s_rx_len   = b;
                crc_feed(b);
                s_rx_state = RxState::WAIT_LEN_HI;
                break;

            case RxState::WAIT_LEN_HI:
                s_rx_len  |= (uint16_t)b << 8;
                crc_feed(b);
                if (s_rx_len > CMD_MAX_PAYLOAD) {
                    Serial.printf("[proto] payload too large (%u)\n", s_rx_len);
                    rx_reset();
                    break;
                }
                s_rx_pos   = 0;
                s_rx_state = (s_rx_len > 0) ? RxState::READ_PAYLOAD
                                             : RxState::WAIT_CRC_LO;
                break;

            case RxState::READ_PAYLOAD:
                s_rx_buf[s_rx_pos++] = b;
                crc_feed(b);
                if (s_rx_pos >= s_rx_len) {
                    s_rx_state = RxState::WAIT_CRC_LO;
                }
                break;

            case RxState::WAIT_CRC_LO:
                s_rx_crc_lo = b;
                s_rx_state  = RxState::WAIT_CRC_HI;
                break;

            case RxState::WAIT_CRC_HI: {
                uint16_t received_crc = (uint16_t)s_rx_crc_lo
                                      | ((uint16_t)b << 8);
                if (received_crc != s_rx_crc) {
                    Serial.printf("[proto] CRC fail got=0x%04X exp=0x%04X seq=%u\n",
                                  received_crc, s_rx_crc, s_rx_seq);
                    Protocol::send_config_error(s_rx_seq, ConfigError::BAD_CRC,
                                                ConfigField::NONE, "CRC16 mismatch");
                    rx_reset();
                    break;
                }
                dispatch(s_rx_type, s_rx_seq, s_rx_buf, s_rx_len);
                rx_reset();
                break;
            }
        }
    }
}

} // namespace Protocol
