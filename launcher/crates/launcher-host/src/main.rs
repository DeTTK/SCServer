use anyhow::Result;
use bscp_launcher_core::Layout;
use bscp_launcher_host::{ipc, supervisor::Supervisor};
use fs2::FileExt;
#[tokio::main(worker_threads = 2)]
async fn main() -> Result<()> {
    let args = std::env::args().collect::<Vec<_>>();
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
    #[cfg(windows)]
    ipc::serve(host).await?;
    Ok(())
}
