//! Run lifecycle state machine.
//!
//! # Responsibilities (to be implemented)
//!
//! * Own the authoritative `RunState` and transition it based on:
//!   - operator start/stop intent
//!   - engine RPM collection threshold (`Config::record_rpm`)
//!   - fault injection from any subsystem
//! * Emit state-change events so fusion and storage can react.
//! * Guard against illegal transitions.
//!
//! # State diagram
//!
//! ```text
//! Idle ──[operator start]──► Armed ──[rpm >= record_rpm]──► Recording
//!   ▲                         ▲                               │
//!   └──────[operator stop]────┴────[rpm < record_rpm]─────────┘
//!
//! Any state ──[fault]──► Fault ──[operator ack]──► Idle
//! ```

use dyno_types::RunState;

/// Manages `RunState` transitions and enforces the dyno lifecycle.
pub struct StateMachine {
    state: RunState,
}

impl StateMachine {
    pub fn new() -> Self {
        Self { state: RunState::Idle }
    }

    pub fn current(&self) -> RunState {
        self.state
    }

    /// Apply RPM-driven transitions.
    ///
    /// # TODO
    ///
    /// Accept latest engine RPM and config thresholds; return
    /// `Some(new_state)` if a transition occurred.
    pub fn update_rpm(&mut self, _engine_rpm: f32, _config: &crate::config::Config) -> Option<RunState> {
        // TODO: implement threshold-based transitions
        None
    }

    /// Transition to `Fault`, recording the triggering fault code.
    pub fn fault(&mut self) {
        self.state = RunState::Fault;
    }
}

impl Default for StateMachine {
    fn default() -> Self {
        Self::new()
    }
}
