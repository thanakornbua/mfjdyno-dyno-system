//! Cooperative suspend/resume gate between the live serial reader
//! ([`crate::serial::SerialTask`]) and anything that needs exclusive access
//! to the port — currently, ESP32 firmware flashing.
//!
//! On a single-USB deployment the flash tool (`arduino-cli upload`) needs the
//! same port the telemetry reader holds open. [`SerialGate::suspend`] asks
//! the reader to let go of the port and waits for it to confirm; dropping the
//! returned guard asks it to resume.
//!
//! Two `watch` channels carry the handshake:
//! - `desired`: API → reader. `true` = the reader may hold the port.
//! - `actual`: reader → API. `true` = the reader currently holds it.

use std::time::Duration;

use tokio::sync::watch;

/// Handle held by the API layer to request exclusive access to the port.
#[derive(Clone)]
pub struct SerialGate {
    desired: watch::Sender<bool>,
    actual: watch::Receiver<bool>,
}

/// Handle held by the serial reader task to observe suspend requests and
/// report whether it currently holds the port.
pub struct SerialGateWorker {
    desired: watch::Receiver<bool>,
    actual: watch::Sender<bool>,
}

/// While held, the reader is parked (or parking) and will not touch the
/// port. Dropping it — including on an early return — resumes the reader.
#[derive(Debug)]
pub struct SerialSuspendGuard {
    desired: watch::Sender<bool>,
}

impl Drop for SerialSuspendGuard {
    fn drop(&mut self) {
        let _ = self.desired.send(true);
    }
}

/// Returned by [`SerialGate::suspend`] when the reader did not confirm
/// release of the port within the timeout.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SuspendTimeout;

impl std::fmt::Display for SuspendTimeout {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "timed out waiting for the serial reader to release the port")
    }
}

impl std::error::Error for SuspendTimeout {}

/// Construct a gate pair. `initial_actual` seeds whether the reader side
/// starts out considered to be holding the port — pass `false` when no
/// reader will ever run (e.g. replay mode), so `suspend()` resolves
/// immediately.
pub fn serial_gate(initial_actual: bool) -> (SerialGate, SerialGateWorker) {
    let (desired_tx, desired_rx) = watch::channel(true);
    let (actual_tx, actual_rx) = watch::channel(initial_actual);
    (
        SerialGate {
            desired: desired_tx,
            actual: actual_rx,
        },
        SerialGateWorker {
            desired: desired_rx,
            actual: actual_tx,
        },
    )
}

impl SerialGate {
    /// Ask the reader to release the port and wait (up to `timeout`) for it
    /// to confirm. On success, the port is free until the returned guard is
    /// dropped. On timeout, the suspend request is withdrawn so the reader
    /// isn't left parked indefinitely.
    pub async fn suspend(&self, timeout: Duration) -> Result<SerialSuspendGuard, SuspendTimeout> {
        let _ = self.desired.send(false);

        let mut actual = self.actual.clone();
        let wait_for_release = async {
            loop {
                if !*actual.borrow() {
                    return;
                }
                if actual.changed().await.is_err() {
                    return;
                }
            }
        };

        match tokio::time::timeout(timeout, wait_for_release).await {
            Ok(()) => Ok(SerialSuspendGuard {
                desired: self.desired.clone(),
            }),
            Err(_) => {
                let _ = self.desired.send(true);
                Err(SuspendTimeout)
            }
        }
    }
}

impl SerialGateWorker {
    /// Park here while a suspend is in effect (or requested); returns once
    /// the reader is allowed to (re)open the port.
    pub async fn wait_until_desired_open(&mut self) {
        loop {
            if *self.desired.borrow() {
                return;
            }
            if self.desired.changed().await.is_err() {
                return;
            }
        }
    }

    /// Resolves once a suspend has been requested. Returns immediately if
    /// one already is in effect.
    pub async fn wait_until_suspend_requested(&mut self) {
        loop {
            if !*self.desired.borrow() {
                return;
            }
            if self.desired.changed().await.is_err() {
                return;
            }
        }
    }

    /// Report whether the reader currently holds the port open.
    pub fn publish_actual(&self, open: bool) {
        let _ = self.actual.send(open);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn suspend_waits_for_reader_release_then_resume_reopens() {
        let (gate, mut worker) = serial_gate(true);

        let worker_task = tokio::spawn(async move {
            worker.wait_until_suspend_requested().await;
            worker.publish_actual(false);
            worker.wait_until_desired_open().await;
            worker.publish_actual(true);
        });

        let guard = gate
            .suspend(Duration::from_secs(1))
            .await
            .expect("suspend should succeed");
        drop(guard);

        worker_task.await.expect("worker task should finish");
    }

    #[tokio::test]
    async fn suspend_times_out_and_withdraws_request_if_reader_never_releases() {
        let (gate, _worker) = serial_gate(true);

        let err = gate
            .suspend(Duration::from_millis(50))
            .await
            .expect_err("suspend should time out");
        assert_eq!(err, SuspendTimeout);
    }

    #[tokio::test]
    async fn suspend_resolves_immediately_when_actual_starts_released() {
        let (gate, _worker) = serial_gate(false);

        let guard = gate
            .suspend(Duration::from_millis(50))
            .await
            .expect("suspend should resolve instantly");
        drop(guard);
    }
}
