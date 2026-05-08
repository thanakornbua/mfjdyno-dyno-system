use std::sync::Arc;

use chrono::Utc;
use dyno_types::RunState;
use tokio::sync::RwLock;

#[derive(Debug, Clone)]
pub struct RunControl {
    inner: Arc<RwLock<RunControlState>>,
}

#[derive(Debug, Clone)]
pub struct RunControlState {
    pub configured: bool,
    pub started: bool,
    pub recording: bool,
    pub run_label: String,
    pub license_plate: String,
}

impl Default for RunControlState {
    fn default() -> Self {
        Self {
            configured: false,
            started: false,
            recording: false,
            run_label: "RUN NOT CONFIGURED".to_owned(),
            license_plate: "—".to_owned(),
        }
    }
}

impl RunControl {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(RwLock::new(RunControlState::default())),
        }
    }

    pub async fn snapshot(&self) -> RunControlState {
        self.inner.read().await.clone()
    }

    pub async fn configure(&self, license_plate: Option<String>) -> RunControlState {
        let mut state = self.inner.write().await;
        state.configured = true;
        state.license_plate = normalize_license_plate(license_plate);
        state.run_label = build_run_label(&state.license_plate);
        state.clone()
    }

    pub async fn start(&self) -> RunControlState {
        let mut state = self.inner.write().await;
        if !state.configured {
            state.configured = true;
            state.license_plate = "—".to_owned();
            state.run_label = build_run_label(&state.license_plate);
        }
        state.started = true;
        state.clone()
    }

    pub async fn stop(&self) -> RunControlState {
        let mut state = self.inner.write().await;
        state.started = false;
        state.recording = false;
        state.clone()
    }

    pub async fn update_runtime_state(&self, run_state: RunState) {
        let mut state = self.inner.write().await;
        state.recording = matches!(run_state, RunState::Recording);
    }
}

fn normalize_license_plate(raw: Option<String>) -> String {
    let Some(raw) = raw else {
        return "—".to_owned();
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        "—".to_owned()
    } else {
        trimmed.to_uppercase()
    }
}

fn build_run_label(license_plate: &str) -> String {
    if license_plate == "—" {
        return format!("RUN-{}", Utc::now().format("%Y%m%d-%H%M%S"));
    }
    format!("RUN {license_plate}")
}
