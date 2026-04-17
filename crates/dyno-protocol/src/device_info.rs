//! Fixed-width device-info payload shared by DEVICE_INFO_GET / DEVICE_INFO_DATA packets.

use serde::{Deserialize, Serialize};

const DEVICE_ID_OFFSET: usize = 8;
const PROTOCOL_VERSION_OFFSET: usize = 12;
const FW_MAJOR_OFFSET: usize = 13;
const FW_MINOR_OFFSET: usize = 14;
const FW_PATCH_OFFSET: usize = 15;
const CAPABILITIES_OFFSET: usize = 16;
const NAME_OFFSET: usize = 20;
const NAME_LEN: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub device_id: u32,
    pub protocol_version: u8,
    pub firmware_major: u8,
    pub firmware_minor: u8,
    pub firmware_patch: u8,
    pub capabilities: u32,
    pub device_name: String,
}

impl DeviceInfo {
    pub fn encode_into_packet(&self, packet: &mut [u8]) {
        packet[DEVICE_ID_OFFSET..DEVICE_ID_OFFSET + 4].copy_from_slice(&self.device_id.to_le_bytes());
        packet[PROTOCOL_VERSION_OFFSET] = self.protocol_version;
        packet[FW_MAJOR_OFFSET] = self.firmware_major;
        packet[FW_MINOR_OFFSET] = self.firmware_minor;
        packet[FW_PATCH_OFFSET] = self.firmware_patch;
        packet[CAPABILITIES_OFFSET..CAPABILITIES_OFFSET + 4].copy_from_slice(&self.capabilities.to_le_bytes());
        packet[NAME_OFFSET..NAME_OFFSET + NAME_LEN].fill(0);
        let bytes = self.device_name.as_bytes();
        let copy_len = bytes.len().min(NAME_LEN);
        packet[NAME_OFFSET..NAME_OFFSET + copy_len].copy_from_slice(&bytes[..copy_len]);
    }

    pub fn from_packet_bytes(packet: &[u8]) -> Self {
        let name_bytes = &packet[NAME_OFFSET..NAME_OFFSET + NAME_LEN];
        let end = name_bytes.iter().position(|byte| *byte == 0).unwrap_or(NAME_LEN);
        Self {
            device_id: u32::from_le_bytes([
                packet[DEVICE_ID_OFFSET],
                packet[DEVICE_ID_OFFSET + 1],
                packet[DEVICE_ID_OFFSET + 2],
                packet[DEVICE_ID_OFFSET + 3],
            ]),
            protocol_version: packet[PROTOCOL_VERSION_OFFSET],
            firmware_major: packet[FW_MAJOR_OFFSET],
            firmware_minor: packet[FW_MINOR_OFFSET],
            firmware_patch: packet[FW_PATCH_OFFSET],
            capabilities: u32::from_le_bytes([
                packet[CAPABILITIES_OFFSET],
                packet[CAPABILITIES_OFFSET + 1],
                packet[CAPABILITIES_OFFSET + 2],
                packet[CAPABILITIES_OFFSET + 3],
            ]),
            device_name: String::from_utf8_lossy(&name_bytes[..end]).trim().to_owned(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::FRAME_SIZE;

    #[test]
    fn device_info_round_trips_through_packet_payload() {
        let info = DeviceInfo {
            device_id: 42,
            protocol_version: 1,
            firmware_major: 2,
            firmware_minor: 3,
            firmware_patch: 4,
            capabilities: 0xA5A5_0001,
            device_name: "esp32-daq".to_owned(),
        };
        let mut packet = [0u8; FRAME_SIZE];

        info.encode_into_packet(&mut packet);
        let restored = DeviceInfo::from_packet_bytes(&packet);

        assert_eq!(restored, info);
    }
}
