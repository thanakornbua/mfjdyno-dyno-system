//! Fixed-length outbound command packet encoding.

use crate::{
    config::DynoConfig,
    frame::{crc16_ccitt, FRAME_SIZE, MAGIC, CRC_OFFSET},
    PacketType,
};

#[derive(Debug, Clone, PartialEq)]
pub enum CommandPacket {
    Ping { seq: u32 },
    ConfigGet { seq: u32 },
    ConfigSet { seq: u32, config: DynoConfig },
    ConfigApply { seq: u32 },
    DeviceInfoGet { seq: u32 },
}

impl CommandPacket {
    pub fn seq(&self) -> u32 {
        match self {
            Self::Ping { seq }
            | Self::ConfigGet { seq }
            | Self::ConfigApply { seq }
            | Self::DeviceInfoGet { seq } => *seq,
            Self::ConfigSet { seq, .. } => *seq,
        }
    }

    pub fn packet_type(&self) -> PacketType {
        match self {
            Self::Ping { .. } => PacketType::Ping,
            Self::ConfigGet { .. } => PacketType::ConfigGet,
            Self::ConfigSet { .. } => PacketType::ConfigSet,
            Self::ConfigApply { .. } => PacketType::ConfigApply,
            Self::DeviceInfoGet { .. } => PacketType::DeviceInfoGet,
        }
    }

    pub fn encode(&self) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = self.packet_type() as u8;
        packet[4..8].copy_from_slice(&self.seq().to_le_bytes());

        if let Self::ConfigSet { config, .. } = self {
            config.encode_into_packet(&mut packet);
        }

        let crc = crc16_ccitt(&packet[..CRC_OFFSET]);
        packet[CRC_OFFSET..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ping_command_encodes_header_and_crc() {
        let packet = CommandPacket::Ping { seq: 7 }.encode();

        assert_eq!(&packet[0..2], &MAGIC.to_le_bytes());
        assert_eq!(packet[2], 1);
        assert_eq!(packet[3], PacketType::Ping as u8);
        assert_eq!(&packet[4..8], &7u32.to_le_bytes());
        assert_eq!(
            u16::from_le_bytes([packet[CRC_OFFSET], packet[CRC_OFFSET + 1]]),
            crc16_ccitt(&packet[..CRC_OFFSET])
        );
    }
}
