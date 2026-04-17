/// Packet type discriminant in the fixed 40-byte UART packet header.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PacketType {
    /// Primary live sensor frame: encoder counts, engine pulse period, AFR.
    Telemetry = 0x01,
    /// Command packet: request the current ESP32 configuration.
    ConfigGet = 0x10,
    /// Command packet: stage a new ESP32 configuration.
    ConfigSet = 0x11,
    /// Command packet: apply the staged configuration.
    ConfigApply = 0x12,
    /// Command packet: liveness probe.
    Ping = 0x13,
    /// Command packet: request device metadata.
    DeviceInfoGet = 0x14,
    /// Response packet: positive acknowledgement.
    Ack = 0x20,
    /// Response packet: command/config error.
    Error = 0x21,
    /// Response packet: returns the current configuration payload.
    ConfigData = 0x22,
    /// Response packet: returns device metadata.
    DeviceInfoData = 0x23,
    /// Unrecognised or future packet type; payload should be skipped.
    Unknown = 0xFF,
}

impl From<u8> for PacketType {
    fn from(v: u8) -> Self {
        match v {
            0x01 => Self::Telemetry,
            0x10 => Self::ConfigGet,
            0x11 => Self::ConfigSet,
            0x12 => Self::ConfigApply,
            0x13 => Self::Ping,
            0x14 => Self::DeviceInfoGet,
            0x20 => Self::Ack,
            0x21 => Self::Error,
            0x22 => Self::ConfigData,
            0x23 => Self::DeviceInfoData,
            _    => Self::Unknown,
        }
    }
}

impl PacketType {
    pub fn is_telemetry(self) -> bool {
        matches!(self, Self::Telemetry)
    }

    pub fn is_command(self) -> bool {
        matches!(
            self,
            Self::ConfigGet | Self::ConfigSet | Self::ConfigApply | Self::Ping | Self::DeviceInfoGet
        )
    }

    pub fn is_response(self) -> bool {
        matches!(self, Self::Ack | Self::Error | Self::ConfigData | Self::DeviceInfoData)
    }
}
