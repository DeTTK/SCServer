use crate::{
    launch,
    network::{self, SignedProxy},
};
use anyhow::{Context, Result, bail};
use bscp_launcher_core::*;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::Digest;
use std::{
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    sync::{Mutex as AsyncMutex, Notify},
};
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
struct Owned {
    pid: u32,
    started: u64,
    session: String,
    world: String,
    admin_port: u16,
    remote: String,
    proxy_port: u16,
    pending_client: bool,
    server_release: String,
    #[serde(skip)]
    ready: Arc<AtomicBool>,
}
#[derive(Default)]
struct Running {
    server: Option<Owned>,
    client: Option<Owned>,
    world_lease: Option<Lease>,
    client_lease: Option<Lease>,
    proxy: Option<SignedProxy>,
    server_tunnel: Option<crate::relay::Tunnel>,
    client_tunnel: Option<crate::relay::Tunnel>,
    blocked: Option<String>,
}
#[derive(Default, Deserialize, Serialize)]
pub struct ServerSummary {
    pub players: Option<Vec<String>>,
    pub uptime_ms: Option<u64>,
    pub heap_used: Option<u64>,
}
pub struct Supervisor {
    pub layout: Layout,
    pub exit: Arc<Notify>,
    lifecycle: AsyncMutex<()>,
    relay_start: AsyncMutex<()>,
    running: Mutex<Running>,
    view: Mutex<Value>,
    java_root: Option<PathBuf>,
    qa: Option<PathBuf>,
    http: reqwest::Client,
    agent: PathBuf,
    retiring: std::sync::atomic::AtomicBool,
    deliveries: Arc<std::sync::atomic::AtomicUsize>,
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> Result<T> {
    Ok(serde_json::from_slice(&std::fs::read(p)?)?)
}
fn write_json(p: &Path, v: &impl Serialize) -> Result<()> {
    bscp_launcher_core::write_json(p, v)
}
fn create_session_folder(path: &Path) -> Result<()> {
    std::fs::create_dir_all(path)
        .with_context(|| format!("AGENT_SESSION_CREATE: создание папки запуска {}", path.display()))?;
    owner_only(path, true)
        .with_context(|| format!("AGENT_SESSION_ACCESS: защита папки запуска {}", path.display()))
}
#[cfg(windows)]
pub fn process_time(pid: u32) -> Option<u64> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, FILETIME, STILL_ACTIVE},
        System::Threading::{
            GetExitCodeProcess, GetProcessTimes, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
        },
    };
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return None;
        }
        let mut code = 0;
        let mut a = FILETIME::default();
        let mut b = a;
        let mut c = a;
        let mut d = a;
        let ok = GetExitCodeProcess(h, &mut code) != 0
            && code == STILL_ACTIVE as u32
            && GetProcessTimes(h, &mut a, &mut b, &mut c, &mut d) != 0;
        CloseHandle(h);
        ok.then_some(((a.dwHighDateTime as u64) << 32) | a.dwLowDateTime as u64)
    }
}
#[cfg(not(windows))]
pub fn process_time(_: u32) -> Option<u64> {
    None
}
fn alive(o: &Owned) -> bool {
    process_time(o.pid) == Some(o.started)
}
#[cfg(windows)]
fn child_start_time(child: &tokio::process::Child) -> Option<u64> {
    use windows_sys::Win32::{Foundation::FILETIME, System::Threading::GetProcessTimes};

    // The original handle remains valid after exit and cannot refer to a recycled PID.
    let handle = child.raw_handle()?;
    let mut creation = FILETIME::default();
    let mut exit = FILETIME::default();
    let mut kernel = FILETIME::default();
    let mut user = FILETIME::default();
    let success =
        unsafe { GetProcessTimes(handle, &mut creation, &mut exit, &mut kernel, &mut user) };
    (success != 0)
        .then_some(((creation.dwHighDateTime as u64) << 32) | creation.dwLowDateTime as u64)
}
#[cfg(not(windows))]
fn child_start_time(child: &tokio::process::Child) -> Option<u64> {
    child.id().and_then(process_time)
}
impl Supervisor {
    pub fn new(
        layout: Layout,
        java_root: Option<PathBuf>,
        qa: Option<PathBuf>,
    ) -> Result<Arc<Self>> {
        let exe = std::env::current_exe()?;
        let mut agent = exe.parent().unwrap().join("modules/bscp-bridge.jar");
        if !agent.exists() {
            agent = layout.root.join("modules/bscp-bridge.jar")
        }
        Ok(Arc::new(Self {
            layout,
            exit: Arc::new(Notify::new()),
            lifecycle: AsyncMutex::new(()),
            relay_start: AsyncMutex::new(()),
            running: Mutex::new(Running::default()),
            view: Mutex::new(
                json!({"State":"Stopped","Pid":null,"SessionId":"","WorldId":"","Detail":"Готов к запуску","ClientRunning":false,"HostBuild":sha(&exe)?}),
            ),
            java_root,
            qa,
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(5))
                .redirect(reqwest::redirect::Policy::none())
                .build()?,
            agent,
            retiring: std::sync::atomic::AtomicBool::new(false),
            deliveries: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
        }))
    }
    fn state(&self, state: &str, detail: &str) {
        let r = self.running.lock().unwrap();
        let mut v = self.view.lock().unwrap();
        v["Transfer"] = Value::Null;
        v["State"] = json!(state);
        v["Detail"] = json!(detail);
        v["ClientRunning"] = json!(r.client.as_ref().is_some_and(alive));
        if let Some(o) = &r.server {
            v["Pid"] = json!(o.pid);
            v["WorldId"] = json!(o.world);
            v["SessionId"] = json!(
                Path::new(&o.session)
                    .file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
            );
        } else {
            v["Pid"] = Value::Null;
        }
        let _ = write_json(&self.layout.root.join("state/rust-runtime.json"), &*v);
    }
    fn transfer_progress(&self, update: bscp_launcher_core::ProgressUpdate) {
        match update {
            bscp_launcher_core::ProgressUpdate::Message(text) => self.state("Updating", &text),
            bscp_launcher_core::ProgressUpdate::Transfer(snapshot) => {
                let mut view = self.view.lock().unwrap();
                if view["Transfer"]["operation"].as_str() == Some(&snapshot.operation)
                    && view["Transfer"]["sequence"]
                        .as_u64()
                        .is_some_and(|old| old >= snapshot.sequence)
                {
                    return;
                }
                view["State"] = json!("Updating");
                view["Detail"] = json!(snapshot.title);
                view["Transfer"] = json!(snapshot);
            }
        }
    }
    fn status(&self) -> Value {
        let mut v = self.view.lock().unwrap().clone();
        v["ClientRunning"] = json!(
            self.running
                .lock()
                .unwrap()
                .client
                .as_ref()
                .is_some_and(alive)
        );
        if self.retiring.load(std::sync::atomic::Ordering::Acquire) {
            v["State"] = json!("Exiting");
        }
        let r = self.running.lock().unwrap();
        v["RelayInvite"] = json!(
            r.server_tunnel
                .as_ref()
                .map(|t| t.invite.as_str())
                .unwrap_or("")
        );
        v
    }
    async fn enable_relay(&self, base: u16) {
        let Ok(_start) = self.relay_start.try_lock() else { return };
        let s = self.layout.load().unwrap_or_default();
        if !s.relay_enabled {
            return;
        }
        let owner = {
            let running = self.running.lock().unwrap();
            if running.server_tunnel.is_some() { return; }
            running.server.clone().filter(|server| server.admin_port.checked_sub(100) == Some(base) && alive(server))
        };
        let Some(owner) = owner else { return };
        self.view.lock().unwrap()["RelayStatus"] = json!("Проверяем ретрансляторы…");
        match crate::relay::Tunnel::host(base, &s.relay_url, &self.layout.root).await {
            Ok(t) => {
                let host = t.relay_host();
                let mut running = self.running.lock().unwrap();
                if !running.server.as_ref().is_some_and(|server| server.pid == owner.pid && server.started == owner.started && alive(server)) {
                    return;
                }
                running.server_tunnel = Some(t);
                drop(running);
                self.view.lock().unwrap()["RelayStatus"] = json!(match host {
                    Some(host) => format!("Приглашение готово · {host}"),
                    None => "Приглашение готово".into(),
                });
            }
            Err(e) => {
                self.view.lock().unwrap()["RelayStatus"] = json!(format!(
                    "Ретранслятор недоступен: {e}. Сервер доступен по обычному адресу."
                ));
            }
        }
    }
    fn worlds(&self) -> Result<WorldStore> {
        WorldStore::new(self.layout.data(&self.layout.load()?))
    }
    async fn write_run_context(&self, folder: &Path, group: &str) {
        let layout = self.layout.clone();
        let folder = folder.to_owned();
        let group = group.to_owned();
        let _ =
            tokio::task::spawn_blocking(move || write_telemetry_context(&layout, &folder, &group))
                .await;
    }
    fn report_background_error(&self, operation: &'static str, error: anyhow::Error) {
        let layout = self.layout.clone();
        let deliveries = self.deliveries.clone();
        deliveries.fetch_add(1, std::sync::atomic::Ordering::AcqRel);
        tokio::task::spawn_blocking(move || {
            let _activity = DeliveryActivity(deliveries);
            let _ = record_launcher_error(&layout, "host", operation, &error);
            let _ = flush_telemetry(&layout, Duration::from_secs(4));
        });
    }
    fn game_agent(&self) -> Result<PathBuf> {
        if self.qa.is_some() && self.agent.is_file() {
            return Ok(self.agent.clone());
        }
        let s = self.layout.load()?;
        if let Ok(root) = self.layout.bundle(&s, "bootstrap") {
            let candidate = component_file(&root, "bscp-bridge.jar")?;
            if candidate.is_file() {
                return Ok(candidate);
            }
        }
        Ok(self.agent.clone())
    }
    fn java(&self, source: &Path) -> Result<PathBuf> {
        let root = if let Some(p) = &self.java_root {
            p.clone()
        } else if let Ok(runtime) = self
            .layout
            .bundle(&self.layout.load()?, "bootstrap")
            .and_then(|p| component_file(&p, "java17"))
        {
            if runtime.join("bin/java.exe").is_file() {
                runtime
            } else {
                std::env::current_exe()?
                    .parent()
                    .unwrap()
                    .join("modules/java17")
            }
        } else {
            let p = std::env::current_exe()?
                .parent()
                .unwrap()
                .join("modules/java17");
            if p.exists() {
                p
            } else if self.layout.root.join("modules/java17").exists() {
                self.layout.root.join("modules/java17")
            } else {
                component_file(source, "jre")?
            }
        };
        launch::java(&root)
    }
    fn guard_busy(&self) -> Result<()> {
        let r = self.running.lock().unwrap();
        if let Some(s) = &r.blocked {
            bail!("RECOVERY_BLOCKED: {s}")
        }
        Ok(())
    }
    async fn admin(&self, o: &Owned, route: &str, post: bool) -> Result<Value> {
        if !alive(o) {
            bail!("SERVER_EXIT: owned process exited")
        }
        let token = std::fs::read_to_string(
            self.worlds()?
                .folder(&o.world)?
                .join("data/admin-token.txt"),
        )?;
        let url = format!("http://127.0.0.1:{}/{route}", o.admin_port);
        let mut r = if post {
            self.http
                .post(url)
                .body("{}")
                .header("Content-Type", "application/json")
        } else {
            self.http.get(url)
        }
        .bearer_auth(token.trim())
        .send()
        .await?;
        if !(route == "health" && r.status() == reqwest::StatusCode::SERVICE_UNAVAILABLE) {
            r = r.error_for_status()?;
        }
        if r.content_length().unwrap_or(0) > 1024 * 1024 {
            bail!("ADMIN_LIMIT")
        }
        let mut b = Vec::new();
        while let Some(chunk) = r.chunk().await? {
            if b.len() + chunk.len() > 1024 * 1024 {
                bail!("ADMIN_LIMIT");
            }
            b.extend_from_slice(&chunk);
        }
        Ok(serde_json::from_slice(&b)?)
    }
    async fn verify(&self, o: &Owned) -> Result<()> {
        let v = self.admin(o, "server", false).await?;
        if v["pid"].as_u64() != Some(o.pid as u64) {
            bail!("SERVER_OWNER: control port belongs to another process")
        }
        Ok(())
    }
    async fn bridge_metrics(&self, o: &Owned, kind: &str) -> Result<Value> {
        if !alive(o) {
            bail!("METRICS_OWNER: owned process exited or PID was reused")
        }
        let folder = child(&self.layout.root, &o.session.replace('\\', "/"))?;
        let status_path = folder.join("agent-status.json");
        let properties_path = folder.join("agent.properties");
        for (path, limit) in [(&status_path, 16384), (&properties_path, 32768)] {
            no_links(path)?;
            if std::fs::metadata(path)?.len() > limit {
                bail!("METRICS_METADATA_LIMIT")
            }
        }
        let status: Value = read_json(&status_path)?;
        if status["pid"].as_u64() != Some(o.pid as u64) || status["kind"] != kind {
            bail!("METRICS_OWNER: Java agent does not attest this owned process")
        }
        let port = status["port"]
            .as_u64()
            .filter(|p| (1..=65535).contains(p))
            .context("METRICS_PORT")?;
        let properties = std::fs::read_to_string(properties_path)?;
        let token = properties
            .lines()
            .filter_map(|l| l.split_once('='))
            .find_map(|(k, v)| (k == "token").then_some(v.trim()))
            .context("METRICS_TOKEN")?;
        if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
            bail!("METRICS_TOKEN: invalid private agent token")
        }
        // The endpoint is constructed locally; session metadata can supply a port, never a URL.
        let mut response = self
            .http
            .get(format!("http://127.0.0.1:{port}/metrics"))
            .bearer_auth(token)
            .timeout(Duration::from_secs(2))
            .send()
            .await?
            .error_for_status()?;
        if response.content_length().unwrap_or(0) > 1024 * 1024 {
            bail!("METRICS_LIMIT")
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            if bytes.len() + chunk.len() > 1024 * 1024 {
                bail!("METRICS_LIMIT")
            }
            bytes.extend_from_slice(&chunk);
        }
        let mut metrics: Value = serde_json::from_slice(&bytes)?;
        if !alive(o) || metrics["pid"].as_u64() != Some(o.pid as u64) {
            bail!("METRICS_OWNER: response PID does not match owned process")
        }
        #[cfg(windows)]
        unsafe {
            use windows_sys::Win32::{
                Foundation::CloseHandle,
                System::{
                    ProcessStatus::{GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS_EX},
                    Threading::{OpenProcess, PROCESS_QUERY_INFORMATION, PROCESS_VM_READ},
                },
            };
            let handle = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, 0, o.pid);
            if !handle.is_null() {
                let mut memory: PROCESS_MEMORY_COUNTERS_EX = std::mem::zeroed();
                memory.cb = std::mem::size_of::<PROCESS_MEMORY_COUNTERS_EX>() as u32;
                if GetProcessMemoryInfo(
                    handle,
                    (&mut memory as *mut PROCESS_MEMORY_COUNTERS_EX).cast(),
                    memory.cb,
                ) != 0
                {
                    metrics["rssBytes"] = json!(memory.WorkingSetSize);
                    metrics["privateBytes"] = json!(memory.PrivateUsage);
                }
                CloseHandle(handle);
            }
        }
        Ok(metrics)
    }
    async fn optional_bridge_metrics(
        &self,
        o: Option<Owned>,
        kind: &str,
    ) -> (Option<Value>, Option<String>) {
        match o {
            None => (None, None),
            Some(o) => match self.bridge_metrics(&o, kind).await {
                Ok(value) => (Some(value), None),
                Err(e) => (None, Some(e.to_string())),
            },
        }
    }
    async fn server_summary(&self, owned: &Owned, include_legacy_jvm: bool) -> Result<ServerSummary> {
        match self.admin(owned, "server/summary", false).await {
            Ok(summary) => return Ok(serde_json::from_value(summary)?),
            Err(error) if error.downcast_ref::<reqwest::Error>()
                .and_then(reqwest::Error::status) == Some(reqwest::StatusCode::NOT_FOUND) => {}
            Err(error) => return Err(error),
        }
        // Older installed servers expose these values only through diagnostics.
        let (health, server) = tokio::join!(
            self.admin(owned, "health", false),
            async {
                if include_legacy_jvm { self.bridge_metrics(owned, "server").await.unwrap_or_default() }
                else { Value::Null }
            },
        );
        let health = health?;
        Ok(ServerSummary {
            players: health["game"]["players"].as_array().map(|rows| rows.iter()
                .filter_map(|row| row["name"].as_str().map(str::to_owned)).collect()),
            uptime_ms: server["uptimeMs"].as_u64().or_else(|| health["uptimeMs"].as_u64()),
            heap_used: server["heapUsed"].as_u64().or_else(|| health["jvm"]["heapUsed"].as_u64()),
        })
    }
    pub async fn recover(&self) -> Result<()> {
        for kind in ["server", "client"] {
            let file = self
                .layout
                .root
                .join(format!("state/rust-{kind}-ownership.json"));
            if !file.exists() {
                continue;
            }
            let o: Owned = read_json(&file).context("read ownership record")?;
            if o.pid == 0 || !alive(&o) {
                continue;
            }
            let result: Result<()> = async {
                let folder = self.layout.root.join(&o.session);
                if !folder.starts_with(&self.layout.root) || o.session.contains("..") {
                    bail!("invalid session path")
                }
                let status: Value = read_json(&folder.join("agent-status.json"))?;
                if status["pid"].as_u64() != Some(o.pid as u64) {
                    bail!("agent did not attest PID")
                }
                if kind == "server" {
                    self.verify(&o).await?;
                    launch::need(&folder.join("server-patches.ok"))?;
                    let lease = self.worlds()?.supervisor_lease(&o.world)?;
                    let mut r = self.running.lock().unwrap();
                    r.world_lease = Some(lease);
                    r.server = Some(o.clone());
                } else {
                    let lease =
                        ClientProfile::lease(&self.layout.client_data(&self.layout.load()?))?;
                    let proxy = SignedProxy::start(
                        network::endpoint(&o.remote)?,
                        LocalIdentity::load(&self.layout)?,
                        Some(o.proxy_port),
                    )
                    .await?;
                    let mut r = self.running.lock().unwrap();
                    r.client_lease = Some(lease);
                    r.proxy = Some(proxy);
                    r.client = Some(o.clone());
                }
                Ok(())
            }
            .await;
            if let Err(e) = result {
                self.running.lock().unwrap().blocked =
                    Some(format!("Live {kind} PID {} requires recovery: {e}", o.pid));
                self.state(
                    "Failed",
                    &format!("Не удалось восстановить управление: {e}"),
                );
                return Ok(());
            }
            if kind == "server" {
                let h = self.admin(&o, "health", false).await?;
                o.ready.store(h["ready"] == true, Ordering::Release);
                self.state(
                    if h["ready"] == true {
                        "Ready"
                    } else {
                        "Starting"
                    },
                    "Восстановлено управление сервером",
                );
            }
        }
        Ok(())
    }
    pub async fn execute(self: &Arc<Self>, r: Value) -> Result<Value> {
        let cmd = r["Command"].as_str().unwrap_or("");
        let args = &r["Arguments"];
        let a = |k: &str| -> Result<String> {
            Ok(args[k]
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("ARGUMENT: {k}"))?
                .to_owned())
        };
        match cmd {
            "status" => return Ok(self.status()),
            "server-summary" => {
                let owned = self.running.lock().unwrap().server.clone();
                let Some(owned) = owned.filter(alive) else {
                    return Ok(serde_json::to_value(ServerSummary::default())?);
                };
                return Ok(serde_json::to_value(self.server_summary(&owned, true).await?)?);
            }
            "metrics" => {
                let (owned_server, owned_client) = {
                    let r = self.running.lock().unwrap();
                    (r.server.clone(), r.client.clone())
                };
                let health_request = async {
                    if let Some(o) = &owned_server {
                        tokio::time::timeout(Duration::from_secs(2), self.admin(o, "health", false))
                            .await
                            .ok()
                            .and_then(Result::ok)
                    } else {
                        None
                    }
                };
                let (mut health, (server, server_error), (client, client_error)) = tokio::join!(
                    health_request,
                    self.optional_bridge_metrics(owned_server.clone(), "server"),
                    self.optional_bridge_metrics(owned_client, "client")
                );
                // Preserve the controller's prior health.jvm / health.uptimeMs reads as aliases.
                if let (Some(h), Some(jvm)) = (health.as_mut(), server.as_ref()) {
                    if h.is_object() {
                        h["jvm"] = jvm.clone();
                        h["uptimeMs"] = jvm["uptimeMs"].clone();
                    }
                }
                return Ok(
                    json!({"state":self.status(),"server":server,"client":client,"health":health,"metricErrors":{"server":server_error,"client":client_error},"relayUdp":self.relay_udp_metrics(),"addresses":network::local_addresses().iter().map(|a| format!("{a}:{}", self.layout.load().map(|s| s.base_port).unwrap_or(29545))).collect::<Vec<_>>()}),
                );
            }
            "probe" => {
                let address = a("address")?;
                if crate::relay::is_invite(&address) {
                    self.deliveries
                        .fetch_add(1, std::sync::atomic::Ordering::AcqRel);
                    let _activity = DeliveryActivity(self.deliveries.clone());
                    let settings = self.layout.load()?;
                    return Ok(
                        match crate::relay::Tunnel::join(
                            &address,
                            &settings.relay_url,
                            &self.layout.root,
                        )
                        .await
                        {
                            Ok(_) => {
                                json!({"online":true,"detail":"Приглашение доступно"})
                            }
                            Err(_) => json!({"online":false,"detail":"Приглашение недоступно"}),
                        },
                    );
                }
                let u = network::endpoint(&address)?;
                let start = std::time::Instant::now();
                let probe = self.http.get(u.join("health")?).send().await;
                return Ok(match probe {
                    Ok(r) => {
                        json!({"online":r.status().is_success(),"milliseconds":start.elapsed().as_millis(),"detail":r.status().to_string()})
                    }
                    Err(e) => {
                        json!({"online":false,"milliseconds":start.elapsed().as_millis(),"detail":e.to_string()})
                    }
                });
            }
            _ => {}
        }
        let _lock = self.lifecycle.lock().await;
        if self.retiring.load(std::sync::atomic::Ordering::Acquire) {
            bail!("HOST_EXITING: supervisor is stopping; retry the operation");
        }
        self.guard_busy()?;
        match cmd {
            "start" => {
                if let Err(e) = self
                    .start(&a("world")?, args["client"].as_str() != Some("false"))
                    .await
                {
                    if !e.to_string().starts_with("START_PENDING") {
                        let active = self
                            .running
                            .lock()
                            .unwrap()
                            .server
                            .as_ref()
                            .is_some_and(alive);
                        self.state(if active { "Starting" } else { "Failed" }, &e.to_string());
                    }
                    return Err(e);
                }
                Ok(self.status())
            }
            "connect" => {
                let settings = self.layout.load()?;
                self.start_client(
                    &a("address")?,
                    args["nickname"].as_str().unwrap_or(&settings.nickname),
                )
                .await?;
                Ok(self.status())
            }
            "stop" => {
                self.stop().await?;
                Ok(self.status())
            }
            "checkpoint" => {
                let o = self
                    .running
                    .lock()
                    .unwrap()
                    .server
                    .clone()
                    .ok_or_else(|| anyhow::anyhow!("SERVER_STOPPED"))?;
                self.verify(&o).await?;
                self.admin(&o, "world/checkpoint", true).await
            }
            "shutdown" => {
                self.require_stopped()?;
                // Let the accepted pipe finish writing the acknowledgement before main exits.
                let exit = self.exit.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(250)).await;
                    exit.notify_one();
                });
                Ok(json!({"stopped":true}))
            }
            "backup" => {
                self.require_stopped()?;
                let path = self.worlds()?.automatic_backup(
                    &a("world")?,
                    self.layout.load()?.automatic_backups as usize,
                )?;
                Ok(json!({"path":path}))
            }
            "update" => {
                self.require_stopped()?;
                let host = self.clone();
                self.state("Updating", "Проверяем игровые компоненты…");
                let result = tokio::task::spawn_blocking(move || -> Result<Value> {
                    let s = host.layout.load()?;
                    let progress_host = host.clone();
                    let progress = move |update| progress_host.transfer_progress(update);
                    if s.game_branch.is_empty() {
                        Ok(serde_json::to_value(
                            UpdateService::new(&host.layout)?
                                .install(&s.feed_url, Some(&progress))?,
                        )?)
                    } else {
                        Ok(serde_json::to_value(
                            RepositoryChannel::new(&host.layout)?
                                .install(&s.game_branch, Some(&progress))?,
                        )?)
                    }
                })
                .await?;
                self.state(
                    "Stopped",
                    if result.is_ok() {
                        "Игра обновлена. Готов к запуску"
                    } else {
                        "Обновление прервано; прежняя установка сохранена"
                    },
                );
                result
            }
            "rollback" => {
                self.require_stopped()?;
                let layout = self.layout.clone();
                Ok(tokio::task::spawn_blocking(move || -> Result<Value> {
                    Ok(serde_json::to_value({
                        let s = layout.load()?;
                        if s.game_branch.is_empty() {
                            serde_json::to_value(UpdateService::new(&layout)?.rollback()?)?
                        } else {
                            serde_json::to_value(
                                RepositoryChannel::new(&layout)?.rollback(&s.game_branch)?,
                            )?
                        }
                    })?)
                })
                .await??)
            }
            "report" => Ok(
                json!({"path":Reports::create(&self.layout,args["description"].as_str().unwrap_or(""),args["performance"].as_str()==Some("true"))?}),
            ),
            _ => bail!("UNKNOWN_COMMAND: {cmd}"),
        }
    }
    fn require_stopped(&self) -> Result<()> {
        let r = self.running.lock().unwrap();
        if r.server.as_ref().is_some_and(alive) || r.client.as_ref().is_some_and(alive) {
            bail!("CHILDREN_RUNNING: close game and save/stop server first")
        }
        Ok(())
    }
    fn agent_config(
        &self,
        folder: &Path,
        kind: &str,
        source: Option<&Path>,
        native: Option<&str>,
        world: &str,
    ) -> Result<()> {
        let s = self.layout.load()?;
        let mut p = std::collections::BTreeMap::<String, String>::from([
            ("kind".into(), kind.into()),
            ("output".into(), launch::path(folder)),
            (
                "session".into(),
                folder.file_name().unwrap().to_string_lossy().into_owned(),
            ),
            (
                "token".into(),
                format!(
                    "{}{}",
                    uuid::Uuid::new_v4().simple(),
                    uuid::Uuid::new_v4().simple()
                ),
            ),
            ("diagnostics".into(), s.diagnostics.to_string()),
        ]);
        if let Some(src) = source {
            p.insert("serverRoot".into(), launch::path(&component_base(src)?));
            p.insert(
                "serverJar".into(),
                launch::path(&component_file(src, "server.jar")?),
            );
            p.insert(
                "ownerKey".into(),
                LocalIdentity::load(&self.layout)?.public_key,
            );
            p.insert(
                "worldLease".into(),
                launch::path(&self.worlds()?.folder(world)?.join("data/.server-lease")),
            );
        }
        if let Some(n) = native {
            p.insert("nativeSession".into(), n.into());
            p.insert(
                "clientLease".into(),
                launch::path(
                    &self
                        .layout
                        .client_data(&s)
                        .join("profiles/default/.client-lease"),
                ),
            );
        }
        atomic_write(
            &folder.join("agent.properties"),
            p.iter()
                .map(|(k, v)| format!("{k}={}\n", launch::prop(v)))
                .collect::<String>().as_bytes(),
        )
        .context("AGENT_CONFIG_WRITE: запись конфигурации Java")?;
        Ok(())
    }
    async fn start(&self, id: &str, with_client: bool) -> Result<()> {
        {
            let mut running = self.running.lock().unwrap();
            if running.server.as_ref().is_some_and(|o| !alive(o)) {
                running.server = None;
                running.world_lease = None;
                running.server_tunnel = None;
            }
        }
        let existing = self.running.lock().unwrap().server.clone().filter(alive);
        if let Some(existing) = existing {
            if with_client
                && !self
                    .running
                    .lock()
                    .unwrap()
                    .client
                    .as_ref()
                    .is_some_and(alive)
            {
                let mut settings = self.layout.load()?;
                settings.selected_world = existing.world.clone();
                self.layout.save(&settings)?;
                self.start_client(
                    &format!("http://127.0.0.1:{}/", existing.admin_port - 100),
                    &settings.nickname,
                )
                .await?;
            }
            self.enable_relay(existing.admin_port - 100).await;
            return Ok(());
        }
        let s = self.layout.load()?;
        s.validate()?;
        let worlds = self.worlds()?;
        self.state("Preparing", "Подготавливаем мир…");
        let prepare_layout = self.layout.clone();
        let prepare_id = id.to_owned();
        let world = tokio::task::spawn_blocking(move || {
            crate::world_tools::prepare(
                &prepare_layout,
                &prepare_id,
                &Arc::new(std::sync::atomic::AtomicBool::new(false)),
            )
        })
        .await?
        .context("SERVER_WORLD_PREPARE: prepare the selected world")?;
        if world.base_identity_mode != "content-v2" {
            bail!("WORLD_MIGRATION")
        }
        let map = worlds.map(&world)?;
        let source = self.layout.bundle(&s, "server")?;
        let java = self
            .java(&source)
            .context("SERVER_JAVA: resolve the bundled JVM")?;
        {
            let _lease = worlds.lease(id)?;
        }
        let data = worlds.folder(id)?.join("data");
        let server_digest = sha(&component_file(&source, "server.jar")?)?;
        // Full portable backups include the map. Preserve one before changing the
        // server version; ordinary restarts rely on durable saves and rollback heads.
        if world.server_release != server_digest
            && std::fs::read_dir(&data)?.filter_map(|x| x.ok()).any(|x| {
                !matches!(
                    x.file_name().to_str(),
                    Some(".server-lease" | "admin-token.txt")
                )
            })
        {
            worlds.automatic_backup(id, s.automatic_backups as usize)?;
        }
        let lease = worlds.lease(id)?;
        let session = self
            .layout
            .root
            .join("state/sessions")
            .join(uuid::Uuid::new_v4().simple().to_string());
        create_session_folder(&session)?;
        self.state("Preparing", "Проверка карты и портов");
        if world.schema_version == 1 && map_digest(&map)? != world.map_hash {
            self.state("Failed", "Базовая карта изменилась");
            bail!("MAP_HASH")
        }
        network::check_ports(s.base_port as u16)?;
        self.agent_config(&session, "server", Some(&source), None, id)
            .context("SERVER_AGENT_CONFIG: prepare the Java diagnostics agent")?;
        self.write_run_context(&session, &relative(&self.layout.root, &session))
            .await;
        let child = launch::server(
            &source,
            &map,
            &data,
            &session,
            &s,
            &self.game_agent()?,
            &java,
        )
        .context("SERVER_RUNTIME: prepare the JVM command")?
        .spawn()
        .context("SERVER_JAVA_SPAWN: start the JVM")?;
        let pid = child.id().unwrap();
        let started = child_start_time(&child);
        let ready = pump(
            child,
            &session,
            "server",
            &self.layout,
            self.deliveries.clone(),
        );
        let o = Owned {
            pid,
            started: started.context("SERVER_PROCESS_TIME: read the JVM creation time")?,
            session: session
                .strip_prefix(&self.layout.root)?
                .to_string_lossy()
                .into_owned(),
            world: id.into(),
            admin_port: s.base_port as u16 + 100,
            pending_client: false,
            server_release: server_digest,
            ready,
            ..Default::default()
        };
        write_json(
            &self.layout.root.join("state/rust-server-ownership.json"),
            &o,
        )?;
        {
            let mut r = self.running.lock().unwrap();
            r.server = Some(o.clone());
            r.world_lease = Some(lease);
        }
        self.state("Starting", "Сервер загружает мир");
        if with_client {
            if let Err(e) = self
                .start_client(&format!("http://127.0.0.1:{}/", s.base_port), &s.nickname)
                .await
            {
                self.state(
                    "Starting",
                    &format!("Сервер запускается. Клиент не запущен: {e}"),
                );
                return Err(e);
            }
        }
        self.enable_relay(s.base_port).await;
        Ok(())
    }
    async fn start_client(&self, address: &str, nickname: &str) -> Result<()> {
        {
            let mut running = self.running.lock().unwrap();
            if running.client.as_ref().is_some_and(|o| !alive(o)) {
                running.client = None;
                running.client_lease = None;
                running.proxy = None;
                running.client_tunnel = None;
            }
        }
        if self
            .running
            .lock()
            .unwrap()
            .client
            .as_ref()
            .is_some_and(alive)
        {
            bail!("CLIENT_RUNNING")
        }
        if nickname.trim().is_empty()
            || nickname.len() > 128
            || nickname.chars().any(char::is_control)
        {
            bail!("LOGIN_INVALID")
        }
        let s = self.layout.load()?;
        let lease = ClientProfile::lease(&self.layout.client_data(&s))?;
        let source = self.layout.bundle(&s, "client")?;
        let game = GameLayout::resolve(self.layout.resolve(&s.game_path))?;
        let java = self.java(&source)?;
        let tunnel = if crate::relay::is_invite(address) {
            Some(crate::relay::Tunnel::join(address, &s.relay_url, &self.layout.root).await?)
        } else {
            None
        };
        let remote = if let Some(t) = &tunnel {
            network::endpoint(&format!("http://127.0.0.1:{}/", t.backend_port))?
        } else {
            network::endpoint(address)?
        };
        let identity = LocalIdentity::load(&self.layout)?;
        let login = format!("bscp-{}", identity.id);
        let local_starting = tunnel.is_none()
            && matches!(remote.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"))
            && self
                .running
                .lock()
                .unwrap()
                .server
                .as_ref()
                .is_some_and(|o| {
                    alive(o) && remote.port_or_known_default() == Some(o.admin_port - 100)
                });
        let folder = self
            .layout
            .root
            .join("state/sessions")
            .join(format!("client-{}", uuid::Uuid::new_v4().simple()));
        create_session_folder(&folder)?;
        let alias_supported = local_starting
            && native_parallel_start_supported(&self.layout.bundle(&s, "server")?).unwrap_or(false);
        let (proxy, native) = if local_starting {
            SignedProxy::start_delayed(
                remote.clone(),
                identity,
                nickname,
                folder.join("native-session.json"),
                alias_supported,
            )
            .await?
        } else {
            let p = SignedProxy::start(remote.clone(), identity, None).await?;
            let n = p.session(nickname).await?;
            (p, n)
        };
        self.agent_config(&folder, "client", None, Some(&native), "")?;
        if local_starting {
            let mut file = std::fs::OpenOptions::new()
                .append(true)
                .open(folder.join("agent.properties"))?;
            writeln!(file, "deferredSessionAlias={alias_supported}")?;
            writeln!(
                file,
                "deferredSessionFile={}",
                launch::prop(&launch::path(&folder.join("native-session.json")))
            )?;
        }
        if let Some(t) = &tunnel {
            let file = folder.join("agent.properties");
            let mut config = std::fs::OpenOptions::new().append(true).open(file)?;
            writeln!(
                config,
                "relayRemoteBase={}\nrelayGamePort={}\nrelayWorldPort={}",
                t.remote_base, t.game_port, t.world_port
            )?;
        }
        let profile = ClientProfile::prepare(&self.layout.client_data(&s), &source)?;
        let group = self
            .running
            .lock()
            .unwrap()
            .server
            .as_ref()
            .filter(|_| local_starting)
            .map(|s| s.session.clone())
            .unwrap_or_else(|| relative(&self.layout.root, &folder));
        self.write_run_context(&folder, &group).await;
        let child = launch::client(
            &source,
            &game,
            &folder,
            &profile,
            &proxy.url(),
            nickname,
            &login,
            &s,
            &self.game_agent()?,
            &java,
            self.qa.as_deref(),
        )
        .context("CLIENT_RUNTIME: prepare the JVM command")?
        .spawn()
        .context("CLIENT_JAVA_SPAWN: start the JVM")?;
        let pid = child.id().unwrap();
        let started = child_start_time(&child);
        pump(
            child,
            &folder,
            "client",
            &self.layout,
            self.deliveries.clone(),
        );
        let o = Owned {
            pid,
            started: started.context("CLIENT_PROCESS_TIME: read the JVM creation time")?,
            session: folder
                .strip_prefix(&self.layout.root)?
                .to_string_lossy()
                .into_owned(),
            remote: if tunnel.is_some() {
                address.to_owned()
            } else {
                remote.to_string()
            },
            proxy_port: proxy.port,
            ..Default::default()
        };
        write_json(
            &self.layout.root.join("state/rust-client-ownership.json"),
            &o,
        )?;
        if self.qa.is_some() {
            let control_port = launch::qa_control_port()?;
            write_json(
                &self.layout.root.join("state/qa-client.json"),
                &json!({"pid":pid,"folder":folder,"control":control_port,"guard":control_port+1,"proxy":proxy.url()}),
            )?;
        }
        let mut r = self.running.lock().unwrap();
        r.client = Some(o);
        r.client_lease = Some(lease);
        r.proxy = Some(proxy);
        r.client_tunnel = tunnel;
        Ok(())
    }
    async fn stop(&self) -> Result<()> {
        let o = self.running.lock().unwrap().server.clone();
        let Some(o) = o.filter(alive) else {
            self.state("Stopped", "Сервер остановлен");
            return Ok(());
        };
        self.verify(&o).await?;
        self.state("Stopping", "Сохраняется мир");
        self.admin(&o, "world/checkpoint", true).await?;
        self.admin(&o, "server/stop", true).await?;
        for _ in 0..720 {
            if !alive(&o) {
                let mut r = self.running.lock().unwrap();
                r.server = None;
                r.world_lease = None;
                r.server_tunnel = None;
                drop(r);
                write_json(
                    &self.layout.root.join("state/rust-server-ownership.json"),
                    &Owned::default(),
                )?;
                self.view.lock().unwrap()["RelayStatus"] = json!("");
                self.state("Stopped", "Мир сохранён, сервер остановлен");
                return Ok(());
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }
        bail!("STOP_PENDING: server is saving; no force kill performed")
    }

    pub async fn refresh_public_listing(self: Arc<Self>) {
        use crate::directory::{ICON_PATH, PublicServer, Publisher, decode_icon};
        let logger = RotatingLog::new(self.layout.at("logs"), "public-directory-host");
        let mut publisher = Publisher::default();
        let mut advertised = None::<iroh::Endpoint>;
        let mut next_send = Instant::now();
        let mut next_relay = Instant::now();
        let mut was_enabled = false;
        loop {
            let settings = self.layout.load().unwrap_or_default();
            let enabled = settings.relay_enabled && settings.public_listing;
            let stopping = self.view.lock().unwrap()["State"] == "Stopping";
            let (owned, tunnel) = if enabled && !stopping {
                let running = self.running.lock().unwrap();
                let world = running.server.as_ref()
                    .filter(|server| server.ready.load(Ordering::Acquire) && alive(server)).cloned();
                let tunnel = running.server_tunnel.as_ref()
                    .map(|tunnel| (tunnel.directory_endpoint(), tunnel.public_invite()));
                (world, tunnel)
            } else {
                (None, None)
            };
            if let Some(server) = owned.as_ref().filter(|_| tunnel.is_none() && Instant::now() >= next_relay) {
                next_relay = Instant::now() + Duration::from_secs(10);
                let host = self.clone();
                let base = server.admin_port - 100;
                tokio::spawn(async move { host.enable_relay(base).await });
            }
            let active = owned.zip(tunnel);
            if let Some(previous) = &advertised {
                if active.as_ref().is_none_or(|(_, (endpoint, _))| endpoint.id() != previous.id()) {
                    if let Err(error) = publisher.advertise(&self.layout, previous, None).await {
                        let _ = logger.line(&redact(&format!("Listing withdrawal failed: {error:#}")));
                    }
                    advertised = None;
                    next_send = Instant::now();
                }
            }
            let status = match active {
                Some((server, (endpoint, invite))) if Instant::now() >= next_send => {
                    let submission: Result<()> = async {
                        let invite = invite?;
                        let (_, relay) = crate::relay::public_ticket(&invite)?;
                        anyhow::ensure!(crate::directory::public_relay(&relay), "DIRECTORY_PUBLIC_RELAY");
                        let players = self.server_summary(&server, false).await?.players.context("DIRECTORY_PLAYERS")?;
                        let world = self.worlds()?.get(&server.world)?;
                        let icon = read_json::<String>(&self.layout.at(ICON_PATH)).ok().filter(|icon| decode_icon(icon).is_ok());
                        advertised = Some(endpoint.clone());
                        publisher.advertise(&self.layout, &endpoint, Some(PublicServer {
                            name: world.name,
                            invite,
                            icon,
                            players: players.len().min(u16::MAX as usize) as u16,
                        })).await
                    }.await;
                    match submission {
                        Ok(()) => {
                            next_send = Instant::now() + Duration::from_secs(7);
                            Some("Сервер опубликован")
                        }
                        Err(error) => {
                            next_send = Instant::now() + Duration::from_secs(2);
                            let _ = logger.line(&redact(&format!("Listing publication failed: {error:#}")));
                            if error.to_string() == "DIRECTORY_PUBLIC_RELAY" {
                                Some("Для публикации нужен ретранслятор из публичного списка BSCPCRAFT")
                            } else {
                                Some("Публикация недоступна. Повторим автоматически.")
                            }
                        }
                    }
                }
                Some(_) => None,
                None if enabled => {
                    next_send = Instant::now();
                    Some("Публикация начнётся, когда мир и ретранслятор будут готовы")
                }
                None if was_enabled => Some(""),
                None => None,
            };
            if let Some(status) = status {
                self.view.lock().unwrap()["DirectoryStatus"] = json!(status);
            }
            was_enabled = enabled;
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }
    fn relay_udp_metrics(&self) -> Value {
        let state = self.running.lock().unwrap();
        json!({
            "host": state.server_tunnel.as_ref().map(crate::relay::Tunnel::udp_metrics),
            "client": state.client_tunnel.as_ref().map(crate::relay::Tunnel::udp_metrics)
        })
    }

    pub async fn record_diagnostics(self: Arc<Self>) {
        let logger = RotatingLog::new(self.layout.at("logs"), "performance");
        let outbox = TelemetryOutbox::new(&self.layout);
        let mut last_queued = std::time::Instant::now() - Duration::from_secs(30);
        let mut seen = std::collections::HashSet::<[u8; 32]>::new();
        let mut intervals = PerformanceSampler::default();
        let mut prior_health: Option<(u32, u64, u64)> = None;
        let mut recent = std::collections::VecDeque::<Value>::new();
        let mut active_group = String::new();
        let mut last_incident = std::time::Instant::now() - Duration::from_secs(30);
        loop {
            tokio::time::sleep(Duration::from_secs(5)).await;
            let (server, client) = {
                let state = self.running.lock().unwrap();
                (state.server.clone(), state.client.clone())
            };
            if server.is_none() && client.is_none() {
                continue;
            }
            let health = async {
                if let Some(owned) = server.as_ref() {
                    tokio::time::timeout(Duration::from_secs(2), self.admin(owned, "health", false))
                        .await
                        .ok()
                        .and_then(Result::ok)
                } else {
                    None
                }
            };
            let (health, (server_metrics, server_error), (client_metrics, client_error)) = tokio::join!(
                health,
                self.optional_bridge_metrics(server.clone(), "server"),
                self.optional_bridge_metrics(client.clone(), "client")
            );
            let mut health = health.map(compact_health);
            let server_metrics = server_metrics.map(|m| intervals.sample("server", m));
            let client_metrics = client_metrics.map(|m| intervals.sample("client", m));
            if let (Some(h), Some(s)) = (health.as_mut(), server.as_ref()) {
                let tick = h["native"]["tick"].as_u64().unwrap_or(0);
                let skipped = h["world"]["skippedSlots"].as_u64().unwrap_or(0);
                if let Some((pid, old_tick, old_skipped)) =
                    prior_health.filter(|(pid, _, _)| *pid == s.pid)
                {
                    let _ = pid;
                    let ms = server_metrics
                        .as_ref()
                        .and_then(|m| m["window"]["sampleMs"].as_f64())
                        .filter(|ms| *ms > 0.0);
                    h["window"] = json!({"nativeTicksDelta":tick.saturating_sub(old_tick),"nativeTps":ms.map(|ms|tick.saturating_sub(old_tick)as f64*1000.0/ms),"skippedSlotsDelta":skipped.saturating_sub(old_skipped)});
                }
                prior_health = Some((s.pid, tick, skipped));
            }
            let folder = server
                .as_ref()
                .or(client.as_ref())
                .map(|o| self.layout.at(&o.session));
            let context = folder
                .as_ref()
                .map(|p| telemetry_context(&self.layout, p))
                .unwrap_or(Value::Null);
            let group = context["runGroup"].as_str().unwrap_or("");
            if group != active_group {
                recent.clear();
                active_group = group.into();
                last_queued = std::time::Instant::now() - Duration::from_secs(30);
            }
            let sample = json!({"type":"performance","run":server.as_ref().or(client.as_ref()).map(|o|o.session.as_str()),"serverPid":server.as_ref().map(|v|v.pid),"clientPid":client.as_ref().map(|v|v.pid),
                "runGroup":context["runGroup"],"context":context,"time":chrono::Utc::now().to_rfc3339(),"health":health,"server":server_metrics,"client":client_metrics,"errors":{"server":server_error,"client":client_error},"relayUdp":self.relay_udp_metrics()});
            let _ = logger.line(&sample.to_string());
            recent.push_back(sample.clone());
            while recent.len() > 12 {
                recent.pop_front();
            }
            let lines = recent
                .iter()
                .map(Value::to_string)
                .collect::<Vec<_>>()
                .join("\n")
                + "\n";
            for owned in [server.as_ref(), client.as_ref()].into_iter().flatten() {
                let _ = atomic_write(
                    &self
                        .layout
                        .at(&owned.session)
                        .join("performance-window.jsonl"),
                    lines.as_bytes(),
                );
            }
            let mut reasons = Vec::new();
            if sample["client"]["window"]["slowFrames"]
                .as_f64()
                .unwrap_or(0.0)
                >= 3.0
            {
                reasons.push("client-slow-frames");
            }
            if sample["health"]["window"]["skippedSlotsDelta"]
                .as_u64()
                .unwrap_or(0)
                > 0
            {
                reasons.push("server-missed-ticks");
            }
            for role in ["client", "server"] {
                if sample[role]["heapUsagePct"].as_f64().unwrap_or(0.0) > 95.0
                    || sample[role]["window"]["gcTimePct"].as_f64().unwrap_or(0.0) > 20.0
                {
                    reasons.push(if role == "client" {
                        "client-memory-pressure"
                    } else {
                        "server-memory-pressure"
                    });
                }
            }
            if !reasons.is_empty() && last_incident.elapsed() >= Duration::from_secs(30) {
                let mut incident = sample.clone();
                incident["reasons"] = json!(reasons);
                let _ = outbox.enqueue("performance-incident", incident);
                last_incident = std::time::Instant::now();
            }
            let upload_due = last_queued.elapsed() >= Duration::from_secs(30);
            if upload_due {
                let _ = outbox.enqueue("performance", sample);
                last_queued = std::time::Instant::now();
            }
            for (role, owned) in [("server", server.as_ref()), ("client", client.as_ref())] {
                if let Some(owned) = owned {
                    capture_agent_events(&self.layout, role, owned, &outbox, &mut seen);
                }
            }
            // Cloud Faro indexes by receipt time. Small periodic batches preserve a useful
            // live timeline; full session logs still ship only after the process exits.
            if upload_due {
                let layout = self.layout.clone();
                let _ = tokio::task::spawn_blocking(move || {
                    flush_telemetry(&layout, Duration::from_secs(2))
                })
                .await;
            }
        }
    }
    pub async fn monitor(self: Arc<Self>) {
        let mut idle_since = Some(std::time::Instant::now());
        let mut startup = None::<(u32, std::time::Instant, bool)>;
        loop {
            tokio::time::sleep(Duration::from_secs(2)).await;
            let Ok(_lock) = self.lifecycle.try_lock() else {
                continue;
            };
            let (exited, server) = {
                let mut r = self.running.lock().unwrap();
                if r.client.as_ref().is_some_and(|o| !alive(o)) {
                    if let Some(o) = &r.client {
                        let _ = RotatingLog::new(self.layout.at("logs"), "lifecycle").line(
                            &json!({"type":"client-exit","pid":o.pid,"session":o.session})
                                .to_string(),
                        );
                    }
                    r.client = None;
                    r.client_lease = None;
                    r.proxy = None;
                    r.client_tunnel = None;
                    let _ = write_json(
                        &self.layout.root.join("state/rust-client-ownership.json"),
                        &Owned::default(),
                    );
                }
                let exited = r.server.as_ref().filter(|o| !alive(o)).cloned();
                if let Some(o) = &exited {
                    let _ = RotatingLog::new(self.layout.at("logs"), "lifecycle").line(
                        &json!({"type":"unexpected-server-exit","pid":o.pid,"session":o.session})
                            .to_string(),
                    );
                    r.server = None;
                    r.world_lease = None;
                    r.server_tunnel = None;
                }
                (exited, r.server.clone())
            };
            if let Some(exited) = exited {
                if let Some(client) = self.running.lock().unwrap().client.as_ref() {
                    let file = self.layout.at(&client.session).join("native-session.json");
                    if !file.exists() {
                        let _ = write_json(&file, &String::new());
                    }
                }
                self.view.lock().unwrap()["RelayStatus"] = json!("");
                let memory_failure =
                    jvm_native_memory_failure(&self.layout.at(&exited.session), exited.pid);
                let detail = if memory_failure {
                    let message = "Windows не смогла выделить память для Java-сервера. Освободите память или уменьшите лимиты игры и сервера в настройках.";
                    let _ = record_launcher_error(
                        &self.layout,
                        "host",
                        "server-exit",
                        &anyhow::anyhow!("SERVER_JVM_MEMORY: {message}"),
                    );
                    message
                } else {
                    "Процесс сервера завершился вне команды остановки; проверьте журнал"
                };
                self.state("Failed", detail);
            }
            if let Some(o) = server {
                let starting = self.status()["State"] == "Starting";
                if starting || o.pending_client {
                    if let Ok(health) = self.admin(&o, "health", false).await {
                        if health["ready"] == true
                            && self.verify(&o).await.is_ok()
                            && self
                                .layout
                                .root
                                .join(&o.session)
                                .join("server-patches.ok")
                                .is_file()
                        {
                            if let Ok(worlds) = self.worlds() {
                                if let Ok(mut world) = worlds.get(&o.world) {
                                    if !o.server_release.is_empty() {
                                        world.server_release = o.server_release.clone();
                                    }
                                    world.last_played = Some(chrono::Utc::now().to_rfc3339());
                                    let _ = worlds.save(&world);
                                }
                            }
                            o.ready.store(true, Ordering::Release);
                            self.state("Ready", "Мир запущен");
                            if o.pending_client {
                                let result: Result<()> = async {
                                    self.clear_pending()?;
                                    let s = self.layout.load()?;
                                    self.start_client(
                                        &format!("127.0.0.1:{}", o.admin_port - 100),
                                        &s.nickname,
                                    )
                                    .await
                                }
                                .await;
                                if let Err(e) = result {
                                    self.state("Ready", &format!("Клиент не запущен: {e}"));
                                }
                            }
                        }
                    }
                }
                if starting && self.view.lock().unwrap()["State"] == "Starting" {
                    if startup.as_ref().is_none_or(|(pid, _, _)| *pid != o.pid) {
                        startup = Some((o.pid, std::time::Instant::now(), false));
                    }
                    if let Some((_, since, reported)) = &mut startup {
                        if !*reported && since.elapsed() >= Duration::from_secs(120) {
                            *reported = true;
                            let stderr = self.layout.at(&o.session).join("server-stderr.log");
                            let tail = diagnostic_tail(&stderr, 4096).unwrap_or_default();
                            self.report_background_error(
                                "server-readiness",
                                anyhow::anyhow!(
                                    "SERVER_READINESS_TIMEOUT: JVM still running; readiness checks did not pass within 120 seconds (pid {}). JVM stderr tail: {}",
                                    o.pid,
                                    String::from_utf8_lossy(&tail),
                                ),
                            );
                        }
                    }
                } else {
                    startup = None;
                }
            } else {
                startup = None;
            }
            let idle = {
                let r = self.running.lock().unwrap();
                r.server.is_none()
                    && r.client.is_none()
                    && r.blocked.is_none()
                    && self.deliveries.load(std::sync::atomic::Ordering::Acquire) == 0
            };
            if idle {
                let since = idle_since.get_or_insert_with(std::time::Instant::now);
                if since.elapsed() >= Duration::from_secs(5) {
                    self.retiring
                        .store(true, std::sync::atomic::Ordering::Release);
                    tokio::time::sleep(Duration::from_millis(250)).await;
                    self.exit.notify_one();
                    return;
                }
            } else {
                idle_since = None;
            }
        }
    }
    fn clear_pending(&self) -> Result<()> {
        let mut r = self.running.lock().unwrap();
        if let Some(o) = r.server.as_mut() {
            o.pending_client = false;
            write_json(
                &self.layout.root.join("state/rust-server-ownership.json"),
                o,
            )?;
        }
        Ok(())
    }
}
fn capture_agent_events(
    layout: &Layout,
    role: &str,
    owned: &Owned,
    outbox: &TelemetryOutbox,
    seen: &mut std::collections::HashSet<[u8; 32]>,
) {
    let path = layout.at(&owned.session).join("bridge.jsonl");
    let Ok(bytes) = diagnostic_tail(&path, 128 * 1024) else {
        return;
    };
    let text = String::from_utf8_lossy(&bytes);
    let context = text
        .chars()
        .rev()
        .take(16000)
        .collect::<String>()
        .chars()
        .rev()
        .collect::<String>();
    for line in text.lines() {
        let Ok(event) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        let kind = event["type"].as_str().unwrap_or("");
        let message = event["message"].as_str().unwrap_or("").to_ascii_lowercase();
        let relevant = matches!(
            kind,
            "exception"
                | "incident"
                | "watchdog-error"
                | "diagnostic-error"
                | "auto-connect-failed"
        ) || kind == "stderr"
            && ["exception", "error", "failed"]
                .iter()
                .any(|s| message.contains(s));
        if !relevant {
            continue;
        }
        let hash: [u8; 32] = sha2::Sha256::digest(line.as_bytes()).into();
        if !seen.insert(hash) {
            continue;
        }
        if seen.len() > 4096 {
            seen.clear();
            seen.insert(hash);
        }
        let technical = telemetry_context(layout, &layout.at(&owned.session));
        let _=outbox.enqueue("exception",json!({"role":role,"pid":owned.pid,"run":owned.session,"runGroup":technical["runGroup"],"context":technical,"sourceEventHash":hex::encode(hash),"event":event,"logTail":context}));
    }
}

fn compact_health(health: Value) -> Value {
    fn bounded(value: &Value, depth: usize) -> Value {
        if depth == 0 {
            return Value::Null;
        }
        match value {
            Value::Array(v) => json!(
                v.iter()
                    .rev()
                    .take(8)
                    .rev()
                    .map(|v| bounded(v, depth - 1))
                    .collect::<Vec<_>>()
            ),
            Value::Object(v) => Value::Object(
                v.iter()
                    .take(32)
                    .map(|(k, v)| (k.clone(), bounded(v, depth - 1)))
                    .collect(),
            ),
            Value::String(s) => json!(s.chars().take(1024).collect::<String>()),
            _ => value.clone(),
        }
    }
    let mut world = serde_json::Map::new();
    for name in [
        "tick",
        "hz",
        "maxTickMs",
        "skippedSlots",
        "failures",
        "queued",
        "running",
        "ownerTasks",
        "maxOwnerWorkMs",
        "maxOwnerTasksPerTick",
        "tickTiming",
    ] {
        if let Some(value) = health["world"].get(name) {
            world.insert(name.into(), bounded(value, 5));
        }
    }
    let pick = |value: &Value, fields: &[&str]| -> Value {
        Value::Object(
            fields
                .iter()
                .filter_map(|key| value.get(*key).map(|v| ((*key).into(), bounded(v, 5))))
                .collect(),
        )
    };
    let mut native = pick(
        &health["game"],
        &[
            "tick",
            "maxTickMs",
            "tickTiming",
            "datagrams",
            "reliableTransport",
            "nativeAdmin",
            "connectionMismatch",
            "rejected",
            "running",
        ],
    );
    native["playerCount"] = json!(
        health["game"]["players"]
            .as_array()
            .map(Vec::len)
            .unwrap_or(0)
    );
    let chunks = pick(
        &health["world"]["residentMap"]["chunks"],
        &[
            "residentChunks",
            "residentBytes",
            "byteLimit",
            "dirtyChunks",
            "loading",
            "savePending",
            "loads",
            "saves",
            "evictions",
            "loadFailures",
            "saveFailures",
            "failure",
        ],
    );
    let commands = pick(
        &health["commands"],
        &["committed", "queued", "rejected", "failure", "healthy"],
    );
    let actors = pick(
        &health["world"]["actorSimulation"],
        &[
            "dynamicBodies",
            "waitingSteps",
            "sceneRequests",
            "pathRequests",
            "budgetDeferrals",
            "sceneRequestsPerTick",
            "collisionBudgetPerTick",
        ],
    );
    json!({"healthy":health["healthy"],"ready":health["ready"],"world":world,"native":native,"actors":actors,"chunks":chunks,"commands":commands,"checkpoint":if health["automaticCheckpoint"].is_null(){&health["checkpoint"]}else{&health["automaticCheckpoint"]}})
}

#[cfg(test)]
mod log_stream_tests {
    use super::*;
    use tokio::io::AsyncWriteExt;
    #[tokio::test]
    async fn run_context_is_prepared_safely_from_async_lifecycle() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        offline_test_layout(&layout);
        let folder = layout.at("state/sessions/context-fixture");
        std::fs::create_dir_all(&folder).unwrap();
        let host = Supervisor::new(layout.clone(), None, None).unwrap();
        host.write_run_context(&folder, "shared-run").await;
        let context = telemetry_context(&layout, &folder);
        assert_eq!(context["runGroup"], "shared-run");
        assert!(context["mods"].is_array());
    }
    #[tokio::test]
    async fn updated_game_bridge_wins_over_stale_launcher_sidecar() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        offline_test_layout(&layout);
        let mut settings = layout.load().unwrap();
        settings.game_branch.clear();
        layout.save(&settings).unwrap();
        let bundled = layout.at("foundation/Bootstrap/bscp-bridge.jar");
        std::fs::create_dir_all(bundled.parent().unwrap()).unwrap();
        std::fs::write(&bundled, b"new bridge").unwrap();
        let legacy = layout.at("old-bridge.jar");
        std::fs::write(&legacy, b"old bridge").unwrap();
        write_json(
            &layout.at("state/bootstrap.json"),
            &json!({"path":"foundation"}),
        )
        .unwrap();
        let mut host = Supervisor::new(layout, None, None).unwrap();
        Arc::get_mut(&mut host).unwrap().agent = legacy.clone();
        assert_eq!(host.game_agent().unwrap(), bundled);
        Arc::get_mut(&mut host).unwrap().qa = Some("explicit-test-only".into());
        assert_eq!(host.game_agent().unwrap(), legacy);
    }
    #[tokio::test]
    async fn oversized_invalid_utf8_line_is_bounded_and_next_line_survives() {
        let (mut writer, reader) = tokio::io::duplex(4096);
        let producer = tokio::spawn(async move {
            for _ in 0..512 {
                writer.write_all(&[255; 4096]).await.unwrap();
            }
            writer.write_all(b"\nSECOND\n").await.unwrap();
        });
        let mut reader = BufReader::new(reader);
        let first = bounded_line(&mut reader).await.unwrap().unwrap();
        assert!(first.len() < 33000);
        assert!(String::from_utf8_lossy(&first).contains("line truncated"));
        assert_eq!(
            bounded_line(&mut reader).await.unwrap().unwrap(),
            b"SECOND\n"
        );
        assert!(bounded_line(&mut reader).await.unwrap().is_none());
        producer.await.unwrap();
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn process_exit_waiter_keeps_exit_code_and_stderr() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        offline_test_layout(&layout);
        write_json(
            &layout.at("config/telemetry.json"),
            &TelemetryConfig::default(),
        )
        .unwrap();
        let folder = layout.at("state/sessions/pipe-test");
        std::fs::create_dir_all(&folder).unwrap();
        let mut command = tokio::process::Command::new("powershell.exe");
        command
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "[Console]::Out.WriteLine('TEST-START token=private-stdout');[Console]::Error.WriteLine('TEST-CRASH Bearer private-stderr');exit 7",
            ])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .creation_flags(0x08000000);
        let deliveries = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let host = Supervisor::new(layout.clone(), None, None).unwrap();
        host.write_run_context(&folder, "state/sessions/server-test")
            .await;
        pump(
            command.spawn().unwrap(),
            &folder,
            "client",
            &layout,
            deliveries.clone(),
        );
        let queue = TelemetryOutbox::new(&layout);
        let events = tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                let events = queue.pending().unwrap();
                if !events.is_empty() && deliveries.load(std::sync::atomic::Ordering::Acquire) == 0
                {
                    break events;
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await
        .unwrap();
        let exit = events
            .iter()
            .find(|event| event.kind == "process-exit")
            .unwrap();
        assert_eq!(exit.payload["exit"]["exitCode"], 7);
        assert_eq!(exit.payload["exit"]["exitCodeHex"], "0x00000007");
        assert_eq!(exit.payload["exit"]["phase"], "jvm-starting");
        assert_eq!(exit.payload["exit"]["readinessReached"], false);
        assert_eq!(exit.payload["runGroup"], "state/sessions/server-test");
        assert_eq!(exit.payload["role"], "client");
        assert!(exit.payload["exit"]["exitTime"].as_str().is_some());
        assert!(exit.payload["exit"]["uptimeMs"].as_u64().is_some());
        assert!(
            exit.payload["stdout"]
                .as_str()
                .unwrap()
                .contains("TEST-START")
        );
        assert!(
            exit.payload["stderr"]
                .as_str()
                .unwrap()
                .contains("TEST-CRASH")
        );
        assert!(!exit.payload.to_string().contains("private-"));
        let local = std::fs::read_to_string(folder.join("client-exit.jsonl")).unwrap();
        let line: Value = serde_json::from_str(local.trim()).unwrap();
        let report: Value = serde_json::from_str(line["message"].as_str().unwrap()).unwrap();
        assert_eq!(report["exit"], exit.payload["exit"]);
        assert_eq!(report["stderr"], exit.payload["stderr"]);
        assert_eq!(report["stdout"], exit.payload["stdout"]);
        assert!(!local.contains("private-"));
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn fast_exit_preserves_creation_time_through_child_handle() {
        let mut child = tokio::process::Command::new("cmd.exe")
            .args(["/D", "/C", "exit 7"])
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        let pid = child.id().unwrap();
        let creation = child_start_time(&child).unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            while process_time(pid).is_some() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        assert_eq!(child_start_time(&child), Some(creation));
        assert_eq!(child.wait().await.unwrap().code(), Some(7));
    }

    #[test]
    fn server_exit_identifies_native_heap_reservation_failures() {
        let run = tempfile::tempdir().unwrap();
        let folder = run.path();
        std::fs::write(
            folder.join("server-stderr.log"),
            "java.lang.OutOfMemoryError: Java heap space",
        )
        .unwrap();
        assert!(!jvm_native_memory_failure(folder, 24));
        std::fs::write(folder.join("hs_err_pid24.log"),
            "# Native memory allocation (mmap) failed to map 165675008 bytes. Error detail: G1 virtual space").unwrap();
        assert!(jvm_native_memory_failure(folder, 24));
        assert!(!jvm_native_memory_failure(folder, 25));
        std::fs::write(folder.join("server-stdout.log"),
            "Error occurred during initialization of VM\nCould not reserve enough space for 4194304KB object heap").unwrap();
        assert!(jvm_native_memory_failure(folder, 25));
    }

    #[test]
    fn launch_phase_uses_role_specific_evidence() {
        let temp = tempfile::tempdir().unwrap();
        let folder = temp.path();
        assert_eq!(
            launch_phase(folder, "client", Some(42), false),
            "jvm-starting"
        );
        write_json(
            &folder.join("agent-status.json"),
            &json!({"pid":42,"kind":"client"}),
        )
        .unwrap();
        assert_eq!(
            launch_phase(folder, "client", Some(42), false),
            "bridge-listening"
        );
        assert_eq!(
            launch_phase(folder, "client", Some(41), false),
            "jvm-starting"
        );
        let metrics = json!({"type":"metrics","message":json!({"pid":42,"frames":3}).to_string()});
        std::fs::write(folder.join("bridge.jsonl"), metrics.to_string()).unwrap();
        assert_eq!(
            launch_phase(folder, "client", Some(42), false),
            "client-frame-observed"
        );
        std::fs::write(folder.join("server-patches.ok"), "").unwrap();
        assert_eq!(
            launch_phase(folder, "server", Some(42), false),
            "server-patches-loaded"
        );
        assert_eq!(
            launch_phase(folder, "server", Some(42), true),
            "server-ready"
        );
    }
    #[test]
    fn unhealthy_world_metrics_survive_compaction_without_large_payloads() {
        let sample = compact_health(
            json!({"healthy":false,"world":{"tick":42,"skippedSlots":8,"failures":1,"actorSimulation":{"huge":"ignored"}},"checkpoint":{"failures":1}}),
        );
        assert_eq!(sample["world"]["failures"], 1);
        assert_eq!(sample["world"]["skippedSlots"], 8);
        assert!(sample["world"]["actorSimulation"].is_null());
    }
}

async fn bounded_line<R: tokio::io::AsyncBufRead + Unpin>(
    reader: &mut R,
) -> std::io::Result<Option<Vec<u8>>> {
    let mut line = Vec::new();
    let mut truncated = false;
    let mut consumed_any = false;
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            if !consumed_any {
                return Ok(None);
            }
            break;
        }
        consumed_any = true;
        let end = available.iter().position(|b| *b == b'\n');
        let count = end.map(|v| v + 1).unwrap_or(available.len());
        let keep = count.min((32768usize).saturating_sub(line.len()));
        line.extend_from_slice(&available[..keep]);
        truncated |= keep < count;
        reader.consume(count);
        if end.is_some() {
            break;
        }
    }
    if truncated {
        line.extend_from_slice(b" [line truncated]\n");
    }
    Ok(Some(line))
}
fn pump(
    mut child: tokio::process::Child,
    folder: &Path,
    name: &str,
    layout: &Layout,
    deliveries: Arc<std::sync::atomic::AtomicUsize>,
) -> Arc<AtomicBool> {
    fn reader<R: tokio::io::AsyncRead + Unpin + Send + 'static>(
        input: R,
        path: PathBuf,
    ) -> tokio::task::JoinHandle<()> {
        tokio::spawn(async move {
            let mut lines = BufReader::new(input);
            let redact=regex::Regex::new(r#"(?i)((?:Bearer\s+)|(?:(?:token|password|secret|nativeSession|privateKey|session)["']?\s*[:=]\s*["']?))[^\s,"'<>}]+"#).unwrap();
            loop {
                // Native DLLs can write bytes in the local code page despite file.encoding=UTF-8.
                // Keep draining the pipe instead of dropping it on one invalid UTF-8 sequence.
                let bytes = match bounded_line(&mut lines).await {
                    Ok(Some(bytes)) => bytes,
                    _ => break,
                };
                let line = String::from_utf8_lossy(&bytes);
                let text = redact.replace_all(line.trim_end(), "${1}[redacted]");
                if std::fs::metadata(&path).is_ok_and(|m| m.len() > 4 * 1024 * 1024) {
                    for i in (1..=2).rev() {
                        let target = path.with_extension(format!("log.{}", i + 1));
                        let _ = std::fs::remove_file(&target);
                        let _ = std::fs::rename(path.with_extension(format!("log.{i}")), target);
                    }
                    let _ = std::fs::remove_file(path.with_extension("log.1"));
                    let _ = std::fs::rename(&path, path.with_extension("log.1"));
                }
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .append(true)
                    .create(true)
                    .open(&path)
                {
                    let _ = writeln!(f, "{}", text.chars().take(32768).collect::<String>());
                }
            }
        })
    }
    let mut readers = Vec::new();
    if let Some(out) = child.stdout.take() {
        readers.push(reader(out, folder.join(format!("{name}-stdout.log"))));
    }
    if let Some(err) = child.stderr.take() {
        readers.push(reader(err, folder.join(format!("{name}-stderr.log"))));
    }
    let logger = RotatingLog::new(folder, &format!("{name}-exit"));
    let outbox = TelemetryOutbox::new(layout);
    let layout = layout.clone();
    let folder = folder.to_path_buf();
    let role = name.to_owned();
    let pid = child.id();
    let started = Instant::now();
    let ready = Arc::new(AtomicBool::new(false));
    let readiness = ready.clone();
    deliveries.fetch_add(1, std::sync::atomic::Ordering::AcqRel);
    tokio::spawn(async move {
        let _activity = DeliveryActivity(deliveries);
        let result = child.wait().await;
        let time = chrono::Utc::now().to_rfc3339();
        let elapsed_ms = started.elapsed().as_millis() as u64;
        let ready = readiness.load(Ordering::Acquire);
        let phase = launch_phase(&folder, &role, pid, ready);
        let mut entry = match result {
            Ok(status) => {
                let code = status.code();
                json!({"type":"process-exit","success":status.success(),"exitCode":code,"exitCodeHex":code.map(|code| format!("0x{:08X}", code as u32))})
            }
            Err(error) => json!({"type":"wait-error","error":error.to_string()}),
        };
        entry["pid"] = json!(pid);
        entry["exitTime"] = json!(time);
        entry["uptimeMs"] = json!(elapsed_ms);
        entry["phase"] = json!(phase);
        entry["readinessReached"] = json!(ready || phase == "client-frame-observed");
        let _ = tokio::time::timeout(Duration::from_secs(2), async {
            for task in readers {
                let _ = task.await;
            }
        })
        .await;
        let context = telemetry_context(&layout, &folder);
        let mut report = json!({
            "role": role,
            "run": relative(&layout.root, &folder),
            "runGroup": context["runGroup"],
            "context": context,
            "exit": entry
        });
        if entry["success"] != true {
            let native_crash = std::fs::read_dir(&folder).ok().is_some_and(|files| {
                files.filter_map(Result::ok).any(|f| {
                    f.file_name().to_string_lossy().starts_with("hs_err_")
                        && f.metadata().is_ok_and(|m| m.len() > 0)
                })
            });
            report["hasNativeCrash"] = json!(native_crash);
            report["stderr"] = json!(launch_tail(&layout, &folder, &role, "stderr"));
            report["stdout"] = json!(launch_tail(&layout, &folder, &role, "stdout"));
        }
        let _ = logger.line(&report.to_string());
        if entry["success"] != true {
            let _ = outbox.enqueue("process-exit", report);
        }
        let _ = queue_session_logs(&layout, &folder, &role, entry);
        let upload_layout = layout.clone();
        let result = tokio::task::spawn_blocking(move || {
            flush_telemetry(&upload_layout, Duration::from_secs(12))
        })
        .await;
        let message = match result {
            Ok(Ok(summary)) => serde_json::to_string(&summary).unwrap_or_default(),
            _ => "Telemetry delivery deferred; local queue retained".into(),
        };
        let _ = RotatingLog::new(layout.at("logs"), "delivery").line(&message);
    });
    ready
}

fn launch_phase(folder: &Path, role: &str, pid: Option<u32>, ready: bool) -> &'static str {
    if ready {
        return "server-ready";
    }
    if role == "server" && folder.join("server-patches.ok").is_file() {
        return "server-patches-loaded";
    }
    if role == "client" && pid.is_some_and(|pid| observed_client_frame(folder, pid)) {
        return "client-frame-observed";
    }
    let path = folder.join("agent-status.json");
    if let Some(pid) = pid {
        if no_links(&path).is_ok() && std::fs::metadata(&path).is_ok_and(|m| m.len() <= 16384) {
            if let Ok(status) = read_json::<Value>(&path) {
                if status["pid"] == pid && status["kind"] == role {
                    return "bridge-listening";
                }
            }
        }
    }
    "jvm-starting"
}

fn observed_client_frame(folder: &Path, pid: u32) -> bool {
    let tail = diagnostic_tail(&folder.join("bridge.jsonl"), 64 * 1024).unwrap_or_default();
    tail.rsplit(|byte| *byte == b'\n').any(|line| {
        let Ok(event) = serde_json::from_slice::<Value>(line) else {
            return false;
        };
        if event["type"] != "metrics" {
            return false;
        }
        event["message"]
            .as_str()
            .and_then(|message| serde_json::from_str::<Value>(message).ok())
            .is_some_and(|stats| stats["pid"] == pid && stats["frames"].as_u64().unwrap_or(0) > 0)
    })
}

fn jvm_native_memory_failure(folder: &Path, pid: u32) -> bool {
    let is_memory_failure = |bytes: &[u8]| {
        let text = String::from_utf8_lossy(bytes);
        text.contains("There is insufficient memory for the Java Runtime Environment to continue")
            || text.contains("Native memory allocation") && text.contains("failed to map")
            || text.contains("Could not reserve enough space for") && text.contains("object heap")
    };
    let mut header = Vec::new();
    if let Ok(file) = std::fs::File::open(folder.join(format!("hs_err_pid{pid}.log"))) {
        let _ = file.take(8192).read_to_end(&mut header);
    }
    is_memory_failure(&header)
        || ["stdout", "stderr"].iter().any(|stream| {
            diagnostic_tail(&folder.join(format!("server-{stream}.log")), 8192)
                .is_ok_and(|tail| is_memory_failure(&tail))
        })
}

fn launch_tail(layout: &Layout, folder: &Path, role: &str, stream: &str) -> String {
    let path = folder.join(format!("{role}-{stream}.log"));
    let bytes = diagnostic_tail(&path, 4096).unwrap_or_default();
    let root = layout.root.to_string_lossy();
    redact(&String::from_utf8_lossy(&bytes))
        .replace(root.as_ref(), "%LAUNCHER_ROOT%")
        .chars()
        .filter(|c| !c.is_control() || matches!(c, '\n' | '\t'))
        .collect()
}
struct DeliveryActivity(Arc<std::sync::atomic::AtomicUsize>);
impl Drop for DeliveryActivity {
    fn drop(&mut self) {
        self.0.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
    }
}

#[cfg(test)]
fn offline_test_layout(layout: &Layout) {
    layout.init().unwrap();
    // Unit fixtures must never send mock process data to the public collector.
    // Exporter tests explicitly replace this with their loopback receiver.
    write_json(
        &layout.at("config/telemetry.json"),
        &TelemetryConfig::default(),
    )
    .unwrap();
}
#[cfg(all(test, windows))]
mod reuse_tests {
    use super::*;
    #[tokio::test]
    async fn exited_client_releases_its_profile_before_an_immediate_reconnect() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        offline_test_layout(&layout);
        let host = Supervisor::new(layout.clone(), None, None).unwrap();
        let lease = ClientProfile::lease(&layout.client_data(&layout.load().unwrap())).unwrap();
        {
            let mut running = host.running.lock().unwrap();
            running.client = Some(Owned {
                pid: u32::MAX,
                started: 1,
                ..Default::default()
            });
            running.client_lease = Some(lease);
        }
        let error = host.start_client("127.0.0.1", "Player").await.unwrap_err();
        assert!(!error.to_string().contains("WORLD_BUSY"));
        assert!(host.running.lock().unwrap().client.is_none());
        assert!(ClientProfile::lease(&layout.client_data(&layout.load().unwrap())).is_ok());
    }
    #[tokio::test]
    async fn repeated_start_keeps_the_owned_server_and_existing_client() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        offline_test_layout(&layout);
        let host = Supervisor::new(layout, None, None).unwrap();
        let owned = Owned {
            pid: std::process::id(),
            started: process_time(std::process::id()).unwrap(),
            world: "already-running-world".into(),
            admin_port: 29645,
            ..Default::default()
        };
        host.running.lock().unwrap().server = Some(owned.clone());
        host.start("a-different-selected-world", false)
            .await
            .unwrap();
        host.running.lock().unwrap().client = Some(owned.clone());
        host.start("a-different-selected-world", true)
            .await
            .unwrap();
        let running = host.running.lock().unwrap();
        assert_eq!(
            running.server.as_ref().unwrap().world,
            "already-running-world"
        );
        assert_eq!(running.server.as_ref().unwrap().pid, owned.pid);
        assert_eq!(running.client.as_ref().unwrap().pid, owned.pid);
    }
}
#[cfg(all(test, windows))]
mod metrics_tests {
    use super::*;
    #[cfg(windows)]
    #[tokio::test]
    async fn monitor_survives_owned_client_and_exits_after_it_stops() {
        let root = tempfile::tempdir().unwrap();
        let layout = Layout::new(root.path());
        offline_test_layout(&layout);
        let host = Supervisor::new(layout, None, None).unwrap();
        host.running.lock().unwrap().client = Some(Owned {
            pid: std::process::id(),
            started: process_time(std::process::id()).unwrap(),
            ..Default::default()
        });
        let monitor = tokio::spawn(host.clone().monitor());
        assert!(
            tokio::time::timeout(Duration::from_secs(7), host.exit.notified())
                .await
                .is_err()
        );
        host.running.lock().unwrap().client = None;
        tokio::time::timeout(Duration::from_secs(10), host.exit.notified())
            .await
            .unwrap();
        monitor.await.unwrap();
        assert_eq!(host.status()["State"], "Exiting");
        let error = host
            .execute(json!({"Command":"stop","Arguments":{}}))
            .await
            .unwrap_err();
        assert!(error.to_string().contains("HOST_EXITING"));
    }
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };
    async fn fixture(
        body: Vec<u8>,
        chunked: bool,
    ) -> (Arc<Supervisor>, Owned, tokio::task::JoinHandle<()>) {
        let root = std::env::temp_dir().join(format!("bscp-rust-metrics-{}", uuid::Uuid::new_v4()));
        let layout = Layout::new(&root);
        offline_test_layout(&layout);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let pid = std::process::id();
        let owned = Owned {
            pid,
            started: process_time(pid).unwrap(),
            session: "state/sessions/mock".into(),
            ..Default::default()
        };
        let folder = root.join(&owned.session);
        std::fs::create_dir_all(&folder).unwrap();
        write_json(
            &folder.join("agent-status.json"),
            &json!({"pid":pid,"port":port,"kind":"server"}),
        )
        .unwrap();
        std::fs::write(
            folder.join("agent.properties"),
            format!("kind=server\ntoken={}\n", "ab".repeat(32)),
        )
        .unwrap();
        let task = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut header = Vec::new();
            while !header.ends_with(b"\r\n\r\n") {
                header.push(stream.read_u8().await.unwrap())
            }
            let header = String::from_utf8(header).unwrap();
            assert!(header.starts_with("GET /metrics HTTP/1.1"));
            assert!(
                header
                    .to_lowercase()
                    .contains(&format!("authorization: bearer {}", "ab".repeat(32)))
            );
            if chunked {
                let _=stream.write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n").await;
                let _ = stream
                    .write_all(format!("{:x}\r\n", body.len()).as_bytes())
                    .await;
                let _ = stream.write_all(&body).await;
                let _ = stream.write_all(b"\r\n0\r\n\r\n").await;
            } else {
                let _ = stream
                    .write_all(
                        format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        )
                        .as_bytes(),
                    )
                    .await;
                let _ = stream.write_all(&body).await;
            }
        });
        (Supervisor::new(layout, None, None).unwrap(), owned, task)
    }
    #[tokio::test]
    async fn background_sampler_records_without_ui_or_lifecycle_lock() {
        let pid = std::process::id();
        let (host, owned, http) = fixture(
            serde_json::to_vec(&json!({"pid":pid,"heapUsed":123456,"heapMax":654321})).unwrap(),
            false,
        )
        .await;
        host.running.lock().unwrap().server = Some(owned);
        let held = host.lifecycle.lock().await;
        let sampler = tokio::spawn(host.clone().record_diagnostics());
        tokio::time::timeout(Duration::from_secs(9), async {
            loop {
                if host.layout.at("logs/performance.jsonl").is_file() {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await
        .unwrap();
        let pending = TelemetryOutbox::new(&host.layout).pending().unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].payload["server"]["heapUsed"], 123456);
        sampler.abort();
        let _ = sampler.await;
        http.await.unwrap();
        drop(held);
        let root = host.layout.root.clone();
        drop(host);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn failed_process_is_uploaded_after_exit_and_idle_activity_is_released() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        offline_test_layout(&layout);
        let folder = layout.at("state/sessions/upload-test");
        std::fs::create_dir_all(&folder).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let config = TelemetryConfig {
            enabled: true,
            faro_url: format!("http://127.0.0.1:{port}/collect/test"),
            ..Default::default()
        };
        write_json(&layout.at("config/telemetry.json"), &config).unwrap();
        let accepted = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let signal = accepted.clone();
        let receiver = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            signal.store(true, std::sync::atomic::Ordering::Release);
            let mut header = Vec::new();
            while !header.ends_with(b"\r\n\r\n") {
                header.push(stream.read_u8().await.unwrap());
                assert!(header.len() < 8192);
            }
            let header = String::from_utf8(header).unwrap();
            let len = header
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length:")
                        .map(|v| v.trim().parse::<usize>().unwrap())
                })
                .unwrap();
            let mut body = vec![0; len];
            stream.read_exact(&mut body).await.unwrap();
            stream
                .write_all(
                    b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                )
                .await
                .unwrap();
            serde_json::from_slice::<Value>(&body).unwrap()
        });
        let mut command = tokio::process::Command::new("powershell.exe");
        command.args(["-NoProfile","-NonInteractive","-Command","[Console]::Out.WriteLine('SESSION-END-PROBE');Start-Sleep -Milliseconds 1500;exit 7"])
            .stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped()).creation_flags(0x08000000);
        let activity = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        pump(
            command.spawn().unwrap(),
            &folder,
            "client",
            &layout,
            activity.clone(),
        );
        tokio::time::sleep(Duration::from_millis(400)).await;
        assert!(!accepted.load(std::sync::atomic::Ordering::Acquire));
        tokio::time::timeout(Duration::from_secs(15), async {
            while activity.load(std::sync::atomic::Ordering::Acquire) != 0 {
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await
        .unwrap();
        let body = receiver.await.unwrap();
        assert!(body.to_string().contains("SESSION-END-PROBE"));
        let exception = body["exceptions"]
            .as_array()
            .unwrap()
            .iter()
            .find(|event| event["type"] == "bscp.ProcessExit")
            .unwrap();
        assert_eq!(exception["context"]["role"], "client");
        assert_eq!(exception["context"]["exit_code"], "7");
        assert_eq!(exception["context"]["launch_phase"], "jvm-starting");
        assert_eq!(exception["value"], "SESSION-END-PROBE\n");
        assert!(TelemetryOutbox::new(&layout).pending().unwrap().is_empty());
    }
    #[tokio::test]
    async fn authenticated_bridge_metrics_verify_pid_and_values() {
        let pid = std::process::id();
        let (host, owned, task) = fixture(
            serde_json::to_vec(
                &json!({"pid":pid,"heapUsed":123456,"heapMax":654321,"uptimeMs":7890}),
            )
            .unwrap(),
            false,
        )
        .await;
        let value = host.bridge_metrics(&owned, "server").await.unwrap();
        assert_eq!(value["heapUsed"], 123456);
        assert_eq!(value["uptimeMs"], 7890);
        task.await.unwrap();
        let root = host.layout.root.clone();
        drop(host);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn bridge_rejects_spoofed_response_pid() {
        let (host, owned, task) = fixture(b"{\"pid\":1,\"heapUsed\":123}".to_vec(), false).await;
        assert!(
            host.bridge_metrics(&owned, "server")
                .await
                .unwrap_err()
                .to_string()
                .contains("METRICS_OWNER")
        );
        task.await.unwrap();
        let root = host.layout.root.clone();
        drop(host);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn bridge_stream_limit_applies_without_content_length() {
        let (host, owned, task) = fixture(vec![b'x'; 1024 * 1024 + 1], true).await;
        assert!(
            host.bridge_metrics(&owned, "server")
                .await
                .unwrap_err()
                .to_string()
                .contains("METRICS_LIMIT")
        );
        task.await.unwrap();
        let root = host.layout.root.clone();
        drop(host);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn bridge_rejects_reused_pid_and_untrusted_metadata_before_connect() {
        let (host, mut owned, task) = fixture(b"{}".to_vec(), false).await;
        owned.started += 1;
        assert!(
            host.bridge_metrics(&owned, "server")
                .await
                .unwrap_err()
                .to_string()
                .contains("METRICS_OWNER")
        );
        owned.started -= 1;
        let folder = host.layout.root.join(&owned.session);
        write_json(
            &folder.join("agent-status.json"),
            &json!({"pid":owned.pid,"port":"https://evil.invalid/","kind":"server"}),
        )
        .unwrap();
        assert!(
            host.bridge_metrics(&owned, "server")
                .await
                .unwrap_err()
                .to_string()
                .contains("METRICS_PORT")
        );
        task.abort();
        let _ = task.await;
        let root = host.layout.root.clone();
        drop(host);
        std::fs::remove_dir_all(root).unwrap();
    }
}
