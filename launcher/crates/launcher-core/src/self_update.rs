//! GitHub Releases executable updates. No game data or registry changes.
use crate::*;
use anyhow::{Context, Result, ensure};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
    sync::{Arc, atomic::AtomicBool},
    time::{Duration, Instant},
};

const REPOSITORY: &str = "https://api.github.com/repos/DeTTK/SCServer";
#[derive(Debug, Deserialize)]
struct Asset {
    name: String,
    size: u64,
    digest: Option<String>,
    browser_download_url: String,
}
#[derive(Debug, Deserialize)]
struct Release {
    tag_name: String,
    draft: bool,
    prerelease: bool,
    assets: Vec<Asset>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct Pending {
    version: String,
    sha256: String,
    size: u64,
    relative_exe: String,
}
#[derive(Debug, Serialize, Deserialize)]
struct ApplyPlan {
    root: PathBuf,
    target: PathBuf,
    helper: PathBuf,
    pending: Pending,
}
pub struct SelfUpdater;
fn version(value: &str) -> Option<[u32; 3]> {
    let p: Vec<_> = value
        .split('.')
        .map(str::parse::<u32>)
        .collect::<std::result::Result<_, _>>()
        .ok()?;
    (p.len() == 3).then(|| [p[0], p[1], p[2]])
}
fn package(releases: Vec<Release>, current: &str) -> Result<Option<(String, Package)>> {
    let current = version(current).context("LAUNCHER_VERSION")?;
    let mut candidates = Vec::new();
    for r in releases {
        // Game releases, old .NET setup binaries and other repositories never qualify.
        if r.draft {
            continue;
        }
        let Some(v) = r
            .tag_name
            .strip_prefix("launcher-v")
            .and_then(|v| v.strip_suffix("-launtest"))
        else {
            continue;
        };
        let Some(numbers) = version(v) else { continue };
        if numbers <= current {
            continue;
        }
        let _ = r.prerelease; // launtest explicitly accepts its own prereleases.
        let Some(a) = r.assets.into_iter().find(|a| a.name == "BSCPCRAFT.exe") else {
            continue;
        };
        let Some(hash) = a
            .digest
            .and_then(|d| d.strip_prefix("sha256:").map(str::to_owned))
        else {
            continue;
        };
        ensure!(
            hash.len() == 64 && hash.bytes().all(|c| c.is_ascii_hexdigit()),
            "LAUNCHER_DIGEST"
        );
        ensure!(
            (1024..=100 * 1024 * 1024).contains(&a.size),
            "LAUNCHER_SIZE"
        );
        let url = UpdateService::url(&a.browser_download_url)?;
        ensure!(
            url.host_str() == Some("github.com")
                && url.path().starts_with(&format!(
                    "/DeTTK/SCServer/releases/download/{}/",
                    r.tag_name
                )),
            "LAUNCHER_ORIGIN"
        );
        candidates.push((
            numbers,
            v.to_owned(),
            Package {
                id: "launcher".into(),
                url: a.browser_download_url,
                sha256: hash.to_lowercase(),
                size: a.size,
                ..Default::default()
            },
        ));
    }
    candidates.sort_by_key(|v| v.0);
    Ok(candidates.pop().map(|(_, v, p)| (v, p)))
}
impl SelfUpdater {
    pub fn pending_version(layout: &Layout) -> Result<Option<String>> {
        let pending: Pending = read_json(&layout.at("state/launcher-update.json"))?;
        Ok((!pending.version.is_empty()).then_some(pending.version))
    }
    pub fn stage_latest(
        layout: &Layout,
        cancel: Arc<AtomicBool>,
        callback: Option<&Progress>,
    ) -> Result<Option<String>> {
        let settings = layout.load()?;
        if settings.game_branch != "launtest" {
            return Ok(None);
        }
        let pending_path = layout.at("state/launcher-update.json");
        match read_json::<Pending>(&pending_path) {
            Ok(p) if !p.version.is_empty() => {
                if self::version(&p.version).is_some()
                    && child(&layout.root, &p.relative_exe)
                        .and_then(|f| validate_exe(&f, p.size, &p.sha256))
                        .is_ok()
                {
                    return Ok(Some(p.version));
                }
                no_links(&pending_path)?;
                fs::rename(
                    &pending_path,
                    pending_path
                        .with_extension(format!("rejected-{}.json", uuid::Uuid::new_v4().simple())),
                )?;
            }
            Err(_) if pending_path.exists() => {
                no_links(&pending_path)?;
                fs::rename(
                    &pending_path,
                    pending_path
                        .with_extension(format!("rejected-{}.json", uuid::Uuid::new_v4().simple())),
                )?;
            }
            _ => {}
        }
        let checked = layout.at("state/launcher-update-checked.json");
        let previous: serde_json::Value = read_json(&checked)?;
        let now = chrono::Utc::now().timestamp();
        if previous["time"]
            .as_i64()
            .is_some_and(|v| now >= v && now - v < 3600)
        {
            return Ok(None);
        }
        progress(callback, "Проверяем обновления лаунчера…");
        let http = reqwest::blocking::Client::builder()
            .user_agent("BSCPCRAFT-launcher-updater")
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let response = http
            .get(format!("{REPOSITORY}/releases?per_page=50"))
            .header("Accept", "application/vnd.github+json")
            .send()?
            .error_for_status()?;
        let mut raw = Vec::new();
        response.take(2 * 1024 * 1024 + 1).read_to_end(&mut raw)?;
        ensure!(raw.len() <= 2 * 1024 * 1024, "LAUNCHER_INDEX_SIZE");
        let selected = package(serde_json::from_slice(&raw)?, env!("CARGO_PKG_VERSION"))?;
        let Some((version, p)) = selected else {
            write_json(&checked, &serde_json::json!({"time":now}))?;
            return Ok(None);
        };
        Self::stage(layout, &version, &p, cancel, callback)?;
        write_json(&checked, &serde_json::json!({"time":now}))?;
        Ok(Some(version))
    }
    fn stage(
        layout: &Layout,
        version: &str,
        p: &Package,
        cancel: Arc<AtomicBool>,
        callback: Option<&Progress>,
    ) -> Result<()> {
        ensure!(self::version(version).is_some(), "LAUNCHER_VERSION");
        let relative = format!(
            "state/launcher-updates/{version}-{}/BSCPCRAFT.exe",
            &p.sha256[..12]
        );
        let exe = child(&layout.root, &relative)?;
        let tracker = TransferTracker::new(callback);
        tracker.item("exe", p.size, UpdateService::available_download(p, &exe)?);
        tracker.planned();
        tracker.stage("download", "Скачиваем обновление лаунчера", 1);
        UpdateService::new(layout)?
            .with_cancel(cancel)
            .download_observed(p, &exe, callback, Some(&|e| tracker.observe("exe", e)))?;
        validate_exe(&exe, p.size, &p.sha256)?;
        write_json(
            &layout.at("state/launcher-update.json"),
            &Pending {
                version: version.into(),
                sha256: p.sha256.clone(),
                size: p.size,
                relative_exe: relative,
            },
        )?;
        tracker.stage("complete", "Обновление лаунчера готово", 1);
        Ok(())
    }
    /// Must run before acquiring the UI lease. Existing games/servers defer replacement.
    pub fn handoff_pending(layout: &Layout, current: &Path) -> Result<bool> {
        let deferred: serde_json::Value =
            read_json(&layout.at("state/launcher-update-deferred.json"))?;
        if deferred["time"]
            .as_i64()
            .is_some_and(|v| chrono::Utc::now().timestamp() - v < 120)
        {
            return Ok(false);
        }
        let pending: Pending = read_json(&layout.at("state/launcher-update.json"))?;
        if pending.version.is_empty() {
            return Ok(false);
        }
        let source = child(&layout.root, &pending.relative_exe)?;
        validate_exe(&source, pending.size, &pending.sha256)?;
        no_links(current)?;
        if sha(current)? == pending.sha256 {
            clear_pending(layout)?;
            return Ok(false);
        }
        if version(&pending.version) <= version(env!("CARGO_PKG_VERSION")) {
            clear_pending(layout)?;
            return Ok(false);
        }
        let host = idle_lock(layout, "state/rust-host.lock")?;
        if host.is_none() {
            return Ok(false);
        }
        let helper = layout.at(&format!(
            "state/launcher-updates/helper-{}/BSCPCRAFT.exe",
            uuid::Uuid::new_v4().simple()
        ));
        fs::create_dir_all(helper.parent().unwrap())?;
        fs::copy(current, &helper)?;
        let plan = ApplyPlan {
            root: layout.root.clone(),
            target: current.to_owned(),
            helper: helper.clone(),
            pending,
        };
        let plan_path = helper.parent().unwrap().join("apply.json");
        write_json(&plan_path, &plan)?;
        spawn_hidden(
            &helper,
            &[
                "--apply-launcher-update",
                plan_path.to_str().context("LAUNCHER_PATH")?,
            ],
        )?;
        Ok(true)
    }
    pub fn apply_plan(path: &Path) -> Result<()> {
        no_links(path)?;
        let plan: ApplyPlan = serde_json::from_slice(&fs::read(path)?)?;
        let actual = std::env::current_exe()?.canonicalize()?;
        ensure!(
            actual == plan.helper.canonicalize()?,
            "LAUNCHER_HELPER_IDENTITY"
        );
        let layout = Layout::new(&plan.root);
        let source = child(&layout.root, &plan.pending.relative_exe)?;
        validate_exe(&source, plan.pending.size, &plan.pending.sha256)?;
        no_links(&plan.target)?;
        let start = Instant::now();
        loop {
            let ui = idle_lock(&layout, "state/rust-ui.lock")?;
            let host = idle_lock(&layout, "state/rust-host.lock")?;
            if ui.is_some() && host.is_some() {
                if let Ok(()) = replace(&plan.target, &source, &plan.pending.sha256) {
                    clear_pending(&layout)?;
                    drop(ui);
                    drop(host);
                    spawn_hidden(
                        &plan.target,
                        &["--root", layout.root.to_str().context("LAUNCHER_PATH")?],
                    )?;
                    return Ok(());
                }
            }
            if start.elapsed() >= Duration::from_secs(30) {
                write_json(
                    &layout.at("state/launcher-update-deferred.json"),
                    &serde_json::json!({"time":chrono::Utc::now().timestamp()}),
                )?;
                let _ = RotatingLog::new(layout.at("logs"), "launcher")
                    .line("Launcher replacement is busy; update retained for next launch");
                spawn_hidden(
                    &plan.target,
                    &["--root", layout.root.to_str().context("LAUNCHER_PATH")?],
                )?;
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    }
}
fn clear_pending(layout: &Layout) -> Result<()> {
    write_json(
        &layout.at("state/launcher-update.json"),
        &Pending::default(),
    )
}
fn idle_lock(layout: &Layout, path: &str) -> Result<Option<fs::File>> {
    let path = layout.at(path);
    no_links(&path)?;
    let file = fs::OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .open(path)?;
    match file.try_lock_exclusive() {
        Ok(()) => Ok(Some(file)),
        Err(e)
            if e.kind() == std::io::ErrorKind::WouldBlock
                || cfg!(windows) && matches!(e.raw_os_error(), Some(32 | 33)) =>
        {
            Ok(None)
        }
        Err(e) => Err(e.into()),
    }
}
fn validate_exe(path: &Path, size: u64, hash: &str) -> Result<()> {
    no_links(path)?;
    ensure!(
        fs::metadata(path)?.len() == size && sha(path)? == hash,
        "LAUNCHER_HASH"
    );
    let mut f = fs::File::open(path)?;
    let mut mz = [0u8; 2];
    f.read_exact(&mut mz)?;
    ensure!(&mz == b"MZ", "LAUNCHER_EXECUTABLE");
    Ok(())
}
fn replace(target: &Path, source: &Path, hash: &str) -> Result<()> {
    let incoming = target.with_extension("incoming.exe");
    let rollback = target.with_extension("previous.exe");
    no_links(&incoming)?;
    no_links(&rollback)?;
    fs::copy(source, &incoming)?;
    ensure!(sha(&incoming)? == hash, "LAUNCHER_COPY_HASH");
    if target.exists() {
        fs::copy(target, &rollback)?;
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{
            MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW,
        };
        let a: Vec<_> = incoming.as_os_str().encode_wide().chain(Some(0)).collect();
        let b: Vec<_> = target.as_os_str().encode_wide().chain(Some(0)).collect();
        ensure!(
            unsafe {
                MoveFileExW(
                    a.as_ptr(),
                    b.as_ptr(),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
                )
            } != 0,
            "LAUNCHER_REPLACE: {}",
            std::io::Error::last_os_error()
        );
    }
    #[cfg(not(windows))]
    fs::rename(incoming, target)?;
    Ok(())
}
fn spawn_hidden(exe: &Path, args: &[&str]) -> Result<()> {
    let mut cmd = std::process::Command::new(exe);
    cmd.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    cmd.spawn()?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn version_order_is_numeric_and_strict() {
        assert!(version("0.5.10") > version("0.5.2"));
        assert_eq!(version("1.2"), None);
        assert_eq!(version("1.2.3.4"), None);
    }
    #[test]
    fn game_assets_and_other_channels_never_qualify() {
        let r:Vec<Release>=serde_json::from_value(serde_json::json!([{"tag_name":"quick-slots","draft":false,"prerelease":false,"assets":[]},{"tag_name":"launcher-v9.0.0-main","draft":false,"prerelease":false,"assets":[]}])).unwrap();
        assert!(package(r, "0.5.0").unwrap().is_none());
    }
    #[test]
    fn release_requires_repository_asset_digest_and_newer_version() {
        let release = |url: &str, digest: Option<String>| -> Vec<Release> {
            serde_json::from_value(serde_json::json!([{"tag_name":"launcher-v0.5.1-launtest","draft":false,"prerelease":true,"assets":[{"name":"BSCPCRAFT.exe","size":4096,"digest":digest,"browser_download_url":url}]}])).unwrap()
        };
        let url = "https://github.com/DeTTK/SCServer/releases/download/launcher-v0.5.1-launtest/BSCPCRAFT.exe";
        assert!(package(release(url, None), "0.5.0").unwrap().is_none());
        let digest = Some(format!("sha256:{}", "a".repeat(64)));
        assert_eq!(
            package(release(url, digest.clone()), "0.5.0")
                .unwrap()
                .unwrap()
                .0,
            "0.5.1"
        );
        assert!(
            package(release(url, digest.clone()), "0.5.1")
                .unwrap()
                .is_none()
        );
        assert!(package(release("https://github.com/other/project/releases/download/launcher-v0.5.1-launtest/BSCPCRAFT.exe",digest),"0.5.0").is_err());
    }
    #[test]
    fn active_host_defers_replacement_and_corrupt_download_cannot_run() {
        let dir = tempfile::tempdir().unwrap();
        let layout = Layout::new(dir.path());
        layout.init().unwrap();
        let file = layout.at("state/launcher-updates/new.exe");
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(&file, b"MZnew").unwrap();
        let pending = Pending {
            version: "99.0.0".into(),
            sha256: sha(&file).unwrap(),
            size: 5,
            relative_exe: "state/launcher-updates/new.exe".into(),
        };
        write_json(&layout.at("state/launcher-update.json"), &pending).unwrap();
        let _host = idle_lock(&layout, "state/rust-host.lock").unwrap().unwrap();
        assert!(!SelfUpdater::handoff_pending(&layout, &std::env::current_exe().unwrap()).unwrap());
        fs::write(file, b"MZbad").unwrap();
        assert!(SelfUpdater::handoff_pending(&layout, &std::env::current_exe().unwrap()).is_err());
    }
    #[test]
    fn replacement_keeps_old_exe_and_rejects_tampering() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("BSCPCRAFT.exe");
        let source = dir.path().join("new.exe");
        fs::write(&target, b"MZold").unwrap();
        fs::write(&source, b"MZnew").unwrap();
        let hash = sha(&source).unwrap();
        assert!(replace(&target, &source, &"0".repeat(64)).is_err());
        assert_eq!(fs::read(&target).unwrap(), b"MZold");
        replace(&target, &source, &hash).unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"MZnew");
        assert_eq!(
            fs::read(target.with_extension("previous.exe")).unwrap(),
            b"MZold"
        );
    }
}
