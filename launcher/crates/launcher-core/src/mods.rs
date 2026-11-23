//! Signed repository catalogue and the native client's existing mod loader.
use crate::*;
use anyhow::{Context, bail, ensure};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashSet},
    fs,
    io::Read,
    path::{Path, PathBuf},
    sync::{Arc, atomic::AtomicBool},
};

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RepositoryMod {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub version: String,
    pub side: String,
    #[serde(default)]
    pub required: bool,
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub files: Vec<ChannelFile>,
}
impl RepositoryMod {
    pub fn payload<'a>(&'a self, m: &'a ChannelManifest) -> Result<&'a ChannelFile> {
        ensure!(
            mod_id(&self.id) && self.side == "client",
            "MOD_CATALOG: неподдерживаемый мод"
        );
        let f = if self.files.len() == 1 {
            &self.files[0]
        } else {
            ensure!(self.files.is_empty(), "MOD_CATALOG: требуется один JAR");
            m.files
                .iter()
                .find(|f| f.path == self.path)
                .context("MOD_CATALOG: отсутствует файл")?
        };
        validate_relative(&f.path)?;
        validate_relative(&f.target)?;
        ensure!(
            f.path.starts_with(&format!("mods/{}/", self.id))
                && f.path.ends_with(".jar")
                && f.target == format!("client/mods/{}.jar", self.id)
                && f.size > 0
                && f.size <= 99 * 1024 * 1024
                && f.sha256.len() == 64
                && f.sha256.bytes().all(|b| b.is_ascii_hexdigit()),
            "MOD_CATALOG: неверный файл"
        );
        Ok(f)
    }
}
pub fn validate_mod_catalog(m: &ChannelManifest) -> Result<Vec<RepositoryMod>> {
    ensure!(m.mods.len() <= 256, "MOD_CATALOG: слишком много модов");
    let mut ids = HashSet::new();
    m.mods
        .iter()
        .map(|v| {
            let item: RepositoryMod = serde_json::from_value(v.clone())?;
            ensure!(
                ids.insert(item.id.clone())
                    && !item.name.trim().is_empty()
                    && item.name.len() <= 256
                    && item.description.len() <= 16384,
                "MOD_CATALOG: неверное описание"
            );
            item.payload(m)?;
            Ok(item)
        })
        .collect()
}
fn mod_id(v: &str) -> bool {
    !v.is_empty()
        && v.len() <= 64
        && v.as_bytes()[0].is_ascii_lowercase()
        && v.bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"_-".contains(&b))
}
#[derive(Clone, Debug, Default)]
pub struct ModView {
    pub id: String,
    pub title: String,
    pub description: String,
    pub version: String,
    pub side: String,
    pub source: String,
    pub required: bool,
    pub installed: bool,
    pub enabled: bool,
    pub update_available: bool,
}
#[derive(Default)]
pub struct ModSnapshot {
    pub catalog: Vec<ModView>,
    pub installed: Vec<ModView>,
}
#[derive(Default, Serialize, Deserialize)]
struct Managed {
    id: String,
    hash: String,
    version: String,
}
#[derive(Default, Debug)]
struct Descriptor {
    id: String,
    name: String,
    version: String,
}
fn descriptor(path: &Path) -> Result<Descriptor> {
    no_links(path)?;
    ensure!(
        fs::metadata(path)?.len() <= 99 * 1024 * 1024,
        "MOD_SIZE: JAR слишком большой"
    );
    let mut z = zip::ZipArchive::new(fs::File::open(path)?)?;
    let mut raw = String::new();
    {
        let e = z
            .by_name("stalcraft-mod.properties")
            .context("MOD_FORMAT: нет stalcraft-mod.properties")?;
        ensure!(e.size() <= 65536, "MOD_FORMAT: описание слишком большое");
        e.take(65537).read_to_string(&mut raw)?;
    }
    let p = properties(&raw);
    let id = p.get("id").cloned().unwrap_or_default();
    let version = p.get("version").cloned().unwrap_or_default();
    let main = p.get("main").context("MOD_FORMAT: нет main")?;
    ensure!(
        mod_id(&id)
            && p.get("api").is_some_and(|s| s == "1")
            && !version.is_empty()
            && version.len() <= 128
            && main.len() <= 256,
        "MOD_FORMAT: неверный id, api или version"
    );
    ensure!(
        z.by_name(&format!("{}.class", main.replace('.', "/")))
            .is_ok(),
        "MOD_FORMAT: отсутствует класс main"
    );
    Ok(Descriptor {
        id: id.clone(),
        name: p.get("name").cloned().unwrap_or(id),
        version,
    })
}
fn properties(raw: &str) -> BTreeMap<String, String> {
    raw.lines()
        .filter_map(|line| {
            let line = line.trim();
            if line.starts_with('#') || line.starts_with('!') {
                return None;
            }
            let (k, v) = line.split_once('=').or_else(|| line.split_once(':'))?;
            Some((k.trim().into(), v.trim().into()))
        })
        .collect()
}
fn enabled(dir: &Path) -> Result<BTreeMap<String, String>> {
    let p = dir.join("enabled.properties");
    no_links(&p)?;
    if !p.exists() {
        return Ok(BTreeMap::new());
    }
    ensure!(fs::metadata(&p)?.len() <= 1024 * 1024, "MOD_CONFIG_LIMIT");
    Ok(properties(&fs::read_to_string(p)?))
}
fn set_enabled(dir: &Path, id: &str, on: bool) -> Result<()> {
    ensure!(mod_id(id), "MOD_ID");
    let mut flags = enabled(dir)?;
    flags.insert(id.into(), on.to_string());
    let raw = flags
        .into_iter()
        .map(|(k, v)| format!("{k}={v}\n"))
        .collect::<String>();
    atomic_write(&dir.join("enabled.properties"), raw.as_bytes())
}
fn bundled_ids(folder: &Path) -> Result<HashSet<String>> {
    let manifest = folder
        .parent()
        .context("MOD_PATH")?
        .join("bundled-mods.json");
    let previous: BTreeMap<String, String> = read_json(&manifest)?;
    let mut ids = HashSet::new();
    for name in previous.keys() {
        let p = child(folder, name)?;
        if p.is_file() {
            if let Ok(d) = descriptor(&p) {
                ids.insert(d.id);
            }
        }
    }
    Ok(ids)
}
fn local_key(name: &str) -> String {
    format!("local:{}", hex::encode(name.as_bytes()))
}

pub struct ModManager {
    layout: Layout,
}
impl ModManager {
    pub fn new(layout: &Layout) -> Self {
        Self {
            layout: layout.clone(),
        }
    }
    pub fn folder(&self) -> Result<PathBuf> {
        let s = self.layout.load()?;
        Ok(self.layout.client_data(&s).join("profiles/default/mods"))
    }
    fn managed_state(&self) -> Result<PathBuf> {
        let s = self.layout.load()?;
        Ok(self.layout.client_data(&s).join("state/mods-managed.json"))
    }
    fn catalogue(&self) -> Result<Option<ChannelAvailable>> {
        let s = self.layout.load()?;
        if s.game_branch.is_empty() {
            return Ok(None);
        }
        RepositoryChannel::new(&self.layout)?.cached(&s.game_branch)
    }
    pub fn snapshot(&self) -> Result<ModSnapshot> {
        let available = self.catalogue()?;
        let catalog = available
            .as_ref()
            .map(|a| validate_mod_catalog(&a.manifest))
            .transpose()?
            .unwrap_or_default();
        let folder = self.folder()?;
        no_links(&folder)?;
        let flags = enabled(&folder)?;
        let bundled = bundled_ids(&folder)?;
        let mut local = Vec::new();
        let mut counts = BTreeMap::<String, usize>::new();
        if folder.is_dir() {
            for e in fs::read_dir(&folder)? {
                ensure!(local.len() < 1024, "MOD_LIMIT: слишком много модов");
                let p = e?.path();
                if p.extension().is_none_or(|x| x != "jar") {
                    continue;
                }
                no_links(&p)?;
                let d = descriptor(&p);
                if let Ok(d) = &d {
                    *counts.entry(d.id.clone()).or_default() += 1;
                }
                local.push((p, d));
            }
        }
        let mut result = ModSnapshot::default();
        for item in &catalog {
            let found = local
                .iter()
                .find(|(_, d)| d.as_ref().is_ok_and(|d| d.id == item.id));
            let payload = item.payload(&available.as_ref().unwrap().manifest)?;
            let (installed, on, update) = if let Some((p, d)) = found {
                (
                    true,
                    item.required
                        || flags
                            .get(&item.id)
                            .is_none_or(|s| s.eq_ignore_ascii_case("true")),
                    sha(p)? != payload.sha256
                        || d.as_ref().unwrap().version != item.version && !item.version.is_empty(),
                )
            } else {
                (false, false, false)
            };
            result.catalog.push(ModView {
                id: format!("repo:{}", item.id),
                title: item.name.clone(),
                description: item.description.clone(),
                version: if item.version.is_empty() {
                    found
                        .and_then(|(_, d)| d.as_ref().ok())
                        .map(|d| d.version.clone())
                        .unwrap_or_default()
                } else {
                    item.version.clone()
                },
                side: "Клиент".into(),
                source: format!("GitHub · {}", available.as_ref().unwrap().manifest.channel),
                required: item.required || bundled.contains(&item.id),
                installed,
                enabled: on,
                update_available: update,
            });
        }
        for (p, d) in local {
            let name = p.file_name().unwrap().to_string_lossy().into_owned();
            match d {
                Ok(d) => {
                    let required = bundled.contains(&d.id)
                        || catalog.iter().any(|x| x.id == d.id && x.required);
                    let mut view = result
                        .catalog
                        .iter()
                        .find(|x| x.id == format!("repo:{}", d.id) && !x.update_available)
                        .cloned()
                        .unwrap_or(ModView {
                            id: local_key(&name),
                            title: d.name,
                            description: "Добавлен вручную. Управляется загрузчиком модов игры."
                                .into(),
                            version: d.version,
                            side: "Клиент".into(),
                            source: "Локальный JAR".into(),
                            required,
                            installed: true,
                            enabled: flags
                                .get(&d.id)
                                .is_none_or(|v| v.eq_ignore_ascii_case("true")),
                            ..Default::default()
                        });
                    if counts.get(&d.id).copied().unwrap_or(0) > 1 {
                        view.id = local_key(&name);
                        view.description="Конфликт: несколько JAR с одинаковым id. Игра пропустит их. Уберите лишнюю копию.".into();
                        view.source = "Конфликт ID".into();
                    } else if let Some(repo) = result
                        .catalog
                        .iter()
                        .find(|x| x.id == format!("repo:{}", d.id))
                    {
                        view.id = repo.id.clone();
                        view.title = repo.title.clone();
                        view.required = required || repo.required;
                        view.update_available = repo.update_available;
                        view.description = repo.description.clone();
                        view.source = repo.source.clone();
                    }
                    view.description = format!("{}\n\nФайл: {name}", view.description);
                    result.installed.push(view);
                }
                Err(error) => result.installed.push(ModView {
                    id: local_key(&name),
                    title: name,
                    description: format!("Этот JAR не будет загружен: {error}"),
                    version: "Некорректный JAR".into(),
                    source: "Локальный JAR".into(),
                    installed: true,
                    ..Default::default()
                }),
            }
        }
        Ok(result)
    }
    fn lock(&self) -> Result<(Lease, Lease)> {
        let s = self.layout.load()?;
        let data = self.layout.client_data(&s);
        let first = ClientProfile::lease(&data)
            .context("MOD_BUSY: закройте игру перед изменением модов")?;
        let second = Lease::acquire(&data.join("profiles/default/.client-lease"))
            .context("MOD_BUSY: закройте игру перед изменением модов")?;
        Ok((first, second))
    }
    pub fn apply(
        &self,
        action: &str,
        key: &str,
        cancel: Arc<AtomicBool>,
        progress: Option<&Progress>,
    ) -> Result<()> {
        let _locks = self.lock()?;
        let folder = self.folder()?;
        no_links(&folder)?;
        fs::create_dir_all(&folder)?;
        let available = self.catalogue()?;
        let catalog = available
            .as_ref()
            .map(|a| validate_mod_catalog(&a.manifest))
            .transpose()?
            .unwrap_or_default();
        let item = key
            .strip_prefix("repo:")
            .and_then(|id| catalog.iter().find(|x| x.id == id));
        let files = self.local_files()?;
        let matching: Vec<_> = files
            .iter()
            .filter(|p| item.is_some_and(|i| descriptor(p).is_ok_and(|d| d.id == i.id)))
            .collect();
        let target = if let Some(item) = item {
            ensure!(
                matching.len() <= 1,
                "MOD_DUPLICATE: сначала уберите лишние копии JAR"
            );
            matching
                .first()
                .map(|p| (*p).clone())
                .unwrap_or(folder.join(format!("{}.jar", item.id)))
        } else {
            let name = key.strip_prefix("local:").context("MOD_UNKNOWN")?;
            let name = String::from_utf8(hex::decode(name)?)?;
            ensure!(
                !name.contains(['/', '\\']) && !name.starts_with('.'),
                "MOD_PATH"
            );
            let p = child(&folder, &name)?;
            ensure!(files.contains(&p), "MOD_MISSING");
            p
        };
        if matches!(action, "remove" | "disable") {
            ensure!(
                !item.is_some_and(|i| i.required),
                "MOD_REQUIRED: обязательный мод нельзя отключить или удалить"
            );
            if let Ok(d) = descriptor(&target) {
                ensure!(
                    !catalog.iter().any(|i| i.id == d.id && i.required)
                        && !bundled_ids(&folder)?.contains(&d.id),
                    "MOD_REQUIRED"
                );
            }
        }
        match action {
            "install" | "update" => {
                let item = item.context("MOD_UNKNOWN")?;
                let a = available.as_ref().unwrap();
                let f = item.payload(&a.manifest)?;
                if item.required {
                    let installed = self.layout.bundle(&self.layout.load()?, "client")?;
                    let bundled = component_file(&installed, &format!("mods/{}.jar", item.id))?;
                    ensure!(
                        bundled.is_file() && sha(&bundled)? == f.sha256,
                        "MOD_GAME_UPDATE: сначала обновите игру в настройках"
                    );
                }
                let cache = self
                    .layout
                    .data(&self.layout.load()?)
                    .join("cache/channel-files")
                    .join(&f.sha256);
                no_links(&cache)?;
                let p = Package {
                    id: item.id.clone(),
                    url: crate::channels::raw_url(&a.build_commit, &f.path)?,
                    size: f.size,
                    sha256: f.sha256.clone(),
                    ..Default::default()
                };
                let tracker = TransferTracker::new(progress);
                let pending = UpdateService::available_download(&p, &cache)?;
                tracker.item(&item.id, f.size, pending);
                tracker.planned();
                tracker.stage("download", "Скачиваем мод", 0);
                UpdateService::new(&self.layout)?
                    .with_cancel(cancel)
                    .download_observed(&p, &cache, None, Some(&|e| tracker.observe(&item.id, e)))?;
                let d = descriptor(&cache)?;
                ensure!(
                    d.id == item.id && (item.version.is_empty() || d.version == item.version),
                    "MOD_ID: описание JAR не совпало с каталогом"
                );
                let keep_enabled = item.required
                    || !target.exists()
                    || enabled(&folder)?
                        .get(&item.id)
                        .is_none_or(|v| v.eq_ignore_ascii_case("true"));
                if target.exists() {
                    let old = sha(&target)?;
                    let state: BTreeMap<String, Managed> = read_json(&self.managed_state()?)?;
                    let owned = state
                        .get(target.file_name().unwrap().to_string_lossy().as_ref())
                        .is_some_and(|m| m.hash == old && m.id == item.id);
                    let bundled: BTreeMap<String, String> =
                        read_json(&folder.parent().unwrap().join("bundled-mods.json"))?;
                    ensure!(
                        old == f.sha256
                            || owned
                            || bundled
                                .get(target.file_name().unwrap().to_string_lossy().as_ref())
                                .is_some_and(|hash| hash == &old),
                        "MOD_LOCAL_CONFLICT: локальный мод изменён вручную. Сначала уберите его через менеджер."
                    );
                    if old != f.sha256 {
                        self.archive(&target)?;
                    }
                }
                atomic_write(&target, &fs::read(&cache)?)?;
                set_enabled(&folder, &item.id, keep_enabled)?;
                let state_path = self.managed_state()?;
                let mut state: BTreeMap<String, Managed> = read_json(&state_path)?;
                state.insert(
                    target.file_name().unwrap().to_string_lossy().into_owned(),
                    Managed {
                        id: item.id.clone(),
                        hash: f.sha256.clone(),
                        version: d.version,
                    },
                );
                write_json(&state_path, &state)?;
                tracker.complete();
            }
            "enable" | "disable" => {
                let d = descriptor(&target)?;
                ensure!(
                    files
                        .iter()
                        .filter(|p| descriptor(p).is_ok_and(|x| x.id == d.id))
                        .count()
                        == 1,
                    "MOD_DUPLICATE: одинаковые ID"
                );
                set_enabled(&folder, &d.id, action == "enable")?;
            }
            "remove" => {
                self.archive(&target)?;
            }
            _ => bail!("MOD_ACTION: неизвестная операция"),
        }
        Ok(())
    }
    fn local_files(&self) -> Result<Vec<PathBuf>> {
        let folder = self.folder()?;
        if !folder.exists() {
            return Ok(vec![]);
        }
        no_links(&folder)?;
        let mut files = vec![];
        for p in fs::read_dir(folder)? {
            let p = p?.path();
            if p.extension().is_some_and(|x| x == "jar") {
                no_links(&p)?;
                files.push(p);
                ensure!(files.len() <= 1024, "MOD_LIMIT");
            }
        }
        Ok(files)
    }
    fn archive(&self, path: &Path) -> Result<()> {
        no_links(path)?;
        let archive = self
            .folder()?
            .join("archive")
            .join(uuid::Uuid::new_v4().to_string());
        no_links(&archive)?;
        fs::create_dir_all(&archive)?;
        fs::rename(path, archive.join(path.file_name().context("MOD_PATH")?))?;
        Ok(())
    }
    pub fn import(&self, source: &Path) -> Result<()> {
        let _locks = self.lock()?;
        let d = descriptor(source)?;
        ensure!(
            !self
                .local_files()?
                .iter()
                .any(|p| descriptor(p).is_ok_and(|x| x.id == d.id)),
            "MOD_DUPLICATE: мод с таким ID уже установлен"
        );
        let folder = self.folder()?;
        no_links(&folder)?;
        fs::create_dir_all(&folder)?;
        let target = folder.join(format!("{}.jar", d.id));
        ensure!(!target.exists(), "MOD_EXISTS");
        atomic_write(&target, &fs::read(source)?)?;
        set_enabled(&folder, &d.id, true)
    }
}
/// Required bundled mods must remain enabled even after a manually edited loader config.
pub fn enforce_bundled_mods(profile: &Path, names: impl Iterator<Item = String>) -> Result<()> {
    for name in names {
        let path = child(&profile.join("mods"), &name)?;
        let Ok(d) = descriptor(&path) else { continue };
        set_enabled(&profile.join("mods"), &d.id, true)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    fn jar(path: &Path, id: &str, version: &str) {
        let mut z = zip::ZipWriter::new(fs::File::create(path).unwrap());
        let opt = zip::write::SimpleFileOptions::default();
        z.start_file("stalcraft-mod.properties", opt).unwrap();
        write!(
            z,
            "id={id}\nname=Test mod\nversion={version}\napi=1\nmain=test.Mod\n"
        )
        .unwrap();
        z.start_file("test/Mod.class", opt).unwrap();
        z.write_all(b"fixture metadata only").unwrap();
        z.finish().unwrap();
    }
    fn fixture() -> (tempfile::TempDir, Layout, ModManager) {
        let t = tempfile::tempdir().unwrap();
        let l = Layout::new(t.path());
        l.init().unwrap();
        let m = ModManager::new(&l);
        (t, l, m)
    }
    #[test]
    fn import_toggle_archive_and_reimport_preserve_jar_and_loader_flags() {
        let (t, _, m) = fixture();
        let src = t.path().join("example.jar");
        jar(&src, "example", "1.0");
        m.import(&src).unwrap();
        let first = m.snapshot().unwrap().installed.pop().unwrap();
        assert!(first.enabled);
        let cancel = Arc::new(AtomicBool::new(false));
        m.apply("disable", &first.id, cancel.clone(), None).unwrap();
        assert!(!m.snapshot().unwrap().installed[0].enabled);
        m.apply("enable", &first.id, cancel.clone(), None).unwrap();
        assert!(m.snapshot().unwrap().installed[0].enabled);
        let hash = sha(&src).unwrap();
        m.apply("remove", &first.id, cancel, None).unwrap();
        assert!(m.snapshot().unwrap().installed.is_empty());
        let archive = fs::read_dir(m.folder().unwrap().join("archive"))
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .path()
            .join("example.jar");
        assert_eq!(sha(&archive).unwrap(), hash);
        m.import(&src).unwrap();
        assert!(m.snapshot().unwrap().installed[0].enabled);
    }
    #[test]
    fn running_client_lease_blocks_mod_writes() {
        let (t, l, m) = fixture();
        let src = t.path().join("example.jar");
        jar(&src, "example", "1");
        let data = l.client_data(&l.load().unwrap());
        let _lease = Lease::acquire(&data.join("profiles/default/.client-lease")).unwrap();
        assert!(m.import(&src).is_err());
        assert!(m.snapshot().unwrap().installed.is_empty());
    }
    #[test]
    fn duplicate_ids_are_reported_and_can_be_archived_without_toggling_both() {
        let (t, _, m) = fixture();
        let src = t.path().join("example.jar");
        jar(&src, "example", "1");
        m.import(&src).unwrap();
        assert!(m.import(&src).is_err());
        let folder = m.folder().unwrap();
        fs::copy(folder.join("example.jar"), folder.join("duplicate.jar")).unwrap();
        let snap = m.snapshot().unwrap();
        assert_eq!(snap.installed.len(), 2);
        assert!(snap.installed.iter().all(|r| r.source == "Конфликт ID"));
        let key = &snap.installed[0].id;
        assert!(
            m.apply("disable", key, Arc::new(AtomicBool::new(false)), None)
                .is_err()
        );
        m.apply("remove", key, Arc::new(AtomicBool::new(false)), None)
            .unwrap();
        assert_eq!(m.snapshot().unwrap().installed.len(), 1);
    }
    #[test]
    fn invalid_jars_and_missing_entrypoint_are_rejected_and_visible() {
        let (t, _, m) = fixture();
        let src = t.path().join("bad.jar");
        fs::write(&src, b"not a jar").unwrap();
        assert!(m.import(&src).is_err());
        let folder = m.folder().unwrap();
        fs::create_dir_all(&folder).unwrap();
        fs::copy(&src, folder.join("bad.jar")).unwrap();
        assert_eq!(
            m.snapshot().unwrap().installed[0].version,
            "Некорректный JAR"
        );
        let key = local_key("../escape.jar");
        assert!(
            m.apply("remove", &key, Arc::new(AtomicBool::new(false)), None)
                .is_err()
        );
    }
    #[test]
    fn bundled_required_mod_is_reenabled_without_changing_optional_choice() {
        let (t, _, m) = fixture();
        let src = t.path().join("required.jar");
        jar(&src, "required", "1");
        m.import(&src).unwrap();
        let folder = m.folder().unwrap();
        set_enabled(&folder, "required", false).unwrap();
        set_enabled(&folder, "optional", false).unwrap();
        enforce_bundled_mods(
            folder.parent().unwrap(),
            vec!["required.jar".into()].into_iter(),
        )
        .unwrap();
        let flags = enabled(&folder).unwrap();
        assert_eq!(flags["required"], "true");
        assert_eq!(flags["optional"], "false");
    }
    #[test]
    fn branch_switch_keeps_mod_profiles_separate() {
        let (_t, l, m) = fixture();
        let mut s = l.load().unwrap();
        s.game_branch.clear();
        l.save(&s).unwrap();
        let direct = m.folder().unwrap();
        s.game_branch = "launtest".into();
        l.save(&s).unwrap();
        let first = m.folder().unwrap();
        s.game_branch = "experimental".into();
        l.save(&s).unwrap();
        assert_ne!(first, m.folder().unwrap());
        assert_ne!(direct, first);
    }
    #[test]
    fn required_bundled_mod_cannot_be_disabled_or_removed_without_online_catalogue() {
        let (t, _, m) = fixture();
        let src = t.path().join("required.jar");
        jar(&src, "required", "1");
        m.import(&src).unwrap();
        let folder = m.folder().unwrap();
        write_json(
            &folder.parent().unwrap().join("bundled-mods.json"),
            &BTreeMap::from([("required.jar".to_string(), sha(&src).unwrap())]),
        )
        .unwrap();
        let row = m.snapshot().unwrap().installed.pop().unwrap();
        assert!(row.required);
        for action in ["disable", "remove"] {
            assert!(
                m.apply(action, &row.id, Arc::new(AtomicBool::new(false)), None)
                    .is_err()
            );
        }
        assert!(folder.join("required.jar").exists());
    }
    #[test]
    fn signed_catalogue_rejects_bad_targets_duplicates_and_unsafe_paths() {
        let f = ChannelFile {
            path: "mods/example/example.jar".into(),
            target: "client/mods/example.jar".into(),
            size: 20,
            sha256: "a".repeat(64),
        };
        let item = RepositoryMod {
            id: "example".into(),
            name: "Example".into(),
            side: "client".into(),
            files: vec![f],
            ..Default::default()
        };
        let mut m = ChannelManifest::default();
        m.mods = vec![serde_json::to_value(&item).unwrap()];
        assert!(validate_mod_catalog(&m).is_ok());
        m.mods.push(m.mods[0].clone());
        assert!(validate_mod_catalog(&m).is_err());
        m.mods.truncate(1);
        m.mods[0]["files"][0]["target"] = "server/server.jar".into();
        assert!(validate_mod_catalog(&m).is_err());
        m.mods[0]["files"][0]["path"] = "mods/example/../example.jar".into();
        assert!(validate_mod_catalog(&m).is_err());
    }
}
