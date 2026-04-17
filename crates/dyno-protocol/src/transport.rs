//! Mixed-packet UART decoder with framing resynchronization.

use crate::{
    frame::{crc16_ccitt, CRC_OFFSET, FRAME_SIZE, MAGIC_BYTES},
    response::{AckResponse, ConfigResponse, DeviceInfoResponse, ErrorResponse},
    DynoFrameV1, PacketType, ProtocolError,
};

const MAX_BUFFER_BYTES: usize = 8192;
const MAX_SCAN_STEPS: usize = 256;

#[derive(Debug, Clone, PartialEq)]
pub enum WirePacket {
    Telemetry(DynoFrameV1),
    Ack(AckResponse),
    Error(ErrorResponse),
    Config(ConfigResponse),
    DeviceInfo(DeviceInfoResponse),
}

impl WirePacket {
    pub fn packet_type(&self) -> PacketType {
        match self {
            Self::Telemetry(_) => PacketType::Telemetry,
            Self::Ack(_) => PacketType::Ack,
            Self::Error(_) => PacketType::Error,
            Self::Config(_) => PacketType::ConfigData,
            Self::DeviceInfo(_) => PacketType::DeviceInfoData,
        }
    }
}

#[derive(Debug, PartialEq)]
pub enum PacketDecodeStatus {
    Packet(WirePacket),
    NeedMoreData,
    BudgetExhausted,
}

pub struct PacketDecoder {
    buffer: Vec<u8>,
    head: usize,
}

impl PacketDecoder {
    pub fn new() -> Self {
        Self {
            buffer: Vec::with_capacity(FRAME_SIZE * 8),
            head: 0,
        }
    }

    pub fn feed(&mut self, bytes: &[u8]) {
        self.maybe_compact();
        self.buffer.extend_from_slice(bytes);
        if self.buffer.len() > MAX_BUFFER_BYTES {
            let keep = FRAME_SIZE - 1;
            let start = self.buffer.len().saturating_sub(keep);
            self.buffer.copy_within(start.., 0);
            self.buffer.truncate(keep);
            self.head = 0;
        }
    }

    pub fn decode_next(&mut self) -> Result<PacketDecodeStatus, ProtocolError> {
        let mut steps = 0;
        loop {
            if steps >= MAX_SCAN_STEPS {
                return Ok(PacketDecodeStatus::BudgetExhausted);
            }
            steps += 1;

            let window = &self.buffer[self.head..];
            let magic_offset = match window.windows(2).position(|w| w == MAGIC_BYTES) {
                Some(pos) => pos,
                None => {
                    if window.len() > 1 {
                        self.head = self.buffer.len() - 1;
                    }
                    return Ok(PacketDecodeStatus::NeedMoreData);
                }
            };

            self.head += magic_offset;
            if self.buffer.len() - self.head < FRAME_SIZE {
                return Ok(PacketDecodeStatus::NeedMoreData);
            }

            let start = self.head;
            let computed = crc16_ccitt(&self.buffer[start..start + CRC_OFFSET]);
            let received = u16::from_le_bytes([
                self.buffer[start + CRC_OFFSET],
                self.buffer[start + CRC_OFFSET + 1],
            ]);
            if computed != received {
                self.head += 1;
                continue;
            }

            let version = self.buffer[start + 2];
            let packet_type = PacketType::from(self.buffer[start + 3]);
            if version != 1 || packet_type == PacketType::Unknown {
                self.head += 1;
                continue;
            }

            let packet = decode_packet(&self.buffer[start..start + FRAME_SIZE], packet_type)?;
            self.head = start + FRAME_SIZE;
            return Ok(PacketDecodeStatus::Packet(packet));
        }
    }

    pub fn buffered_len(&self) -> usize {
        self.buffer.len() - self.head
    }

    fn maybe_compact(&mut self) {
        if self.head == 0 {
            return;
        }
        let len = self.buffer.len();
        if self.head > 1024 || self.head > len / 2 {
            let remaining = len - self.head;
            self.buffer.copy_within(self.head.., 0);
            self.buffer.truncate(remaining);
            self.head = 0;
        }
    }
}

impl Default for PacketDecoder {
    fn default() -> Self {
        Self::new()
    }
}

fn decode_packet(bytes: &[u8], packet_type: PacketType) -> Result<WirePacket, ProtocolError> {
    match packet_type {
        PacketType::Telemetry => Ok(WirePacket::Telemetry(DynoFrameV1::from_bytes(bytes)?)),
        PacketType::Ack => Ok(WirePacket::Ack(AckResponse::from_packet_bytes(bytes)?)),
        PacketType::Error => Ok(WirePacket::Error(ErrorResponse::from_packet_bytes(bytes)?)),
        PacketType::ConfigData => Ok(WirePacket::Config(ConfigResponse::from_packet_bytes(bytes)?)),
        PacketType::DeviceInfoData => Ok(WirePacket::DeviceInfo(DeviceInfoResponse::from_packet_bytes(bytes)?)),
        PacketType::ConfigGet
        | PacketType::ConfigSet
        | PacketType::ConfigApply
        | PacketType::Ping
        | PacketType::DeviceInfoGet
        | PacketType::Unknown => Err(ProtocolError::Desync),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        command::CommandPacket,
        config::DynoConfig,
        config::EngineEdgeMode,
        device_info::DeviceInfo,
        frame::tests::make_frame_bytes,
    };

    fn make_ack_packet(seq: u32, request_type: PacketType, status_code: u8, message: &[u8]) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&crate::frame::MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::Ack as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        packet[8] = request_type as u8;
        packet[9] = status_code;
        let copy_len = message.len().min(26);
        packet[12..12 + copy_len].copy_from_slice(&message[..copy_len]);
        let crc = crc16_ccitt(&packet[..CRC_OFFSET]);
        packet[CRC_OFFSET..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    fn make_config_response(seq: u32, config: DynoConfig) -> [u8; FRAME_SIZE] {
        let mut packet = CommandPacket::ConfigSet { seq, config }.encode();
        packet[3] = PacketType::ConfigData as u8;
        let crc = crc16_ccitt(&packet[..CRC_OFFSET]);
        packet[CRC_OFFSET..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    fn make_device_info_response(seq: u32, info: DeviceInfo) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&crate::frame::MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::DeviceInfoData as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        info.encode_into_packet(&mut packet);
        let crc = crc16_ccitt(&packet[..CRC_OFFSET]);
        packet[CRC_OFFSET..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    #[test]
    fn decoder_emits_telemetry_then_ack() {
        let telemetry = make_frame_bytes(1, 1_000, 8_000);
        let ack = make_ack_packet(2, PacketType::Ping, 0, b"pong");
        let mut stream = Vec::new();
        stream.extend_from_slice(&telemetry);
        stream.extend_from_slice(&ack);

        let mut decoder = PacketDecoder::new();
        decoder.feed(&stream);

        match decoder.decode_next().expect("telemetry decode") {
            PacketDecodeStatus::Packet(WirePacket::Telemetry(frame)) => assert_eq!(frame.seq, 1),
            other => panic!("unexpected packet: {other:?}"),
        }
        match decoder.decode_next().expect("ack decode") {
            PacketDecodeStatus::Packet(WirePacket::Ack(ack)) => {
                assert_eq!(ack.seq, 2);
                assert_eq!(ack.request_type, PacketType::Ping);
                assert_eq!(ack.message.as_deref(), Some("pong"));
            }
            other => panic!("unexpected packet: {other:?}"),
        }
    }

    #[test]
    fn decoder_resyncs_after_corrupted_packet() {
        let mut bad = make_ack_packet(1, PacketType::Ping, 0, b"bad");
        bad[15] ^= 0xFF;
        let good = make_frame_bytes(7, 100, 8_000);

        let mut decoder = PacketDecoder::new();
        decoder.feed(&bad);
        decoder.feed(&good);

        match decoder.decode_next().expect("telemetry decode") {
            PacketDecodeStatus::Packet(WirePacket::Telemetry(frame)) => assert_eq!(frame.seq, 7),
            other => panic!("unexpected packet: {other:?}"),
        }
    }

    #[test]
    fn config_response_round_trips() {
        let config = DynoConfig {
            engine_pulse_pin: 4,
            engine_pulses_per_rev: 1.0,
            engine_edge_mode: EngineEdgeMode::Rising,
            encoder_pin_a: 5,
            encoder_ppr: 60,
            can_rx_pin: 21,
            can_tx_pin: 22,
            can_bitrate: 500_000,
            uart_tx_pin: 17,
            uart_rx_pin: 16,
            uart_baud: 921_600,
            telemetry_rate_hz: 50,
        };
        let packet = make_config_response(11, config.clone());
        let mut decoder = PacketDecoder::new();
        decoder.feed(&packet);

        match decoder.decode_next().expect("config decode") {
            PacketDecodeStatus::Packet(WirePacket::Config(response)) => {
                assert_eq!(response.seq, 11);
                assert_eq!(response.config, config);
            }
            other => panic!("unexpected packet: {other:?}"),
        }
    }

    #[test]
    fn device_info_response_round_trips() {
        let info = DeviceInfo {
            device_id: 77,
            protocol_version: 1,
            firmware_major: 2,
            firmware_minor: 1,
            firmware_patch: 0,
            capabilities: 0x55AA_0001,
            device_name: "esp32-daq".to_owned(),
        };
        let packet = make_device_info_response(15, info.clone());
        let mut decoder = PacketDecoder::new();
        decoder.feed(&packet);

        match decoder.decode_next().expect("device info decode") {
            PacketDecodeStatus::Packet(WirePacket::DeviceInfo(response)) => {
                assert_eq!(response.seq, 15);
                assert_eq!(response.device_info, info);
            }
            other => panic!("unexpected packet: {other:?}"),
        }
    }
}
