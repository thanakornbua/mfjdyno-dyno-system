//! Fixed-width configuration payload shared by CONFIG_SET / CONFIG_DATA packets.

use serde::{Deserialize, Serialize};

/// Byte offset of the config payload in CONFIG_SET / CONFIG_DATA packets.
pub const CONFIG_OFFSET: usize = 8;
/// Number of config bytes carried in a fixed packet.
pub const CONFIG_SIZE: usize = 30;
const ENGINE_PULSE_PIN_OFFSET: usize = CONFIG_OFFSET;
const ENGINE_PULSES_PER_REV_OFFSET: usize = CONFIG_OFFSET + 1;
const ENGINE_EDGE_MODE_OFFSET: usize = CONFIG_OFFSET + 5;
const ENCODER_PIN_A_OFFSET: usize = CONFIG_OFFSET + 6;
const ENCODER_PPR_OFFSET: usize = CONFIG_OFFSET + 7;
const CAN_RX_PIN_OFFSET: usize = CONFIG_OFFSET + 9;
const CAN_TX_PIN_OFFSET: usize = CONFIG_OFFSET + 10;
const CAN_BITRATE_OFFSET: usize = CONFIG_OFFSET + 11;
const UART_TX_PIN_OFFSET: usize = CONFIG_OFFSET + 15;
const UART_RX_PIN_OFFSET: usize = CONFIG_OFFSET + 16;
const UART_BAUD_OFFSET: usize = CONFIG_OFFSET + 17;
const TELEMETRY_RATE_HZ_OFFSET: usize = CONFIG_OFFSET + 21;
const RESERVED_OFFSET: usize = CONFIG_OFFSET + 23;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[repr(u8)]
pub enum EngineEdgeMode {
    Rising = 0,
    Falling = 1,
    Both = 2,
}

impl From<u8> for EngineEdgeMode {
    fn from(value: u8) -> Self {
        match value {
            1 => Self::Falling,
            2 => Self::Both,
            _ => Self::Rising,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DynoConfig {
    pub engine_pulse_pin: u8,
    pub engine_pulses_per_rev: f32,
    pub engine_edge_mode: EngineEdgeMode,
    pub encoder_pin_a: u8,
    pub encoder_ppr: u16,
    pub can_rx_pin: u8,
    pub can_tx_pin: u8,
    pub can_bitrate: u32,
    pub uart_tx_pin: u8,
    pub uart_rx_pin: u8,
    pub uart_baud: u32,
    pub telemetry_rate_hz: u16,
}

impl DynoConfig {
    pub fn encode_into_packet(&self, packet: &mut [u8]) {
        packet[ENGINE_PULSE_PIN_OFFSET] = self.engine_pulse_pin;
        packet[ENGINE_PULSES_PER_REV_OFFSET..ENGINE_PULSES_PER_REV_OFFSET + 4]
            .copy_from_slice(&self.engine_pulses_per_rev.to_le_bytes());
        packet[ENGINE_EDGE_MODE_OFFSET] = self.engine_edge_mode as u8;
        packet[ENCODER_PIN_A_OFFSET] = self.encoder_pin_a;
        packet[ENCODER_PPR_OFFSET..ENCODER_PPR_OFFSET + 2].copy_from_slice(&self.encoder_ppr.to_le_bytes());
        packet[CAN_RX_PIN_OFFSET] = self.can_rx_pin;
        packet[CAN_TX_PIN_OFFSET] = self.can_tx_pin;
        packet[CAN_BITRATE_OFFSET..CAN_BITRATE_OFFSET + 4].copy_from_slice(&self.can_bitrate.to_le_bytes());
        packet[UART_TX_PIN_OFFSET] = self.uart_tx_pin;
        packet[UART_RX_PIN_OFFSET] = self.uart_rx_pin;
        packet[UART_BAUD_OFFSET..UART_BAUD_OFFSET + 4].copy_from_slice(&self.uart_baud.to_le_bytes());
        packet[TELEMETRY_RATE_HZ_OFFSET..TELEMETRY_RATE_HZ_OFFSET + 2]
            .copy_from_slice(&self.telemetry_rate_hz.to_le_bytes());
        packet[RESERVED_OFFSET..CONFIG_OFFSET + CONFIG_SIZE].fill(0);
    }

    pub fn from_packet_bytes(packet: &[u8]) -> Self {
        Self {
            engine_pulse_pin: packet[ENGINE_PULSE_PIN_OFFSET],
            engine_pulses_per_rev: f32::from_le_bytes([
                packet[ENGINE_PULSES_PER_REV_OFFSET],
                packet[ENGINE_PULSES_PER_REV_OFFSET + 1],
                packet[ENGINE_PULSES_PER_REV_OFFSET + 2],
                packet[ENGINE_PULSES_PER_REV_OFFSET + 3],
            ]),
            engine_edge_mode: EngineEdgeMode::from(packet[ENGINE_EDGE_MODE_OFFSET]),
            encoder_pin_a: packet[ENCODER_PIN_A_OFFSET],
            encoder_ppr: u16::from_le_bytes([packet[ENCODER_PPR_OFFSET], packet[ENCODER_PPR_OFFSET + 1]]),
            can_rx_pin: packet[CAN_RX_PIN_OFFSET],
            can_tx_pin: packet[CAN_TX_PIN_OFFSET],
            can_bitrate: u32::from_le_bytes([
                packet[CAN_BITRATE_OFFSET],
                packet[CAN_BITRATE_OFFSET + 1],
                packet[CAN_BITRATE_OFFSET + 2],
                packet[CAN_BITRATE_OFFSET + 3],
            ]),
            uart_tx_pin: packet[UART_TX_PIN_OFFSET],
            uart_rx_pin: packet[UART_RX_PIN_OFFSET],
            uart_baud: u32::from_le_bytes([
                packet[UART_BAUD_OFFSET],
                packet[UART_BAUD_OFFSET + 1],
                packet[UART_BAUD_OFFSET + 2],
                packet[UART_BAUD_OFFSET + 3],
            ]),
            telemetry_rate_hz: u16::from_le_bytes([
                packet[TELEMETRY_RATE_HZ_OFFSET],
                packet[TELEMETRY_RATE_HZ_OFFSET + 1],
            ]),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::FRAME_SIZE;

    #[test]
    fn config_round_trips_through_packet_payload() {
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
        let mut packet = [0u8; FRAME_SIZE];

        config.encode_into_packet(&mut packet);
        let restored = DynoConfig::from_packet_bytes(&packet);

        assert_eq!(restored, config);
    }
}
