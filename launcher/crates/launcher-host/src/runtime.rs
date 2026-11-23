use crate::{ipc, supervisor::Supervisor};
use anyhow::Result;
use bscp_launcher_core::Layout;
use fs2::FileExt;
/// Service/RPC entry point shared by the single launcher executable.
/// This is dispatched before Slint, window creation and the UI lease.
pub fn run(args: Vec<String>) -> Result<()> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?
        .block_on(run_async(args))
}

pub fn is_service_mode(args: &[String]) -> bool {
    args.iter()
        .skip(1)
        .any(|arg| matches!(arg.as_str(), "--host" | "--rpc"))
}

async fn run_async(args: Vec<String>) -> Result<()> {
    anyhow::ensure!(
        !(args.iter().any(|v| v == "--host") && args.iter().any(|v| v == "--rpc")),
        "Use --host or --rpc, not both"
    );
    let opt = |key: &str| {
        args.iter()
            .position(|s| s == key)
            .and_then(|i| args.get(i + 1))
            .map(std::path::PathBuf::from)
    };
    let root = opt("--root").unwrap_or(std::env::current_exe()?.parent().unwrap().to_owned());
    let layout = Layout::new(root);
    if let Some(at) = args.iter().position(|v| v == "--rpc") {
        let command = args
            .get(at + 1)
            .ok_or_else(|| anyhow::anyhow!("--rpc requires a command"))?;
        let mut arguments = std::collections::BTreeMap::new();
        for (i, item) in args.iter().enumerate() {
            if item == "--arg" {
                let value = args
                    .get(i + 1)
                    .ok_or_else(|| anyhow::anyhow!("--arg requires key=value"))?;
                let (key, value) = value
                    .split_once('=')
                    .ok_or_else(|| anyhow::anyhow!("--arg requires key=value"))?;
                if key.is_empty() {
                    anyhow::bail!("--arg key must not be empty")
                }
                arguments.insert(key.to_owned(), value.to_owned());
            }
        }
        match ipc::HostClient::new(&layout).call(command, &arguments) {
            Ok(value) => println!("{}", serde_json::to_string(&value)?),
            Err(e) => {
                println!(
                    "{}",
                    serde_json::json!({"Ok":false,"Code":"RPC_FAILED","Error":e.to_string()})
                );
                return Err(e);
            }
        }
        return Ok(());
    }
    layout.init()?;
    let file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(layout.root.join("state/rust-host.lock"))?;
    if file.try_lock_exclusive().is_err() {
        return Ok(());
    }
    let host = Supervisor::new(layout, opt("--java-root"), opt("--isolated-qa-guard"))?;
    host.recover().await?;
    tokio::spawn(host.clone().monitor());
    tokio::spawn(host.clone().record_diagnostics());
    #[cfg(windows)]
    ipc::serve(host).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn args(parts: &[&str]) -> Vec<String> {
        parts.iter().map(|v| v.to_string()).collect()
    }
    #[test]
    fn service_dispatch_precedes_ui_only_for_explicit_modes() {
        assert!(!is_service_mode(&args(&[
            "BSCPCRAFT.exe",
            "--root",
            "portable data"
        ])));
        assert!(is_service_mode(&args(&[
            "BSCPCRAFT.exe",
            "--host",
            "--root",
            "portable data"
        ])));
        assert!(is_service_mode(&args(&[
            "BSCPCRAFT.exe",
            "--rpc",
            "status"
        ])));
        assert!(!is_service_mode(&args(&[
            "BSCPCRAFT.exe",
            "--arg",
            "value=--host"
        ])));
    }
    #[test]
    fn conflicting_background_modes_are_rejected_before_opening_storage() {
        let error = run(args(&["BSCPCRAFT.exe", "--host", "--rpc", "status"])).unwrap_err();
        assert!(error.to_string().contains("not both"));
    }
}
