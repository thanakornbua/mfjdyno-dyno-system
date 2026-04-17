//! ESP32 config-management layer for the UART-connected DAQ.
//!
//! Validation boundaries:
//! - UI validation should be immediate and ergonomic, but is advisory only.
//! - Backend validation in this module is authoritative before bytes are sent.
//! - Device validation is final: the ESP32 may still reject a config with an
//!   `ERROR` response based on firmware/runtime constraints.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::time::timeout;
use tracing::{info, warn};

use dyno_protocol::{
    DeviceInfo, DeviceInfoResponse, DynoConfig, ErrorCode, ErrorResponse, PacketType,
    WirePacket,
};

use crate::{config::Config, serial_link::{DynoSerialLink, DynoUartLink}};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PersistedEsp32ConfigState {
    pub synced_at_ms: i64,
    pub device_info: DeviceInfo,
    pub last_known_applied_config: DynoConfig,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Esp32ConfigSyncStatus {
    Unchanged,
    Applied,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Esp32ConfigSyncResult {
    pub device_info: DeviceInfo,
    pub desired_config: DynoConfig,
    pub device_config_before: DynoConfig,
    pub applied_config: DynoConfig,
    pub status: Esp32ConfigSyncStatus,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackendValidationError {
    pub errors: Vec<String>,
}

#[derive(Debug, Error)]
pub enum Esp32ConfigError {
    #[error("failed to load desired ESP32 config from {path}: {source}")]
    DesiredConfigLoad {
        path: String,
        source: anyhow::Error,
    },
    #[error("failed to persist last-known applied ESP32 config to {path}: {source}")]
    AppliedConfigPersist {
        path: String,
        source: anyhow::Error,
    },
    #[error("backend ESP32 config validation failed: {details}")]
    BackendValidation { details: String },
    #[error(
        "refusing dangerous live UART transport change while connected: {details}. \
         update the Pi-side serial settings first, then apply the ESP32 UART change deliberately"
    )]
    DangerousLiveChange { details: String },
    #[error("serial I/O failed during {operation}: {source}")]
    SerialIo {
        operation: &'static str,
        #[source]
        source: io::Error,
    },
    #[error("timed out waiting for {operation} after {timeout_ms} ms")]
    Timeout {
        operation: &'static str,
        timeout_ms: u64,
    },
    #[error("device rejected {request_type:?}: {error_code:?}{message}")]
    DeviceRejected {
        request_type: PacketType,
        error_code: ErrorCode,
        message: String,
    },
    #[error("unexpected ACK status for {request_type:?}: {status_code}")]
    UnexpectedAckStatus {
        request_type: PacketType,
        status_code: u8,
    },
    #[error("ESP32 command retry budget is invalid: retries must be at least 1")]
    InvalidRetryBudget,
}

pub struct Esp32ConfigManager {
    desired_config_path: PathBuf,
    applied_state_path: PathBuf,
    command_timeout: Duration,
    command_retries: u32,
}

impl Esp32ConfigManager {
    pub fn from_runtime_config(config: &Config) -> Self {
        Self {
            desired_config_path: PathBuf::from(&config.esp32_config_path),
            applied_state_path: PathBuf::from(&config.esp32_applied_config_path),
            command_timeout: Duration::from_millis(config.esp32_command_timeout_ms),
            command_retries: config.esp32_command_retries,
        }
    }

    pub async fn synchronize_startup(
        &self,
        serial_path: &str,
        serial_baud: u32,
    ) -> Result<Esp32ConfigSyncResult, Esp32ConfigError> {
        let mut link = DynoUartLink::open(serial_path, serial_baud)
            .map_err(|source| Esp32ConfigError::SerialIo {
                operation: "open serial port for ESP32 config sync",
                source: io::Error::new(io::ErrorKind::Other, source.to_string()),
            })?;
        self.synchronize_with_link(&mut link).await
    }

    pub async fn synchronize_with_link<T>(
        &self,
        link: &mut DynoSerialLink<T>,
    ) -> Result<Esp32ConfigSyncResult, Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        if self.command_retries == 0 {
            return Err(Esp32ConfigError::InvalidRetryBudget);
        }

        let desired_config = self.load_desired_config()?;
        validate_backend_config(&desired_config)
            .map_err(|validation| Esp32ConfigError::BackendValidation {
                details: validation.errors.join("; "),
            })?;

        let device_info = self.request_device_info(link).await?;
        let current_config = self.request_current_config(link).await?;

        if current_config == desired_config {
            self.persist_last_known_applied(&PersistedEsp32ConfigState {
                synced_at_ms: now_ms(),
                device_info: device_info.clone(),
                last_known_applied_config: current_config.clone(),
            })?;
            info!(
                device_name = %device_info.device_name,
                firmware = format_args!(
                    "{}.{}.{}",
                    device_info.firmware_major,
                    device_info.firmware_minor,
                    device_info.firmware_patch
                ),
                "esp32 config already matches desired state"
            );
            return Ok(Esp32ConfigSyncResult {
                device_info,
                desired_config,
                device_config_before: current_config.clone(),
                applied_config: current_config,
                status: Esp32ConfigSyncStatus::Unchanged,
            });
        }

        guard_against_dangerous_live_change(&current_config, &desired_config)?;

        self.push_config(link, desired_config.clone()).await?;
        self.apply_config(link).await?;
        let applied_config = self.request_current_config(link).await?;

        if applied_config != desired_config {
            return Err(Esp32ConfigError::BackendValidation {
                details: "device config still differs after CONFIG_SET + CONFIG_APPLY".to_owned(),
            });
        }

        self.persist_last_known_applied(&PersistedEsp32ConfigState {
            synced_at_ms: now_ms(),
            device_info: device_info.clone(),
            last_known_applied_config: applied_config.clone(),
        })?;

        Ok(Esp32ConfigSyncResult {
            device_info,
            desired_config,
            device_config_before: current_config,
            applied_config,
            status: Esp32ConfigSyncStatus::Applied,
        })
    }

    pub fn load_desired_config(&self) -> Result<DynoConfig, Esp32ConfigError> {
        load_config_file(&self.desired_config_path).map_err(|source| Esp32ConfigError::DesiredConfigLoad {
            path: self.desired_config_path.display().to_string(),
            source,
        })
    }

    pub fn load_last_known_applied(&self) -> Result<Option<PersistedEsp32ConfigState>, Esp32ConfigError> {
        load_optional_state_file(&self.applied_state_path).map_err(|source| Esp32ConfigError::AppliedConfigPersist {
            path: self.applied_state_path.display().to_string(),
            source,
        })
    }

    fn persist_last_known_applied(
        &self,
        state: &PersistedEsp32ConfigState,
    ) -> Result<(), Esp32ConfigError> {
        write_json_file(&self.applied_state_path, state).map_err(|source| Esp32ConfigError::AppliedConfigPersist {
            path: self.applied_state_path.display().to_string(),
            source,
        })
    }

    async fn request_device_info<T>(&self, link: &mut DynoSerialLink<T>) -> Result<DeviceInfo, Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let mut last_error = None;
        for attempt in 1..=self.command_retries {
            match self.request_device_info_once(link).await {
                Ok(info) => return Ok(info),
                Err(err) if err.is_retryable() && attempt < self.command_retries => {
                    warn!("esp32 config: device info request attempt {attempt} failed: {err}");
                    last_error = Some(err);
                }
                Err(err) => return Err(err),
            }
        }
        Err(last_error.expect("retry loop should retain the last error"))
    }

    async fn request_current_config<T>(&self, link: &mut DynoSerialLink<T>) -> Result<DynoConfig, Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let mut last_error = None;
        for attempt in 1..=self.command_retries {
            match self.request_current_config_once(link).await {
                Ok(config) => return Ok(config),
                Err(err) if err.is_retryable() && attempt < self.command_retries => {
                    warn!("esp32 config: current-config request attempt {attempt} failed: {err}");
                    last_error = Some(err);
                }
                Err(err) => return Err(err),
            }
        }
        Err(last_error.expect("retry loop should retain the last error"))
    }

    async fn push_config<T>(
        &self,
        link: &mut DynoSerialLink<T>,
        config: DynoConfig,
    ) -> Result<(), Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let mut last_error = None;
        for attempt in 1..=self.command_retries {
            match self.push_config_once(link, config.clone()).await {
                Ok(()) => return Ok(()),
                Err(err) if err.is_retryable() && attempt < self.command_retries => {
                    warn!("esp32 config: CONFIG_SET attempt {attempt} failed: {err}");
                    last_error = Some(err);
                }
                Err(err) => return Err(err),
            }
        }
        Err(last_error.expect("retry loop should retain the last error"))
    }

    async fn apply_config<T>(&self, link: &mut DynoSerialLink<T>) -> Result<(), Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let mut last_error = None;
        for attempt in 1..=self.command_retries {
            match self.apply_config_once(link).await {
                Ok(()) => return Ok(()),
                Err(err) if err.is_retryable() && attempt < self.command_retries => {
                    warn!("esp32 config: CONFIG_APPLY attempt {attempt} failed: {err}");
                    last_error = Some(err);
                }
                Err(err) => return Err(err),
            }
        }
        Err(last_error.expect("retry loop should retain the last error"))
    }

    async fn request_device_info_once<T>(&self, link: &mut DynoSerialLink<T>) -> Result<DeviceInfo, Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let seq = link
            .send_device_info_get()
            .await
            .map_err(|source| Esp32ConfigError::SerialIo {
                operation: "send DEVICE_INFO_GET",
                source,
            })?;

        self.read_matching(link, "receive DEVICE_INFO_DATA", move |packet| match packet {
            WirePacket::DeviceInfo(DeviceInfoResponse { seq: response_seq, device_info }) if response_seq == seq => {
                Some(Ok(device_info))
            }
            WirePacket::Error(ErrorResponse { seq: response_seq, request_type, error_code, message }) if response_seq == seq => {
                Some(Err(Esp32ConfigError::DeviceRejected {
                    request_type,
                    error_code,
                    message: message.map(|message| format!(": {message}")).unwrap_or_default(),
                }))
            }
            _ => None,
        }).await
    }

    async fn request_current_config_once<T>(&self, link: &mut DynoSerialLink<T>) -> Result<DynoConfig, Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let seq = link
            .send_config_get()
            .await
            .map_err(|source| Esp32ConfigError::SerialIo {
                operation: "send CONFIG_GET",
                source,
            })?;

        self.read_matching(link, "receive CONFIG_DATA", move |packet| match packet {
            WirePacket::Config(response) if response.seq == seq => Some(Ok(response.config)),
            WirePacket::Error(ErrorResponse { seq: response_seq, request_type, error_code, message }) if response_seq == seq => {
                Some(Err(Esp32ConfigError::DeviceRejected {
                    request_type,
                    error_code,
                    message: message.map(|message| format!(": {message}")).unwrap_or_default(),
                }))
            }
            _ => None,
        }).await
    }

    async fn push_config_once<T>(
        &self,
        link: &mut DynoSerialLink<T>,
        config: DynoConfig,
    ) -> Result<(), Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let seq = link
            .send_config_set(config)
            .await
            .map_err(|source| Esp32ConfigError::SerialIo {
                operation: "send CONFIG_SET",
                source,
            })?;

        self.read_matching(link, "receive ACK for CONFIG_SET", move |packet| match packet {
            WirePacket::Ack(response) if response.seq == seq => {
                if response.status_code == 0 {
                    Some(Ok(()))
                } else {
                    Some(Err(Esp32ConfigError::UnexpectedAckStatus {
                        request_type: response.request_type,
                        status_code: response.status_code,
                    }))
                }
            }
            WirePacket::Error(ErrorResponse { seq: response_seq, request_type, error_code, message }) if response_seq == seq => {
                Some(Err(Esp32ConfigError::DeviceRejected {
                    request_type,
                    error_code,
                    message: message.map(|message| format!(": {message}")).unwrap_or_default(),
                }))
            }
            _ => None,
        }).await
    }

    async fn apply_config_once<T>(&self, link: &mut DynoSerialLink<T>) -> Result<(), Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
    {
        let seq = link
            .send_config_apply()
            .await
            .map_err(|source| Esp32ConfigError::SerialIo {
                operation: "send CONFIG_APPLY",
                source,
            })?;

        self.read_matching(link, "receive ACK for CONFIG_APPLY", move |packet| match packet {
            WirePacket::Ack(response) if response.seq == seq => {
                if response.status_code == 0 {
                    Some(Ok(()))
                } else {
                    Some(Err(Esp32ConfigError::UnexpectedAckStatus {
                        request_type: response.request_type,
                        status_code: response.status_code,
                    }))
                }
            }
            WirePacket::Error(ErrorResponse { seq: response_seq, request_type, error_code, message }) if response_seq == seq => {
                Some(Err(Esp32ConfigError::DeviceRejected {
                    request_type,
                    error_code,
                    message: message.map(|message| format!(": {message}")).unwrap_or_default(),
                }))
            }
            _ => None,
        }).await
    }

    async fn read_matching<T, R, F>(
        &self,
        link: &mut DynoSerialLink<T>,
        operation: &'static str,
        mut matcher: F,
    ) -> Result<R, Esp32ConfigError>
    where
        T: AsyncRead + AsyncWrite + Unpin,
        F: FnMut(WirePacket) -> Option<Result<R, Esp32ConfigError>>,
    {
        timeout(self.command_timeout, async {
            loop {
                let packet = link.read_packet().await.map_err(|source| Esp32ConfigError::SerialIo {
                    operation,
                    source,
                })?;
                if let Some(result) = matcher(packet) {
                    return result;
                }
            }
        })
        .await
        .map_err(|_| Esp32ConfigError::Timeout {
            operation,
            timeout_ms: self.command_timeout.as_millis() as u64,
        })?
    }
}

impl Esp32ConfigError {
    fn is_retryable(&self) -> bool {
        matches!(self, Self::Timeout { .. } | Self::SerialIo { .. })
    }
}

pub fn validate_backend_config(config: &DynoConfig) -> Result<(), BackendValidationError> {
    let mut errors = Vec::new();

    validate_pin("engine_pulse_pin", config.engine_pulse_pin, &mut errors);
    validate_pin("encoder_pin_a", config.encoder_pin_a, &mut errors);
    validate_pin("can_rx_pin", config.can_rx_pin, &mut errors);
    validate_pin("can_tx_pin", config.can_tx_pin, &mut errors);
    validate_pin("uart_tx_pin", config.uart_tx_pin, &mut errors);
    validate_pin("uart_rx_pin", config.uart_rx_pin, &mut errors);

    if !config.engine_pulses_per_rev.is_finite() || config.engine_pulses_per_rev <= 0.0 {
        errors.push("engine_pulses_per_rev must be positive".to_owned());
    }
    if config.encoder_ppr == 0 {
        errors.push("encoder_ppr must be greater than zero".to_owned());
    }
    if !(10_000..=1_000_000).contains(&config.can_bitrate) {
        errors.push("can_bitrate must be within 10_000..=1_000_000".to_owned());
    }
    if !(9_600..=2_000_000).contains(&config.uart_baud) {
        errors.push("uart_baud must be within 9_600..=2_000_000".to_owned());
    }
    if !(1..=500).contains(&config.telemetry_rate_hz) {
        errors.push("telemetry_rate_hz must be within 1..=500".to_owned());
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(BackendValidationError { errors })
    }
}

fn guard_against_dangerous_live_change(
    current: &DynoConfig,
    desired: &DynoConfig,
) -> Result<(), Esp32ConfigError> {
    let mut dangerous = Vec::new();
    if current.uart_tx_pin != desired.uart_tx_pin {
        dangerous.push(format!("uart_tx_pin {} -> {}", current.uart_tx_pin, desired.uart_tx_pin));
    }
    if current.uart_rx_pin != desired.uart_rx_pin {
        dangerous.push(format!("uart_rx_pin {} -> {}", current.uart_rx_pin, desired.uart_rx_pin));
    }
    if current.uart_baud != desired.uart_baud {
        dangerous.push(format!("uart_baud {} -> {}", current.uart_baud, desired.uart_baud));
    }
    if dangerous.is_empty() {
        Ok(())
    } else {
        Err(Esp32ConfigError::DangerousLiveChange {
            details: dangerous.join(", "),
        })
    }
}

fn validate_pin(name: &str, value: u8, errors: &mut Vec<String>) {
    if value > 39 {
        errors.push(format!("{name} must be within 0..=39"));
    }
}

fn load_config_file(path: &Path) -> Result<DynoConfig, anyhow::Error> {
    let raw = fs::read_to_string(path)
        .map_err(anyhow::Error::from)
        .map_err(|source| anyhow::anyhow!("read {}: {source}", path.display()))?;
    serde_json::from_str(&raw)
        .map_err(anyhow::Error::from)
        .map_err(|source| anyhow::anyhow!("parse {}: {source}", path.display()))
}

fn load_optional_state_file(path: &Path) -> Result<Option<PersistedEsp32ConfigState>, anyhow::Error> {
    match fs::read_to_string(path) {
        Ok(raw) => serde_json::from_str(&raw)
            .map(Some)
            .map_err(anyhow::Error::from)
            .map_err(|source| anyhow::anyhow!("parse {}: {source}", path.display())),
        Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(err) => Err(anyhow::anyhow!("read {}: {err}", path.display())),
    }
}

fn write_json_file<T: Serialize>(path: &Path, value: &T) -> Result<(), anyhow::Error> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)?;
        }
    }
    let json = serde_json::to_string_pretty(value)?;
    let tmp_path = path.with_extension("tmp");
    fs::write(&tmp_path, json)?;
    fs::rename(&tmp_path, path)?;
    Ok(())
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[cfg(test)]
mod tests {
    use super::*;
    use dyno_protocol::{DeviceInfo, EngineEdgeMode, PacketType, crc16_ccitt, FRAME_SIZE, MAGIC};
    use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};

    fn sample_config() -> DynoConfig {
        DynoConfig {
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
        }
    }

    fn sample_device_info() -> DeviceInfo {
        DeviceInfo {
            device_id: 42,
            protocol_version: 1,
            firmware_major: 1,
            firmware_minor: 2,
            firmware_patch: 3,
            capabilities: 0x0000_0001,
            device_name: "esp32-daq".to_owned(),
        }
    }

    fn manager_with_paths(config_path: &Path, state_path: &Path) -> Esp32ConfigManager {
        Esp32ConfigManager {
            desired_config_path: config_path.to_path_buf(),
            applied_state_path: state_path.to_path_buf(),
            command_timeout: Duration::from_millis(500),
            command_retries: 2,
        }
    }

    fn make_ack(seq: u32, request_type: PacketType) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::Ack as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        packet[8] = request_type as u8;
        let crc = crc16_ccitt(&packet[..FRAME_SIZE - 2]);
        packet[FRAME_SIZE - 2..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    fn make_config_data(seq: u32, config: &DynoConfig) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::ConfigData as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        config.encode_into_packet(&mut packet);
        let crc = crc16_ccitt(&packet[..FRAME_SIZE - 2]);
        packet[FRAME_SIZE - 2..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    fn make_device_info_data(seq: u32, info: &DeviceInfo) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::DeviceInfoData as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        info.encode_into_packet(&mut packet);
        let crc = crc16_ccitt(&packet[..FRAME_SIZE - 2]);
        packet[FRAME_SIZE - 2..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    fn make_error(
        seq: u32,
        request_type: PacketType,
        error_code: ErrorCode,
        message: &str,
    ) -> [u8; FRAME_SIZE] {
        let mut packet = [0u8; FRAME_SIZE];
        packet[0..2].copy_from_slice(&MAGIC.to_le_bytes());
        packet[2] = 1;
        packet[3] = PacketType::Error as u8;
        packet[4..8].copy_from_slice(&seq.to_le_bytes());
        packet[8] = request_type as u8;
        packet[9] = error_code as u8;
        let bytes = message.as_bytes();
        let copy_len = bytes.len().min(26);
        packet[12..12 + copy_len].copy_from_slice(&bytes[..copy_len]);
        let crc = crc16_ccitt(&packet[..FRAME_SIZE - 2]);
        packet[FRAME_SIZE - 2..FRAME_SIZE].copy_from_slice(&crc.to_le_bytes());
        packet
    }

    #[test]
    fn backend_validation_rejects_bad_values() {
        let mut config = sample_config();
        config.encoder_ppr = 0;
        config.telemetry_rate_hz = 0;

        let err = validate_backend_config(&config).expect_err("validation failure");
        assert_eq!(err.errors.len(), 2);
    }

    #[test]
    fn persistence_round_trips() {
        let unique = now_ms();
        let path = std::env::temp_dir().join(format!("esp32-state-{unique}.json"));
        let state = PersistedEsp32ConfigState {
            synced_at_ms: unique,
            device_info: sample_device_info(),
            last_known_applied_config: sample_config(),
        };

        write_json_file(&path, &state).expect("write state");
        let restored = load_optional_state_file(&path)
            .expect("load state")
            .expect("persisted state");

        assert_eq!(restored, state);
        let _ = fs::remove_file(path);
    }

    #[tokio::test]
    async fn startup_sync_applies_changed_config_and_persists_state() {
        let unique = now_ms();
        let desired_path = std::env::temp_dir().join(format!("esp32-desired-{unique}.json"));
        let state_path = std::env::temp_dir().join(format!("esp32-state-{unique}.json"));
        let desired = sample_config();
        let desired_for_server = desired.clone();
        let mut current = sample_config();
        current.telemetry_rate_hz = 25;
        write_json_file(&desired_path, &desired).expect("write desired config");

        let manager = manager_with_paths(&desired_path, &state_path);
        let (client, mut server) = duplex(1024);
        tokio::spawn(async move {
            let mut buf = [0u8; FRAME_SIZE];

            server.read_exact(&mut buf).await.expect("device info get");
            server.write_all(&make_device_info_data(1, &sample_device_info())).await.expect("device info response");

            server.read_exact(&mut buf).await.expect("config get");
            server.write_all(&make_config_data(2, &current)).await.expect("config response");

            server.read_exact(&mut buf).await.expect("config set");
            let pushed = DynoConfig::from_packet_bytes(&buf);
            assert_eq!(pushed, desired_for_server);
            server.write_all(&make_ack(3, PacketType::ConfigSet)).await.expect("config set ack");

            server.read_exact(&mut buf).await.expect("config apply");
            server.write_all(&make_ack(4, PacketType::ConfigApply)).await.expect("config apply ack");

            server.read_exact(&mut buf).await.expect("config get after apply");
            server.write_all(&make_config_data(5, &desired_for_server)).await.expect("final config response");
        });

        let mut link = DynoSerialLink::new(client);
        let result = manager.synchronize_with_link(&mut link).await.expect("sync result");

        assert_eq!(result.status, Esp32ConfigSyncStatus::Applied);
        assert_eq!(result.applied_config, desired);
        let persisted = manager
            .load_last_known_applied()
            .expect("load applied state")
            .expect("persisted state");
        assert_eq!(persisted.last_known_applied_config, result.applied_config);

        let _ = fs::remove_file(desired_path);
        let _ = fs::remove_file(state_path);
    }

    #[tokio::test]
    async fn startup_sync_noops_when_device_already_matches_desired_config() {
        let unique = now_ms();
        let desired_path = std::env::temp_dir().join(format!("esp32-desired-unchanged-{unique}.json"));
        let state_path = std::env::temp_dir().join(format!("esp32-state-unchanged-{unique}.json"));
        let desired = sample_config();
        write_json_file(&desired_path, &desired).expect("write desired config");

        let manager = manager_with_paths(&desired_path, &state_path);
        let (client, mut server) = duplex(1024);
        tokio::spawn(async move {
            let mut buf = [0u8; FRAME_SIZE];

            server.read_exact(&mut buf).await.expect("device info get");
            server.write_all(&make_device_info_data(1, &sample_device_info())).await.expect("device info response");

            server.read_exact(&mut buf).await.expect("config get");
            server.write_all(&make_config_data(2, &desired)).await.expect("config response");
        });

        let mut link = DynoSerialLink::new(client);
        let result = manager.synchronize_with_link(&mut link).await.expect("sync result");

        assert_eq!(result.status, Esp32ConfigSyncStatus::Unchanged);
        assert_eq!(result.device_config_before, result.applied_config);
        assert_eq!(result.applied_config, sample_config());

        let persisted = manager
            .load_last_known_applied()
            .expect("load applied state")
            .expect("persisted state");
        assert_eq!(persisted.last_known_applied_config, sample_config());

        let _ = fs::remove_file(desired_path);
        let _ = fs::remove_file(state_path);
    }

    #[tokio::test]
    async fn startup_sync_retries_after_timeout_and_surfaces_device_error() {
        let unique = now_ms();
        let desired_path = std::env::temp_dir().join(format!("esp32-desired-retry-{unique}.json"));
        let state_path = std::env::temp_dir().join(format!("esp32-state-retry-{unique}.json"));
        write_json_file(&desired_path, &sample_config()).expect("write desired config");

        let manager = Esp32ConfigManager {
            desired_config_path: desired_path.clone(),
            applied_state_path: state_path.clone(),
            command_timeout: Duration::from_millis(25),
            command_retries: 2,
        };

        let (client, mut server) = duplex(1024);
        tokio::spawn(async move {
            let mut buf = [0u8; FRAME_SIZE];

            server.read_exact(&mut buf).await.expect("device info get attempt 1");

            server.read_exact(&mut buf).await.expect("device info get attempt 2");
            server
                .write_all(&make_error(
                    2,
                    PacketType::DeviceInfoGet,
                    ErrorCode::Busy,
                    "device busy",
                ))
                .await
                .expect("device info error response");
        });

        let mut link = DynoSerialLink::new(client);
        let err = manager
            .synchronize_with_link(&mut link)
            .await
            .expect_err("sync should fail");

        match err {
            Esp32ConfigError::DeviceRejected { request_type, error_code, message } => {
                assert_eq!(request_type, PacketType::DeviceInfoGet);
                assert_eq!(error_code, ErrorCode::Busy);
                assert_eq!(message, ": device busy");
            }
            other => panic!("unexpected error: {other:?}"),
        }

        let _ = fs::remove_file(desired_path);
        let _ = fs::remove_file(state_path);
    }

    #[tokio::test]
    async fn startup_sync_rejects_dangerous_uart_change() {
        let unique = now_ms();
        let desired_path = std::env::temp_dir().join(format!("esp32-desired-danger-{unique}.json"));
        let state_path = std::env::temp_dir().join(format!("esp32-state-danger-{unique}.json"));
        let mut desired = sample_config();
        desired.uart_baud = 115_200;
        write_json_file(&desired_path, &desired).expect("write desired config");
        let manager = manager_with_paths(&desired_path, &state_path);

        let (client, mut server) = duplex(512);
        tokio::spawn(async move {
            let mut buf = [0u8; FRAME_SIZE];
            server.read_exact(&mut buf).await.expect("device info get");
            server.write_all(&make_device_info_data(1, &sample_device_info())).await.expect("device info response");
            server.read_exact(&mut buf).await.expect("config get");
            server.write_all(&make_config_data(2, &sample_config())).await.expect("config response");
        });

        let mut link = DynoSerialLink::new(client);
        let err = manager
            .synchronize_with_link(&mut link)
            .await
            .expect_err("dangerous change should fail");
        assert!(matches!(err, Esp32ConfigError::DangerousLiveChange { .. }));

        let _ = fs::remove_file(desired_path);
        let _ = fs::remove_file(state_path);
    }
}
