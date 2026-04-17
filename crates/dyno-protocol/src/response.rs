//! Inbound acknowledgement, error, config, and device-info response decoding.

use crate::{config::DynoConfig, device_info::DeviceInfo, frame::FRAME_SIZE, PacketType, ProtocolError};

const REQUEST_TYPE_OFFSET: usize = 8;
const STATUS_OR_ERROR_OFFSET: usize = 9;
const MESSAGE_OFFSET: usize = 12;
const MESSAGE_LEN: usize = 26;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ErrorCode {
    InvalidConfig = 1,
    Busy = 2,
    UnsupportedCommand = 3,
    Internal = 4,
    Unknown = 0xFF,
}

impl From<u8> for ErrorCode {
    fn from(value: u8) -> Self {
        match value {
            1 => Self::InvalidConfig,
            2 => Self::Busy,
            3 => Self::UnsupportedCommand,
            4 => Self::Internal,
            _ => Self::Unknown,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct AckResponse {
    pub seq: u32,
    pub request_type: PacketType,
    pub status_code: u8,
    pub message: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ErrorResponse {
    pub seq: u32,
    pub request_type: PacketType,
    pub error_code: ErrorCode,
    pub message: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ConfigResponse {
    pub seq: u32,
    pub config: DynoConfig,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DeviceInfoResponse {
    pub seq: u32,
    pub device_info: DeviceInfo,
}

impl AckResponse {
    pub fn from_packet_bytes(packet: &[u8]) -> Result<Self, ProtocolError> {
        validate_packet_len(packet)?;
        Ok(Self {
            seq: u32::from_le_bytes([packet[4], packet[5], packet[6], packet[7]]),
            request_type: PacketType::from(packet[REQUEST_TYPE_OFFSET]),
            status_code: packet[STATUS_OR_ERROR_OFFSET],
            message: parse_message(packet),
        })
    }
}

impl ErrorResponse {
    pub fn from_packet_bytes(packet: &[u8]) -> Result<Self, ProtocolError> {
        validate_packet_len(packet)?;
        Ok(Self {
            seq: u32::from_le_bytes([packet[4], packet[5], packet[6], packet[7]]),
            request_type: PacketType::from(packet[REQUEST_TYPE_OFFSET]),
            error_code: ErrorCode::from(packet[STATUS_OR_ERROR_OFFSET]),
            message: parse_message(packet),
        })
    }
}

impl ConfigResponse {
    pub fn from_packet_bytes(packet: &[u8]) -> Result<Self, ProtocolError> {
        validate_packet_len(packet)?;
        Ok(Self {
            seq: u32::from_le_bytes([packet[4], packet[5], packet[6], packet[7]]),
            config: DynoConfig::from_packet_bytes(packet),
        })
    }
}

impl DeviceInfoResponse {
    pub fn from_packet_bytes(packet: &[u8]) -> Result<Self, ProtocolError> {
        validate_packet_len(packet)?;
        Ok(Self {
            seq: u32::from_le_bytes([packet[4], packet[5], packet[6], packet[7]]),
            device_info: DeviceInfo::from_packet_bytes(packet),
        })
    }
}

fn parse_message(packet: &[u8]) -> Option<String> {
    let bytes = &packet[MESSAGE_OFFSET..MESSAGE_OFFSET + MESSAGE_LEN];
    let end = bytes.iter().position(|b| *b == 0).unwrap_or(bytes.len());
    if end == 0 {
        return None;
    }
    let message = String::from_utf8_lossy(&bytes[..end]).trim().to_owned();
    if message.is_empty() {
        None
    } else {
        Some(message)
    }
}

fn validate_packet_len(packet: &[u8]) -> Result<(), ProtocolError> {
    if packet.len() < FRAME_SIZE {
        return Err(ProtocolError::InvalidLength {
            got: packet.len(),
            expected: FRAME_SIZE,
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ack_message_stops_at_first_nul() {
        let mut packet = [0u8; FRAME_SIZE];
        packet[4..8].copy_from_slice(&9u32.to_le_bytes());
        packet[REQUEST_TYPE_OFFSET] = PacketType::Ping as u8;
        packet[MESSAGE_OFFSET..MESSAGE_OFFSET + 7].copy_from_slice(b"pong ok");

        let ack = AckResponse::from_packet_bytes(&packet).expect("ack");
        assert_eq!(ack.seq, 9);
        assert_eq!(ack.request_type, PacketType::Ping);
        assert_eq!(ack.message.as_deref(), Some("pong ok"));
    }
}
