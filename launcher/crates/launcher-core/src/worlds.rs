use crate::storage::*;
use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
use uuid::Uuid;
fn now() -> String {
    chrono::Utc::now().to_rfc3339()
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct WorldInfo {
    #[serde(alias = "schema_version", alias = "schemaVersion")]
    pub schema_version: i32,
    #[serde(alias = "id")]
    pub id: String,
    #[serde(alias = "name")]
    pub name: String,
    #[serde(alias = "created")]
    pub created: String,
    #[serde(alias = "last_played", alias = "lastPlayed")]
    pub last_played: Option<String>,
    #[serde(alias = "map_path", alias = "mapPath")]
    pub map_path: String,
    #[serde(alias = "map_hash", alias = "mapHash")]
    pub map_hash: String,
    #[serde(alias = "server_release", alias = "serverRelease")]
    pub server_release: String,
    #[serde(alias = "base_identity_mode", alias = "baseIdentityMode")]
    pub base_identity_mode: String,
    #[serde(alias = "owner_id", alias = "ownerId")]
    pub owner_id: String,
}
impl Default for WorldInfo {
    fn default() -> Self {
        Self {
            schema_version: 1,
            id: Uuid::new_v4().simple().to_string(),
            name: "РќРѕРІС‹Р№ РјРёСЂ".into(),
            created: now(),
            last_played: None,
            map_path: String::new(),
            map_hash: String::new(),
            server_release: String::new(),
            base_identity_mode: "content-v2".into(),
            owner_id: String::new(),
        }
    }
}
#[derive(Debug, Clone)]
pub struct WorldStore {
    pub root: PathBuf,
}
impl WorldStore {
    pub fn new(data: impl AsRef<Path>) -> Result<Self> {
        let root = std::path::absolute(data)?.join("worlds");
        no_links(&root)?;
        fs::create_dir_all(&root)?;
        Ok(Self { root })
    }
    pub fn folder(&self, id: &str) -> Result<PathBuf> {
        if id.len() != 32
            || !id.bytes().all(|b| b.is_ascii_hexdigit())
            || Uuid::parse_str(id).is_err()
        {
            bail!("WORLD_ID: invalid world id")
        }
        child(&self.root, id)
    }
    pub fn list(&self) -> Result<Vec<WorldInfo>> {
        let mut out = vec![];
        for e in fs::read_dir(&self.root)? {
            let p = e?.path();
            let id = p.file_name().unwrap_or_default().to_string_lossy();
            if self.folder(&id).is_ok()
                && p.join("world.json").is_file()
                && !p.join("restore-source.json").exists()
            {
                out.push(self.get(&id)?);
            }
        }
        out.sort_by(|a, b| {
            b.last_played
                .as_ref()
                .unwrap_or(&b.created)
                .cmp(a.last_played.as_ref().unwrap_or(&a.created))
        });
        Ok(out)
    }
    pub fn get(&self, id: &str) -> Result<WorldInfo> {
        let p = self.folder(id)?.join("world.json");
        if !p.is_file() {
            bail!("WORLD_MISSING: world not found")
        }
        let w: WorldInfo = read_json(&p)?;
        if w.id != id || w.schema_version != 1 {
            bail!("WORLD_FORMAT: invalid world metadata")
        }
        Ok(w)
    }
    pub fn save(&self, w: &WorldInfo) -> Result<()> {
        write_json(&self.folder(&w.id)?.join("world.json"), w)
    }
    pub fn map(&self, w: &WorldInfo) -> Result<PathBuf> {
        child(self.root.parent().unwrap(), &w.map_path.replace('\\', "/"))
    }
    pub fn supervisor_lease(&self, id: &str) -> Result<Lease> {
        self.folder(id)?;
        Lease::acquire(&self.root.join(".locks").join(format!("{id}.lock")))
    }
    pub fn lease(&self, id: &str) -> Result<Lease> {
        let held = self.supervisor_lease(id)?;
        for p in ["data", "data.before-restore"] {
            let path = self.folder(id)?.join(p).join(".server-lease");
            if path.exists() {
                let _probe = Lease::acquire(&path)?;
            }
        }
        self.recover_restore(id)?;
        Ok(held)
    }
    pub fn create(&self, name: &str, map: &Path, owner: &str) -> Result<WorldInfo> {
        self.create_cancellable(name, map, owner, &std::sync::atomic::AtomicBool::new(false))
    }
    pub fn create_cancellable(
        &self,
        name: &str,
        map: &Path,
        owner: &str,
        cancel: &std::sync::atomic::AtomicBool,
    ) -> Result<WorldInfo> {
        validate_name(name)?;
        if !map.join("registry.json").is_file() || !map.join("region").is_dir() {
            bail!("MAP_INVALID: registry.json and region required")
        }
        let hash = map_digest_cancellable(map, cancel)?;
        let base = self.root.parent().unwrap().join("maps").join(&hash);
        no_links(&base)?;
        if !base.exists() {
            fs::create_dir_all(base.parent().unwrap())?;
            let stage = base.with_extension(format!("incoming-{}", Uuid::new_v4().simple()));
            let result = (|| {
                copy_tree_cancellable(map, &stage, |_| true, cancel)?;
                if map_digest_cancellable(&stage, cancel)? != hash {
                    bail!("MAP_CHANGED: map changed during copy")
                }
                match fs::rename(&stage, &base) {
                    Ok(_) => Ok(()),
                    Err(_) if base.is_dir() => Ok(()),
                    Err(e) => Err(e.into()),
                }
            })();
            delete_owned(base.parent().unwrap(), &stage)?;
            result?;
        } else if map_digest(&base)? != hash {
            bail!("MAP_CHANGED: managed base changed")
        };
        let w = WorldInfo {
            name: name.trim().into(),
            map_path: format!("maps/{hash}"),
            map_hash: hash,
            owner_id: owner.into(),
            ..Default::default()
        };
        fs::create_dir_all(self.folder(&w.id)?.join("data"))?;
        self.save(&w)?;
        Ok(w)
    }
    pub fn clone_world(&self, id: &str, name: &str) -> Result<WorldInfo> {
        validate_name(name)?;
        let _lease = self.lease(id)?;
        let old = self.get(id)?;
        let w = WorldInfo {
            name: name.trim().into(),
            map_path: old.map_path,
            map_hash: old.map_hash,
            owner_id: old.owner_id,
            base_identity_mode: old.base_identity_mode,
            server_release: old.server_release,
            ..Default::default()
        };
        let target = self.folder(&w.id)?;
        let result = (|| {
            copy_tree(&self.folder(id)?.join("data"), &target.join("data"), |p| {
                !secret(p) && p != ".server-lease"
            })?;
            self.save(&w)?;
            Ok(w)
        })();
        if result.is_err() {
            delete_owned(&self.root, &target)?;
        }
        result
    }
    pub fn rename(&self, id: &str, name: &str) -> Result<()> {
        validate_name(name)?;
        let _lease = self.lease(id)?;
        let mut w = self.get(id)?;
        w.name = name.trim().into();
        self.save(&w)
    }
    pub fn delete(&self, id: &str, confirmation: &str) -> Result<()> {
        let lease = self.lease(id)?;
        let w = self.get(id)?;
        if confirmation != w.name {
            bail!("WORLD_CONFIRM: type complete world name")
        };
        let tomb = child(&self.root, &format!(".deleting-{id}"))?;
        fs::rename(self.folder(id)?, &tomb)?;
        drop(lease);
        delete_owned(&self.root, &tomb)
    }
    pub fn export(&self, id: &str, destination: &Path) -> Result<()> {
        let _lease = self.lease(id)?;
        let root = self.folder(id)?;
        no_links(destination)?;
        if destination.starts_with(&root) {
            bail!("ARCHIVE_PATH: destination is inside world")
        };
        fs::create_dir_all(destination.parent().context("archive parent")?)?;
        let temp = destination.with_extension(format!("{}.partial", Uuid::new_v4().simple()));
        let result = (|| {
            let mut zip = zip::ZipWriter::new(
                OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&temp)?,
            );
            let opts = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);
            let mut manifest = ArchiveManifest::default();
            for p in files(&root)? {
                let name = relative(&root, &p);
                if secret(&name)
                    || name.ends_with(".bak")
                    || name == "data/.server-lease"
                    || name == ".lock"
                {
                    continue;
                }
                manifest.files.insert(name.clone(), sha(&p)?);
                zip.start_file(name, opts)?;
                std::io::copy(&mut File::open(p)?, &mut zip)?;
            }
            zip.start_file("archive-manifest.json", opts)?;
            zip.write_all(&serde_json::to_vec_pretty(&manifest)?)?;
            zip.finish()?.sync_all()?;
            if destination.exists() {
                bail!("ARCHIVE_EXISTS: destination exists")
            };
            fs::rename(&temp, destination)?;
            Ok(())
        })();
        let _ = fs::remove_file(temp);
        result
    }
    pub fn import(&self, archive: &Path) -> Result<WorldInfo> {
        let parent = self.root.parent().unwrap().join("temp");
        fs::create_dir_all(&parent)?;
        let stage = child(&parent, &Uuid::new_v4().simple().to_string())?;
        fs::create_dir(&stage)?;
        let result = (|| {
            safe_extract(archive, &stage, 20 * 1024 * 1024 * 1024, |_| true)?;
            let manifest: ArchiveManifest = read_json(&stage.join("archive-manifest.json"))?;
            if manifest.schema_version != 1 || manifest.kind != "world" || manifest.files.is_empty()
            {
                bail!("ARCHIVE_FORMAT: invalid manifest")
            };
            verify_inventory(&stage, &manifest.files, Some("archive-manifest.json"))?;
            if manifest.files.keys().any(|p| secret(p)) {
                bail!("ARCHIVE_SECRET: archive contains credentials")
            };
            let mut w: WorldInfo = read_json(&stage.join("world.json"))?;
            if w.schema_version != 1
                || !valid_hash(&w.map_hash)
                || w.base_identity_mode != "content-v2"
            {
                bail!("WORLD_FORMAT: unsupported world")
            };
            w.id = Uuid::new_v4().simple().to_string();
            w.name += " (РёРјРїРѕСЂС‚)";
            w.last_played = None;
            w.map_path = format!("maps/{}", w.map_hash);
            if !self.map(&w)?.is_dir() {
                bail!("MAP_MISSING: import world base first")
            };
            fs::create_dir_all(stage.join("data"))?;
            fs::remove_file(stage.join("archive-manifest.json"))?;
            write_json(&stage.join("world.json"), &w)?;
            fs::rename(&stage, self.folder(&w.id)?)?;
            Ok(w)
        })();
        delete_owned(&parent, &stage)?;
        result
    }
    pub fn restore(&self, id: &str, archive: &Path) -> Result<()> {
        let _lease = self.lease(id)?;
        let current = self.get(id)?;
        let imported = self.import(archive)?;
        let source = self.folder(&imported.id)?;
        let folder = self.folder(id)?;
        let pending = folder.join("restore.pending.json");
        let result = (|| {
            if current.map_hash != imported.map_hash {
                bail!("BACKUP_BASE: backup belongs to another map")
            };
            if folder.join("data.before-restore").exists() {
                bail!("RESTORE_PENDING: previous restore incomplete")
            };
            if !folder.join("data").is_dir() {
                bail!("RESTORE_MISSING: original data is missing")
            };
            write_json(
                &source.join("restore-source.json"),
                &BTreeMap::from([("target", id)]),
            )?;
            let mut txn = RestoreTransaction {
                imported_id: imported.id.clone(),
                phase: "prepared".into(),
            };
            write_json(&pending, &txn)?;
            fs::rename(folder.join("data"), folder.join("data.before-restore"))?;
            fs::rename(source.join("data"), folder.join("data"))?;
            txn.phase = "committed".into();
            write_json(&pending, &txn)?;
            self.recover_restore(id)
        })();
        if result.is_err() && pending.exists() {
            self.recover_restore(id)?;
        }
        if !pending.exists() {
            delete_owned(&self.root, &source)?;
        }
        result
    }
    pub fn recover_restore(&self, id: &str) -> Result<()> {
        let folder = self.folder(id)?;
        let pending = folder.join("restore.pending.json");
        let old = folder.join("data.before-restore");
        let data = folder.join("data");
        if !pending.exists() {
            if old.exists() {
                bail!("RESTORE_PENDING: unjournaled preserved data")
            };
            return Ok(());
        };
        let txn: RestoreTransaction = read_json(&pending)?;
        if txn.imported_id == id || !["prepared", "committed"].contains(&txn.phase.as_str()) {
            bail!("RESTORE_FORMAT: invalid restore journal")
        };
        let source = self.folder(&txn.imported_id)?;
        let _source_lease = self.lease(&txn.imported_id)?;
        if source.exists() {
            let marker: BTreeMap<String, String> = read_json(&source.join("restore-source.json"))?;
            if marker.get("target").map(String::as_str) != Some(id) {
                bail!("RESTORE_SOURCE: missing source marker")
            }
        }
        if txn.phase == "prepared" {
            if old.exists() {
                if data.exists() {
                    if source.join("data").exists() {
                        bail!("RESTORE_SOURCE: conflicting source data")
                    };
                    fs::create_dir_all(&source)?;
                    fs::rename(&data, source.join("data"))?;
                }
                fs::rename(&old, &data)?;
            }
            if !data.is_dir() {
                bail!("RESTORE_MISSING: original data missing; launch blocked")
            }
        } else {
            if !data.is_dir() {
                bail!("RESTORE_MISSING: restored data missing; launch blocked")
            };
            delete_owned(&folder, &old)?;
        }
        delete_owned(&self.root, &source)?;
        fs::remove_file(&pending)?;
        let _ = fs::remove_file(format!("{}.bak", pending.display()));
        Ok(())
    }
    pub fn automatic_backup(&self, id: &str, retain: usize) -> Result<PathBuf> {
        if !(1..=100).contains(&retain) {
            bail!("BACKUP_LIMIT")
        };
        self.folder(id)?;
        let parent = self.root.parent().unwrap().join("backups");
        let folder = child(&parent, id)?;
        fs::create_dir_all(&folder)?;
        let path = folder.join(format!(
            "{}-auto.zip",
            chrono::Utc::now().format("%Y%m%d-%H%M%S-%f")
        ));
        self.export(id, &path)?;
        let mut entries = files(&folder)?
            .into_iter()
            .filter(|p| p.to_string_lossy().ends_with("-auto.zip"))
            .collect::<Vec<_>>();
        entries.sort();
        entries.reverse();
        for p in entries.into_iter().skip(retain) {
            no_links(&p)?;
            fs::remove_file(p)?;
        }
        Ok(path)
    }
}
fn validate_name(name: &str) -> Result<()> {
    if name.trim().is_empty()
        || name.encode_utf16().count() > 100
        || name.chars().any(char::is_control)
    {
        bail!("WORLD_NAME: name must contain 1..100 characters")
    }
    Ok(())
}
pub fn relative(root: &Path, file: &Path) -> String {
    file.strip_prefix(root)
        .unwrap()
        .to_string_lossy()
        .replace('\\', "/")
}
pub fn map_digest(root: &Path) -> Result<String> {
    map_digest_cancellable(root, &std::sync::atomic::AtomicBool::new(false))
}
pub fn map_digest_cancellable(
    root: &Path,
    cancel: &std::sync::atomic::AtomicBool,
) -> Result<String> {
    let mut entries = files(root)?;
    entries.sort_by_cached_key(|p| {
        relative(root, p)
            .replace('/', "\\")
            .encode_utf16()
            .collect::<Vec<_>>()
    });
    let mut h = Sha256::new();
    for p in entries {
        check_cancel(cancel)?;
        let name = relative(root, &p);
        h.update((i32::try_from(name.len())?).to_le_bytes());
        h.update(name.as_bytes());
        let mut f = File::open(&p)?;
        h.update((i64::try_from(f.metadata()?.len())?).to_le_bytes());
        let mut b = [0; 131072];
        loop {
            check_cancel(cancel)?;
            let n = f.read(&mut b)?;
            if n == 0 {
                break;
            }
            h.update(&b[..n]);
        }
    }
    Ok(hex::encode(h.finalize()))
}
pub fn copy_tree(from: &Path, to: &Path, include: impl Fn(&str) -> bool) -> Result<()> {
    copy_tree_cancellable(
        from,
        to,
        include,
        &std::sync::atomic::AtomicBool::new(false),
    )
}
pub fn copy_tree_cancellable(
    from: &Path,
    to: &Path,
    include: impl Fn(&str) -> bool,
    cancel: &std::sync::atomic::AtomicBool,
) -> Result<()> {
    if contains_path(from, to)? {
        bail!("COPY_RECURSIVE: папка назначения находится внутри источника")
    }
    no_links(to)?;
    fs::create_dir_all(to)?;
    for p in files(from)? {
        check_cancel(cancel)?;
        let rel = relative(from, &p);
        if rel == ".lock" || rel.ends_with(".tmp") || !include(&rel) {
            continue;
        }
        let target = child(to, &rel)?;
        fs::create_dir_all(target.parent().unwrap())?;
        let mut out = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(target)?;
        let mut input = File::open(p)?;
        let mut b = [0; 131072];
        loop {
            check_cancel(cancel)?;
            let n = input.read(&mut b)?;
            if n == 0 {
                break;
            }
            out.write_all(&b[..n])?;
        }
        out.sync_all()?;
    }
    Ok(())
}
pub fn secret(relative: &str) -> bool {
    relative.split('/').any(|p| {
        let p = p.to_lowercase();
        p.contains("token")
            || p.contains("secret")
            || p.contains("password")
            || p.contains("private")
            || p == "identity.json"
            || p == "admin.json"
            || p.ends_with(".key")
    })
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct ArchiveManifest {
    #[serde(alias = "schema_version", alias = "schemaVersion")]
    pub schema_version: i32,
    #[serde(alias = "kind")]
    pub kind: String,
    #[serde(alias = "created")]
    pub created: String,
    #[serde(alias = "files")]
    pub files: BTreeMap<String, String>,
}
impl Default for ArchiveManifest {
    fn default() -> Self {
        Self {
            schema_version: 1,
            kind: "world".into(),
            created: now(),
            files: Default::default(),
        }
    }
}
#[derive(Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
struct RestoreTransaction {
    imported_id: String,
    phase: String,
}
pub fn valid_hash(hash: &str) -> bool {
    hash.len() == 64
        && hash
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
pub fn verify_inventory(
    root: &Path,
    index: &BTreeMap<String, String>,
    excluded: Option<&str>,
) -> Result<()> {
    let mut expected = HashSet::new();
    for (name, hash) in index {
        validate_relative(name)?;
        if !expected.insert(name.to_lowercase()) || !valid_hash(hash) {
            bail!("PACKAGE_INDEX: invalid or duplicate inventory entry")
        };
        if sha(&child(root, name)?)? != *hash {
            bail!("PACKAGE_HASH: modified file {name}")
        }
    }
    let actual: HashSet<_> = files(root)?
        .iter()
        .map(|p| relative(root, p))
        .filter(|p| Some(p.as_str()) != excluded)
        .map(|p| p.to_lowercase())
        .collect();
    if actual != expected {
        bail!("PACKAGE_CONTENTS: inventory mismatch")
    }
    Ok(())
}
pub fn safe_extract(
    source: &Path,
    target: &Path,
    budget: u64,
    include: impl Fn(&str) -> bool,
) -> Result<()> {
    safe_extract_cancellable(
        source,
        target,
        budget,
        include,
        &std::sync::atomic::AtomicBool::new(false),
    )
}
pub fn safe_extract_cancellable(
    source: &Path,
    target: &Path,
    budget: u64,
    include: impl Fn(&str) -> bool,
    cancel: &std::sync::atomic::AtomicBool,
) -> Result<()> {
    check_cancel(cancel)?;
    no_links(source)?;
    no_links(target)?;
    let mut zip = zip::ZipArchive::new(File::open(source)?)?;
    if zip.len() > 500000 {
        bail!("ZIP_COUNT: too many entries")
    };
    let mut seen = HashSet::new();
    let mut total = 0u64;
    for i in 0..zip.len() {
        check_cancel(cancel)?;
        let mut e = zip.by_index(i)?;
        let name = e.name().trim_end_matches('/').to_owned();
        if name.is_empty() {
            continue;
        }
        let path = child(target, &name)?;
        if !seen.insert(name.to_lowercase()) {
            bail!("ZIP_DUPLICATE: duplicate filename")
        };
        let mode = e.unix_mode().unwrap_or(0) & 0xf000;
        if ![0, 0x8000, 0x4000].contains(&mode) || e.is_symlink() {
            bail!("ZIP_LINK: links forbidden")
        };
        total = total
            .checked_add(e.size())
            .context("ZIP_BUDGET: overflow")?;
        if total > budget {
            bail!("ZIP_BUDGET: expanded size exceeded")
        };
        if !include(&name) {
            continue;
        }
        if e.is_dir() {
            fs::create_dir_all(path)?;
        } else {
            fs::create_dir_all(path.parent().unwrap())?;
            let size = e.size();
            let mut out = OpenOptions::new().write(true).create_new(true).open(path)?;
            let mut written = 0u64;
            let mut buffer = [0; 131072];
            loop {
                check_cancel(cancel)?;
                let n = e.read(&mut buffer)?;
                if n == 0 {
                    break;
                }
                written = written
                    .checked_add(n as u64)
                    .context("ZIP_SIZE: overflow")?;
                if written > size {
                    bail!("ZIP_SIZE: размер файла превышает заявленный")
                }
                out.write_all(&buffer[..n])?;
            }
            if written != size {
                bail!("ZIP_SIZE: invalid size")
            };
            out.sync_all()?;
        }
    }
    Ok(())
}

pub fn check_cancel(cancel: &std::sync::atomic::AtomicBool) -> Result<()> {
    if cancel.load(std::sync::atomic::Ordering::Relaxed) {
        bail!("CANCELLED: операция отменена")
    };
    Ok(())
}
