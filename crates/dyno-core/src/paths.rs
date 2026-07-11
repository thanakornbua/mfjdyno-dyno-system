//! Resolves the fixed, per-machine data directory used for the database and
//! ESP32 config files, independent of whatever directory `dynod` is launched
//! from.
//!
//! Precedence: `DYNO_DATA_DIR` env override → `/var/lib/dyno` if it exists or
//! can be created and is writable → `$XDG_DATA_HOME/dyno` →
//! `~/.local/share/dyno`. The chosen directory is created (with parents) at
//! startup; if none of the candidates are usable, startup fails with a clear
//! message rather than silently falling back to the current directory.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::Context;
use tracing::warn;

const SYSTEM_DATA_DIR: &str = "/var/lib/dyno";

/// Resolve and create the data directory.
pub fn resolve_data_dir() -> anyhow::Result<PathBuf> {
    if let Ok(dir) = env::var("DYNO_DATA_DIR") {
        let dir = dir.trim();
        if !dir.is_empty() {
            let path = PathBuf::from(dir);
            ensure_writable_dir(&path)?;
            return Ok(path);
        }
    }

    let system_dir = PathBuf::from(SYSTEM_DATA_DIR);
    if ensure_writable_dir(&system_dir).is_ok() {
        return Ok(system_dir);
    }

    let fallback = xdg_data_home().join("dyno");
    ensure_writable_dir(&fallback)
        .with_context(|| {
            format!(
                "could not create a usable data directory (tried {} and {})",
                system_dir.display(),
                fallback.display()
            )
        })?;
    Ok(fallback)
}

fn xdg_data_home() -> PathBuf {
    if let Ok(dir) = env::var("XDG_DATA_HOME") {
        if !dir.trim().is_empty() {
            return PathBuf::from(dir);
        }
    }
    let home = env::var("HOME").unwrap_or_else(|_| ".".to_owned());
    PathBuf::from(home).join(".local").join("share")
}

/// Create `path` (with parents) if needed and verify it is writable.
fn ensure_writable_dir(path: &Path) -> anyhow::Result<()> {
    fs::create_dir_all(path)
        .with_context(|| format!("failed to create data directory {}", path.display()))?;
    let probe = path.join(".dyno-write-check");
    fs::write(&probe, b"")
        .with_context(|| format!("data directory {} is not writable", path.display()))?;
    let _ = fs::remove_file(&probe);
    Ok(())
}

/// If `value` is still the built-in relative default (i.e. the caller did not
/// override it via an env var), anchor it inside `data_dir`. Explicit
/// overrides — absolute or relative — are returned unchanged.
pub fn anchor_default(data_dir: &Path, value: String, default: &str) -> String {
    if value == default {
        data_dir.join(default).display().to_string()
    } else {
        value
    }
}

/// Best-effort warning when a legacy database from before self-provisioning
/// is found sitting in the current directory. Never moves or deletes it.
pub fn warn_if_legacy_cwd_db_exists(resolved_db_path: &str, legacy_relative_name: &str) {
    if resolved_db_path == legacy_relative_name {
        // db_path was explicitly overridden to the legacy relative path —
        // that IS the active database, not a stray leftover.
        return;
    }
    let resolved = Path::new(resolved_db_path);
    let legacy = Path::new(legacy_relative_name);
    if !resolved.exists() && legacy.exists() {
        warn!(
            "found a legacy database at ./{legacy_relative_name} in the current directory; \
             the backend now stores its database at {resolved_db_path} regardless of launch \
             directory. If this is real data, stop dynod and run: \
             mv {legacy_relative_name} {resolved_db_path}"
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn temp_dir(label: &str) -> PathBuf {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!("dyno-paths-{label}-{unique}"))
    }

    #[test]
    fn anchor_default_rewrites_unmodified_default() {
        let data_dir = PathBuf::from("/tmp/dyno-data-test");
        let anchored = anchor_default(&data_dir, "dyno.db".to_owned(), "dyno.db");
        assert_eq!(anchored, "/tmp/dyno-data-test/dyno.db");
    }

    #[test]
    fn anchor_default_respects_explicit_override() {
        let data_dir = PathBuf::from("/tmp/dyno-data-test");
        let anchored = anchor_default(&data_dir, "/custom/path.db".to_owned(), "dyno.db");
        assert_eq!(anchored, "/custom/path.db");
    }

    #[test]
    fn resolve_data_dir_honors_dyno_data_dir_override() {
        // Serialize with every other test that touches DYNO_* env vars.
        let _guard = crate::test_env_lock();
        let dir = temp_dir("override");
        let _ = fs::remove_dir_all(&dir);
        std::env::set_var("DYNO_DATA_DIR", dir.display().to_string());
        let resolved = resolve_data_dir().expect("resolve data dir");
        std::env::remove_var("DYNO_DATA_DIR");

        assert_eq!(resolved, dir);
        assert!(dir.is_dir());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn warn_if_legacy_cwd_db_exists_does_not_panic_when_absent() {
        // No legacy file present in the test's CWD under this name; just
        // exercise the no-op path.
        warn_if_legacy_cwd_db_exists("/tmp/dyno-data-test/dyno.db", "dyno-nonexistent-test.db");
    }
}
