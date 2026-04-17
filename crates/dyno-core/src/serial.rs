//! Async UART ingestion task.
//!
//! # Pipeline
//!
//! ```text
//! /dev/serial0 ──[DynoSerialLink]──► telemetry packets ──[watch::send]──► watch::Sender<DynoFrameV1>
//! ```
//!
//! The task owns a [`DynoSerialLink`](crate::serial_link::DynoSerialLink) that
//! reads fixed-length UART packets, validates magic/version/CRC, resynchronizes
//! on framing errors, and yields only telemetry packets to the fusion path.
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

use tokio::sync::watch;
use tokio::task::{self, JoinHandle};
use tracing::{error, info};

use dyno_protocol::DynoFrameV1;

use crate::config::Config;
use crate::serial_link::DynoUartLink;

/// How long to wait before retrying a failed port open.
const OPEN_RETRY_DELAY: Duration = Duration::from_secs(5);

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
    pub fn spawn(config: &Config, tx: watch::Sender<DynoFrameV1>) -> Self {
        let port_path = config.serial_port.clone();
        let baud      = config.serial_baud;
        let handle    = tokio::spawn(serial_task_outer(port_path, baud, tx));
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
async fn serial_task_outer(port_path: String, baud: u32, tx: watch::Sender<DynoFrameV1>) {
    loop {
        let port = match open_port(&port_path, baud) {
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

        match serial_read_loop(port, &tx).await {
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
        }
    }
}

// ── Inner read loop ───────────────────────────────────────────────────────────

enum LoopExit {
    /// All watch receivers were dropped; no point continuing.
    ReceiverDropped,
    /// A serial I/O error; the caller should reopen the port.
    IoError(io::Error),
}

async fn serial_read_loop(
    mut link: DynoUartLink,
    tx: &watch::Sender<DynoFrameV1>,
) -> LoopExit {
    let mut total = 0u64;

    loop {
        let frame = match link.read_telemetry_frame().await {
            Ok(frame) => frame,
            Err(e) => {
                info!("serial: closing after {total} frames");
                return LoopExit::IoError(e);
            }
        };

        total += 1;
        if tx.send(frame).is_err() {
            info!(
                "serial: all receivers dropped after {total} frames \
                 — task stopping"
            );
            return LoopExit::ReceiverDropped;
        }
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
fn open_port(path: &str, baud: u32) -> tokio_serial::Result<DynoUartLink> {
    DynoUartLink::open(path, baud)
}
