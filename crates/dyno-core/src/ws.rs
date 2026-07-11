//! WebSocket server task.
//!
//! Each connected client receives the current `LiveFrame` immediately and then
//! subsequent updates from a cloned `watch::Receiver`. Because `watch` stores
//! only the latest value, slow clients naturally skip stale frames instead of
//! building backpressure via unbounded queues.

use std::sync::atomic::{AtomicU64, Ordering};

use anyhow::Context;
use serde::Serialize;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio::task::JoinHandle;
use tokio_tungstenite::{accept_async, tungstenite::protocol::Message};
use tracing::{debug, error, info, warn};

use dyno_types::LiveFrame;

static NEXT_CLIENT_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Serialize)]
struct LiveFrameEnvelope<'a> {
    #[serde(rename = "type")]
    message_type: &'static str,
    data: &'a LiveFrame,
}

impl<'a> LiveFrameEnvelope<'a> {
    fn new(frame: &'a LiveFrame) -> Self {
        Self {
            message_type: "live_frame",
            data: frame,
        }
    }
}

pub(crate) fn serialize_live_frame_message(frame: &LiveFrame) -> serde_json::Result<String> {
    serde_json::to_string(&LiveFrameEnvelope::new(frame))
}

fn warn_suspicious_frame(frame: &LiveFrame) {
    if matches!(frame.engine_rpm, Some(value) if value < 0.0) {
        warn!(ts_ms = frame.ts_ms, engine_rpm = frame.engine_rpm, "websocket: negative engine RPM observed");
    }

    if matches!(frame.roller_rpm, Some(value) if value < 0.0) {
        warn!(ts_ms = frame.ts_ms, roller_rpm = frame.roller_rpm, "websocket: negative roller RPM observed");
    }

    if matches!(frame.power_hp, Some(value) if !value.is_finite()) {
        warn!(ts_ms = frame.ts_ms, power_hp = frame.power_hp, "websocket: non-finite power observed");
    }

    if matches!(frame.torque_nm, Some(value) if !value.is_finite()) {
        warn!(ts_ms = frame.ts_ms, torque_nm = frame.torque_nm, "websocket: non-finite torque observed");
    }

    if !frame.correction_factor.is_finite() || frame.correction_factor <= 0.0 || frame.correction_factor > 2.0 {
        warn!(
            ts_ms = frame.ts_ms,
            correction_factor = frame.correction_factor,
            "websocket: impossible correction factor observed"
        );
    }

    if let (Some(roller_rpm), Some(torque_nm)) = (frame.roller_rpm, frame.torque_nm) {
        if roller_rpm.abs() <= 10.0 && torque_nm.abs() >= 250.0 {
            warn!(
                ts_ms = frame.ts_ms,
                roller_rpm,
                torque_nm,
                "websocket: implausible torque spike near zero omega"
            );
        }
    }
}

/// Handle returned by the WebSocket broadcast server task.
pub struct WsTask {
    handle: JoinHandle<()>,
}

impl WsTask {
    /// Bind the WebSocket broadcast server and spawn its accept loop.
    ///
    /// Binding happens before the task is spawned so a port conflict (e.g. a
    /// second `dynod` instance already running) is reported as an error the
    /// caller can act on, instead of the task silently logging and exiting.
    pub async fn spawn(bind_addr: &str, rx: watch::Receiver<LiveFrame>) -> anyhow::Result<Self> {
        let listener = TcpListener::bind(bind_addr).await.with_context(|| {
            format!(
                "websocket: failed to bind {bind_addr} — is another dynod instance \
                 (e.g. the systemd service) already running?"
            )
        })?;
        info!("websocket: listening on {bind_addr}");

        let handle = tokio::spawn(async move {
            ws_task_loop(listener, rx).await;
        });

        Ok(Self { handle })
    }
}

impl Drop for WsTask {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

async fn ws_task_loop(listener: TcpListener, rx: watch::Receiver<LiveFrame>) {
    loop {
        match listener.accept().await {
            Ok((stream, peer_addr)) => {
                let client_id = NEXT_CLIENT_ID.fetch_add(1, Ordering::Relaxed);
                info!("websocket: client {client_id} connected from {peer_addr}");

                tokio::spawn(handle_client(stream, rx.clone(), client_id, peer_addr.to_string()));
            }
            Err(err) => {
                warn!("websocket: accept failed: {err}");
            }
        }
    }
}

async fn handle_client(
    stream: TcpStream,
    mut rx: watch::Receiver<LiveFrame>,
    client_id: u64,
    peer_addr: String,
) {
    let mut websocket = match accept_async(stream).await {
        Ok(websocket) => websocket,
        Err(err) => {
            warn!("websocket: handshake failed for client {client_id} ({peer_addr}): {err}");
            return;
        }
    };

    if let Err(err) = send_latest_frame(&mut websocket, &rx).await {
        warn!("websocket: initial send failed for client {client_id} ({peer_addr}): {err}");
        return;
    }

    loop {
        match rx.changed().await {
            Ok(()) => {
                if let Err(err) = send_latest_frame(&mut websocket, &rx).await {
                    info!("websocket: client {client_id} disconnected ({peer_addr}): {err}");
                    return;
                }
            }
            Err(_) => {
                info!("websocket: live frame channel closed; client {client_id} ({peer_addr}) exiting");
                return;
            }
        }
    }
}

async fn send_latest_frame<S>(
    websocket: &mut tokio_tungstenite::WebSocketStream<S>,
    rx: &watch::Receiver<LiveFrame>,
) -> Result<(), tokio_tungstenite::tungstenite::Error>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    use futures_util::SinkExt;

    let frame = rx.borrow().clone();
    warn_suspicious_frame(&frame);

    let message = match serialize_live_frame_message(&frame) {
        Ok(message) => message,
        Err(err) => {
            error!("websocket: failed to serialize LiveFrame: {err}");
            return Err(tokio_tungstenite::tungstenite::Error::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                err,
            )));
        }
    };

    debug!("websocket: sending latest live frame");
    websocket.send(Message::Text(message)).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;
    use dyno_types::{Esp32TelemetryStatus, RunState};
    use crate::replay::golden_live_frame;

    fn sample_frame() -> LiveFrame {
        LiveFrame {
            ts_ms: 1234,
            engine_rpm: Some(4500.0),
            roller_rpm: Some(1200.0),
            speed_kmh: Some(95.4),
            power_hp: Some(87.3),
            torque_nm: Some(135.0),
            correction_factor: 1.02,
            afr: Some(13.8),
            lambda: Some(0.939),
            can_present: true,
            can_frames_seen: 1,
            afr_valid: true,
            can_valid: true,
            can_status_text: "AEM UEGO active".to_owned(),
            ambient_temp_c: Some(24.5),
            humidity_pct: Some(55.0),
            pressure_hpa: Some(1013.25),
            esp32_status: Esp32TelemetryStatus::default(),
            run_state: RunState::Recording,
            faults: vec![],
            alerts: Default::default(),
        }
    }

    #[test]
    fn serializes_expected_message_shape() {
        let json = serialize_live_frame_message(&sample_frame()).expect("serialize");
        assert!(json.contains("\"type\":\"live_frame\""));
        assert!(json.contains("\"data\":"));
        assert!(json.contains("\"power_hp\":87.3"));
    }

    #[test]
    fn golden_fixture_matches_backend_envelope_shape() {
        let actual = serialize_live_frame_message(&golden_live_frame()).expect("serialize");
        let actual_json: Value = serde_json::from_str(&actual).expect("actual json");
        let fixture_json: Value = serde_json::from_str(include_str!("../fixtures/live_frame_golden.json"))
            .expect("fixture json");

        assert_eq!(actual_json, fixture_json);
    }

    #[tokio::test]
    async fn spawn_smoke_test_with_ephemeral_bind() {
        let (_tx, rx) = watch::channel(LiveFrame::idle(0));
        let task = WsTask::spawn("127.0.0.1:0", rx).await.expect("spawn ws task");
        tokio::time::sleep(std::time::Duration::from_millis(25)).await;
        drop(task);
    }

    #[tokio::test]
    async fn spawn_fails_fast_when_port_is_already_bound() {
        let blocker = std::net::TcpListener::bind("127.0.0.1:0").expect("bind blocker");
        let addr = blocker.local_addr().expect("blocker addr").to_string();

        let (_tx, rx) = watch::channel(LiveFrame::idle(0));
        let result = WsTask::spawn(&addr, rx).await;
        assert!(result.is_err());

        drop(blocker);
    }
}
