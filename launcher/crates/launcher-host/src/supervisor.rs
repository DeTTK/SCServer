use crate::{
    launch,
    network::{self, SignedProxy},
};
use anyhow::{Context, Result, bail};
use bscp_launcher_core::*;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
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
}
#[derive(Default)]
struct Running {
    server: Option<Owned>,
    client: Option<Owned>,
    world_lease: Option<Lease>,
    client_lease: Option<Lease>,
    proxy: Option<SignedProxy>,
    blocked: Option<String>,
}
pub struct Supervisor {
    pub layout: Layout,
    pub exit: Arc<Notify>,
    lifecycle: AsyncMutex<()>,
    running: Mutex<Running>,
    view: Mutex<Value>,
    java_root: Option<PathBuf>,
    qa: Option<PathBuf>,
    http: reqwest::Client,
    agent: PathBuf,
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> Result<T> {
    Ok(serde_json::from_slice(&std::fs::read(p)?)?)
}
fn write_json(p: &Path, v: &impl Serialize) -> Result<()> {
    bscp_launcher_core::write_json(p, v)
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
        }))
    }
    fn state(&self, state: &str, detail: &str) {
        let r = self.running.lock().unwrap();
        let mut v = self.view.lock().unwrap();
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
        v
    }
    fn worlds(&self) -> Result<WorldStore> {
        WorldStore::new(self.layout.data(&self.layout.load()?))
    }
    fn game_agent(&self) -> Result<PathBuf> {
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
        let r = if post {
            self.http
                .post(url)
                .body("{}")
                .header("Content-Type", "application/json")
        } else {
            self.http.get(url)
        }
        .bearer_auth(token.trim())
        .send()
        .await?
        .error_for_status()?;
        if r.content_length().unwrap_or(0) > 1024 * 1024 {
            bail!("ADMIN_LIMIT")
        }
        let b = r.bytes().await?;
        if b.len() > 1024 * 1024 {
            bail!("ADMIN_LIMIT")
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
        let metrics: Value = serde_json::from_slice(&bytes)?;
        if !alive(o) || metrics["pid"].as_u64() != Some(o.pid as u64) {
            bail!("METRICS_OWNER: response PID does not match owned process")
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
    pub async fn execute(&self, r: Value) -> Result<Value> {
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
                    json!({"state":self.status(),"server":server,"client":client,"health":health,"metricErrors":{"server":server_error,"client":client_error},"addresses":network::local_addresses().iter().map(|a| format!("{a}:{}", self.layout.load().map(|s| s.base_port).unwrap_or(29545))).collect::<Vec<_>>()}),
                );
            }
            "probe" => {
                let u = network::endpoint(&a("address")?)?;
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
        self.guard_busy()?;
        match cmd {
            "start" => {
                if let Err(e) = self
                    .start(&a("world")?, args["client"].as_str() != Some("false"))
                    .await
                {
                    if !e.to_string().starts_with("START_PENDING") {
                        self.state("Failed", &e.to_string());
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
                let layout = self.layout.clone();
                Ok(tokio::task::spawn_blocking(move || -> Result<Value> {
                    let s = layout.load()?;
                    Ok(serde_json::to_value(if s.game_branch.is_empty() {
                        serde_json::to_value(
                            UpdateService::new(&layout)?.install(&s.feed_url, None)?,
                        )?
                    } else {
                        serde_json::to_value(
                            RepositoryChannel::new(&layout)?.install(&s.game_branch, None)?,
                        )?
                    })?)
                })
                .await??)
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
        std::fs::create_dir_all(folder)?;
        owner_only(folder, true)?;
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
            p.insert("autoJoin".into(), "true".into());
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
        std::fs::write(
            folder.join("agent.properties"),
            p.iter()
                .map(|(k, v)| format!("{k}={}\n", launch::prop(v)))
                .collect::<String>(),
        )?;
        Ok(())
    }
    async fn start(&self, id: &str, with_client: bool) -> Result<()> {
        if self
            .running
            .lock()
            .unwrap()
            .server
            .as_ref()
            .is_some_and(alive)
        {
            bail!("SERVER_RUNNING")
        }
        let s = self.layout.load()?;
        s.validate()?;
        let worlds = self.worlds()?;
        let mut world = worlds.get(id)?;
        if world.base_identity_mode != "content-v2" {
            bail!("WORLD_MIGRATION")
        }
        let map = worlds.map(&world)?;
        let source = self.layout.bundle(&s, "server")?;
        let java = self.java(&source)?;
        {
            let _lease = worlds.lease(id)?;
        }
        let data = worlds.folder(id)?.join("data");
        if std::fs::read_dir(&data)?.filter_map(|x| x.ok()).any(|x| {
            !matches!(
                x.file_name().to_str(),
                Some(".server-lease" | "admin-token.txt")
            )
        }) {
            worlds.automatic_backup(id, s.automatic_backups as usize)?;
        }
        let lease = worlds.lease(id)?;
        let session = self
            .layout
            .root
            .join("state/sessions")
            .join(uuid::Uuid::new_v4().simple().to_string());
        std::fs::create_dir_all(&session)?;
        self.state("Preparing", "Проверка карты и портов");
        if map_digest(&map)? != world.map_hash {
            self.state("Failed", "Базовая карта изменилась");
            bail!("MAP_HASH")
        }
        network::check_ports(s.base_port as u16)?;
        self.agent_config(&session, "server", Some(&source), None, id)?;
        let mut child = launch::server(
            &source,
            &map,
            &data,
            &session,
            &s,
            &self.game_agent()?,
            &java,
        )?
        .spawn()?;
        let pid = child.id().unwrap();
        pump(&mut child, &session, "server");
        let o = Owned {
            pid,
            started: process_time(pid).ok_or_else(|| anyhow::anyhow!("PROCESS_TIME"))?,
            session: session
                .strip_prefix(&self.layout.root)?
                .to_string_lossy()
                .into_owned(),
            world: id.into(),
            admin_port: s.base_port as u16 + 100,
            pending_client: with_client,
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
        for _ in 0..800 {
            if !alive(&o) {
                self.state("Failed", "Сервер завершился при загрузке");
                bail!("SERVER_EXIT")
            }
            if let Ok(h) = self.admin(&o, "health", false).await {
                if h["ready"] == true {
                    self.verify(&o).await?;
                    launch::need(&session.join("server-patches.ok"))?;
                    world.server_release = hex::encode(sha2::Sha256::digest(std::fs::read(
                        component_file(&source, "server.jar")?,
                    )?));
                    world.last_played = Some(chrono::Utc::now().to_rfc3339());
                    worlds.save(&world)?;
                    self.state("Ready", "Мир запущен");
                    if with_client {
                        self.clear_pending()?;
                        self.start_client(&format!("127.0.0.1:{}", s.base_port), &s.nickname)
                            .await?;
                    }
                    return Ok(());
                }
            }
            tokio::time::sleep(Duration::from_millis(750)).await;
        }
        bail!("START_PENDING: server is still loading; inspect logs before stopping")
    }
    async fn start_client(&self, address: &str, nickname: &str) -> Result<()> {
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
        let remote = network::endpoint(address)?;
        let identity = LocalIdentity::load(&self.layout)?;
        let login = format!("bscp-{}", identity.id);
        let proxy = SignedProxy::start(remote.clone(), identity, None).await?;
        let native = proxy.session(nickname).await?;
        let folder = self
            .layout
            .root
            .join("state/sessions")
            .join(format!("client-{}", uuid::Uuid::new_v4().simple()));
        self.agent_config(&folder, "client", None, Some(&native), "")?;
        let profile = ClientProfile::prepare(&self.layout.client_data(&s), &source)?;
        let mut child = launch::client(
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
        )?
        .spawn()?;
        let pid = child.id().unwrap();
        pump(&mut child, &folder, "client");
        let o = Owned {
            pid,
            started: process_time(pid).ok_or_else(|| anyhow::anyhow!("PROCESS_TIME"))?,
            session: folder
                .strip_prefix(&self.layout.root)?
                .to_string_lossy()
                .into_owned(),
            remote: remote.to_string(),
            proxy_port: proxy.port,
            ..Default::default()
        };
        write_json(
            &self.layout.root.join("state/rust-client-ownership.json"),
            &o,
        )?;
        if self.qa.is_some() {
            write_json(
                &self.layout.root.join("state/qa-client.json"),
                &json!({"pid":pid,"folder":folder,"control":41780,"guard":41781,"proxy":proxy.url()}),
            )?;
        }
        let mut r = self.running.lock().unwrap();
        r.client = Some(o);
        r.client_lease = Some(lease);
        r.proxy = Some(proxy);
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
                drop(r);
                write_json(
                    &self.layout.root.join("state/rust-server-ownership.json"),
                    &Owned::default(),
                )?;
                self.state("Stopped", "Мир сохранён, сервер остановлен");
                self.worlds()?
                    .automatic_backup(&o.world, self.layout.load()?.automatic_backups as usize)?;
                return Ok(());
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }
        bail!("STOP_PENDING: server is saving; no force kill performed")
    }
    pub async fn monitor(self: Arc<Self>) {
        loop {
            tokio::time::sleep(Duration::from_secs(2)).await;
            let Ok(_lock) = self.lifecycle.try_lock() else {
                continue;
            };
            let (exited, server) = {
                let mut r = self.running.lock().unwrap();
                if r.client.as_ref().is_some_and(|o| !alive(o)) {
                    r.client = None;
                    r.client_lease = None;
                    r.proxy = None;
                    let _ = write_json(
                        &self.layout.root.join("state/rust-client-ownership.json"),
                        &Owned::default(),
                    );
                }
                let exited = r.server.as_ref().is_some_and(|o| !alive(o));
                if exited {
                    r.server = None;
                    r.world_lease = None;
                }
                (exited, r.server.clone())
            };
            if exited {
                self.state(
                    "Failed",
                    "Процесс сервера завершился вне команды остановки; проверьте журнал",
                );
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
use sha2::Digest;
fn pump(child: &mut tokio::process::Child, folder: &Path, name: &str) {
    fn reader<R: tokio::io::AsyncRead + Unpin + Send + 'static>(input: R, path: PathBuf) {
        tokio::spawn(async move {
            let mut lines = BufReader::new(input);
            let redact=regex::Regex::new(r#"(?i)((?:Bearer\s+)|(?:(?:token|password|secret|nativeSession|privateKey|session)["']?\s*[:=]\s*["']?))[^\s,"'<>}]+"#).unwrap();
            loop {
                // Native DLLs can write bytes in the local code page despite file.encoding=UTF-8.
                // Keep draining the pipe instead of dropping it on one invalid UTF-8 sequence.
                let mut bytes = Vec::new();
                match lines.read_until(b'\n', &mut bytes).await {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {}
                }
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
        });
    }
    if let Some(out) = child.stdout.take() {
        reader(out, folder.join(format!("{name}-stdout.log")))
    }
    if let Some(err) = child.stderr.take() {
        reader(err, folder.join(format!("{name}-stderr.log")))
    }
}
#[cfg(all(test, windows))]
mod metrics_tests {
    use super::*;
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
        layout.init().unwrap();
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
