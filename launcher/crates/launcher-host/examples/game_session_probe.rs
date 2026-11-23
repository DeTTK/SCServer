//! Explicit isolated QA account setup; never touches a production profile.
use anyhow::{Result, ensure};
use bscp_launcher_core::*;
use bscp_launcher_host::{
    network::{SignedProxy, endpoint},
    relay,
};
#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    ensure!(
        args.len() == 4,
        "game_session_probe <qa-root> <address-or-invite> <nickname>"
    );
    let layout = Layout::new(&args[1]);
    ensure!(
        layout
            .root
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("qa-"),
        "isolated QA root required"
    );
    layout.init()?;
    let tunnel = if relay::is_invite(&args[2]) {
        Some(relay::Tunnel::join(&args[2], "").await?)
    } else {
        None
    };
    let remote = if let Some(t) = &tunnel {
        endpoint(&format!("http://127.0.0.1:{}/", t.backend_port))?
    } else {
        endpoint(&args[2])?
    };
    let proxy = SignedProxy::start(remote, LocalIdentity::load(&layout)?, None).await?;
    let session = proxy.session(&args[3]).await?;
    let http = reqwest::Client::new();
    http.post(format!("{}characters/accept_legal", proxy.url()))
        .form(&[
            ("session", session.as_str()),
            ("version", "v15_ru_launcher"),
        ])
        .send()
        .await?
        .error_for_status()?;
    let list: serde_json::Value = http
        .get(format!("{}characters/list", proxy.url()))
        .query(&[("session", session)])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    println!(
        "{}",
        serde_json::json!({"prepared":true,"characters":list["characters"]})
    );
    Ok(())
}
