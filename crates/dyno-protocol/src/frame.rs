//! Wire-format struct, field offsets, CRC, and physical-unit decoding.

use crate::{PacketType, ProtocolError};

// ── Constants ─────────────────────────────────────────────────────────────────

/// Wire magic: `uint16_t magic = 0x4459` ('DY').
///
/// Stored **little-endian** on the ESP32, so the byte pattern on the wire is
/// `[0x59, 0x44]`.  `MAGIC_BYTES` holds that wire representation.
pub const MAGIC: u16 = 0x4459;

/// Two-byte pattern to scan for in the byte stream.
/// Little-endian encoding of `MAGIC` (0x4459): low byte 0x59, high byte 0x44.
pub(crate) const MAGIC_BYTES: [u8; 2] = [0x59, 0x44];

/// Total length of one `DynoFrameV1` on the wire, in bytes.
///
/// Verified at compile time against manual field-offset arithmetic below.
pub const FRAME_SIZE: usize = 40;

/// Byte offset of the CRC16 field (last 2 bytes of the frame).
pub(crate) const CRC_OFFSET: usize = FRAME_SIZE - 2;

// Compile-time proof that FRAME_SIZE matches the manual layout.
const _: () = {
    // magic(2) version(1) packet_type(1) seq(4) ts_us(4)        = 12
    // encoder_count_total(4) encoder_delta(4)                   = 20
    // engine_period_us(4) engine_pulse_count_window(2)          = 26
    // afr_raw(2) afr_scaled_x100(2) lambda_scaled_x1000(2)     = 32
    // can_status(2) signal_flags(2) fault_flags(2)              = 38
    // crc16(2)                                                   = 40
    assert!(FRAME_SIZE == 40, "FRAME_SIZE must be 40");
};

// ── Raw frame struct ──────────────────────────────────────────────────────────

/// Raw ESP32 sensor frame, wire-format integer fields.
///
/// Mirrors `DynoFrameV1` in the ESP32 firmware (all fields little-endian,
/// `#pragma pack(push,1)`).  Construct with [`DynoFrameV1::from_bytes`];
/// convert to physical units with [`DynoFrameV1::decode`].
///
/// # Safety / UB note
///
/// We do **not** use `#[repr(C, packed)]` + pointer casting.  Rust allows
/// unaligned reads only through `read_unaligned`, which is unsafe.  Instead,
/// every field is extracted with `from_le_bytes` on explicit byte-slice
/// windows.  This is safe, deterministic, and works on any host alignment.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct DynoFrameV1 {
    pub magic: u16,
    pub version: u8,
    pub packet_type: u8,
    pub seq: u32,
    /// Source timestamp from the ESP32, microseconds since boot.
    pub ts_us: u32,

    /// Running total of encoder pulses since power-on.
    pub encoder_count_total: u32,
    /// Encoder pulses in the last measurement window.
    pub encoder_delta: u32,

    /// Period between successive engine ignition pulses, in microseconds.
    /// Inversely proportional to engine RPM.
    pub engine_period_us: u32,
    /// Number of ignition pulses captured in the last window.
    pub engine_pulse_count_window: u16,

    /// Raw ADC reading from the wideband O2 sensor controller.
    pub afr_raw: u16,
    /// AFR × 100, signed.  Example: 1380 → 13.80.
    pub afr_scaled_x100: i16,
    /// Lambda × 1000, signed.  Example: 939 → 0.939.
    pub lambda_scaled_x1000: i16,

    pub can_status: u16,
    pub signal_flags: u16,
    /// Raw fault/status bitfield from the ESP32 DAQ.
    pub fault_flags: u16,

    /// CRC-16/CCITT-FALSE over bytes 0..=37.
    pub crc16: u16,
}

impl DynoFrameV1 {
    /// Parse a raw frame from a byte slice of exactly [`FRAME_SIZE`] bytes.
    ///
    /// Does **not** validate the CRC or magic; call
    /// [`FrameDecoder::decode_next`] for a fully validated frame.
    pub fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        if data.len() < FRAME_SIZE {
            return Err(ProtocolError::InvalidLength {
                got: data.len(),
                expected: FRAME_SIZE,
            });
        }
        Ok(Self {
            magic:                      rd_u16(data, 0),
            version:                    data[2],
            packet_type:                data[3],
            seq:                        rd_u32(data, 4),
            ts_us:                      rd_u32(data, 8),
            encoder_count_total:        rd_u32(data, 12),
            encoder_delta:              rd_u32(data, 16),
            engine_period_us:           rd_u32(data, 20),
            engine_pulse_count_window:  rd_u16(data, 24),
            afr_raw:                    rd_u16(data, 26),
            afr_scaled_x100:            rd_i16(data, 28),
            lambda_scaled_x1000:        rd_i16(data, 30),
            can_status:                 rd_u16(data, 32),
            signal_flags:               rd_u16(data, 34),
            fault_flags:                rd_u16(data, 36),
            crc16:                      rd_u16(data, 38),
        })
    }

    /// Decode raw integer fields into physical-unit values.
    ///
    /// Returns `Err(ProtocolError::UnsupportedVersion)` if `version != 1`.
    pub fn decode(&self) -> Result<DecodedFrame, ProtocolError> {
        if self.version != 1 {
            return Err(ProtocolError::UnsupportedVersion(self.version));
        }
        Ok(DecodedFrame {
            seq:                       self.seq,
            ts_us:                     self.ts_us,
            version:                   self.version,
            packet_type:               PacketType::from(self.packet_type),
            encoder_count_total:       self.encoder_count_total,
            encoder_delta:             self.encoder_delta,
            engine_period_us:          self.engine_period_us,
            engine_pulse_count_window: self.engine_pulse_count_window,
            afr_raw:                   self.afr_raw,
            afr:                       self.afr_scaled_x100 as f32 / 100.0,
            lambda:                    self.lambda_scaled_x1000 as f32 / 1000.0,
            can_status:                self.can_status,
            signal_flags:              self.signal_flags,
            fault_flags:               self.fault_flags,
        })
    }
}

// ── Decoded frame (physical units) ───────────────────────────────────────────

/// Sensor frame with integer fields converted to physical units.
///
/// Produced by [`DynoFrameV1::decode`]; consumed by the fusion layer.
#[derive(Debug, Clone, PartialEq)]
pub struct DecodedFrame {
    pub seq: u32,
    /// Microseconds since ESP32 boot.
    pub ts_us: u32,
    pub version: u8,
    pub packet_type: PacketType,

    pub encoder_count_total: u32,
    pub encoder_delta: u32,

    /// Ignition pulse period in µs (reciprocal of engine RPM).
    pub engine_period_us: u32,
    pub engine_pulse_count_window: u16,

    pub afr_raw: u16,
    /// Air/fuel ratio (14.7 = stoichiometric for petrol).
    pub afr: f32,
    /// Lambda (1.0 = stoichiometric).
    pub lambda: f32,

    pub can_status: u16,
    pub signal_flags: u16,
    pub fault_flags: u16,
}

pub const SIG_ENGINE_VALID: u16 = 1 << 0;
pub const SIG_ROLLER_VALID: u16 = 1 << 1;
pub const SIG_AFR_VALID: u16 = 1 << 2;
pub const SIG_CAN_ACTIVE: u16 = 1 << 3;
pub const SIG_ENGINE_STALL: u16 = 1 << 4;
pub const SIG_ROLLER_STOP: u16 = 1 << 5;

pub const FLT_ENGINE_INIT: u16 = 1 << 0;
pub const FLT_ENCODER_INIT: u16 = 1 << 1;
pub const FLT_CAN_INIT: u16 = 1 << 2;
pub const FLT_CAN_BUS_OFF: u16 = 1 << 3;
pub const FLT_CONFIG_INVALID: u16 = 1 << 4;
pub const FLT_UART_OVERRUN: u16 = 1 << 5;

pub const CAN_STATUS_OK: u16 = 0x00;
pub const CAN_STATUS_NO_DATA: u16 = 0x01;
pub const CAN_STATUS_STALE: u16 = 0x02;
pub const CAN_STATUS_BUS_OFF: u16 = 0x03;
pub const CAN_STATUS_NOT_INIT: u16 = 0xFF;

// ── CRC-16/CCITT-FALSE ────────────────────────────────────────────────────────

/// CRC-16/CCITT-FALSE checksum.
///
/// Parameters: polynomial `0x1021`, init `0xFFFF`, no input/output reflection,
/// no final XOR.  Known check value for `"123456789"`: `0x29B1`.
pub fn crc16_ccitt(data: &[u8]) -> u16 {
    let mut crc: u16 = 0xFFFF;
    for &byte in data {
        crc ^= (byte as u16) << 8;
        for _ in 0..8 {
            if crc & 0x8000 != 0 {
                crc = (crc << 1) ^ 0x1021;
            } else {
                crc <<= 1;
            }
        }
    }
    crc
}

// ── Byte-level read helpers ───────────────────────────────────────────────────

#[inline(always)]
fn rd_u16(data: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([data[offset], data[offset + 1]])
}

#[inline(always)]
fn rd_i16(data: &[u8], offset: usize) -> i16 {
    i16::from_le_bytes([data[offset], data[offset + 1]])
}

#[inline(always)]
fn rd_u32(data: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([data[offset], data[offset + 1], data[offset + 2], data[offset + 3]])
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    #[test]
    fn crc_known_vector() {
        // CRC-16/CCITT-FALSE of "123456789" must be 0x29B1.
        let result = crc16_ccitt(b"123456789");
        assert_eq!(result, 0x29B1, "CRC known-vector mismatch: {result:#06x}");
    }

    #[test]
    fn crc_empty_is_init() {
        // CRC of empty input = init value 0xFFFF.
        assert_eq!(crc16_ccitt(&[]), 0xFFFF);
    }

    #[test]
    fn crc_single_zero_byte() {
        // Regression: single 0x00 byte should not equal 0xFFFF.
        assert_ne!(crc16_ccitt(&[0x00]), 0xFFFF);
    }

    #[test]
    fn from_bytes_rejects_short_slice() {
        let short = [0u8; FRAME_SIZE - 1];
        let err = DynoFrameV1::from_bytes(&short).unwrap_err();
        assert!(matches!(
            err,
            ProtocolError::InvalidLength { got: 39, expected: 40 }
        ));
    }

    #[test]
    fn decode_rejects_wrong_version() {
        let mut buf = make_frame_bytes(1, 0, 0);
        buf[2] = 2; // overwrite version
        // Re-compute CRC so the frame is otherwise valid.
        let crc = crc16_ccitt(&buf[..CRC_OFFSET]);
        buf[CRC_OFFSET..].copy_from_slice(&crc.to_le_bytes());
        let frame = DynoFrameV1::from_bytes(&buf).unwrap();
        assert!(matches!(frame.decode(), Err(ProtocolError::UnsupportedVersion(2))));
    }

    // ── Shared builder used by codec tests too ────────────────────────────────

    /// Build a syntactically valid frame byte array with a correct CRC.
    pub(crate) fn make_frame_bytes(seq: u32, ts_us: u32, engine_period_us: u32) -> [u8; FRAME_SIZE] {
        let mut buf = [0u8; FRAME_SIZE];
        buf[0..2].copy_from_slice(&MAGIC_BYTES);       // magic (LE)
        buf[2] = 1;                                    // version
        buf[3] = PacketType::Telemetry as u8;          // packet_type = telemetry
        buf[4..8].copy_from_slice(&seq.to_le_bytes());
        buf[8..12].copy_from_slice(&ts_us.to_le_bytes());
        buf[12..16].copy_from_slice(&1000u32.to_le_bytes()); // encoder_count_total
        buf[16..20].copy_from_slice(&100u32.to_le_bytes());  // encoder_delta
        buf[20..24].copy_from_slice(&engine_period_us.to_le_bytes());
        buf[24..26].copy_from_slice(&10u16.to_le_bytes());   // engine_pulse_count_window
        buf[26..28].copy_from_slice(&0u16.to_le_bytes());    // afr_raw
        buf[28..30].copy_from_slice(&1380i16.to_le_bytes()); // afr * 100 = 13.80
        buf[30..32].copy_from_slice(&939i16.to_le_bytes());  // lambda * 1000 = 0.939
        buf[32..34].copy_from_slice(&0u16.to_le_bytes());    // can_status
        buf[34..36].copy_from_slice(&0u16.to_le_bytes());    // signal_flags
        buf[36..38].copy_from_slice(&0u16.to_le_bytes());    // fault_flags
        let crc = crc16_ccitt(&buf[..CRC_OFFSET]);
        buf[CRC_OFFSET..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        buf
    }
}
