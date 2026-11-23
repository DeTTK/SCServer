use bscp_launcher_core::*;
use std::{
    path::PathBuf,
    sync::{Arc, atomic::AtomicBool},
};
fn main() -> anyhow::Result<()> {
    let args: Vec<_> = std::env::args().collect();
    let layout = Layout::new(args.get(1).expect("root required"));
    layout.init()?;
    let manager = ModManager::new(&layout);
    let action = args.get(2).map(String::as_str).unwrap_or("list");
    if action == "check" {
        let s = layout.load()?;
        RepositoryChannel::new(&layout)?.check(&s.game_branch)?;
    } else if action == "import" {
        manager.import(&PathBuf::from(args.get(3).expect("JAR required")))?;
    } else if action != "list" {
        manager.apply(
            action,
            args.get(3).expect("mod key required"),
            Arc::new(AtomicBool::new(false)),
            Some(&|p| eprintln!("{p:?}")),
        )?;
    }
    let snap = manager.snapshot()?;
    for (title, rows) in [("catalog", snap.catalog), ("installed", snap.installed)] {
        for row in rows {
            println!(
                "{title} | {} | {} | {} | enabled={} update={}",
                row.id, row.title, row.version, row.enabled, row.update_available
            );
        }
    }
    Ok(())
}
