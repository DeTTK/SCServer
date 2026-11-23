//! Send a synthetic diagnostic event from an explicitly named portable QA profile.
use anyhow::{Result, bail};
use bscp_launcher_core::*;
use serde_json::json;
use std::time::Duration;

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 3 || args[2] != "send-synthetic" {
        bail!("telemetry_probe <qa-profile> send-synthetic");
    }
    let layout = Layout::new(&args[1]);
    layout.init()?;
    TelemetryOutbox::new(&layout).enqueue(
        "verification",
        json!({
            "run":"qa-grafana-20261123", "role":"launcher",
            "text":"BSCPCRAFT collector verification: synthetic diagnostic, no player data"
        }),
    )?;
    let result = flush_telemetry(&layout, Duration::from_secs(12))?;
    println!("{}", serde_json::to_string_pretty(&result)?);
    if result.sent_events == 0 || result.remaining != 0 {
        bail!("Grafana did not acknowledge the synthetic event");
    }
    Ok(())
}
