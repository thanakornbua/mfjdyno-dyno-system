use dyno_core::{app::App, config::Config, deps, detect};
use tracing::{info, warn};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // ── Logging ───────────────────────────────────────────────────────────────
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    // ── Config ────────────────────────────────────────────────────────────────
    let mut config = Config::from_env()?;
    detect::resolve_devices(&mut config);
    info!("dyno backend starting\nEffective configuration:\n{config}");

    // ── Dependency check ─────────────────────────────────────────────────────
    for check in deps::check_dependencies(&config) {
        let line = format!(
            "dependency [{}/{}]: {:?} — {}",
            check.category, check.name, check.status, check.detail
        );
        if check.status == deps::DependencyStatus::Ok {
            info!("{line}");
        } else if check.required {
            warn!("{line} (required: {})", check.remediation);
        } else {
            info!("{line} (optional: {})", check.remediation);
        }
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    let _app = App::start(config).await?;

    // TODO: drive the main event loop here once tasks are implemented.
    // Expected flow:
    //   1. serial task feeds decoded frames → fusion channel
    //   2. bme280 task feeds ambient samples → fusion channel
    //   3. fusion task produces LiveFrames → ws broadcast + storage write
    //   4. main loop waits for SIGTERM / SIGINT and triggers graceful shutdown

    info!("all subsystems initialised — awaiting shutdown signal");
    let signal = wait_for_shutdown_signal().await?;
    info!("shutdown signal received ({signal}) — exiting");

    Ok(())
}

#[cfg(unix)]
async fn wait_for_shutdown_signal() -> anyhow::Result<&'static str> {
    use tokio::signal::unix::{SignalKind, signal};

    let mut sigterm = signal(SignalKind::terminate())?;
    let mut sigint = signal(SignalKind::interrupt())?;

    tokio::select! {
        _ = sigterm.recv() => Ok("SIGTERM"),
        _ = sigint.recv() => Ok("SIGINT"),
    }
}

#[cfg(not(unix))]
async fn wait_for_shutdown_signal() -> anyhow::Result<&'static str> {
    tokio::signal::ctrl_c().await?;
    Ok("ctrl_c")
}
