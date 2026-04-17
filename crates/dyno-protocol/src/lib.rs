//! Binary frame protocol for the ESP32 → Raspberry Pi UART link.
//!
//! # Wire format (`DynoFrameV1`)
//!
//! ```text
//! Offset  Size  Type     Field
//! ──────  ────  ───────  ────────────────────────────────────────────
//!      0     2  u16 LE   magic = 0x4459 ('DY'); wire bytes [0x59,0x44]
//!      2     1  u8       version (current: 1)
//!      3     1  u8       packet_type (see PacketType)
//!      4     4  u32 LE   seq (sequence counter, wraps at u32::MAX)
//!      8     4  u32 LE   ts_us (µs since ESP32 boot)
//!     12     4  u32 LE   encoder_count_total
//!     16     4  u32 LE   encoder_delta
//!     20     4  u32 LE   engine_period_us
//!     24     2  u16 LE   engine_pulse_count_window
//!     26     2  u16 LE   afr_raw
//!     28     2  i16 LE   afr_scaled_x100
//!     30     2  i16 LE   lambda_scaled_x1000
//!     32     2  u16 LE   can_status
//!     34     2  u16 LE   signal_flags
//!     36     2  u16 LE   fault_flags
//!     38     2  u16 LE   crc16 (CRC-16/CCITT-FALSE over bytes 0..37)
//! ──────  ────
//!             40 bytes total
//! ```
//!
//! # Usage
//!
//! ```no_run
//! use dyno_protocol::{DecodeStatus, FrameDecoder};
//!
//! let mut dec = FrameDecoder::new();
//! // dec.feed(&uart_bytes);
//! // loop {
//! //     match dec.decode_next().unwrap() {
//! //         DecodeStatus::Frame(f)    => { /* process f */ }
//! //         DecodeStatus::NeedMoreData   => break,
//! //         DecodeStatus::BudgetExhausted => { /* yield and retry */ break }
//! //     }
//! // }
//! ```

pub mod codec;
pub mod command;
pub mod config;
pub mod device_info;
pub mod error;
pub mod frame;
pub mod packet;
pub mod response;
pub mod transport;

pub use codec::{DecodeStatus, FrameDecoder};
pub use command::CommandPacket;
pub use config::{DynoConfig, EngineEdgeMode};
pub use device_info::DeviceInfo;
pub use error::ProtocolError;
pub use frame::{
    crc16_ccitt, DecodedFrame, DynoFrameV1, CAN_STATUS_BUS_OFF, CAN_STATUS_NO_DATA,
    CAN_STATUS_NOT_INIT, CAN_STATUS_OK, CAN_STATUS_STALE, FLT_CAN_BUS_OFF, FLT_CAN_INIT,
    FLT_CONFIG_INVALID, FLT_ENCODER_INIT, FLT_ENGINE_INIT, FLT_UART_OVERRUN, FRAME_SIZE, MAGIC,
    SIG_AFR_VALID, SIG_CAN_ACTIVE, SIG_ENGINE_STALL, SIG_ENGINE_VALID, SIG_ROLLER_STOP,
    SIG_ROLLER_VALID,
};
pub use packet::PacketType;
pub use response::{AckResponse, ConfigResponse, DeviceInfoResponse, ErrorCode, ErrorResponse};
pub use transport::{PacketDecodeStatus, PacketDecoder, WirePacket};
