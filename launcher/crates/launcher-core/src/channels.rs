//! Signed per-file game updates from generated branches of the one project repository.
use crate::*;
use anyhow::{Context, Result, bail, ensure};
use rsa::{
    RsaPublicKey,
    pkcs1v15::{Signature, VerifyingKey},
    pkcs8::DecodePublicKey,
    signature::Verifier,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashSet,
    fs,
    io::Read,
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

pub const GAME_REPOSITORY: &str = "DeTTK/SCServer";
const PUBLIC_KEY: &str = include_str!("../../../channel-public-key.txt");
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelFile {
    pub path: String,
    pub target: String,
    pub size: u64,
    pub sha256: String,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelManifest {
    pub schema_version: u32,
    pub channel: String,
    pub source_commit: String,
    pub sequence: u64,
    pub base_id: String,
    pub files: Vec<ChannelFile>,
    pub changelog: String,
    #[serde(default)]
    pub mods: Vec<serde_json::Value>,
    #[serde(default)]
    pub foundation: Option<FoundationPin>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelAvailable {
    pub build_commit: String,
    pub revision: String,
    pub manifest: ChannelManifest,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelInstalled {
    pub revision: String,
    pub previous: String,
    pub build_commit: String,
    pub source_commit: String,
    pub sequence: u64,
    pub downloaded_bytes: u64,
    pub reused_bytes: u64,
    pub files: usize,
}
pub fn validate_branch(branch: &str) -> Result<()> {
    ensure!(
        !branch.is_empty()
            && branch.len() <= 80
            && !branch.starts_with("build-")
            && branch
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-_/".contains(&b)),
        "CHANNEL_NAME: некорректное имя ветки"
    );
    ensure!(
        !branch.starts_with('/') && !branch.ends_with('/') && !branch.contains("//"),
        "CHANNEL_NAME: некорректное имя ветки"
    );
    Ok(())
}
fn branch_key(branch: &str) -> String {
    hex::encode(Sha256::digest(branch.as_bytes()))[..20].into()
}
fn state_dir(layout: &Layout, branch: &str) -> Result<PathBuf> {
    validate_branch(branch)?;
    Ok(layout.at(format!("state/channels/{}", branch_key(branch))))
}
fn revisions(layout: &Layout, s: &Settings) -> PathBuf {
    layout
        .data(s)
        .join("channels")
        .join(branch_key(&s.game_branch))
        .join("revisions")
}
fn hex_valid(s: &str, n: usize) -> bool {
    s.len() == n && s.bytes().all(|b| b.is_ascii_hexdigit())
}
impl ChannelManifest {
    pub fn validate(&self, branch: &str) -> Result<()> {
        validate_branch(branch)?;
        ensure!(
            matches!(self.schema_version, 1 | 2)
                && self.channel == branch
                && hex_valid(&self.source_commit, 40)
                && self.sequence > 0,
            "CHANNEL_MANIFEST: неверное описание сборки"
        );
        if let Some(f) = &self.foundation {
            validate_relative(&f.id)?;
            ensure!(
                f.id == self.base_id
                    && f.path == "bootstrap/foundation.json"
                    && hex_valid(&f.sha256, 64)
                    && f.size <= 1024 * 1024,
                "CHANNEL_BASE"
            );
        } else {
            ensure!(
                self.base_id
                    == format!(
                        "bootstrap-{}",
                        &hex::encode(Sha256::digest(
                            include_str!("../../../bootstrap-pins.json").as_bytes()
                        ))[..16]
                    ),
                "CHANNEL_BASE: сборке требуется другая игровая основа"
            );
        }
        ensure!(
            !self.files.is_empty() && self.files.len() <= 10000,
            "CHANNEL_LIMIT: неверное число файлов"
        );
        crate::mods::validate_mod_catalog(self)?;
        let mut paths = HashSet::new();
        let mut targets = HashSet::new();
        let mut total = 0u64;
        for f in &self.files {
            validate_relative(&f.path)?;
            validate_relative(&f.target)?;
            ensure!(
                matches!(f.path.split('/').next(), Some("server" | "client" | "mods")),
                "CHANNEL_PATH: путь вне игровых компонентов"
            );
            ensure!(
                matches!(f.target.split('/').next(), Some("server" | "client"))
                    && f.target.contains('/'),
                "CHANNEL_TARGET: путь вне игровых компонентов"
            );
            ensure!(
                !f.target.split('/').any(|p| p.starts_with('.'))
                    && paths.insert(f.path.to_lowercase())
                    && targets.insert(f.target.to_lowercase()),
                "CHANNEL_PATH: повторный или служебный путь"
            );
            ensure!(
                hex_valid(&f.sha256, 64) && f.size > 0 && f.size <= 99 * 1024 * 1024,
                "CHANNEL_HASH: неверный размер или хеш файла"
            );
            total = total.checked_add(f.size).context("CHANNEL_LIMIT")?;
        }
        ensure!(
            total <= 512 * 1024 * 1024,
            "CHANNEL_LIMIT: обновление слишком велико"
        );
        Ok(())
    }
}
pub struct RepositoryChannel {
    pub layout: Layout,
    http: reqwest::blocking::Client,
    pub(crate) cancel: Arc<AtomicBool>,
}
impl RepositoryChannel {
    pub fn new(layout: &Layout) -> Result<Self> {
        Ok(Self {
            layout: layout.clone(),
            http: reqwest::blocking::ClientBuilder::from(
                reqwest::Client::builder()
                    .redirect(reqwest::redirect::Policy::none())
                    .connect_timeout(Duration::from_secs(20))
                    .timeout(Duration::from_secs(1200)),
            )
            .timeout(Duration::from_secs(20))
            .user_agent("BSCPCRAFT-Rust/0.4")
            .build()?,
            cancel: Arc::new(AtomicBool::new(false)),
        })
    }
    pub fn with_cancel(mut self, flag: Arc<AtomicBool>) -> Self {
        self.cancel = flag;
        self
    }
    pub(crate) fn cancelled(&self) -> Result<()> {
        ensure!(
            !self.cancel.load(Ordering::Relaxed),
            "CANCELLED: операция отменена"
        );
        Ok(())
    }
    pub(crate) fn small(&self, url: &str, limit: u64) -> Result<Vec<u8>> {
        self.cancelled()?;
        let response = self.http.get(url).send()?;
        if response.status() == reqwest::StatusCode::NOT_FOUND {
            bail!("CHANNEL_UNAVAILABLE: готовая сборка ветки ещё не опубликована")
        }
        let response = response.error_for_status()?;
        let mut bytes = vec![];
        response.take(limit + 1).read_to_end(&mut bytes)?;
        ensure!(
            bytes.len() as u64 <= limit,
            "CHANNEL_LIMIT: ответ слишком велик"
        );
        self.cancelled()?;
        Ok(bytes)
    }
    fn authenticate(&self, raw: &[u8], sig: &[u8], branch: &str) -> Result<ChannelManifest> {
        ensure!(
            raw.len() <= 2 * 1024 * 1024 && sig.len() <= 8192,
            "CHANNEL_LIMIT"
        );
        VerifyingKey::<Sha256>::new(RsaPublicKey::from_public_key_pem(PUBLIC_KEY)?)
            .verify(raw, &Signature::try_from(sig)?)
            .context("CHANNEL_SIGNATURE: подпись сборки не прошла проверку")?;
        let manifest: ChannelManifest = serde_json::from_slice(raw)?;
        manifest.validate(branch)?;
        Ok(manifest)
    }
    pub fn list(&self) -> Result<Vec<String>> {
        let raw = self.small(
            &format!("https://api.github.com/repos/{GAME_REPOSITORY}/branches?per_page=100"),
            256 * 1024,
        )?;
        let branches: Vec<serde_json::Value> = serde_json::from_slice(&raw)?;
        let mut result = branches
            .into_iter()
            .filter_map(|v| v["name"].as_str()?.strip_prefix("build-").map(String::from))
            .filter(|v| validate_branch(v).is_ok())
            .collect::<Vec<_>>();
        result.sort();
        result.dedup();
        Ok(result)
    }
    pub fn check(&self, branch: &str) -> Result<ChannelAvailable> {
        validate_branch(branch)?;
        let mut url = reqwest::Url::parse(&format!(
            "https://api.github.com/repos/{GAME_REPOSITORY}/branches/"
        ))?;
        url.path_segments_mut()
            .map_err(|_| anyhow::anyhow!("CHANNEL_URL"))?
            .pop_if_empty()
            .push(&format!("build-{branch}"));
        let reference: serde_json::Value =
            serde_json::from_slice(&self.small(url.as_str(), 65536)?)?;
        let commit = reference["commit"]["sha"]
            .as_str()
            .context("CHANNEL_COMMIT")?
            .to_string();
        ensure!(hex_valid(&commit, 40), "CHANNEL_COMMIT");
        let raw = self.small(&raw_url(&commit, "delivery.json")?, 2 * 1024 * 1024)?;
        let sig = self.small(&raw_url(&commit, "delivery.json.sig")?, 8192)?;
        let manifest = self.authenticate(&raw, &sig, branch)?;
        let state = state_dir(&self.layout, branch)?;
        let installed: ChannelInstalled = read_json(&state.join("installed.json"))?;
        ensure!(
            manifest.sequence >= installed.sequence,
            "CHANNEL_REPLAY: сборка старее установленной"
        );
        let available = ChannelAvailable {
            build_commit: commit,
            revision: hex::encode(Sha256::digest(&raw)),
            manifest,
        };
        atomic_write(&state.join("available.manifest"), &raw)?;
        atomic_write(&state.join("available.sig"), &sig)?;
        write_json(&state.join("available.json"), &available)?;
        Ok(available)
    }
    pub fn install(&self, branch: &str, progress: Option<&Progress>) -> Result<ChannelInstalled> {
        let settings = self.layout.load()?;
        ensure!(
            settings.game_branch == branch,
            "CHANNEL_CHANGED: выбранная ветка изменилась"
        );
        let state = state_dir(&self.layout, branch)?;
        fs::create_dir_all(&state)?;
        let _lease = Lease::acquire(&state.join("install.lock"))?;
        let available = self.check(branch)?;
        let tracker = TransferTracker::new(progress);
        let old: ChannelInstalled = read_json(&state.join("installed.json"))?;
        let roots = revisions(&self.layout, &settings);
        no_links(&roots)?;
        fs::create_dir_all(&roots)?;
        let destination = child(&roots, &available.revision)?;
        if destination.exists() {
            verify_revision(&destination, &available.manifest)?;
            let previous = if old.revision == available.revision {
                old.previous
            } else {
                old.revision
            };
            let value = ChannelInstalled {
                revision: available.revision,
                previous,
                build_commit: available.build_commit,
                source_commit: available.manifest.source_commit,
                sequence: old.sequence.max(available.manifest.sequence),
                files: available.manifest.files.len(),
                ..Default::default()
            };
            write_json(&state.join("installed.json"), &value)?;
            return Ok(value);
        }
        let complete_base = if available.manifest.foundation.is_some() {
            Some(self.prepare_foundation(&available, &self.layout.data(&settings), &tracker)?)
        } else {
            None
        };
        let client = if let Some(base) = &complete_base {
            base.join("Client")
        } else {
            self.layout.base_bundle(&settings, "client")?
        };
        let server = if let Some(base) = &complete_base {
            base.join("Server")
        } else {
            self.layout.base_bundle(&settings, "server")?
        };
        Setup::check_components(&client, &server)?;
        for base in [&client, &server] {
            ensure!(
                base.parent()
                    .and_then(|p| p.file_name())
                    .is_some_and(|p| p == available.manifest.base_id.as_str()),
                "CHANNEL_BASE: выберите проверенную основу {}",
                available.manifest.base_id
            );
        }
        let total: u64 = available.manifest.files.iter().map(|f| f.size).sum();
        ensure!(
            fs2::available_space(&roots)? > total.saturating_mul(2),
            "DISK_SPACE: недостаточно места"
        );
        let stage = child(
            &roots,
            &format!(".incoming-{}", uuid::Uuid::new_v4().simple()),
        )?;
        fs::create_dir(&stage)?;
        let result = (|| {
            let mut reused = 0;
            let downloader = UpdateService::new(&self.layout)?.with_cancel(self.cancel.clone());
            tracker.stage(
                "checking",
                "Проверяем обновления",
                available.manifest.files.len(),
            );
            let mut plans = Vec::new();
            for f in &available.manifest.files {
                self.cancelled()?;
                let (component, relative) = f.target.split_once('/').context("CHANNEL_TARGET")?;
                let base = if component == "server" {
                    &server
                } else {
                    &client
                };
                let previous = component_file(base, relative)?;
                no_links(&previous)?;
                let reuse = if previous.is_file()
                    && fs::metadata(&previous)?.len() == f.size
                    && sha(&previous)? == f.sha256
                {
                    Some(previous)
                } else {
                    None
                };
                let cache = self
                    .layout
                    .data(&settings)
                    .join("cache/channel-files")
                    .join(&f.sha256);
                no_links(&cache)?;
                let package = Package {
                    id: f.path.clone(),
                    url: raw_url(&available.build_commit, &f.path)?,
                    sha256: f.sha256.clone(),
                    size: f.size,
                    ..Default::default()
                };
                let id = format!("channel/{}", f.sha256);
                if reuse.is_none() {
                    tracker.item(
                        &id,
                        f.size,
                        UpdateService::available_download(&package, &cache)?,
                    );
                }
                plans.push((f, cache, package, id, reuse));
                tracker.file_done();
            }
            tracker.planned();
            if tracker.snapshot().remaining_bytes > 0 {
                tracker.stage("download", "Скачиваем обновления", 0);
            }
            for (_, cache, package, id, reuse) in &plans {
                if reuse.is_none() {
                    let observe = |event| tracker.observe(id, event);
                    downloader.download_observed(package, cache, None, Some(&observe))?;
                }
            }
            tracker.stage("install", "Применяем обновления", plans.len());
            for (f, cache, _, _, previous) in plans {
                self.cancelled()?;
                let (component, relative) = f.target.split_once('/').context("CHANNEL_TARGET")?;
                let target = child(
                    &stage.join(if component == "server" {
                        "Server"
                    } else {
                        "Client"
                    }),
                    relative,
                )?;
                fs::create_dir_all(target.parent().unwrap())?;
                let source = if let Some(previous) = &previous {
                    reused += f.size;
                    previous
                } else {
                    &cache
                };
                if fs::hard_link(source, &target).is_err() {
                    fs::copy(source, &target)?;
                }
                ensure!(sha(&target)? == f.sha256, "CHANNEL_FILE_HASH");
                tracker.file_done();
            }
            for (name, base) in [("Client", &client), ("Server", &server)] {
                let folder = stage.join(name);
                fs::create_dir_all(&folder)?;
                let final_folder = destination.join(name);
                let relative = relative_base(&final_folder, base);
                write_json(
                    &folder.join(".component-base.json"),
                    &relative.to_string_lossy().into_owned(),
                )?;
            }
            let raw = fs::read(state.join("available.manifest"))?;
            let sig = fs::read(state.join("available.sig"))?;
            self.authenticate(&raw, &sig, branch)?;
            ensure!(
                hex::encode(Sha256::digest(&raw)) == available.revision,
                "CHANNEL_CHANGED"
            );
            atomic_write(&stage.join(".manifest"), &raw)?;
            atomic_write(&stage.join(".manifest.sig"), &sig)?;
            verify_revision(&stage, &available.manifest)?;
            self.cancelled()?;
            fs::rename(&stage, &destination)?;
            let value = ChannelInstalled {
                revision: available.revision,
                previous: old.revision,
                build_commit: available.build_commit,
                source_commit: available.manifest.source_commit,
                sequence: available.manifest.sequence,
                downloaded_bytes: tracker.snapshot().downloaded_bytes,
                reused_bytes: reused,
                files: available.manifest.files.len(),
            };
            write_json(&state.join("installed.json"), &value)?;
            tracker.complete();
            Ok(value)
        })();
        delete_owned(&roots, &stage)?;
        result
    }
    /// Startup checks must not spawn a supervisor for an already verified revision.
    pub fn is_current(&self, available: &ChannelAvailable) -> Result<bool> {
        let settings = self.layout.load()?;
        if settings.game_branch != available.manifest.channel || !hex_valid(&available.revision, 64)
        {
            return Ok(false);
        }
        let installed: ChannelInstalled =
            read_json(&state_dir(&self.layout, &settings.game_branch)?.join("installed.json"))?;
        if installed.revision != available.revision {
            return Ok(false);
        }
        let folder = child(&revisions(&self.layout, &settings), &available.revision)?;
        no_links(&folder)?;
        Ok(verify_revision(&folder, &available.manifest).is_ok())
    }
    pub fn cached(&self, branch: &str) -> Result<Option<ChannelAvailable>> {
        let state = state_dir(&self.layout, branch)?;
        if !state.join("available.json").exists() {
            return Ok(None);
        }
        let mut value: ChannelAvailable = read_json(&state.join("available.json"))?;
        let raw = fs::read(state.join("available.manifest"))?;
        let sig = fs::read(state.join("available.sig"))?;
        ensure!(
            hex::encode(Sha256::digest(&raw)) == value.revision
                && hex_valid(&value.build_commit, 40),
            "CHANNEL_CACHE"
        );
        value.manifest = self.authenticate(&raw, &sig, branch)?;
        Ok(Some(value))
    }
    pub fn rollback(&self, branch: &str) -> Result<ChannelInstalled> {
        let settings = self.layout.load()?;
        ensure!(settings.game_branch == branch, "CHANNEL_CHANGED");
        let state = state_dir(&self.layout, branch)?;
        let _lease = Lease::acquire(&state.join("install.lock"))?;
        let old: ChannelInstalled = read_json(&state.join("installed.json"))?;
        ensure!(
            hex_valid(&old.previous, 64),
            "ROLLBACK_MISSING: предыдущая сборка отсутствует"
        );
        // The revision's authenticated manifest is retained separately from the current online index.
        let folder = child(&revisions(&self.layout, &settings), &old.previous)?;
        let raw = fs::read(folder.join(".manifest"))?;
        let sig = fs::read(folder.join(".manifest.sig"))?;
        let m = self.authenticate(&raw, &sig, branch)?;
        verify_revision(&folder, &m)?;
        let value = ChannelInstalled {
            revision: old.previous,
            previous: old.revision,
            source_commit: m.source_commit,
            sequence: old.sequence,
            ..Default::default()
        };
        write_json(&state.join("installed.json"), &value)?;
        Ok(value)
    }
}
fn verify_revision(folder: &Path, manifest: &ChannelManifest) -> Result<()> {
    for f in &manifest.files {
        let (component, relative) = f.target.split_once('/').context("CHANNEL_TARGET")?;
        let target = child(
            &folder.join(if component == "server" {
                "Server"
            } else {
                "Client"
            }),
            relative,
        )?;
        ensure!(
            target.is_file() && fs::metadata(&target)?.len() == f.size && sha(&target)? == f.sha256,
            "CHANNEL_CONTENTS: файл сборки повреждён: {}",
            f.target
        );
    }
    Ok(())
}
pub(crate) fn raw_url(commit: &str, path: &str) -> Result<String> {
    ensure!(hex_valid(commit, 40), "CHANNEL_COMMIT");
    validate_relative(path)?;
    let mut url = reqwest::Url::parse(&format!(
        "https://raw.githubusercontent.com/{GAME_REPOSITORY}/{commit}/"
    ))?;
    {
        let mut s = url
            .path_segments_mut()
            .map_err(|_| anyhow::anyhow!("CHANNEL_URL"))?;
        s.pop_if_empty();
        for part in path.split('/') {
            s.push(part);
        }
    }
    Ok(url.into())
}
fn relative_base(from: &Path, to: &Path) -> PathBuf {
    let a: Vec<_> = from.components().collect();
    let b: Vec<_> = to.components().collect();
    let common = a.iter().zip(&b).take_while(|(a, b)| a == b).count();
    if common == 0 {
        return to.to_owned();
    }
    let mut p = PathBuf::new();
    for _ in common..a.len() {
        p.push("..")
    }
    for c in &b[common..] {
        p.push(c.as_os_str())
    }
    p
}
pub fn component_base(source: &Path) -> Result<PathBuf> {
    no_links(source)?;
    let marker = source.join(".component-base.json");
    if !marker.exists() {
        return Ok(source.to_owned());
    }
    no_links(&marker)?;
    ensure!(fs::metadata(&marker)?.len() <= 16384, "CHANNEL_BASE");
    let relative: String = read_json(&marker)?;
    ensure!(!relative.is_empty(), "CHANNEL_BASE");
    let path = std::path::absolute(source.join(relative))?;
    no_links(&path)?;
    ensure!(path.is_dir(), "CHANNEL_BASE: игровая основа недоступна");
    Ok(path)
}
pub fn component_file(source: &Path, relative: &str) -> Result<PathBuf> {
    validate_relative(relative)?;
    let direct = child(source, relative)?;
    if direct.exists() {
        return Ok(direct);
    }
    let base = component_base(source)?;
    child(&base, relative)
}
pub fn installed_component(
    layout: &Layout,
    s: &Settings,
    component: &str,
) -> Result<Option<PathBuf>> {
    if !matches!(component, "server" | "client") {
        return Ok(None);
    }
    let state = state_dir(layout, &s.game_branch)?;
    let installed: ChannelInstalled = read_json(&state.join("installed.json"))?;
    if installed.revision.is_empty() {
        return Ok(None);
    }
    ensure!(hex_valid(&installed.revision, 64), "CHANNEL_REVISION");
    let path = child(&revisions(layout, s), &installed.revision)?.join(if component == "server" {
        "Server"
    } else {
        "Client"
    });
    no_links(&path)?;
    ensure!(
        path.is_dir(),
        "CHANNEL_CONTENTS: установленная сборка отсутствует"
    );
    Ok(Some(path))
}

/// One immutable runtime is shared by client and locally hosted server.
pub fn runtime_root(component: &Path) -> Result<PathBuf> {
    let base = component_base(component)?;
    let shared = base
        .parent()
        .context("FOUNDATION_PATH")?
        .join("Bootstrap/runtime");
    if shared.join("game.jar").is_file() {
        no_links(&shared)?;
        return Ok(shared);
    }
    component_file(component, "runtime")
}
pub fn runtime_part(runtime: &Path, directory: &str) -> PathBuf {
    let jar = match directory {
        "classes" => "game.jar",
        "libs" => "libraries.jar",
        "bootstrap" => "bootstrap.jar",
        "imgui-compat" => "imgui-compat.jar",
        _ => return runtime.join(directory),
    };
    if runtime.join(jar).is_file() {
        runtime.join(jar)
    } else {
        runtime.join(directory)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn authentic_manifest_fixture_rejects_tamper_and_wrong_branch() {
        use base64::Engine;
        let fixture: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/channel-v1.json")).unwrap();
        let mut raw = base64::engine::general_purpose::STANDARD
            .decode(fixture["rawBase64"].as_str().unwrap())
            .unwrap();
        let mut sig = base64::engine::general_purpose::STANDARD
            .decode(fixture["signatureBase64"].as_str().unwrap())
            .unwrap();
        let t = tempfile::tempdir().unwrap();
        let service = RepositoryChannel::new(&Layout::new(t.path())).unwrap();
        assert!(service.authenticate(&raw, &sig, "launtest").is_ok());
        assert!(service.authenticate(&raw, &sig, "main").is_err());
        raw[3] ^= 1;
        assert!(service.authenticate(&raw, &sig, "launtest").is_err());
        raw[3] ^= 1;
        sig[0] ^= 1;
        assert!(service.authenticate(&raw, &sig, "launtest").is_err());
    }
    #[test]
    fn branch_names_reject_build_refs_and_traversal() {
        for name in [
            "",
            "build-main",
            "../main",
            "/main",
            "a//b",
            "main?x",
            "main ",
        ] {
            assert!(validate_branch(name).is_err())
        }
        assert!(validate_branch("feature/fpv").is_ok());
    }
    #[test]
    fn urls_pin_commit_and_encode_only_safe_paths() {
        let u = raw_url(&"a".repeat(40), "client/compat/мод.jar").unwrap();
        assert!(u.contains(&"a".repeat(40)));
        assert!(u.contains("%D0"));
        assert!(raw_url("main", "server/server.jar").is_err());
        assert!(raw_url(&"a".repeat(40), "../secret").is_err());
    }
    #[test]
    fn shared_runtime_resolves_through_revision_overlay() {
        let t = tempfile::tempdir().unwrap();
        let base = t.path().join("bundle/Client");
        let runtime = t.path().join("bundle/Bootstrap/runtime");
        let overlay = t.path().join("revisions/new/Client");
        fs::create_dir_all(&base).unwrap();
        fs::create_dir_all(&runtime).unwrap();
        fs::create_dir_all(&overlay).unwrap();
        fs::write(runtime.join("game.jar"), b"game").unwrap();
        fs::write(runtime.join("bootstrap.jar"), b"bootstrap").unwrap();
        write_json(
            &overlay.join(".component-base.json"),
            &relative_base(&overlay, &base)
                .to_string_lossy()
                .into_owned(),
        )
        .unwrap();
        assert_eq!(runtime_root(&overlay).unwrap(), runtime);
        assert_eq!(runtime_part(&runtime, "classes"), runtime.join("game.jar"));
        assert_eq!(
            runtime_part(&runtime, "bootstrap"),
            runtime.join("bootstrap.jar")
        );
    }
    #[test]
    fn overlay_uses_changed_file_and_preserves_foundation() {
        let t = tempfile::tempdir().unwrap();
        let base = t.path().join("base");
        let overlay = t.path().join("revisions/version/Server");
        fs::create_dir_all(&base).unwrap();
        fs::create_dir_all(&overlay).unwrap();
        fs::write(base.join("server.jar"), b"old").unwrap();
        fs::write(base.join("dependency"), b"shared").unwrap();
        fs::write(overlay.join("server.jar"), b"new").unwrap();
        write_json(
            &overlay.join(".component-base.json"),
            &relative_base(&overlay, &base)
                .to_string_lossy()
                .into_owned(),
        )
        .unwrap();
        assert_eq!(
            fs::read(component_file(&overlay, "server.jar").unwrap()).unwrap(),
            b"new"
        );
        assert_eq!(
            fs::read(component_file(&overlay, "dependency").unwrap()).unwrap(),
            b"shared"
        );
        assert_eq!(fs::read(base.join("server.jar")).unwrap(), b"old");
    }
}
