use crate::*;
use anyhow::{Context, Result, bail};
use rsa::{
    RsaPublicKey,
    pkcs1::DecodeRsaPublicKey,
    pkcs1v15::{Signature, VerifyingKey},
    pkcs8::DecodePublicKey,
    signature::Verifier,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct ReleaseManifest {
    #[serde(alias = "schema_version", alias = "schemaVersion")]
    pub schema_version: i32,
    #[serde(alias = "release_id", alias = "releaseId")]
    pub release_id: String,
    #[serde(alias = "minimum_launcher_version", alias = "minimumLauncherVersion")]
    pub minimum_launcher_version: String,
    #[serde(alias = "sequence")]
    pub sequence: i64,
    #[serde(alias = "changelog")]
    pub changelog: String,
    #[serde(alias = "components")]
    pub components: Vec<Package>,
    #[serde(alias = "servers")]
    pub servers: Vec<ServerEntry>,
}
impl Default for ReleaseManifest {
    fn default() -> Self {
        Self {
            schema_version: 1,
            release_id: String::new(),
            minimum_launcher_version: "0.1.0".into(),
            sequence: 0,
            changelog: String::new(),
            components: vec![],
            servers: vec![],
        }
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct Package {
    #[serde(alias = "id")]
    pub id: String,
    #[serde(alias = "url")]
    pub url: String,
    #[serde(alias = "sha256")]
    pub sha256: String,
    #[serde(alias = "size")]
    pub size: u64,
    #[serde(alias = "expanded_size", alias = "expandedSize")]
    pub expanded_size: u64,
    #[serde(alias = "files")]
    pub files: BTreeMap<String, String>,
    #[serde(alias = "file_index_sha256", alias = "fileIndexSha256")]
    pub file_index_sha256: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct InstalledRelease {
    #[serde(alias = "release_id", alias = "releaseId")]
    pub release_id: String,
    #[serde(alias = "sequence")]
    pub sequence: i64,
    #[serde(alias = "previous")]
    pub previous: String,
}
pub struct UpdateService {
    pub layout: Layout,
    http: reqwest::blocking::Client,
    pub cancel: Arc<AtomicBool>,
}
impl UpdateService {
    pub fn new(layout: &Layout) -> Result<Self> {
        Ok(Self {
            layout: layout.clone(),
            // Separate overall async deadline from blocking header/body waits.
            // Async read_timeout is unsafe with blocking response readers.
            http: reqwest::blocking::ClientBuilder::from(
                reqwest::Client::builder()
                    .redirect(reqwest::redirect::Policy::none())
                    .timeout(Duration::from_secs(1200))
                    .connect_timeout(Duration::from_secs(20))
                    .user_agent("BSCPCRAFT-Rust/0.4"),
            )
            .timeout(Duration::from_secs(20))
            .build()?,
            cancel: Arc::new(AtomicBool::new(false)),
        })
    }
    pub fn with_cancel(mut self, cancel: Arc<AtomicBool>) -> Self {
        self.cancel = cancel;
        self
    }
    fn cancelled(&self) -> Result<()> {
        if self.cancel.load(Ordering::Relaxed) {
            bail!("CANCELLED: operation cancelled")
        };
        Ok(())
    }
    pub fn url(value: &str) -> Result<reqwest::Url> {
        let u = reqwest::Url::parse(value)?;
        if u.scheme() != "https"
            || !u.username().is_empty()
            || u.password().is_some()
            || ![
                "github.com",
                "raw.githubusercontent.com",
                "release-assets.githubusercontent.com",
                "objects.githubusercontent.com",
            ]
            .contains(&u.host_str().unwrap_or(""))
        {
            bail!("UPDATE_URL: only approved GitHub HTTPS endpoints permitted")
        };
        Ok(u)
    }
    fn send(&self, value: &str, offset: u64) -> Result<reqwest::blocking::Response> {
        let mut url = Self::url(value)?;
        for _ in 0..5 {
            self.cancelled()?;
            let mut request = self.http.get(url.clone());
            if offset > 0 {
                request = request.header(reqwest::header::RANGE, format!("bytes={offset}-"));
            }
            let response = request.send()?;
            if response.status().is_redirection() {
                let location = response
                    .headers()
                    .get(reqwest::header::LOCATION)
                    .context("UPDATE_REDIRECT: missing location")?
                    .to_str()?;
                url = Self::url(url.join(location)?.as_str())?;
                continue;
            }
            if [403, 429].contains(&response.status().as_u16()) {
                bail!(
                    "UPDATE_RATE_LIMIT: GitHub download limit; installed version remains available"
                )
            };
            return Ok(response.error_for_status()?);
        }
        bail!("UPDATE_REDIRECT: too many redirects")
    }
    fn small(&self, url: &str, limit: u64) -> Result<Vec<u8>> {
        let response = self.send(url, 0)?;
        let mut b = vec![];
        response.take(limit + 1).read_to_end(&mut b)?;
        self.cancelled()?;
        if b.len() as u64 > limit {
            bail!("UPDATE_SIZE: response too large")
        };
        Ok(b)
    }
    fn authenticate(&self, raw: &[u8], sig: &[u8]) -> Result<ReleaseManifest> {
        if raw.len() > 1024 * 1024 || sig.len() > 8192 {
            bail!("UPDATE_SIZE: invalid signed manifest size")
        };
        let pem = fs::read_to_string(self.layout.at("config/release-public.pem"))
            .context("UPDATE_KEY: trusted release-public.pem required")?;
        let key = RsaPublicKey::from_public_key_pem(&pem)
            .or_else(|_| RsaPublicKey::from_pkcs1_pem(&pem))?;
        let signature = Signature::try_from(sig)?;
        VerifyingKey::<Sha256>::new(key)
            .verify(raw, &signature)
            .context("UPDATE_SIGNATURE: manifest authentication failed")?;
        let m: ReleaseManifest = serde_json::from_slice(raw)?;
        Self::validate(&m)?;
        Ok(m)
    }
    fn authenticated_feed(&self, feed: &str) -> Result<(ReleaseManifest, Vec<u8>, Vec<u8>)> {
        Self::url(feed)?;
        let raw = self.small(feed, 1024 * 1024)?;
        let sig = self.small(&format!("{feed}.sig"), 8192)?;
        let m = self.authenticate(&raw, &sig)?;
        let installed: InstalledRelease = read_json(&self.layout.at("state/installed.json"))?;
        if m.sequence < installed.sequence {
            bail!("UPDATE_REPLAY: signed feed sequence is older than installed high water mark")
        };
        Ok((m, raw, sig))
    }
    pub fn check(&self, feed: &str) -> Result<ReleaseManifest> {
        let (m, _, _) = self.authenticated_feed(feed)?;
        write_json(&self.layout.at("state/available.json"), &m)?;
        Ok(m)
    }
    pub fn validate(m: &ReleaseManifest) -> Result<()> {
        let id = |s: &str, max: usize, dot: bool| {
            !s.is_empty()
                && s.len() <= max
                && s.as_bytes()[0].is_ascii_alphanumeric()
                && s.bytes().all(|c| {
                    c.is_ascii_alphanumeric() || c == b'_' || c == b'-' || dot && c == b'.'
                })
        };
        let version = m
            .minimum_launcher_version
            .split('.')
            .map(str::parse::<u32>)
            .collect::<std::result::Result<Vec<_>, _>>()?;
        if m.schema_version != 1
            || !id(&m.release_id, 80, true)
            || !(1..=32).contains(&m.components.len())
            || m.sequence < 1
            || version.len() < 2
            || version.len() > 4
            || version.as_slice() > [0, 4, 0, 0].as_slice()
        {
            bail!("UPDATE_MANIFEST: unsupported release manifest")
        };
        let mut ids = HashSet::new();
        for p in &m.components {
            Self::url(&p.url)?;
            if !id(&p.id, 40, false)
                || !ids.insert(p.id.to_lowercase())
                || !valid_hash(&p.sha256)
                || p.size == 0
                || p.size > 20 * 1024 * 1024 * 1024
                || p.expanded_size == 0
                || p.expanded_size > 100 * 1024 * 1024 * 1024
                || (p.files.is_empty() && !valid_hash(&p.file_index_sha256))
                || (!p.files.is_empty() && !p.file_index_sha256.is_empty())
            {
                bail!("UPDATE_PACKAGE: invalid package metadata")
            };
            let mut names = HashSet::new();
            for (name, hash) in &p.files {
                validate_relative(name)?;
                if !names.insert(name.to_lowercase()) || !valid_hash(hash) {
                    bail!("UPDATE_HASH: invalid or duplicate inventory entry")
                }
            }
        }
        Ok(())
    }
    pub fn download(
        &self,
        p: &Package,
        destination: &Path,
        callback: Option<&Progress>,
    ) -> Result<()> {
        self.cancelled()?;
        Self::url(&p.url)?;
        if !valid_hash(&p.sha256) || p.size == 0 {
            bail!("DOWNLOAD_METADATA: invalid pinned size/hash")
        };
        no_links(destination)?;
        fs::create_dir_all(destination.parent().context("download parent")?)?;
        if destination.is_file()
            && fs::metadata(destination)?.len() == p.size
            && sha(destination)? == p.sha256
        {
            return Ok(());
        };
        let part = PathBuf::from(format!("{}.part", destination.display()));
        no_links(&part)?;
        let mut offset = if part.exists() {
            fs::metadata(&part)?.len()
        } else {
            0
        };
        if offset >= p.size {
            if offset == p.size && sha(&part)? == p.sha256 {
                if destination.exists() {
                    fs::remove_file(destination)?;
                }
                fs::rename(part, destination)?;
                return Ok(());
            }
            fs::remove_file(&part)?;
            offset = 0;
        }
        let mut response = self.send(&p.url, offset)?;
        if response.status() == reqwest::StatusCode::PARTIAL_CONTENT {
            let expected = format!("bytes {offset}-");
            let range = response
                .headers()
                .get(reqwest::header::CONTENT_RANGE)
                .context("DOWNLOAD_RANGE")?
                .to_str()?;
            if !range.starts_with(&expected) || !range.ends_with(&format!("/{}", p.size)) {
                bail!("DOWNLOAD_RANGE: invalid resumed range")
            }
        } else if response.status() == reqwest::StatusCode::OK {
            offset = 0
        } else {
            bail!("DOWNLOAD_STATUS: unexpected HTTP response")
        };
        let mut out = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(offset == 0)
            .append(offset > 0)
            .open(&part)?;
        let mut count = offset;
        let mut b = [0; 131072];
        let mut last = std::time::Instant::now();
        loop {
            self.cancelled()?;
            let n = response.read(&mut b)?;
            if n == 0 {
                break;
            };
            count = count.checked_add(n as u64).context("DOWNLOAD_SIZE")?;
            if count > p.size {
                bail!("DOWNLOAD_SIZE: exceeds authenticated size")
            };
            out.write_all(&b[..n])?;
            if last.elapsed() > Duration::from_millis(500) {
                progress(callback, format!("{}: {}%", p.id, count * 100 / p.size));
                last = std::time::Instant::now();
            }
        }
        out.sync_all()?;
        drop(out);
        if count != p.size {
            bail!("DOWNLOAD_INCOMPLETE: resume on next attempt")
        };
        if sha(&part)? != p.sha256 {
            fs::remove_file(part)?;
            bail!("DOWNLOAD_HASH: downloaded hash mismatch")
        };
        if destination.exists() {
            fs::remove_file(destination)?;
        }
        fs::rename(part, destination)?;
        Ok(())
    }
    pub fn contents(root: &Path, p: &Package) -> Result<BTreeMap<String, String>> {
        if p.file_index_sha256.is_empty() {
            return Ok(p.files.clone());
        }
        let index = child(root, ".bscp-files.json")?;
        if fs::metadata(&index)?.len() > 32 * 1024 * 1024 || sha(&index)? != p.file_index_sha256 {
            bail!("PACKAGE_INDEX: index authentication failed")
        };
        let mut files: BTreeMap<String, String> = read_json(&index)?;
        if files.is_empty()
            || files.len() > 500000
            || files
                .keys()
                .any(|p| p.eq_ignore_ascii_case(".bscp-files.json"))
        {
            bail!("PACKAGE_INDEX: invalid index")
        };
        let mut names = HashSet::new();
        for (name, hash) in &files {
            validate_relative(name)?;
            if !names.insert(name.to_lowercase()) || !valid_hash(hash) {
                bail!("PACKAGE_INDEX: invalid inventory")
            }
        }
        files.insert(".bscp-files.json".into(), p.file_index_sha256.clone());
        Ok(files)
    }
    pub fn verify_package(root: &Path, p: &Package) -> Result<()> {
        verify_inventory(root, &Self::contents(root, p)?, None)
    }
    fn verify_contents(root: &Path, m: &ReleaseManifest) -> Result<()> {
        for p in &m.components {
            Self::verify_package(&child(root, &p.id)?, p)?;
        }
        Ok(())
    }
    pub fn install(&self, feed: &str, callback: Option<&Progress>) -> Result<InstalledRelease> {
        let _lease = Lease::acquire(&self.layout.at("state/update.lock"))?;
        let (m, raw, sig) = self.authenticated_feed(feed)?;
        let data = self.layout.data(&self.layout.load()?);
        let releases = data.join("releases");
        no_links(&releases)?;
        fs::create_dir_all(&releases)?;
        let destination = child(&releases, &m.release_id)?;
        let old: InstalledRelease = read_json(&self.layout.at("state/installed.json"))?;
        if destination.exists() {
            if fs::read(destination.join("release.manifest"))? != raw {
                bail!("RELEASE_COLLISION: release id has different authenticated contents")
            };
            Self::verify_contents(&destination, &m)?;
            atomic_write(&destination.join("release.manifest.sig"), &sig)?;
            if old.release_id == m.release_id {
                return Ok(old);
            }
            let installed = InstalledRelease {
                release_id: m.release_id,
                sequence: old.sequence.max(m.sequence),
                previous: old.release_id,
            };
            write_json(&self.layout.at("state/installed.json"), &installed)?;
            return Ok(installed);
        }
        let stage = child(
            &releases,
            &format!(".incoming-{}", uuid::Uuid::new_v4().simple()),
        )?;
        fs::create_dir(&stage)?;
        let result = (|| {
            let required = m
                .components
                .iter()
                .try_fold(0u64, |n, p| {
                    n.checked_add(p.size)?.checked_add(p.expanded_size)
                })
                .context("DISK_SPACE: size overflow")?;
            if fs2::available_space(&releases)? < required {
                bail!("DISK_SPACE: insufficient space")
            };
            for p in &m.components {
                self.cancelled()?;
                let archive = data.join("cache").join(format!("{}.zip", p.sha256));
                self.download(p, &archive, callback)?;
                let target = child(&stage, &p.id)?;
                fs::create_dir(&target)?;
                safe_extract_cancellable(
                    &archive,
                    &target,
                    p.expanded_size,
                    |_| true,
                    &self.cancel,
                )?;
                self.cancelled()?;
                Self::verify_package(&target, p)?;
            }
            write_json(&stage.join("release.json"), &m)?;
            atomic_write(&stage.join("release.manifest"), &raw)?;
            atomic_write(&stage.join("release.manifest.sig"), &sig)?;
            self.cancelled()?;
            fs::rename(&stage, &destination)?;
            let installed = InstalledRelease {
                release_id: m.release_id,
                sequence: m.sequence,
                previous: old.release_id,
            };
            write_json(&self.layout.at("state/installed.json"), &installed)?;
            Ok(installed)
        })();
        delete_owned(&releases, &stage)?;
        result
    }
    fn saved_manifest(&self, folder: &Path) -> Result<ReleaseManifest> {
        let raw = read_bounded(&folder.join("release.manifest"), 1024 * 1024)?;
        let sig = read_bounded(&folder.join("release.manifest.sig"), 8192)?;
        self.authenticate(&raw, &sig)
    }
    pub fn rollback(&self) -> Result<InstalledRelease> {
        let _lease = Lease::acquire(&self.layout.at("state/update.lock"))?;
        let old: InstalledRelease = read_json(&self.layout.at("state/installed.json"))?;
        if old.previous.is_empty() {
            bail!("ROLLBACK_MISSING: no previous release")
        };
        let folder = child(
            &self.layout.data(&self.layout.load()?).join("releases"),
            &old.previous,
        )?;
        let m = self.saved_manifest(&folder)?;
        if m.release_id != old.previous {
            bail!("ROLLBACK_ID: wrong release")
        };
        Self::verify_contents(&folder, &m)?;
        let installed = InstalledRelease {
            release_id: m.release_id,
            sequence: old.sequence,
            previous: old.release_id,
        };
        write_json(&self.layout.at("state/installed.json"), &installed)?;
        Ok(installed)
    }
    pub fn verified_launcher(&self) -> Result<Option<PathBuf>> {
        let installed: InstalledRelease = read_json(&self.layout.at("state/installed.json"))?;
        if installed.release_id.is_empty() {
            return Ok(None);
        }
        let folder = child(
            &self.layout.data(&self.layout.load()?).join("releases"),
            &installed.release_id,
        )?;
        let m = self.saved_manifest(&folder)?;
        if m.release_id != installed.release_id {
            bail!("UPDATE_ID: installed release mismatch")
        };
        if let Some(p) = m.components.iter().find(|p| p.id == "launcher") {
            let root = child(&folder, "launcher")?;
            Self::verify_package(&root, p)?;
            let index = Self::contents(&root, p)?;
            if !index.contains_key("BSCPCRAFT.exe") || !index.contains_key("bscp-host.exe") {
                bail!(
                    "LAUNCHER_CONTENTS: в релизе отсутствуют нативные BSCPCRAFT.exe и bscp-host.exe"
                )
            };
            return Ok(Some(root.join("BSCPCRAFT.exe")));
        }
        Ok(None)
    }
}
fn read_bounded(path: &Path, limit: u64) -> Result<Vec<u8>> {
    no_links(path)?;
    let mut b = vec![];
    File::open(path)?.take(limit + 1).read_to_end(&mut b)?;
    if b.len() as u64 > limit {
        bail!("UPDATE_SIZE: retained manifest too large")
    };
    Ok(b)
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct BootstrapFile {
    #[serde(alias = "id")]
    pub id: String,
    #[serde(alias = "url")]
    pub url: String,
    #[serde(alias = "sha256")]
    pub sha256: String,
    #[serde(alias = "size")]
    pub size: u64,
    #[serde(alias = "target")]
    pub target: String,
}
impl BootstrapFile {
    fn package(&self) -> Package {
        Package {
            id: self.id.clone(),
            url: self.url.clone(),
            sha256: self.sha256.clone(),
            size: self.size,
            ..Default::default()
        }
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct BootstrapPins {
    #[serde(alias = "base")]
    pub base: BootstrapFile,
    #[serde(alias = "native")]
    pub native: BootstrapFile,
    #[serde(alias = "patches")]
    pub patches: Vec<BootstrapFile>,
    #[serde(alias = "native_files", alias = "nativeFiles")]
    pub native_files: BTreeMap<String, String>,
}
pub struct BundleInstaller;
impl BundleInstaller {
    pub fn pins() -> Result<BootstrapPins> {
        Ok(serde_json::from_str(Self::pin_bytes())?)
    }
    fn pin_bytes() -> &'static str {
        include_str!("../../../bootstrap-pins.json")
    }
    pub fn install(
        layout: &Layout,
        data: &Path,
        callback: Option<&Progress>,
        local_archive: Option<&Path>,
    ) -> Result<PathBuf> {
        Self::install_cancellable(
            layout,
            data,
            callback,
            local_archive,
            Arc::new(AtomicBool::new(false)),
        )
    }
    pub fn install_cancellable(
        layout: &Layout,
        data: &Path,
        callback: Option<&Progress>,
        local_archive: Option<&Path>,
        cancel: Arc<AtomicBool>,
    ) -> Result<PathBuf> {
        let pins = Self::pins()?;
        let id = &hex::encode(Sha256::digest(Self::pin_bytes().as_bytes()))[..16];
        let bundles = data.join("bundles");
        no_links(&bundles)?;
        fs::create_dir_all(&bundles)?;
        let _lease = Lease::acquire(&layout.at("state/bundle-install.lock"))?;
        let destination = child(&bundles, &format!("bootstrap-{id}"))?;
        if destination.join(".complete").is_file() {
            Setup::check_components(&destination.join("Client"), &destination.join("Server"))?;
            write_json(
                &layout.at("state/bootstrap.json"),
                &BTreeMap::from([("path", layout.store(&destination))]),
            )?;
            return Ok(destination);
        }
        let stage = child(
            &bundles,
            &format!(".incoming-{}", uuid::Uuid::new_v4().simple()),
        )?;
        fs::create_dir(&stage)?;
        let result = (|| {
            if fs2::available_space(data)? < 5 * 1024 * 1024 * 1024 {
                bail!("DISK_SPACE: at least 5 GB required")
            };
            let updates = UpdateService::new(layout)?.with_cancel(cancel.clone());
            let get = |p: &BootstrapFile| -> Result<PathBuf> {
                let path = data.join("cache").join(format!("{}.download", p.sha256));
                updates.download(&p.package(), &path, callback)?;
                Ok(path)
            };
            let archive = if let Some(path) = local_archive {
                if fs::metadata(path)?.len() != pins.base.size || sha(path)? != pins.base.sha256 {
                    bail!("BUNDLE_HASH: archive does not match embedded distribution pin")
                };
                path.to_owned()
            } else {
                get(&pins.base)?
            };
            progress(callback, "Распаковываем игровые компоненты…");
            safe_extract_cancellable(
                &archive,
                &stage,
                8 * 1024 * 1024 * 1024,
                |name| {
                    let p = name.split('/').collect::<Vec<_>>();
                    ["Client", "Server", "Map"].contains(&p[0])
                        && !(p.len() > 1 && ["data", "logs", "saves", "reports"].contains(&p[1]))
                        && !secret(name)
                },
                &cancel,
            )?;
            updates.cancelled()?;
            for patch in &pins.patches {
                let source = get(patch)?;
                let target = child(&stage, &patch.target)?;
                fs::create_dir_all(target.parent().unwrap())?;
                fs::copy(source, target)?;
            }
            let native_archive = get(&pins.native)?;
            let native_stage = child(&stage, ".native")?;
            fs::create_dir(&native_stage)?;
            safe_extract_cancellable(
                &native_archive,
                &native_stage,
                200 * 1024 * 1024,
                |_| true,
                &cancel,
            )?;
            updates.cancelled()?;
            verify_inventory(&native_stage, &pins.native_files, None)?;
            for name in pins.native_files.keys() {
                let target = child(&stage.join("Client/runtime/natives"), name)?;
                fs::create_dir_all(target.parent().unwrap())?;
                fs::copy(child(&native_stage, name)?, target)?;
            }
            delete_owned(&stage, &native_stage)?;
            Setup::check_components(&stage.join("Client"), &stage.join("Server"))?;
            updates.cancelled()?;
            fs::write(stage.join(".complete"), id)?;
            fs::rename(&stage, &destination)?;
            write_json(
                &layout.at("state/bootstrap.json"),
                &BTreeMap::from([("path", layout.store(&destination))]),
            )?;
            Ok(destination)
        })();
        delete_owned(&bundles, &stage)?;
        result
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn blocking_transport_streams_body_without_tokio_reactor() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let worker = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut request = [0; 1024];
            let _ = socket.read(&mut request).unwrap();
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\na")
                .unwrap();
            std::thread::sleep(Duration::from_millis(20));
            socket.write_all(b"bc").unwrap();
        });
        let t = tempfile::tempdir().unwrap();
        let client = UpdateService::new(&Layout::new(t.path())).unwrap();
        let mut response = client
            .http
            .get(format!("http://{address}/"))
            .send()
            .unwrap();
        let mut body = String::new();
        response.read_to_string(&mut body).unwrap();
        assert_eq!(body, "abc");
        worker.join().unwrap();
    }
    use rsa::{
        RsaPrivateKey,
        pkcs1v15::SigningKey,
        pkcs8::{EncodePublicKey, LineEnding},
        signature::{SignatureEncoding, Signer},
    };
    fn release(id: &str, sequence: i64) -> ReleaseManifest {
        ReleaseManifest {
            release_id: id.into(),
            sequence,
            components: vec![Package {
                id: "client".into(),
                url: "https://github.com/example/repo/releases/download/v/client.zip".into(),
                sha256: "a".repeat(64),
                size: 1,
                expanded_size: 1,
                files: BTreeMap::from([("file".into(), hex::encode(Sha256::digest(b"x")))]),
                ..Default::default()
            }],
            ..Default::default()
        }
    }
    #[test]
    fn authenticated_rollback_verifies_files_and_preserves_replay_high_water_mark() {
        let temp = tempfile::TempDir::new().unwrap();
        let layout = Layout::new(temp.path());
        layout.init().unwrap();
        let key = RsaPrivateKey::new(&mut rand_core::OsRng, 2048).unwrap();
        fs::write(
            layout.at("config/release-public.pem"),
            key.to_public_key()
                .to_public_key_pem(LineEnding::LF)
                .unwrap(),
        )
        .unwrap();
        let service = UpdateService::new(&layout).unwrap();
        let mut manifest = release("old", 4);
        let mut launcher = manifest.components[0].clone();
        launcher.id = "launcher".into();
        launcher.files = BTreeMap::from([
            (
                "BSCPCRAFT.exe".into(),
                hex::encode(Sha256::digest(b"native-ui")),
            ),
            (
                "bscp-host.exe".into(),
                hex::encode(Sha256::digest(b"native-host")),
            ),
        ]);
        manifest.components.push(launcher);
        let raw = serde_json::to_vec(&manifest).unwrap();
        let signature = SigningKey::<Sha256>::new(key).sign(&raw).to_vec();
        assert!(service.authenticate(&raw, &signature).is_ok());
        let mut corrupt = raw.clone();
        corrupt[0] = b' ';
        assert!(service.authenticate(&corrupt, &signature).is_err());
        let folder = layout.data(&layout.load().unwrap()).join("releases/old");
        fs::create_dir_all(folder.join("client")).unwrap();
        fs::write(folder.join("client/file"), b"x").unwrap();
        fs::create_dir(folder.join("launcher")).unwrap();
        fs::write(folder.join("launcher/BSCPCRAFT.exe"), b"native-ui").unwrap();
        fs::write(folder.join("launcher/bscp-host.exe"), b"native-host").unwrap();
        fs::write(folder.join("release.manifest"), raw).unwrap();
        fs::write(folder.join("release.manifest.sig"), signature).unwrap();
        write_json(
            &layout.at("state/installed.json"),
            &InstalledRelease {
                release_id: "new".into(),
                sequence: 10,
                previous: "old".into(),
            },
        )
        .unwrap();
        let result = service.rollback().unwrap();
        assert_eq!(result.release_id, "old");
        assert_eq!(result.sequence, 10);
        assert_eq!(result.previous, "new");
        assert_eq!(
            service.verified_launcher().unwrap(),
            Some(folder.join("launcher/BSCPCRAFT.exe"))
        );
        write_json(
            &layout.at("state/installed.json"),
            &InstalledRelease {
                release_id: "new".into(),
                sequence: 10,
                previous: "old".into(),
            },
        )
        .unwrap();
        fs::write(folder.join("client/file"), b"tampered").unwrap();
        assert!(service.rollback().is_err());
        let unchanged: InstalledRelease = read_json(&layout.at("state/installed.json")).unwrap();
        assert_eq!(unchanged.release_id, "new");
    }
    #[test]
    fn download_origins_reject_credentials_and_untrusted_redirect_targets() {
        for value in [
            "http://github.com/a",
            "https://evil.example/a",
            "https://github.com.evil.example/a",
            "https://user@github.com/a",
        ] {
            assert!(UpdateService::url(value).is_err());
        }
        assert!(UpdateService::url("https://release-assets.githubusercontent.com/a").is_ok());
    }
    #[test]
    fn pins_are_embedded_and_have_valid_hashes() {
        let pins = BundleInstaller::pins().unwrap();
        assert!(valid_hash(&pins.base.sha256));
        assert!(valid_hash(&pins.native.sha256));
        assert!(!pins.native_files.is_empty());
    }
}
