//! Run lifecycle state machine.
//!
//! # Responsibilities (to be implemented)
//!
//! * Own the authoritative `RunState` and transition it based on:
//!   - engine RPM thresholds (`Config::arm_rpm`, `record_rpm`, `stop_rpm`)
//!   - explicit operator commands (arm, abort)
//!   - fault injection from any subsystem
//! * Emit state-change events so fusion and storage can react.
//! * Guard against illegal transitions (e.g., Recording → Armed without
//!   passing through Stopping).
//!
//! # State diagram
//!
//! ```text
//! Idle ──[rpm > arm_rpm]──► Armed ──[rpm > record_rpm]──► Recording
//!   ▲                                                          │
//!   └──[run saved]── Stopping ◄──[rpm < stop_rpm]────────────┘
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
