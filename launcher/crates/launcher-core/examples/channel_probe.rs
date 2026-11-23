//! Explicit diagnostic CLI for the experimental repository channel.
use anyhow::{Result, bail};
use bscp_launcher_core::*;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() < 4 {
        bail!(
            "channel_probe <portable-root> <branch> <check|install|rollback|setup> [installed-game]"
        )
    }
    let layout = Layout::new(&args[1]);
    layout.init()?;
    let cancel = Arc::new(AtomicBool::new(false));
    let service = RepositoryChannel::new(&layout)?.with_cancel(cancel.clone());
    let budget = if args[3] == "install-cancel" {
        args.get(4)
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(64)
            * 1024
            * 1024
    } else {
        u64::MAX
    };
    let progress = move |message: ProgressUpdate| match message {
        ProgressUpdate::Message(text) => eprintln!("{text}"),
        ProgressUpdate::Transfer(snapshot) => {
            eprintln!("{}", serde_json::to_string(&snapshot).unwrap());
            if snapshot.downloaded_bytes >= budget {
                cancel.store(true, Ordering::Relaxed);
            }
        }
    };
    let value = match args[3].as_str() {
        "check" => serde_json::to_value(service.check(&args[2])?)?,
        "install" | "install-cancel" => {
            serde_json::to_value(service.install(&args[2], Some(&progress))?)?
        }
        "rollback" => serde_json::to_value(service.rollback(&args[2])?)?,
        "setup" => {
            let mut s = layout.load()?;
            s.game_path = args
                .get(4)
                .ok_or_else(|| anyhow::anyhow!("Installed game directory required"))?
                .clone();
            s.game_branch = args[2].clone();
            let b: std::collections::BTreeMap<String, String> =
                read_json(&layout.at("state/bootstrap.json"))?;
            let root = layout.resolve(
                b.get("path")
                    .ok_or_else(|| anyhow::anyhow!("Install first"))?,
            );
            s.client_path = layout.store(&root.join("Client"));
            s.server_path = layout.store(&root.join("Server"));
            serde_json::to_value(Setup::complete(
                &layout,
                s,
                &root.join("Map"),
                "Мир launtest",
                Some(&progress),
            )?)?
        }
        _ => bail!("Unknown command"),
    };
    println!("{}", serde_json::to_string_pretty(&value)?);
    Ok(())
}
