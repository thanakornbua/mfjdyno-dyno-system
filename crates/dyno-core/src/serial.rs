//! Async UART ingestion task.
//!
//! # Pipeline
//!
//! ```text
//! /dev/ttyUSB0 ──[JSON lines]──► telemetry parser ──[watch::send]──► watch::Sender<DynoFrameV1>
//! ```
//!
//! The task owns the live serial port after startup config sync and reads one
//! newline-delimited ESP32 JSON telemetry object per live frame.
//! The latest frame is forwarded via a `watch` channel, which always holds the
//! most recent value. Readers that are too slow simply observe the latest
//! frame; no frames are queued or dropped by policy.
//!
//! # Cooperative scheduling
//!
//! The read loop yields to the Tokio scheduler every 32 successfully decoded
//! telemetry frames to bound per-burst CPU time.
//!
//! # Shutdown
//!
//! Dropping [`SerialTask`] calls [`JoinHandle::abort`] on the background task.
//! Tokio cancels the pending async read; the `SerialStream` is dropped,
//! closing the file descriptor.  No explicit shutdown signal is needed because
//! this is the only owner of the serial port.
//!
//! If all `watch::Receiver` handles are dropped the send returns an error;
//! the task treats this as a clean shutdown signal.
//!
//! # Retry
//!
//! If the port cannot be opened (device absent, permissions, etc.) the task
//! logs the error and retries after [`OPEN_RETRY_DELAY`].  A read error
//! closes the port and re-enters the open loop with the same delay.

use std::io;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::watch;
use tokio::task::{self, JoinHandle};
use tracing::{error, info, warn};

use dyno_protocol::DynoFrameV1;

use crate::bme280::AmbientSample;
use crate::config::Config;
use crate::esp32_json::{
    parse_json_telemetry_line, telemetry_ambient_or_stub, telemetry_to_frame, JsonTelemetryMapping,
};
use crate::serial_gate::SerialGateWorker;
use crate::serial_link::{discard_buffered_input, open_port};

/// How long to wait before retrying a failed port open.
const OPEN_RETRY_DELAY: Duration = Duration::from_secs(5);
const READ_LINE_TIMEOUT: Duration = Duration::from_millis(500);
const MAX_CONSECUTIVE_FAILURES: u32 = 3;

/// Opening the serial port resets a UART0-connected ESP32 (DTR/RTS toggled by
/// the tty driver on open). Give the boot ROM + sketch time to settle and
/// flush the resulting boot-banner/garbage bytes before reading lines, so
/// startup noise never counts against `MAX_CONSECUTIVE_FAILURES`.
const POST_OPEN_SETTLE: Duration = Duration::from_millis(1_500);

/// Yield to the Tokio scheduler after this many decoded frames per read burst.
const YIELD_EVERY_N_FRAMES: u64 = 32;

// ── Public handle ─────────────────────────────────────────────────────────────

/// Handle to the background UART reader task.
///
/// Dropping this value aborts the task and closes the serial port.
pub struct SerialTask {
    handle: JoinHandle<()>,
}

impl SerialTask {
    /// Spawn the serial reader.
    ///
    /// Clones the port path and baud rate from `config`; the config reference
    /// is not held after this call returns.
    pub fn spawn(
        config: &Config,
        tx: watch::Sender<DynoFrameV1>,
        ambient_tx: watch::Sender<AmbientSample>,
        gate: SerialGateWorker,
    ) -> Self {
        let port_path = config.serial_port.clone();
        let baud      = config.serial_baud;
        let mapping   = JsonTelemetryMapping::from_runtime_config(config);
        let handle = tokio::spawn(serial_task_outer(port_path, baud, mapping, tx, ambient_tx, gate));
        info!("serial task spawned");
        Self { handle }
    }
}

impl Drop for SerialTask {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

// ── Outer retry loop ──────────────────────────────────────────────────────────

/// Outer loop: open port → run read loop → reopen on I/O error.
/// Returns only when all watch receivers are dropped (App is shutting down).
async fn serial_task_outer(
    port_path: String,
    baud: u32,
    mapping: JsonTelemetryMapping,
    tx: watch::Sender<DynoFrameV1>,
    ambient_tx: watch::Sender<AmbientSample>,
    mut gate: SerialGateWorker,
) {
    loop {
        // Park here while a flash (or other exclusive user) holds the port.
        gate.wait_until_desired_open().await;

        let mut port = match open_json_port(&port_path, baud) {
            Ok(p) => {
                info!("serial: opened {port_path} at {baud} baud");
                p
            }
            Err(e) => {
                error!(
                    "serial: cannot open {port_path} at {baud} baud: {e} \
                     — retrying in {OPEN_RETRY_DELAY:?}"
                );
                tokio::time::sleep(OPEN_RETRY_DELAY).await;
                continue;
            }
        };

        // Opening the port resets the ESP32 (DTR/RTS toggled by the tty
        // driver). Let the boot ROM + sketch settle, then discard whatever
        // boot-banner/garbage bytes accumulated so the reader starts on a
        // clean line boundary.
        tokio::time::sleep(POST_OPEN_SETTLE).await;
        if let Err(e) = discard_buffered_input(&mut port).await {
            warn!("serial: failed to clear input buffer after open: {e}");
        }

        gate.publish_actual(true);
        let exit = serial_read_loop(port, mapping, &tx, &ambient_tx, &mut gate).await;
        gate.publish_actual(false);

        match exit {
            LoopExit::ReceiverDropped => {
                info!("serial: all watch receivers dropped — task stopping");
                return;
            }
            LoopExit::IoError(e) => {
                error!(
                    "serial: read error on {port_path}: {e} \
                     — reopening in {OPEN_RETRY_DELAY:?}"
                );
                tokio::time::sleep(OPEN_RETRY_DELAY).await;
            }
            LoopExit::Suspended => {
                info!("serial: suspended for exclusive port access (e.g. firmware flash)");
            }
        }
    }
}

// ── Inner read loop ───────────────────────────────────────────────────────────

enum LoopExit {
    /// All watch receivers were dropped; no point continuing.
    ReceiverDropped,
    /// A serial I/O error; the caller should reopen the port.
    IoError(io::Error),
    /// The gate requested exclusive access to the port; the caller should
    /// park (not retry-delay) until it's released.
    Suspended,
}

async fn serial_read_loop(
    port: tokio_serial::SerialStream,
    mapping: JsonTelemetryMapping,
    tx: &watch::Sender<DynoFrameV1>,
    ambient_tx: &watch::Sender<AmbientSample>,
    gate: &mut SerialGateWorker,
) -> LoopExit {
    let mut reader = BufReader::new(port);
    let mut total = 0u64;
    let mut consecutive_failures = 0u32;

    loop {
        let mut raw = Vec::new();
        let read = tokio::select! {
            biased;
            _ = gate.wait_until_suspend_requested() => {
                return LoopExit::Suspended;
            }
            result = tokio::time::timeout(READ_LINE_TIMEOUT, reader.read_until(b'\n', &mut raw)) => result,
        };
        let read = match read {
            Ok(Ok(read)) => read,
            Ok(Err(e)) => {
                consecutive_failures += 1;
                if consecutive_failures > MAX_CONSECUTIVE_FAILURES {
                    warn!(
                        failures = consecutive_failures,
                        "serial: repeated line read failures; closing port"
                    );
                    return LoopExit::IoError(e);
                }
                continue;
            }
            Err(_) => {
                consecutive_failures += 1;
                if consecutive_failures > MAX_CONSECUTIVE_FAILURES {
                    warn!(
                        failures = consecutive_failures,
                        "serial: repeated JSON telemetry timeouts; closing port"
                    );
                    return LoopExit::IoError(io::Error::new(
                        io::ErrorKind::TimedOut,
                        "timed out reading JSON telemetry line",
                    ));
                }
                continue;
            }
        };
        if read == 0 {
            consecutive_failures += 1;
            if consecutive_failures > MAX_CONSECUTIVE_FAILURES {
                warn!(
                    failures = consecutive_failures,
                    "serial: repeated EOF reads; closing port"
                );
                return LoopExit::IoError(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "serial port returned EOF",
                ));
            }
            task::yield_now().await;
            continue;
        }

        // The link may carry boot-ROM garbage or non-UTF8 noise (e.g. right
        // after an ESP32 reset on UART0); decode it lossily rather than
        // treating it as a read error — `parse_json_telemetry_line` already
        // skips anything that isn't a JSON object line.
        let line = String::from_utf8_lossy(&raw);
        let telemetry = match parse_json_telemetry_line(&line) {
            Ok(Some(telemetry)) => telemetry,
            Ok(None) => continue,
            Err(e) => {
                warn!("serial: skipping malformed telemetry line: {e}");
                continue;
            }
        };

        consecutive_failures = 0;
        let frame = telemetry_to_frame(&telemetry, mapping);
        let ambient = telemetry_ambient_or_stub(&telemetry);
        total += 1;
        if tx.send(frame).is_err() {
            info!(
                "serial: all receivers dropped after {total} frames \
                 — task stopping"
            );
            return LoopExit::ReceiverDropped;
        }
        let _ = ambient_tx.send(ambient);
        if total % YIELD_EVERY_N_FRAMES == 0 {
            task::yield_now().await;
        }
    }
}

// ── Port configuration ────────────────────────────────────────────────────────

/// Open the serial port with the correct settings for the ESP32 UART link.
///
/// All parameters are explicit to avoid surprising defaults when the
/// serialport crate changes them between versions.
fn open_json_port(path: &str, baud: u32) -> tokio_serial::Result<tokio_serial::SerialStream> {
    open_port(path, baud)
}
