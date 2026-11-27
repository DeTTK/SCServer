use anyhow::{Context, Result, bail};
#[cfg(windows)]
use bscp_launcher_core::storage::{current_user_sid, process_user_sid};
use bscp_launcher_core::Layout;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, path::PathBuf, time::Duration};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
pub fn pipe_name(root: &std::path::Path) -> String {
    let key = root.to_string_lossy().replace('/', "\\").to_lowercase();
    format!(
        "\\\\.\\pipe\\bscp-rust-{}",
        hex::encode(Sha256::digest(key.as_bytes()))[..24].to_owned()
    )
}
pub async fn line<R: AsyncRead + Unpin>(r: &mut R) -> Result<Vec<u8>> {
    let mut b = Vec::new();
    loop {
        let c = r.read_u8().await?;
        if c == b'\n' {
            return Ok(b);
        }
        if b.len() >= 1024 * 1024 {
            bail!("IPC_LIMIT: message exceeds 1 MiB")
        }
        b.push(c);
    }
}
#[derive(Clone)]
pub struct HostClient {
    root: PathBuf,
}
impl HostClient {
    pub fn new(layout: &Layout) -> Self {
        Self {
            root: layout.root.clone(),
        }
    }
    pub fn call(&self, command: &str, args: &BTreeMap<String, String>) -> Result<Value> {
        let root = self.root.clone();
        let command = command.to_owned();
        let args = args.clone();
        let deadline = if matches!(command.as_str(), "status" | "metrics" | "probe" | "server-summary") {
            5
        } else {
            1800
        };
        std::thread::spawn(move || tokio::runtime::Builder::new_current_thread().enable_all().build()?.block_on(async move {
            #[cfg(windows)] {
                use tokio::net::windows::named_pipe::ClientOptions;
                let name = pipe_name(&root);
                let started = tokio::time::Instant::now();
                let mut pipe = loop {
                    match ClientOptions::new().open(&name) {
                        Ok(pipe) => break pipe,
                        Err(e) if e.raw_os_error() == Some(231) && started.elapsed() < Duration::from_millis(300) => {
                            tokio::time::sleep(Duration::from_millis(20)).await;
                        }
                        Err(e) => return Err(e).with_context(|| format!("IPC_OPEN: связь со службой для {}", root.display())),
                    }
                };
                verify_server_user(&pipe).context("IPC_VERIFY: проверка владельца службы")?;
                let mut bytes = serde_json::to_vec(&json!({"ProtocolVersion":1,"RequestId":uuid::Uuid::new_v4().simple().to_string(),"Command":command,"Arguments":args}))?;
                bytes.push(b'\n');
                pipe.write_all(&bytes).await?;
                let raw = tokio::time::timeout(Duration::from_secs(deadline), line(&mut pipe)).await??;
                let response: Value = serde_json::from_slice(&raw)?;
                if response["Ok"] != true {
                    let code = response["Code"].as_str().unwrap_or("OPERATION_FAILED");
                    let error = response["Error"].as_str().unwrap_or("Supervisor rejected the operation");
                    if error.split(':').next() == Some(code) { bail!("{error}"); }
                    bail!("{code}: {error}");
                }
                Ok(response["Data"].clone())
            }
            #[cfg(not(windows))] {let _=(root,command,args,deadline);bail!("Windows named pipes required")}
        })).join().map_err(|_|anyhow::anyhow!("IPC worker panicked"))?
    }
    pub fn status(&self) -> Result<bscp_launcher_core::ProcessView> {
        Ok(serde_json::from_value(
            self.call("status", &BTreeMap::new())?,
        )?)
    }
}
pub fn ensure_host(layout: &Layout) -> Result<HostClient> {
    let c = HostClient::new(layout);
    let exe = std::env::current_exe()?;
    // An idle instance may already have committed to exit. Wait for its lock/pipe
    // to disappear instead of handing the caller a retiring supervisor.
    for _ in 0..30 {
        if c.status().is_ok_and(|s| s.state == "Exiting") {
            std::thread::sleep(Duration::from_millis(100));
        } else {
            break;
        }
    }
    let status = match c.status() {
        Ok(status) => Some(status),
        Err(error) if error.downcast_ref::<std::io::Error>().is_some_and(|error| error.raw_os_error() == Some(5)) => {
            return Err(error).context("HOST_ACCESS: Windows отказала в доступе к уже запущенной службе лаунчера");
        }
        Err(_) => None,
    };
    if let Some(status) = status {
        let expected = bscp_launcher_core::sha(&exe)?;
        let raw = c.call("status", &BTreeMap::new())?;
        if raw["HostBuild"].as_str() == Some(&expected) {
            return Ok(c);
        }
        if status.client_running
            || matches!(
                status.state.as_str(),
                "Ready" | "Starting" | "Stopping" | "Preparing" | "Updating"
            )
        {
            bail!("HOST_VERSION: остановите игру и сервер перед сменой версии Supervisor")
        }
        c.call("shutdown", &BTreeMap::new())?;
        for _ in 0..30 {
            std::thread::sleep(Duration::from_millis(100));
            if c.status().is_err() {
                break;
            }
        }
    }
    let mut cmd = std::process::Command::new(&exe);
    cmd.arg("--host").arg("--root").arg(&layout.root);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000 | 0x00000008);
    }
    cmd.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .with_context(|| format!("start {}", exe.display()))?;
    for _ in 0..100 {
        std::thread::sleep(Duration::from_millis(100));
        if c.call("status", &BTreeMap::new()).is_ok() {
            return Ok(c);
        }
    }
    bail!("HOST_START: Rust supervisor did not open its pipe")
}
#[cfg(windows)]
pub async fn serve(s: std::sync::Arc<crate::supervisor::Supervisor>) -> Result<()> {
    use tokio::net::windows::named_pipe::ServerOptions;
    // Only the current token user SID can connect; elevated token owner groups get no grant.
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{
            Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW,
            SECURITY_ATTRIBUTES,
        },
    };
    let sddl: Vec<u16> = format!("D:P(A;;GA;;;{})", current_user_sid()?)
        .encode_utf16()
        .chain(Some(0))
        .collect();
    let mut descriptor = std::ptr::null_mut();
    if unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            1,
            &mut descriptor,
            std::ptr::null_mut(),
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error().into());
    }
    let mut sa = SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: descriptor,
        bInheritHandle: 0,
    };
    let mut create = |first| unsafe {
        ServerOptions::new()
            .first_pipe_instance(first)
            .reject_remote_clients(true)
            .create_with_security_attributes_raw(
                pipe_name(&s.layout.root),
                &mut sa as *mut _ as *mut _,
            )
    };
    let mut pipe = create(true)?;
    loop {
        tokio::select! {r=pipe.connect()=>{r?},_=s.exit.notified()=>break}
        let connected = pipe;
        pipe = create(false)?;
        let host = s.clone();
        tokio::spawn(async move {
            let mut pipe = connected;
            let result: Result<Value> = async {
                let raw = tokio::time::timeout(Duration::from_secs(30), line(&mut pipe)).await??;
                let r: Value = serde_json::from_slice(&raw)?;
                if r["ProtocolVersion"] != 1 {
                    bail!("IPC_VERSION")
                }
                host.execute(r).await
            }
            .await;
            let v = match result {
                Ok(v) => json!({"Ok":true,"Data":v,"Code":"","Error":""}),
                Err(e) => {
                    let message = e.to_string();
                    let prefix = message.split(':').next().unwrap_or("");
                    let code = if !prefix.is_empty()
                        && prefix.bytes().all(|c| c.is_ascii_uppercase() || c == b'_')
                    {
                        prefix
                    } else {
                        "OPERATION_FAILED"
                    };
                    json!({"Ok":false,"Data":null,"Code":code,"Error":format!("{e:#}")})
                }
            };
            if let Ok(mut b) = serde_json::to_vec(&v) {
                b.push(b'\n');
                let _ = pipe.write_all(&b).await;
            }
        });
    }
    unsafe { LocalFree(descriptor) };
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn frame_limit_and_incomplete_are_rejected() {
        let (mut a, mut b) = tokio::io::duplex(8192);
        let producer = tokio::spawn(async move {
            let payload = vec![b'x'; 1024 * 1024 + 1];
            let _ = a.write_all(&payload).await;
        });
        assert!(
            line(&mut b)
                .await
                .unwrap_err()
                .to_string()
                .contains("IPC_LIMIT")
        );
        drop(b);
        producer.await.unwrap();
        let mut empty = &b"no newline"[..];
        assert!(line(&mut empty).await.is_err());
    }
    #[test]
    fn rust_pipe_is_distinct_and_root_specific() {
        assert_ne!(
            pipe_name(std::path::Path::new("X:/one")),
            pipe_name(std::path::Path::new("X:/two"))
        );
        assert!(pipe_name(std::path::Path::new("X:/one")).contains("bscp-rust-"));
    }
}
#[cfg(windows)]
fn verify_server_user(pipe: &tokio::net::windows::named_pipe::NamedPipeClient) -> Result<()> {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::{
        Foundation::CloseHandle,
        System::{
            Pipes::GetNamedPipeServerProcessId,
            Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION},
        },
    };
    unsafe {
        let mut pid = 0;
        if GetNamedPipeServerProcessId(pipe.as_raw_handle(), &mut pid) == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if process.is_null() {
            return Err(std::io::Error::last_os_error().into());
        }
        let sid = process_user_sid(process);
        CloseHandle(process);
        if sid? != current_user_sid()? {
            bail!("IPC_OWNER: supervisor pipe belongs to a different Windows user")
        }
        Ok(())
    }
}
