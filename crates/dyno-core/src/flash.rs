//! ESP32 firmware flashing.
//!
//! Mirrors `tools/flash-esp32.sh`: an `arduino-cli compile` followed by an
//! `arduino-cli upload -p <port>`, against a configurable sketch and FQBN.
//! The command execution is abstracted behind [`CommandRunner`] so tests can
//! assert the argv without invoking a real toolchain.
//!
//! Flashing is long-running (a cold compile can take minutes), so callers run
//! [`run_flash`] on a background task and surface progress through
//! [`FlashJob`].

use std::path::Path;
use std::sync::Arc;
use std::sync::Mutex;

use serde::Serialize;

/// Result of running a single external command.
pub struct CommandOutput {
    pub success: bool,
    pub combined_output: String,
}

/// Abstraction over running an external process, so the flash flow is testable.
pub trait CommandRunner: Send + Sync {
    fn run(&self, program: &str, args: &[String]) -> anyhow::Result<CommandOutput>;
    /// True when `program` is resolvable on `PATH`.
    fn tool_available(&self, program: &str) -> bool;
}

/// Real command runner backed by `std::process::Command`.
pub struct SystemCommandRunner;

impl CommandRunner for SystemCommandRunner {
    fn run(&self, program: &str, args: &[String]) -> anyhow::Result<CommandOutput> {
        let output = std::process::Command::new(program).args(args).output()?;
        let mut combined = String::from_utf8_lossy(&output.stdout).into_owned();
        combined.push_str(&String::from_utf8_lossy(&output.stderr));
        Ok(CommandOutput {
            success: output.status.success(),
            combined_output: combined,
        })
    }

    fn tool_available(&self, program: &str) -> bool {
        // Absolute path: check directly. Bare name: scan PATH.
        if program.contains('/') {
            return Path::new(program).exists();
        }
        std::env::var_os("PATH")
            .map(|paths| {
                std::env::split_paths(&paths).any(|dir| dir.join(program).exists())
            })
            .unwrap_or(false)
    }
}

/// Flashing configuration, defaulted to match `tools/flash-esp32.sh` and
/// overridable via `DYNO_FLASH_*` environment variables.
#[derive(Debug, Clone)]
pub struct FlashOptions {
    pub tool: String,
    pub fqbn: String,
    pub sketch: String,
}

const DEFAULT_SKETCH: &str = "firmware/firmware-test";

/// The firmware sketch is embedded in the binary so a minimal install (just
/// `dynod`) can still flash without shipping firmware sources alongside it.
/// It is a single self-contained `.ino` with no local headers.
const EMBEDDED_SKETCH_NAME: &str = "firmware-test";
const EMBEDDED_SKETCH_INO: &str =
    include_str!("../../../firmware/firmware-test/firmware-test.ino");

impl FlashOptions {
    pub fn from_env() -> Self {
        Self {
            tool: env_or("DYNO_FLASH_TOOL", "arduino-cli"),
            fqbn: env_or("DYNO_FLASH_FQBN", "esp32:esp32:esp32"),
            sketch: env_or("DYNO_FLASH_SKETCH", DEFAULT_SKETCH),
        }
    }
}

/// Write the embedded firmware sketch to a temp directory and return the sketch
/// directory path. arduino-cli requires the main file to be `<dir>/<dir>.ino`.
fn materialize_embedded_sketch() -> anyhow::Result<std::path::PathBuf> {
    let sketch_dir = std::env::temp_dir()
        .join("dyno-firmware")
        .join(EMBEDDED_SKETCH_NAME);
    std::fs::create_dir_all(&sketch_dir)?;
    std::fs::write(
        sketch_dir.join(format!("{EMBEDDED_SKETCH_NAME}.ino")),
        EMBEDDED_SKETCH_INO,
    )?;
    Ok(sketch_dir)
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key)
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| default.to_owned())
}

/// Lifecycle state of the (single-flight) flash job.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum FlashState {
    Idle,
    Running,
    Success,
    Error,
}

#[derive(Debug, Clone, Serialize)]
pub struct FlashStatus {
    pub state: FlashState,
    pub log: String,
    pub port: Option<String>,
    pub started_at_ms: Option<i64>,
    pub finished_at_ms: Option<i64>,
}

impl Default for FlashStatus {
    fn default() -> Self {
        Self {
            state: FlashState::Idle,
            log: String::new(),
            port: None,
            started_at_ms: None,
            finished_at_ms: None,
        }
    }
}

/// Shared, single-flight flash job status. Cheap to clone (reference-counted).
#[derive(Clone, Default)]
pub struct FlashJob {
    inner: Arc<Mutex<FlashStatus>>,
}

impl FlashJob {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn status(&self) -> FlashStatus {
        self.inner.lock().expect("flash job mutex").clone()
    }

    pub fn is_running(&self) -> bool {
        self.inner.lock().expect("flash job mutex").state == FlashState::Running
    }

    /// Atomically transition Idle/Success/Error → Running. Returns false if a
    /// flash is already in progress (single-flight guard).
    pub fn try_begin(&self, port: &str, now_ms: i64) -> bool {
        let mut guard = self.inner.lock().expect("flash job mutex");
        if guard.state == FlashState::Running {
            return false;
        }
        *guard = FlashStatus {
            state: FlashState::Running,
            log: String::new(),
            port: Some(port.to_owned()),
            started_at_ms: Some(now_ms),
            finished_at_ms: None,
        };
        true
    }

    pub(crate) fn finish(&self, success: bool, log: String, now_ms: i64) {
        let mut guard = self.inner.lock().expect("flash job mutex");
        guard.state = if success { FlashState::Success } else { FlashState::Error };
        guard.log = log;
        guard.finished_at_ms = Some(now_ms);
    }
}

const MAX_LOG_BYTES: usize = 256 * 1024;

/// Run compile + upload for `port` using `opts`, recording the outcome into
/// `job`. Intended to run on a blocking background task. `now_ms` supplies the
/// finish timestamp so callers control the clock.
pub fn run_flash(
    runner: &dyn CommandRunner,
    opts: &FlashOptions,
    port: &str,
    job: &FlashJob,
    now_ms: i64,
) {
    let mut log = String::new();

    // Preflight: the external toolchain is the one thing we cannot carry, so
    // fail fast (and legibly) if it is absent.
    if !runner.tool_available(&opts.tool) {
        log.push_str(&format!(
            "flash tool '{}' was not found on PATH. Install arduino-cli (and the esp32 core) \
             on this machine, or set DYNO_FLASH_TOOL to its path.\n",
            opts.tool
        ));
        job.finish(false, log, now_ms);
        return;
    }

    // Resolve the sketch. A sketch present on disk (dev checkout, or an explicit
    // DYNO_FLASH_SKETCH) wins; otherwise fall back to the firmware embedded in
    // this binary, so a minimal install can still flash.
    let sketch = if Path::new(&opts.sketch).exists() {
        opts.sketch.clone()
    } else {
        if opts.sketch != DEFAULT_SKETCH {
            log.push_str(&format!(
                "configured firmware sketch '{}' was not found; using the firmware built into \
                 this binary instead.\n",
                opts.sketch
            ));
        } else {
            log.push_str("using the firmware built into this binary.\n");
        }
        match materialize_embedded_sketch() {
            Ok(dir) => dir.display().to_string(),
            Err(err) => {
                log.push_str(&format!(
                    "could not stage the built-in firmware sketch: {err}\n"
                ));
                job.finish(false, log, now_ms);
                return;
            }
        }
    };

    let compile_args = vec![
        "compile".to_owned(),
        "--fqbn".to_owned(),
        opts.fqbn.clone(),
        sketch.clone(),
    ];
    let upload_args = vec![
        "upload".to_owned(),
        "-p".to_owned(),
        port.to_owned(),
        "--fqbn".to_owned(),
        opts.fqbn.clone(),
        sketch.clone(),
    ];

    for (label, args) in [("compile", compile_args), ("upload", upload_args)] {
        log.push_str(&format!("$ {} {}\n", opts.tool, args.join(" ")));
        match runner.run(&opts.tool, &args) {
            Ok(output) => {
                log.push_str(&output.combined_output);
                if !log.ends_with('\n') {
                    log.push('\n');
                }
                if !output.success {
                    log.push_str(&format!("{label} failed.\n"));
                    truncate_log(&mut log);
                    job.finish(false, log, now_ms);
                    return;
                }
            }
            Err(err) => {
                log.push_str(&format!("{label} could not be started: {err}\n"));
                truncate_log(&mut log);
                job.finish(false, log, now_ms);
                return;
            }
        }
    }

    log.push_str("Flash completed successfully.\n");
    truncate_log(&mut log);
    job.finish(true, log, now_ms);
}

fn truncate_log(log: &mut String) {
    if log.len() > MAX_LOG_BYTES {
        // Keep the tail — the failure/end is what matters most.
        let start = log.len() - MAX_LOG_BYTES;
        // Align to a char boundary.
        let start = (start..log.len())
            .find(|&i| log.is_char_boundary(i))
            .unwrap_or(log.len());
        let tail = log[start..].to_owned();
        *log = format!("[log truncated]\n{tail}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    /// Serializes tests that touch the shared embedded-sketch temp directory
    /// (`materialize_embedded_sketch` writes to a fixed path; a concurrent
    /// truncate-then-write can be observed as an empty file by another test).
    fn sketch_dir_lock() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: std::sync::OnceLock<StdMutex<()>> = std::sync::OnceLock::new();
        LOCK.get_or_init(|| StdMutex::new(()))
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Fake runner: records argv and returns scripted outcomes per program call.
    struct FakeRunner {
        available: bool,
        calls: StdMutex<Vec<Vec<String>>>,
        /// success flag returned for each successive call.
        outcomes: StdMutex<Vec<bool>>,
    }

    impl FakeRunner {
        fn new(available: bool, outcomes: Vec<bool>) -> Self {
            Self {
                available,
                calls: StdMutex::new(Vec::new()),
                outcomes: StdMutex::new(outcomes),
            }
        }
    }

    impl CommandRunner for FakeRunner {
        fn run(&self, program: &str, args: &[String]) -> anyhow::Result<CommandOutput> {
            let mut recorded = vec![program.to_owned()];
            recorded.extend(args.iter().cloned());
            self.calls.lock().unwrap().push(recorded);
            let success = {
                let mut outcomes = self.outcomes.lock().unwrap();
                if outcomes.is_empty() { true } else { outcomes.remove(0) }
            };
            Ok(CommandOutput {
                success,
                combined_output: format!("output for {program}\n"),
            })
        }

        fn tool_available(&self, _program: &str) -> bool {
            self.available
        }
    }

    fn opts_with_existing_sketch() -> (FlashOptions, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!(
            "dyno-flash-sketch-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let opts = FlashOptions {
            tool: "arduino-cli".to_owned(),
            fqbn: "esp32:esp32:esp32".to_owned(),
            sketch: dir.display().to_string(),
        };
        (opts, dir)
    }

    #[test]
    fn runs_compile_then_upload_with_expected_argv() {
        let (opts, sketch_dir) = opts_with_existing_sketch();
        let runner = FakeRunner::new(true, vec![true, true]);
        let job = FlashJob::new();
        assert!(job.try_begin("/dev/ttyUSB1", 1000));

        run_flash(&runner, &opts, "/dev/ttyUSB1", &job, 2000);

        let calls = runner.calls.lock().unwrap();
        assert_eq!(calls.len(), 2);
        assert_eq!(
            calls[0],
            vec![
                "arduino-cli",
                "compile",
                "--fqbn",
                "esp32:esp32:esp32",
                &opts.sketch
            ]
        );
        assert_eq!(
            calls[1],
            vec![
                "arduino-cli",
                "upload",
                "-p",
                "/dev/ttyUSB1",
                "--fqbn",
                "esp32:esp32:esp32",
                &opts.sketch
            ]
        );
        let status = job.status();
        assert_eq!(status.state, FlashState::Success);
        assert_eq!(status.finished_at_ms, Some(2000));

        std::fs::remove_dir_all(sketch_dir).ok();
    }

    #[test]
    fn compile_failure_skips_upload_and_marks_error() {
        let (opts, sketch_dir) = opts_with_existing_sketch();
        let runner = FakeRunner::new(true, vec![false]);
        let job = FlashJob::new();
        job.try_begin("/dev/ttyUSB1", 1000);

        run_flash(&runner, &opts, "/dev/ttyUSB1", &job, 2000);

        let calls = runner.calls.lock().unwrap();
        assert_eq!(calls.len(), 1, "upload must not run after compile fails");
        assert_eq!(job.status().state, FlashState::Error);

        std::fs::remove_dir_all(sketch_dir).ok();
    }

    #[test]
    fn missing_tool_fails_preflight_without_running() {
        let (opts, sketch_dir) = opts_with_existing_sketch();
        let runner = FakeRunner::new(false, vec![]);
        let job = FlashJob::new();
        job.try_begin("/dev/ttyUSB1", 1000);

        run_flash(&runner, &opts, "/dev/ttyUSB1", &job, 2000);

        assert!(runner.calls.lock().unwrap().is_empty());
        let status = job.status();
        assert_eq!(status.state, FlashState::Error);
        assert!(status.log.contains("was not found on PATH"));

        std::fs::remove_dir_all(sketch_dir).ok();
    }

    #[test]
    fn missing_sketch_falls_back_to_embedded_firmware() {
        let _guard = sketch_dir_lock();
        let opts = FlashOptions {
            tool: "arduino-cli".to_owned(),
            fqbn: "esp32:esp32:esp32".to_owned(),
            sketch: "/nonexistent/dyno-sketch-dir".to_owned(),
        };
        let runner = FakeRunner::new(true, vec![true, true]);
        let job = FlashJob::new();
        job.try_begin("/dev/ttyUSB1", 1000);

        run_flash(&runner, &opts, "/dev/ttyUSB1", &job, 2000);

        // Falls back to the embedded firmware and still runs compile + upload.
        let calls = runner.calls.lock().unwrap();
        assert_eq!(calls.len(), 2);
        // The sketch arg is the materialized embedded sketch dir.
        let compile_sketch = calls[0].last().unwrap();
        assert!(compile_sketch.ends_with("firmware-test"), "got {compile_sketch}");
        assert!(std::path::Path::new(compile_sketch)
            .join("firmware-test.ino")
            .exists());
        assert_eq!(job.status().state, FlashState::Success);
        assert!(job.status().log.contains("using the firmware built into this binary")
            || job.status().log.contains("was not found; using the firmware built into this binary"));
    }

    #[test]
    fn embedded_sketch_materializes_the_ino() {
        let _guard = sketch_dir_lock();
        let dir = materialize_embedded_sketch().expect("materialize");
        let ino = dir.join("firmware-test.ino");
        assert!(ino.exists());
        let contents = std::fs::read_to_string(&ino).expect("read ino");
        assert!(!contents.trim().is_empty(), "embedded firmware must be non-empty");
    }

    #[test]
    fn try_begin_is_single_flight() {
        let job = FlashJob::new();
        assert!(job.try_begin("/dev/ttyUSB1", 1000));
        assert!(!job.try_begin("/dev/ttyUSB1", 1001), "second begin must be rejected");
        assert!(job.is_running());
    }
}
