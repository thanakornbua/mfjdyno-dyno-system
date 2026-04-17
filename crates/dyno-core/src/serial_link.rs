//! Bidirectional serial-link wrapper for dyno UART traffic.
//!
//! `DynoSerialLink` owns an async byte transport plus a fixed-length packet
//! decoder. It can read mixed inbound packets (telemetry, ack/error, config
//! responses) and send outbound command packets.

use std::io;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use dyno_protocol::{
    CommandPacket, DynoConfig, DynoFrameV1, PacketDecodeStatus, PacketDecoder, WirePacket,
};

/// Read buffer sized for several UART packets per syscall at 921_600 baud.
const READ_BUF_SIZE: usize = 1024;

pub type DynoUartLink = DynoSerialLink<tokio_serial::SerialStream>;

pub struct DynoSerialLink<T> {
    transport: T,
    decoder: PacketDecoder,
    next_seq: u32,
}

impl DynoUartLink {
    pub fn open(path: &str, baud: u32) -> tokio_serial::Result<Self> {
        Ok(Self::new(open_port(path, baud)?))
    }
}

impl<T> DynoSerialLink<T>
where
    T: AsyncRead + AsyncWrite + Unpin,
{
    pub fn new(transport: T) -> Self {
        Self {
            transport,
            decoder: PacketDecoder::new(),
            next_seq: 1,
        }
    }

    pub async fn read_packet(&mut self) -> io::Result<WirePacket> {
        let mut buf = [0u8; READ_BUF_SIZE];

        loop {
            match self.decoder.decode_next() {
                Ok(PacketDecodeStatus::Packet(packet)) => return Ok(packet),
                Ok(PacketDecodeStatus::BudgetExhausted) => {
                    tokio::task::yield_now().await;
                    continue;
                }
                Ok(PacketDecodeStatus::NeedMoreData) => {}
                Err(err) => {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, err.to_string()));
                }
            }

            let read = self.transport.read(&mut buf).await?;
            if read == 0 {
                tokio::time::sleep(Duration::from_millis(10)).await;
                continue;
            }
            self.decoder.feed(&buf[..read]);
        }
    }

    pub async fn read_telemetry_frame(&mut self) -> io::Result<DynoFrameV1> {
        loop {
            match self.read_packet().await? {
                WirePacket::Telemetry(frame) => return Ok(frame),
                WirePacket::Ack(_) | WirePacket::Error(_) | WirePacket::Config(_) | WirePacket::DeviceInfo(_) => continue,
            }
        }
    }

    pub async fn send_command(&mut self, command: CommandPacket) -> io::Result<u32> {
        let seq = command.seq();
        let bytes = command.encode();
        self.transport.write_all(&bytes).await?;
        self.transport.flush().await?;
        Ok(seq)
    }

    pub async fn send_ping(&mut self) -> io::Result<u32> {
        let seq = self.allocate_seq();
        self.send_command(CommandPacket::Ping { seq }).await
    }

    pub async fn send_config_get(&mut self) -> io::Result<u32> {
        let seq = self.allocate_seq();
        self.send_command(CommandPacket::ConfigGet { seq }).await
    }

    pub async fn send_config_set(&mut self, config: DynoConfig) -> io::Result<u32> {
        let seq = self.allocate_seq();
        self.send_command(CommandPacket::ConfigSet { seq, config }).await
    }

    pub async fn send_config_apply(&mut self) -> io::Result<u32> {
        let seq = self.allocate_seq();
        self.send_command(CommandPacket::ConfigApply { seq }).await
    }

    pub async fn send_device_info_get(&mut self) -> io::Result<u32> {
        let seq = self.allocate_seq();
        self.send_command(CommandPacket::DeviceInfoGet { seq }).await
    }

    fn allocate_seq(&mut self) -> u32 {
        let seq = self.next_seq;
        self.next_seq = self.next_seq.wrapping_add(1).max(1);
        seq
    }
}

/// Open the serial port with explicit settings for the ESP32 UART link.
pub fn open_port(path: &str, baud: u32) -> tokio_serial::Result<tokio_serial::SerialStream> {
    use tokio_serial::{DataBits, FlowControl, Parity, SerialPortBuilderExt, StopBits};

    tokio_serial::new(path, baud)
        .data_bits(DataBits::Eight)
        .flow_control(FlowControl::None)
        .parity(Parity::None)
        .stop_bits(StopBits::One)
        .open_native_async()
}

#[cfg(test)]
mod tests {
    use super::*;
    use dyno_protocol::{
        AckResponse, DynoConfig, EngineEdgeMode, PacketType, WirePacket, crc16_ccitt, FRAME_SIZE,
        MAGIC,
    };
    use tokio::io::duplex;

    fn make_ack_packet(seq: u32) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::Ack as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        packet[8] = PacketType::Ping as u8;
        packet[12..16].copy_from_slice(b"pong");
        let crc = crc16_ccitt(&packet[..FRAME_SIZE - 2]);
        packet[FRAME_SIZE - 2..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    fn make_telemetry_packet(seq: u32, ts_us: u32, engine_period_us: u32) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::Telemetry as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        packet[8..12].copy_from_slice(&ts_us.to_le_bytes());
        packet[12..16].copy_from_slice(&1000u32.to_le_bytes());
        packet[16..20].copy_from_slice(&100u32.to_le_bytes());
        packet[20..24].copy_from_slice(&engine_period_us.to_le_bytes());
        packet[24..26].copy_from_slice(&10u16.to_le_bytes());
        packet[28..30].copy_from_slice(&1380i16.to_le_bytes());
        packet[30..32].copy_from_slice(&939i16.to_le_bytes());
        let crc = crc16_ccitt(&packet[..FRAME_SIZE - 2]);
        packet[FRAME_SIZE - 2..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    #[tokio::test]
    async fn send_ping_writes_fixed_length_command_packet() {
        let (client, mut server) = duplex(256);
        let mut link = DynoSerialLink::new(client);

        let seq = link.send_ping().await.expect("send ping");
        let mut buf = [0u8; FRAME_SIZE];
        server.read_exact(&mut buf).await.expect("read command");

        assert_eq!(seq, 1);
        assert_eq!(&buf[0..2], &MAGIC.to_le_bytes());
        assert_eq!(buf[3], PacketType::Ping as u8);
    }

    #[tokio::test]
    async fn read_packet_returns_ack_response() {
        let (client, mut server) = duplex(256);
        let mut link = DynoSerialLink::new(client);
        let ack = make_ack_packet(9);

        tokio::spawn(async move {
            server.write_all(&ack).await.expect("write ack");
        });

        match link.read_packet().await.expect("read packet") {
            WirePacket::Ack(AckResponse { seq, request_type, .. }) => {
                assert_eq!(seq, 9);
                assert_eq!(request_type, PacketType::Ping);
            }
            other => panic!("unexpected packet: {other:?}"),
        }
    }

    #[tokio::test]
    async fn read_telemetry_skips_non_telemetry_packets() {
        let (client, mut server) = duplex(512);
        let mut link = DynoSerialLink::new(client);
        let ack = make_ack_packet(1);
        let telemetry = make_telemetry_packet(7, 1000, 8000);

        tokio::spawn(async move {
            server.write_all(&ack).await.expect("write ack");
            server.write_all(&telemetry).await.expect("write telemetry");
        });

        let frame = link.read_telemetry_frame().await.expect("read telemetry");
        assert_eq!(frame.seq, 7);
    }

    #[tokio::test]
    async fn send_config_set_writes_config_payload() {
        let (client, mut server) = duplex(256);
        let mut link = DynoSerialLink::new(client);
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

        let _ = link.send_config_set(config.clone()).await.expect("send config");
        let mut buf = [0u8; FRAME_SIZE];
        server.read_exact(&mut buf).await.expect("read config");

        let restored = DynoConfig::from_packet_bytes(&buf);
        assert_eq!(restored, config);
    }

    #[tokio::test]
    async fn send_device_info_get_writes_command_packet() {
        let (client, mut server) = duplex(256);
        let mut link = DynoSerialLink::new(client);

        let seq = link.send_device_info_get().await.expect("send device info get");
        let mut buf = [0u8; FRAME_SIZE];
        server.read_exact(&mut buf).await.expect("read command");

        assert_eq!(seq, 1);
        assert_eq!(buf[3], PacketType::DeviceInfoGet as u8);
    }
}
