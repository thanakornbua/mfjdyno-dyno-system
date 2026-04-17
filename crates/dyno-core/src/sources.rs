//! Abstraction over physical and simulated data sources.
//!
//! Introducing this trait boundary allows `dyno-sim` to plug in
//! without requiring the serial port or ESP32 hardware to be present.
//!
//! # To be implemented
//!
//! Concrete implementors:
//! * `SerialSource`  — reads from the live UART (production)
//! * `SimSource`     — generates synthetic frames (development / testing)

use dyno_types::LiveFrame;

/// A source of raw dyno data frames.
///
/// Implementors drive the fusion pipeline; only one source should be active
/// at a time.
pub trait DataSource: Send + 'static {
    /// Blocking-style poll: return the next available frame, or `None` if the
    /// source has been exhausted (sim replay end-of-file, etc.).
    ///
    /// This will likely become `async fn next_frame` once `async-trait` or
    /// RPITIT stabilisation is leveraged.
    fn next_frame_blocking(&mut self) -> Option<LiveFrame>;
}
