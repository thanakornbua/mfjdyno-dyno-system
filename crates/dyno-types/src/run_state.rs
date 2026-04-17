use serde::{Deserialize, Serialize};

/// Lifecycle state of a dyno run, driven by the state machine in `dyno-core`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RunState {
    /// No run in progress; waiting for operator action.
    Idle,
    /// RPM has crossed the arm threshold; ready to begin recording.
    Armed,
    /// Actively capturing frames to the current run.
    Recording,
    /// RPM has fallen below the stop threshold; finalising the run.
    Stopping,
    /// A hard fault has occurred; operator must acknowledge before next run.
    Fault,
}

impl Default for RunState {
    fn default() -> Self {
        Self::Idle
    }
}

impl std::fmt::Display for RunState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::Idle      => "idle",
            Self::Armed     => "armed",
            Self::Recording => "recording",
            Self::Stopping  => "stopping",
            Self::Fault     => "fault",
        };
        f.write_str(s)
    }
}
