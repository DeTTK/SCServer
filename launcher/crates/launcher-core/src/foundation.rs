//! Complete, deduplicated game installation from build-branch objects, without release archives.
use crate::*;
use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap},
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FoundationPin {
    pub id: String,
    pub path: String,
    pub sha256: String,
    pub size: u64,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexPin {
    path: String,
    sha256: String,
    size: u64,
    decoded_size: u64,
    files: usize,
    component: String,
}
#[derive(Clone, Debug, Deserialize)]
struct ObjectPin {
    sha256: String,
    size: u64,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Foundation {
    schema_version: u32,
    id: String,
    file_count: usize,
    expanded_bytes: u64,
    indices: Vec<IndexPin>,
    objects: Vec<ObjectPin>,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Piece {
    object: String,
    offset: u64,
    length: u64,
    decoded_size: u64,
    codec: String,
}
#[derive(Clone, Debug, Deserialize)]
struct BaseFile {
    path: String,
    size: u64,
    sha256: String,
    pieces: Vec<Piece>,
}
#[derive(Default, Serialize, Deserialize)]
struct PreparedFoundation {
    stage: String,
    sha256: String,
}
fn hash_valid(hash: &str) -> bool {
    hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit())
}
fn hash(raw: &[u8]) -> String {
    hex::encode(Sha256::digest(raw))
}
fn target(root: &Path, path: &str) -> Result<PathBuf> {
    validate_relative(path)?;
    let (component, relative) = path.split_once('/').context("FOUNDATION_PATH")?;
    let name = match component {
        "client" => "Client",
        "server" => "Server",
        "map" => "Map",
        "bootstrap" => "Bootstrap",
        _ => bail!("FOUNDATION_PATH: неизвестный компонент"),
    };
    Ok(root.join(name).join(relative))
}
fn inflate(bytes: &[u8], expected: u64) -> Result<Vec<u8>> {
    ensure!(expected <= 8 * 1024 * 1024, "FOUNDATION_LIMIT");
    let mut out = Vec::with_capacity(expected as usize);
    flate2::read::ZlibDecoder::new(bytes)
        .take(expected + 1)
        .read_to_end(&mut out)?;
    ensure!(out.len() as u64 == expected, "FOUNDATION_SIZE");
    Ok(out)
}
impl RepositoryChannel {
    fn pinned_bytes(
        &self,
        commit: &str,
        path: &str,
        digest: &str,
        size: u64,
        limit: u64,
    ) -> Result<Vec<u8>> {
        ensure!(
            hash_valid(digest) && size > 0 && size <= limit,
            "FOUNDATION_METADATA"
        );
        let raw = self.small(&crate::channels::raw_url(commit, path)?, limit)?;
        ensure!(
            raw.len() as u64 == size && hash(&raw) == digest,
            "FOUNDATION_HASH: повреждён индекс основы"
        );
        Ok(raw)
    }
    pub fn prepare_base(
        &self,
        branch: &str,
        data: &Path,
        callback: Option<&Progress>,
    ) -> Result<PathBuf> {
        let available = self.check(branch)?;
        self.prepare_foundation(&available, data, callback)
    }
    pub(crate) fn prepare_foundation(
        &self,
        available: &ChannelAvailable,
        data: &Path,
        callback: Option<&Progress>,
    ) -> Result<PathBuf> {
        let pin = available
            .manifest
            .foundation
            .as_ref()
            .context("FOUNDATION_MISSING: в этой сборке пока нет полной установки")?;
        let bundles = data.join("bundles");
        no_links(&bundles)?;
        fs::create_dir_all(&bundles)?;
        let _lease = Lease::acquire(&self.layout.at("state/foundation-install.lock"))?;
        let destination = child(&bundles, &pin.id)?;
        if destination.join(".foundation-complete").is_file()
            && fs::read_to_string(destination.join(".foundation-complete"))? == pin.sha256
        {
            Setup::check_components(&destination.join("Client"), &destination.join("Server"))?;
            write_json(
                &self.layout.at("state/bootstrap.json"),
                &BTreeMap::from([("path", self.layout.store(&destination))]),
            )?;
            return Ok(destination);
        }
        ensure!(
            !destination.exists(),
            "FOUNDATION_INCOMPLETE: папка основы существует без завершённой установки; исходные данные сохранены"
        );
        progress(callback, "Получаем полный индекс игровой основы…");
        let raw = self.pinned_bytes(
            &available.build_commit,
            &pin.path,
            &pin.sha256,
            pin.size,
            1024 * 1024,
        )?;
        let descriptor: Foundation = serde_json::from_slice(&raw)?;
        ensure!(
            descriptor.schema_version == 1
                && descriptor.id == pin.id
                && descriptor.file_count > 0
                && descriptor.file_count <= 1000000
                && descriptor.expanded_bytes <= 16 * 1024 * 1024 * 1024,
            "FOUNDATION_METADATA"
        );
        ensure!(
            !descriptor.indices.is_empty()
                && descriptor.indices.len() <= 2000
                && descriptor.objects.len() <= 2000,
            "FOUNDATION_LIMIT"
        );
        ensure!(
            fs2::available_space(&bundles)?
                > descriptor.expanded_bytes.saturating_add(
                    descriptor
                        .objects
                        .iter()
                        .try_fold(0u64, |sum, o| sum.checked_add(o.size))
                        .context("FOUNDATION_LIMIT")?
                ),
            "DISK_SPACE: недостаточно места для полной установки"
        );
        let old: BTreeMap<String, String> = read_json(&self.layout.at("state/bootstrap.json"))?;
        let candidate = old
            .get("path")
            .map(|p| self.layout.resolve(p))
            .filter(|p| p.is_dir());
        let object_root = data.join("cache/foundation-objects");
        fs::create_dir_all(&object_root)?;
        no_links(&object_root)?;
        let mut objects = HashMap::new();
        for object in descriptor.objects {
            ensure!(
                hash_valid(&object.sha256)
                    && object.size > 0
                    && object.size <= 32 * 1024 * 1024
                    && objects.insert(object.sha256.clone(), object).is_none(),
                "FOUNDATION_OBJECT"
            );
        }
        let journal = self.layout.at("state/foundation-prepared.json");
        let prepared: PreparedFoundation = read_json(&journal)?;
        let recovery = prepared.sha256 == pin.sha256
            && prepared
                .stage
                .strip_prefix(".incoming-")
                .is_some_and(|id| uuid::Uuid::parse_str(id).is_ok())
            && child(&bundles, &prepared.stage)?.is_dir();
        let stage = if recovery {
            child(&bundles, &prepared.stage)?
        } else {
            let p = child(
                &bundles,
                &format!(".incoming-{}", uuid::Uuid::new_v4().simple()),
            )?;
            fs::create_dir(&p)?;
            p
        };
        owner_only(&stage, true)?;
        write_json(
            &journal,
            &PreparedFoundation {
                stage: stage.file_name().unwrap().to_string_lossy().into_owned(),
                sha256: pin.sha256.clone(),
            },
        )?;
        let result = (|| {
            let downloader = UpdateService::new(&self.layout)?.with_cancel(self.cancel.clone());
            let mut count = 0usize;
            let mut expanded = 0u64;
            let mut checked = std::collections::HashSet::new();
            let mut handles: HashMap<String, File> = HashMap::new();
            let mut directories = std::collections::HashSet::new();
            for index in descriptor.indices {
                self.cancelled()?;
                ensure!(
                    matches!(
                        index.component.as_str(),
                        "bootstrap" | "client" | "server" | "map"
                    ) && index
                        .path
                        .starts_with(&format!("{}/index-", index.component))
                        && index.decoded_size <= 8 * 1024 * 1024,
                    "FOUNDATION_INDEX"
                );
                progress(
                    callback,
                    format!(
                        "Установка {}: {} / {} файлов",
                        index.component, count, descriptor.file_count
                    ),
                );
                let compressed = self.pinned_bytes(
                    &available.build_commit,
                    &index.path,
                    &index.sha256,
                    index.size,
                    2 * 1024 * 1024,
                )?;
                let files: Vec<BaseFile> =
                    serde_json::from_slice(&inflate(&compressed, index.decoded_size)?)?;
                ensure!(
                    files.len() == index.files && files.len() <= 2000,
                    "FOUNDATION_INDEX"
                );
                if candidate.is_none() {
                    let added = files
                        .iter()
                        .try_fold(0u64, |sum, f| sum.checked_add(f.size))
                        .context("FOUNDATION_LIMIT")?;
                    ensure!(
                        count + files.len() <= descriptor.file_count
                            && expanded
                                .checked_add(added)
                                .is_some_and(|n| n <= descriptor.expanded_bytes),
                        "FOUNDATION_LIMIT"
                    );
                    for f in &files {
                        ensure!(
                            f.path.starts_with(&format!("{}/", index.component))
                                && hash_valid(&f.sha256)
                                && f.size <= 4 * 1024 * 1024 * 1024
                                && f.pieces
                                    .iter()
                                    .try_fold(0u64, |sum, p| sum.checked_add(p.decoded_size))
                                    == Some(f.size),
                            "FOUNDATION_FILE"
                        );
                        let dest = target(&stage, &f.path)?;
                        let parent = dest.parent().unwrap();
                        if directories.insert(parent.to_owned()) {
                            no_links(parent)?;
                            fs::create_dir_all(parent)?;
                        }
                        for piece in &f.pieces {
                            let object = objects
                                .get(&piece.object)
                                .context("FOUNDATION_OBJECT_MISSING")?;
                            if checked.insert(object.sha256.clone()) {
                                let path = child(&object_root, &object.sha256)?;
                                downloader.download(
                                    &Package {
                                        id: "Игровая основа".into(),
                                        url: crate::channels::raw_url(
                                            &available.build_commit,
                                            &format!("bootstrap/objects/{}.pack", object.sha256),
                                        )?,
                                        sha256: object.sha256.clone(),
                                        size: object.size,
                                        ..Default::default()
                                    },
                                    &path,
                                    callback,
                                )?;
                            }
                        }
                    }
                    self.write_fresh_files(&files, &stage, &object_root, &objects, recovery)?;
                    count += files.len();
                    expanded += added;
                    continue;
                }
                for file in files {
                    self.cancelled()?;
                    ensure!(
                        file.path.starts_with(&format!("{}/", index.component))
                            && hash_valid(&file.sha256)
                            && file.size <= 4 * 1024 * 1024 * 1024,
                        "FOUNDATION_FILE"
                    );
                    ensure!(
                        file.pieces
                            .iter()
                            .try_fold(0u64, |sum, p| sum.checked_add(p.decoded_size))
                            == Some(file.size),
                        "FOUNDATION_PIECE_SIZE"
                    );
                    let dest = target(&stage, &file.path)?;
                    if recovery && dest.exists() {
                        no_links(&dest)?;
                        if fs::metadata(&dest)?.len() == file.size && sha(&dest)? == file.sha256 {
                            count += 1;
                            expanded = expanded
                                .checked_add(file.size)
                                .context("FOUNDATION_LIMIT")?;
                            ensure!(
                                count <= descriptor.file_count
                                    && expanded <= descriptor.expanded_bytes,
                                "FOUNDATION_LIMIT"
                            );
                            continue;
                        }
                        fs::remove_file(&dest)?;
                    }
                    ensure!(!dest.exists(), "FOUNDATION_DUPLICATE");
                    let parent = dest.parent().unwrap();
                    if directories.insert(parent.to_owned()) {
                        no_links(parent)?;
                        fs::create_dir_all(parent)?;
                    }
                    let reused = if let Some(base) = &candidate {
                        let previous = target(base, &file.path)?;
                        if previous.is_file()
                            && fs::metadata(&previous)?.len() == file.size
                            && sha(&previous)? == file.sha256
                        {
                            no_links(&previous)?;
                            if fs::hard_link(&previous, &dest).is_err() {
                                fs::copy(previous, &dest)?;
                            }
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    };
                    if !reused {
                        let mut out = OpenOptions::new()
                            .create_new(true)
                            .write(true)
                            .open(&dest)?;
                        let mut actual = Sha256::new();
                        for piece in file.pieces {
                            let object = objects
                                .get(&piece.object)
                                .context("FOUNDATION_OBJECT_MISSING")?;
                            ensure!(
                                piece.length <= 4 * 1024 * 1024
                                    && piece.decoded_size <= 4 * 1024 * 1024
                                    && piece
                                        .offset
                                        .checked_add(piece.length)
                                        .is_some_and(|end| end <= object.size),
                                "FOUNDATION_OFFSET"
                            );
                            let path = child(&object_root, &object.sha256)?;
                            if checked.insert(object.sha256.clone()) {
                                downloader.download(
                                    &Package {
                                        id: "Игровая основа".into(),
                                        url: crate::channels::raw_url(
                                            &available.build_commit,
                                            &format!("bootstrap/objects/{}.pack", object.sha256),
                                        )?,
                                        sha256: object.sha256.clone(),
                                        size: object.size,
                                        ..Default::default()
                                    },
                                    &path,
                                    callback,
                                )?;
                                handles.insert(object.sha256.clone(), File::open(&path)?);
                            }
                            let source = handles
                                .get_mut(&object.sha256)
                                .context("FOUNDATION_OBJECT_HANDLE")?;
                            source.seek(SeekFrom::Start(piece.offset))?;
                            let mut bytes = vec![0; piece.length as usize];
                            source.read_exact(&mut bytes)?;
                            let decoded = match piece.codec.as_str() {
                                "raw" => {
                                    ensure!(piece.length == piece.decoded_size, "FOUNDATION_CODEC");
                                    bytes
                                }
                                "zlib" => inflate(&bytes, piece.decoded_size)?,
                                _ => bail!("FOUNDATION_CODEC"),
                            };
                            actual.update(&decoded);
                            out.write_all(&decoded)?;
                        }
                        ensure!(
                            hex::encode(actual.finalize()) == file.sha256,
                            "FOUNDATION_FILE_HASH: {}",
                            file.path
                        );
                        out.flush()?;
                    }
                    count += 1;
                    expanded = expanded
                        .checked_add(file.size)
                        .context("FOUNDATION_LIMIT")?;
                    ensure!(
                        count <= descriptor.file_count && expanded <= descriptor.expanded_bytes,
                        "FOUNDATION_LIMIT"
                    );
                }
            }
            ensure!(
                count == descriptor.file_count && expanded == descriptor.expanded_bytes,
                "FOUNDATION_INCOMPLETE"
            );
            Setup::check_components(&stage.join("Client"), &stage.join("Server"))?;
            ensure!(
                stage.join("Map/registry.json").is_file()
                    && stage.join("Map/region").is_dir()
                    && stage.join("Bootstrap/java17/bin/java.exe").is_file()
                    && stage.join("Bootstrap/bscp-bridge.jar").is_file(),
                "FOUNDATION_COMPONENTS"
            );
            self.cancelled()?;
            atomic_write(&stage.join(".foundation-complete"), pin.sha256.as_bytes())?;
            fs::rename(&stage, &destination)?;
            write_json(
                &self.layout.at("state/bootstrap.json"),
                &BTreeMap::from([("path", self.layout.store(&destination))]),
            )?;
            Ok(destination)
        })();
        // Keep a verified, owned staging area on interrupted downloads. A retry
        // checks existing file hashes and resumes instead of writing everything again.
        if result.is_ok() && journal.is_file() {
            fs::remove_file(journal)?;
        }
        result
    }
    fn write_fresh_files(
        &self,
        files: &[BaseFile],
        stage: &Path,
        cache: &Path,
        objects: &HashMap<String, ObjectPin>,
        resume: bool,
    ) -> Result<()> {
        let workers = 8usize.min(files.len().max(1));
        let batch = files.len().div_ceil(workers).max(1);
        std::thread::scope(|scope| -> Result<()> {
            let mut jobs = vec![];
            for group in files.chunks(batch) {
                jobs.push(scope.spawn(move || -> Result<()> {
                    let mut handles: HashMap<String, File> = HashMap::new();
                    for file in group {
                        self.cancelled()?;
                        let dest = target(stage, &file.path)?;
                        if resume && dest.exists() {
                            let metadata = fs::symlink_metadata(&dest)?;
                            ensure!(
                                metadata.file_type().is_file()
                                    && !metadata.file_type().is_symlink(),
                                "FOUNDATION_LINK"
                            );
                            if metadata.len() == file.size && sha(&dest)? == file.sha256 {
                                continue;
                            }
                            no_links(&dest)?;
                            fs::remove_file(&dest)?;
                        }
                        let mut output = OpenOptions::new()
                            .create_new(true)
                            .write(true)
                            .open(&dest)?;
                        let mut actual = Sha256::new();
                        for piece in &file.pieces {
                            self.cancelled()?;
                            let object = objects
                                .get(&piece.object)
                                .context("FOUNDATION_OBJECT_MISSING")?;
                            ensure!(
                                piece.length <= 4 * 1024 * 1024
                                    && piece.decoded_size <= 4 * 1024 * 1024
                                    && piece
                                        .offset
                                        .checked_add(piece.length)
                                        .is_some_and(|end| end <= object.size),
                                "FOUNDATION_OFFSET"
                            );
                            if !handles.contains_key(&piece.object) {
                                handles.insert(
                                    piece.object.clone(),
                                    File::open(cache.join(&piece.object))?,
                                );
                            }
                            let source = handles.get_mut(&piece.object).unwrap();
                            source.seek(SeekFrom::Start(piece.offset))?;
                            let mut bytes = vec![0; piece.length as usize];
                            source.read_exact(&mut bytes)?;
                            let decoded = match piece.codec.as_str() {
                                "raw" => {
                                    ensure!(piece.length == piece.decoded_size, "FOUNDATION_CODEC");
                                    bytes
                                }
                                "zlib" => inflate(&bytes, piece.decoded_size)?,
                                _ => bail!("FOUNDATION_CODEC"),
                            };
                            actual.update(&decoded);
                            output.write_all(&decoded)?;
                        }
                        ensure!(
                            hex::encode(actual.finalize()) == file.sha256,
                            "FOUNDATION_FILE_HASH: {}",
                            file.path
                        );
                    }
                    Ok(())
                }));
            }
            for job in jobs {
                job.join()
                    .map_err(|_| anyhow::anyhow!("FOUNDATION_WORKER"))??;
            }
            Ok(())
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parallel_install_preserves_bytes_and_rejects_duplicate_paths() {
        let t = tempfile::tempdir().unwrap();
        let cache = t.path().join("cache");
        fs::create_dir(&cache).unwrap();
        let stage = t.path().join("stage");
        fs::create_dir_all(stage.join("Client")).unwrap();
        let bytes = b"identical class bytes";
        let object = hash(bytes);
        fs::write(cache.join(&object), bytes).unwrap();
        let mut objects = HashMap::new();
        objects.insert(
            object.clone(),
            ObjectPin {
                sha256: object.clone(),
                size: bytes.len() as u64,
            },
        );
        let files = (0..32)
            .map(|n| BaseFile {
                path: format!("client/{n}.class"),
                size: bytes.len() as u64,
                sha256: object.clone(),
                pieces: vec![Piece {
                    object: object.clone(),
                    offset: 0,
                    length: bytes.len() as u64,
                    decoded_size: bytes.len() as u64,
                    codec: "raw".into(),
                }],
            })
            .collect::<Vec<_>>();
        let service = RepositoryChannel::new(&Layout::new(t.path())).unwrap();
        service
            .write_fresh_files(&files, &stage, &cache, &objects, false)
            .unwrap();
        for f in &files {
            assert_eq!(fs::read(target(&stage, &f.path).unwrap()).unwrap(), bytes);
        }
        assert!(
            service
                .write_fresh_files(&files, &stage, &cache, &objects, false)
                .is_err()
        );
    }
    #[test]
    fn inflation_is_bounded_and_exact() {
        use flate2::{Compression, write::ZlibEncoder};
        let mut z = ZlibEncoder::new(vec![], Compression::default());
        z.write_all(b"payload").unwrap();
        let bytes = z.finish().unwrap();
        assert_eq!(inflate(&bytes, 7).unwrap(), b"payload");
        assert!(inflate(&bytes, 6).is_err());
        assert!(inflate(&bytes, 8).is_err());
    }
    #[test]
    fn foundation_paths_stay_in_components() {
        let root = Path::new("owned");
        assert_eq!(
            target(root, "client/runtime/x.class").unwrap(),
            root.join("Client/runtime/x.class")
        );
        for path in [
            "../config/identity",
            "config/key.pem",
            "client/../secret",
            "server/C:file",
        ] {
            assert!(target(root, path).is_err());
        }
    }
}
