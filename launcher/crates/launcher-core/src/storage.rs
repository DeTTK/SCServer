use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct Settings {
    #[serde(alias = "schema_version", alias = "schemaVersion")]
    pub schema_version: i32,
    #[serde(alias = "data_path", alias = "dataPath")]
    pub data_path: String,
    #[serde(alias = "game_path", alias = "gamePath")]
    pub game_path: String,
    #[serde(alias = "client_path", alias = "clientPath")]
    pub client_path: String,
    #[serde(alias = "server_path", alias = "serverPath")]
    pub server_path: String,
    #[serde(alias = "nickname")]
    pub nickname: String,
    #[serde(alias = "selected_world", alias = "selectedWorld")]
    pub selected_world: String,
    #[serde(alias = "base_port", alias = "basePort")]
    pub base_port: u16,
    pub relay_enabled: bool,
    pub relay_url: String,
    #[serde(alias = "client_memory_mb", alias = "clientMemoryMb")]
    pub client_memory_mb: u32,
    #[serde(alias = "server_memory_mb", alias = "serverMemoryMb")]
    pub server_memory_mb: u32,
    #[serde(alias = "diagnostics")]
    pub diagnostics: bool,
    #[serde(alias = "feed_url", alias = "feedUrl")]
    pub feed_url: String,
    #[serde(alias = "gameBranch", alias = "game_branch")]
    pub game_branch: String,
    #[serde(alias = "automatic_backups", alias = "automaticBackups")]
    pub automatic_backups: usize,
    #[serde(alias = "setup_completed", alias = "setupCompleted")]
    pub setup_completed: bool,
}
#[cfg(test)]
mod route_settings_tests {
    use super::*;
    #[test]
    fn obsolete_public_host_setting_is_ignored_and_not_saved() {
        let settings: Settings = serde_json::from_value(
            serde_json::json!({"AdvertisedHost":"obsolete.invalid","nickname":"Player"}),
        )
        .unwrap();
        settings.validate().unwrap();
        let saved = serde_json::to_value(settings).unwrap();
        assert!(saved.get("AdvertisedHost").is_none());
        assert!(saved.get("advertised_host").is_none());
    }
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            schema_version: 1,
            data_path: "GameData".into(),
            game_path: String::new(),
            client_path: String::new(),
            server_path: String::new(),
            nickname: "Player".into(),
            selected_world: String::new(),
            base_port: 29545,
            relay_enabled: false,
            relay_url: String::new(),
            client_memory_mb: 4096,
            server_memory_mb: 2048,
            diagnostics: true,
            feed_url: String::new(),
            game_branch: "main".into(),
            automatic_backups: 5,
            setup_completed: false,
        }
    }
}
impl Settings {
    pub fn validate(&self) -> Result<()> {
        if self.schema_version != 1 {
            bail!("CONFIG_VERSION: unsupported settings schema")
        }
        if !self.game_branch.is_empty() {
            crate::channels::validate_branch(&self.game_branch)?;
        }
        if self.data_path.trim().is_empty() || self.data_path.chars().any(char::is_control) {
            bail!("DATA_PATH: укажите отдельную папку для данных игры")
        }
        if self.nickname.trim().is_empty()
            || self.nickname.encode_utf16().count() > 64
            || self.nickname.chars().any(char::is_control)
        {
            bail!("LOGIN_INVALID: nickname must contain 1..64 characters")
        }
        if !(1024..=65000).contains(&self.base_port) {
            bail!("PORT_INVALID: port outside 1024..65000")
        }
        if [self.client_memory_mb, self.server_memory_mb]
            .iter()
            .any(|n| !(512..=65536).contains(n))
        {
            bail!("MEMORY_INVALID: memory outside 512..65536 MB")
        }
        if !(1..=100).contains(&self.automatic_backups) {
            bail!("BACKUP_LIMIT: retain 1..100 backups")
        }
        if !self.feed_url.is_empty() && reqwest::Url::parse(&self.feed_url)?.scheme() != "https" {
            bail!("FEED_INVALID: HTTPS required")
        }
        Ok(())
    }
}
#[derive(Debug, Clone)]
pub struct Layout {
    pub root: PathBuf,
}
impl Layout {
    pub fn new(root: impl AsRef<Path>) -> Self {
        Self {
            root: std::path::absolute(root).expect("absolute launcher root"),
        }
    }
    pub fn at(&self, path: impl AsRef<Path>) -> PathBuf {
        self.root.join(path)
    }
    pub fn init(&self) -> Result<()> {
        no_links(&self.root)?;
        for p in ["config", "state", "logs", "reports", "temp"] {
            fs::create_dir_all(self.at(p))?;
        }
        if !self.at("config/launcher.json").exists() {
            self.save(&Settings::default())?;
        }
        Ok(())
    }
    pub fn initialize(&self) -> Result<()> {
        self.init()
    }
    pub fn resolve(&self, path: &str) -> PathBuf {
        let p = Path::new(path);
        if p.is_absolute() {
            p.to_owned()
        } else {
            self.root.join(p)
        }
    }
    pub fn store(&self, path: &Path) -> String {
        path.strip_prefix(&self.root)
            .unwrap_or(path)
            .to_string_lossy()
            .into_owned()
    }
    pub fn load(&self) -> Result<Settings> {
        let s: Settings = read_json(&self.at("config/launcher.json"))?;
        if s.schema_version != 1 {
            bail!("CONFIG_VERSION: unsupported settings schema")
        }
        s.validate()?;
        Ok(s)
    }
    pub fn save(&self, s: &Settings) -> Result<()> {
        s.validate()?;
        write_json(&self.at("config/launcher.json"), s)
    }
    pub fn data(&self, s: &Settings) -> PathBuf {
        self.resolve(&s.data_path)
    }
    pub fn client_data(&self, s: &Settings) -> PathBuf {
        if s.game_branch.is_empty() {
            self.data(s)
        } else {
            self.data(s)
                .join("channels")
                .join(&hex::encode(Sha256::digest(s.game_branch.as_bytes()))[..20])
        }
    }
    pub fn bundle(&self, s: &Settings, component: &str) -> Result<PathBuf> {
        if !s.game_branch.is_empty() {
            if let Some(path) = crate::channels::installed_component(self, s, component)? {
                return Ok(path);
            }
        }
        self.base_bundle(s, component)
    }
    pub fn base_bundle(&self, s: &Settings, component: &str) -> Result<PathBuf> {
        if component == "bootstrap" {
            if !s.game_branch.is_empty() {
                if let Some(server) = crate::channels::installed_component(self, s, "server")? {
                    let base = crate::channels::component_base(&server)?;
                    let root = base.parent().context("FOUNDATION_PATH")?.join("Bootstrap");
                    no_links(&root)?;
                    return Ok(root);
                }
            }
            let stored: std::collections::BTreeMap<String, String> =
                read_json(&self.at("state/bootstrap.json"))?;
            let root = self
                .resolve(
                    stored
                        .get("path")
                        .context("FOUNDATION_MISSING: сначала установите игровую основу")?,
                )
                .join("Bootstrap");
            no_links(&root)?;
            return Ok(root);
        }
        if !["client", "server", "launcher"].contains(&component) {
            bail!("BUNDLE_PATH: unknown component")
        };
        let i: crate::InstalledRelease = read_json(&self.at("state/installed.json"))?;
        if !i.release_id.is_empty() {
            let p = child(
                &self.data(s).join("releases"),
                &format!("{}/{component}", i.release_id),
            )?;
            if p.is_dir() {
                return Ok(p);
            }
        }
        let configured = if component == "server" {
            &s.server_path
        } else {
            &s.client_path
        };
        if configured.is_empty() {
            let bootstrap: std::collections::BTreeMap<String, String> =
                read_json(&self.at("state/bootstrap.json"))?;
            if let Some(path) = bootstrap.get("path") {
                let root = self.resolve(path);
                let candidate = root.join(if component == "server" {
                    "Server"
                } else {
                    "Client"
                });
                no_links(&candidate)?;
                if candidate.is_dir() {
                    return Ok(candidate);
                }
            }
            bail!("BUNDLE_PATH: укажите комплект или установите компоненты {component}")
        }
        Ok(self.resolve(configured))
    }
    pub fn pipe(&self) -> String {
        format!(
            "bscp-next-{}",
            &hex::encode_upper(Sha256::digest(
                self.root.to_string_lossy().to_uppercase().as_bytes()
            ))[..20]
        )
    }
}
pub fn read_json<T: DeserializeOwned + Default>(path: &Path) -> Result<T> {
    no_links(path)?;
    if !path.exists() {
        return Ok(T::default());
    }
    match serde_json::from_slice(&fs::read(path)?) {
        Ok(v) => Ok(v),
        Err(e) => {
            let bak = PathBuf::from(format!("{}.bak", path.display()));
            no_links(&bak)?;
            if bak.exists() {
                Ok(serde_json::from_slice(&fs::read(bak)?)?)
            } else {
                Err(e).context("CONFIG_CORRUPT")
            }
        }
    }
}
pub fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    atomic_write(path, &serde_json::to_vec_pretty(value)?)
}
pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    no_links(path)?;
    fs::create_dir_all(path.parent().context("missing parent")?)?;
    let temp = path.with_extension(format!("{}.tmp", Uuid::new_v4().simple()));
    let result = (|| {
        let mut f = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        replace_file(&temp, path)
    })();
    if temp.exists() {
        let _ = fs::remove_file(temp);
    }
    result
}
fn replace_file(temp: &Path, path: &Path) -> Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{
            MOVEFILE_WRITE_THROUGH, MoveFileExW, ReplaceFileW,
        };
        let wide = |p: &Path| {
            p.as_os_str()
                .encode_wide()
                .chain(Some(0))
                .collect::<Vec<u16>>()
        };
        let t = wide(temp);
        let p = wide(path);
        let bak = wide(&PathBuf::from(format!("{}.bak", path.display())));
        let ok = unsafe {
            if path.exists() {
                ReplaceFileW(
                    p.as_ptr(),
                    t.as_ptr(),
                    bak.as_ptr(),
                    0,
                    std::ptr::null(),
                    std::ptr::null(),
                )
            } else {
                MoveFileExW(t.as_ptr(), p.as_ptr(), MOVEFILE_WRITE_THROUGH)
            }
        };
        if ok == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
    }
    #[cfg(not(windows))]
    {
        if path.exists() {
            fs::copy(path, format!("{}.bak", path.display()))?;
        }
        fs::rename(temp, path)?;
        File::open(path.parent().unwrap())?.sync_all()?;
    }
    Ok(())
}
pub fn no_links(path: &Path) -> Result<()> {
    let mut p = Some(path);
    while let Some(v) = p {
        if let Ok(m) = fs::symlink_metadata(v) {
            if m.file_type().is_symlink() {
                bail!("PATH_LINK: symlink forbidden: {}", v.display())
            }
            #[cfg(windows)]
            {
                use std::os::windows::fs::MetadataExt;
                if m.file_attributes() & 0x400 != 0 {
                    bail!("PATH_LINK: reparse point forbidden: {}", v.display())
                }
            }
        }
        p = v.parent();
    }
    Ok(())
}
#[cfg(windows)]
pub fn current_user_sid() -> Result<String> {
    process_user_sid(unsafe { windows_sys::Win32::System::Threading::GetCurrentProcess() })
}
#[cfg(windows)]
pub fn process_user_sid(process: windows_sys::Win32::Foundation::HANDLE) -> Result<String> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, LocalFree},
        Security::{
            Authorization::ConvertSidToStringSidW, GetTokenInformation, TOKEN_QUERY, TOKEN_USER,
            TokenUser,
        },
        System::Threading::OpenProcessToken,
    };
    unsafe {
        let mut token = std::ptr::null_mut();
        if OpenProcessToken(process, TOKEN_QUERY, &mut token) == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let mut needed = 0;
        GetTokenInformation(token, TokenUser, std::ptr::null_mut(), 0, &mut needed);
        // usize storage guarantees alignment for TOKEN_USER and its embedded SID.
        let mut info = vec![0usize; (needed as usize).div_ceil(std::mem::size_of::<usize>())];
        if GetTokenInformation(
            token,
            TokenUser,
            info.as_mut_ptr().cast(),
            needed,
            &mut needed,
        ) == 0
        {
            let error = std::io::Error::last_os_error();
            CloseHandle(token);
            return Err(error.into());
        }
        let user = &*(info.as_ptr() as *const TOKEN_USER);
        let mut raw = std::ptr::null_mut();
        if ConvertSidToStringSidW(user.User.Sid, &mut raw) == 0 {
            let error = std::io::Error::last_os_error();
            CloseHandle(token);
            return Err(error.into());
        }
        let mut len = 0;
        while *raw.add(len) != 0 {
            len += 1
        }
        let result = String::from_utf16(std::slice::from_raw_parts(raw, len));
        LocalFree(raw.cast());
        CloseHandle(token);
        Ok(result?)
    }
}

/// Limits access to the current account, even when Windows assigns ownership to Administrators.
pub fn owner_only(path: &Path, directory: bool) -> Result<()> {
    no_links(path)?;
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::{
            Foundation::LocalFree,
            Security::{
                Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW,
                DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION, SetFileSecurityW,
            },
        };
        let user = current_user_sid()?;
        let inheritance = if directory { "OICI" } else { "" };
        let sddl = format!("D:P(A;{inheritance};FA;;;{user})");
        let wide = sddl.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
        let name = path
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect::<Vec<_>>();
        let mut descriptor = std::ptr::null_mut();
        unsafe {
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                wide.as_ptr(),
                1,
                &mut descriptor,
                std::ptr::null_mut(),
            ) == 0
            {
                return Err(std::io::Error::last_os_error().into());
            }
            let result = SetFileSecurityW(
                name.as_ptr(),
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                descriptor,
            );
            let error = std::io::Error::last_os_error();
            LocalFree(descriptor);
            if result == 0 {
                return Err(error.into());
            }
        }
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(
            path,
            fs::Permissions::from_mode(if directory { 0o700 } else { 0o600 }),
        )?;
    }
    Ok(())
}
pub fn validate_relative(relative: &str) -> Result<()> {
    if relative.is_empty() || relative.contains([':', '\\']) || relative.starts_with('/') {
        bail!("PATH_UNSAFE: unsafe relative path")
    };
    for part in relative.split('/') {
        let stem = part.split('.').next().unwrap_or("").to_ascii_uppercase();
        if part.is_empty()
            || part == "."
            || part == ".."
            || part.ends_with(['.', ' '])
            || part.chars().any(|c| c < ' ' || "<>\"|?*".contains(c))
            || ["CON", "PRN", "AUX", "NUL"].contains(&stem.as_str())
            || ((stem.starts_with("COM") || stem.starts_with("LPT"))
                && stem.len() == 4
                && matches!(stem.as_bytes()[3], b'1'..=b'9'))
        {
            bail!("PATH_UNSAFE: unsafe Windows filename")
        }
    }
    Ok(())
}
pub fn child(root: &Path, relative: &str) -> Result<PathBuf> {
    validate_relative(relative)?;
    no_links(root)?;
    let p = root.join(relative);
    no_links(&p)?;
    Ok(p)
}
/// Windows paths compare without case; absolute expansion also removes `..`.
pub fn contains_path(parent: &Path, target: &Path) -> Result<bool> {
    let parent = std::path::absolute(parent)?;
    let target = std::path::absolute(target)?;
    #[cfg(windows)]
    {
        let p = parent
            .to_string_lossy()
            .trim_end_matches(['\\', '/'])
            .to_lowercase();
        let t = target.to_string_lossy().to_lowercase();
        Ok(t == p || t.starts_with(&(p + "\\")))
    }
    #[cfg(not(windows))]
    {
        Ok(target.starts_with(parent))
    }
}
pub fn files(root: &Path) -> Result<Vec<PathBuf>> {
    no_links(root)?;
    let mut out = vec![];
    for e in fs::read_dir(root)? {
        let p = e?.path();
        no_links(&p)?;
        if p.is_dir() {
            out.extend(files(&p)?)
        } else if p.is_file() {
            out.push(p)
        } else {
            bail!("PATH_TYPE: unsupported file type")
        }
    }
    Ok(out)
}
pub fn sha(path: &Path) -> Result<String> {
    sha_observed(path, |_| {})
}
pub fn sha_observed(path: &Path, mut processed: impl FnMut(u64)) -> Result<String> {
    no_links(path)?;
    let mut h = Sha256::new();
    let mut f = File::open(path)?;
    let mut b = [0; 131072];
    loop {
        let n = f.read(&mut b)?;
        if n == 0 {
            break;
        }
        h.update(&b[..n]);
        processed(n as u64);
    }
    Ok(hex::encode(h.finalize()))
}
pub fn delete_owned(parent: &Path, target: &Path) -> Result<()> {
    if !target.starts_with(parent) || target == parent {
        bail!("DELETE_ESCAPE: outside operation directory")
    }
    if target.exists() {
        no_links(target)?;
        files(target)?;
        fs::remove_dir_all(target)?;
    }
    Ok(())
}
#[derive(Debug)]
pub struct Lease {
    pub file: File,
}
impl Lease {
    pub fn acquire(path: &Path) -> Result<Self> {
        no_links(path)?;
        fs::create_dir_all(path.parent().context("lease parent")?)?;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(path)?;
        fs2::FileExt::try_lock_exclusive(&file)
            .context("WORLD_BUSY: resource is already in use")?;
        Ok(Self { file })
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        let _ = fs2::FileExt::unlock(&self.file);
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct HostRequest {
    #[serde(alias = "protocol_version", alias = "protocolVersion")]
    pub protocol_version: i32,
    #[serde(alias = "request_id", alias = "requestId")]
    pub request_id: String,
    #[serde(alias = "command")]
    pub command: String,
    #[serde(alias = "arguments")]
    pub arguments: std::collections::BTreeMap<String, String>,
}
impl Default for HostRequest {
    fn default() -> Self {
        Self {
            protocol_version: 1,
            request_id: Uuid::new_v4().simple().to_string(),
            command: "status".into(),
            arguments: Default::default(),
        }
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct HostResponse {
    #[serde(alias = "ok")]
    pub ok: bool,
    #[serde(alias = "code")]
    pub code: String,
    #[serde(alias = "error")]
    pub error: String,
    #[serde(alias = "data")]
    pub data: serde_json::Value,
}
impl HostResponse {
    pub fn success(data: impl Serialize) -> Self {
        Self {
            ok: true,
            data: serde_json::to_value(data).unwrap_or_default(),
            ..Default::default()
        }
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct ProcessView {
    pub relay_invite: String,
    pub relay_status: String,
    #[serde(alias = "state")]
    pub state: String,
    #[serde(alias = "pid")]
    pub pid: Option<u32>,
    #[serde(alias = "session_id", alias = "sessionId")]
    pub session_id: String,
    #[serde(alias = "world_id", alias = "worldId")]
    pub world_id: String,
    #[serde(alias = "detail")]
    pub detail: String,
    #[serde(alias = "client_running", alias = "clientRunning")]
    pub client_running: bool,
    #[serde(alias = "host_build", alias = "hostBuild")]
    pub host_build: String,
    #[serde(alias = "transfer")]
    pub transfer: Option<crate::TransferSnapshot>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct ServerEntry {
    #[serde(alias = "id")]
    pub id: String,
    #[serde(alias = "name")]
    pub name: String,
    #[serde(alias = "address")]
    pub address: String,
    #[serde(alias = "nickname")]
    pub nickname: String,
}
