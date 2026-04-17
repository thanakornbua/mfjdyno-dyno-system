//! Simulated data source for development and integration testing.
//!
//! Provides a [`SimSource`] that implements [`dyno_core::sources::DataSource`]
//! and generates synthetic `LiveFrame` values without requiring physical
//! hardware.
//!
//! # Intended usage
//!
//! ```no_run
//! // (once implemented)
//! // let source = SimSource::sine_sweep(1000.0..=8000.0, Duration::from_secs(30));
//! // app.replace_source(source);
//! ```
//!
//! # Not yet implemented
//!
//! * `SimSource` — configurable synthetic frame generator (sine sweep, ramp,
//!   fixed value, replay from CSV).
//! * `ReplaySource` — replay a recorded SQLite run at wall-clock speed.

use dyno_core::sources::DataSource;
use dyno_types::LiveFrame;

/// Minimal stub that satisfies the `DataSource` trait but produces nothing.
pub struct SimSource;

impl DataSource for SimSource {
    fn next_frame_blocking(&mut self) -> Option<LiveFrame> {
        // TODO: generate a synthetic frame based on a configurable profile.
        None
    }
}
