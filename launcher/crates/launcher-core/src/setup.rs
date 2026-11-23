use crate::*;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
};
#[derive(Debug, Clone)]
pub struct GameLayout {
    pub root: PathBuf,
    pub assets: PathBuf,
    pub mod_assets: PathBuf,
    pub patch_assets: Option<PathBuf>,
    pub native_bin: Option<PathBuf>,
    pub variant: String,
}
impl GameLayout {
    pub fn resolve(selected: impl AsRef<Path>) -> Result<Self> {
        let selected = selected.as_ref();
        if selected.as_os_str().is_empty() {
            bail!("GAME_PATH: select installed game")
        };
        let p = std::path::absolute(selected)?;
        let p = if p.is_file() {
            p.parent().unwrap().to_owned()
        } else {
            p
        };
        let special = matches!(
            p.file_name().and_then(|n| n.to_str()),
            Some("bin_ru" | "bin_global")
        );
        let mut roots = vec![];
        if special {
            roots.push(p.parent().unwrap().to_owned())
        };
        roots.push(p.clone());
        for n in ["game", "client", "contents"] {
            roots.push(p.join(n));
        }
        let variants = if special {
            vec![p.file_name().unwrap().to_str().unwrap()]
        } else {
            vec!["", "bin_ru", "bin_global"]
        };
        for root in roots {
            for v in &variants {
                let bin = root.join(v);
                let mod_assets = root.join("modassets");
                let patch = bin.join("patchassets");
                let config = if v.is_empty() {
                    mod_assets.join("assets/bundle/configs.xeon")
                } else {
                    patch.join("assets/bundle/configs.xeon")
                };
                if root.join("assets").is_dir()
                    && mod_assets.is_dir()
                    && bin.join("classes.jar").is_file()
                    && config.is_file()
                {
                    let mut native = bin.join("win64/java/bin");
                    if !native.is_dir() {
                        native = bin.join("win64")
                    };
                    return Ok(Self {
                        assets: root.join("assets"),
                        mod_assets,
                        patch_assets: patch.is_dir().then_some(patch),
                        native_bin: native.is_dir().then_some(native),
                        variant: if v.is_empty() {
                            "launcher".into()
                        } else {
                            v.to_string()
                        },
                        root,
                    });
                }
            }
        }
        bail!("GAME_INVALID: assets, modassets, classes.jar and configs.xeon required")
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct BundleLocation {
    #[serde(alias = "client")]
    pub client: String,
    #[serde(alias = "server")]
    pub server: String,
    #[serde(alias = "map")]
    pub map: String,
}
pub struct Setup;
impl Setup {
    pub fn discover(root: impl AsRef<Path>) -> BundleLocation {
        let root = root.as_ref();
        let find = |name: &str| {
            [
                root.join(name),
                root.join("BSCPCRAFT").join(name),
                root.to_owned(),
            ]
            .into_iter()
            .find(|p| match name {
                "Client" => {
                    p.join("runtime/bootstrap/StandardJvmBootstrap.class")
                        .is_file()
                        || (p.is_dir()
                            && p.parent().is_some_and(|r| {
                                r.join("Bootstrap/runtime/bootstrap.jar").is_file()
                            }))
                }
                "Server" => p.join("server.jar").is_file(),
                _ => {
                    (p.join("registry.json").is_file() || p.join("region/registry.json").is_file())
                        && p.join("region").is_dir()
                }
            })
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_default()
        };
        BundleLocation {
            client: find("Client"),
            server: find("Server"),
            map: find("Map"),
        }
    }
    pub fn check_components(client: &Path, server: &Path) -> Result<()> {
        for (root, names) in [
            (
                client,
                &[
                    "compat/game-resource-compat.jar",
                    "compat/startup-guard.jar",
                    "compat/weapon-events.jar",
                ][..],
            ),
            (
                server,
                &[
                    "server.jar",
                    "server.template.properties",
                    "lib/gson-2.13.2.jar",
                ][..],
            ),
        ] {
            no_links(root)?;
            for name in names {
                if !component_file(root, name)?.exists() {
                    bail!("BUNDLE_INCOMPLETE: missing {name}");
                }
            }
            let runtime = runtime_root(root)?;
            for name in ["classes", "bootstrap", "libs", "dependencies"] {
                if !runtime_part(&runtime, name).exists() {
                    bail!("BUNDLE_INCOMPLETE: missing runtime {name}");
                }
            }
        }
        Ok(())
    }
    pub fn complete(
        layout: &Layout,
        proposed: Settings,
        map: &Path,
        world_name: &str,
        callback: Option<&Progress>,
    ) -> Result<Settings> {
        Self::complete_cancellable(
            layout,
            proposed,
            map,
            world_name,
            callback,
            &std::sync::atomic::AtomicBool::new(false),
        )
    }
    pub fn complete_cancellable(
        layout: &Layout,
        mut proposed: Settings,
        map: &Path,
        world_name: &str,
        callback: Option<&Progress>,
        cancel: &std::sync::atomic::AtomicBool,
    ) -> Result<Settings> {
        check_cancel(cancel)?;
        proposed.validate()?;
        if proposed.game_path.trim().is_empty() {
            bail!("GAME_PATH: выберите папку установленной игры")
        }
        if proposed.client_path.trim().is_empty() || proposed.server_path.trim().is_empty() {
            bail!("BUNDLE_PATH: сначала установите или укажите комплекты клиента и сервера")
        }
        let game = GameLayout::resolve(layout.resolve(&proposed.game_path))?;
        Self::check_components(
            &layout.resolve(&proposed.client_path),
            &layout.resolve(&proposed.server_path),
        )?;
        let data = layout.data(&proposed);
        no_links(&data)?;
        let absolute_data = std::path::absolute(&data)?;
        if contains_path(&game.root, &absolute_data)? {
            bail!("SETUP_DATA: выберите папку данных вне установленной игры")
        };
        fs::create_dir_all(&data)?;
        let probe = data.join(format!(".write-probe-{}", uuid::Uuid::new_v4().simple()));
        fs::write(&probe, b"write-test")?;
        fs::remove_file(probe)?;
        let worlds = WorldStore::new(&data)?;
        let list = worlds.list()?;
        if list.is_empty() {
            progress(callback, "Создаём самостоятельный мир…");
            proposed.selected_world = worlds
                .create_cancellable(world_name, map, &LocalIdentity::load(layout)?.id, cancel)?
                .id;
        } else if !list.iter().any(|w| w.id == proposed.selected_world) {
            proposed.selected_world = list[0].id.clone()
        };
        check_cancel(cancel)?;
        proposed.setup_completed = true;
        layout.save(&proposed)?;
        Ok(proposed)
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct LocalIdentity {
    #[serde(alias = "id")]
    pub id: String,
    #[serde(alias = "public_key", alias = "publicKey")]
    pub public_key: String,
    #[serde(alias = "private_key", alias = "privateKey")]
    pub private_key: String,
}
impl LocalIdentity {
    pub fn load(layout: &Layout) -> Result<Self> {
        use base64::Engine;
        use p256::pkcs8::{EncodePrivateKey, EncodePublicKey};
        use sha2::{Digest, Sha256};
        let path = layout.at("config/identity/profile.json");
        no_links(&path)?;
        fs::create_dir_all(path.parent().unwrap())?;
        owner_only(path.parent().unwrap(), true)?;
        if path.exists() {
            owner_only(&path, false)?;
            let identity: Self = read_json(&path)?;
            identity.validate()?;
            return Ok(identity);
        }
        let key = p256::SecretKey::random(&mut rand_core::OsRng);
        let public = key.public_key().to_public_key_der()?;
        let value = Self {
            id: hex::encode(Sha256::digest(public.as_bytes())),
            public_key: base64::engine::general_purpose::STANDARD.encode(public.as_bytes()),
            private_key: base64::engine::general_purpose::STANDARD
                .encode(key.to_pkcs8_der()?.as_bytes()),
        };
        write_json(&path, &value)?;
        owner_only(&path, false)?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<()> {
        use anyhow::Context;
        let validate = || -> Result<()> {
            use base64::Engine;
            use p256::pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePublicKey};
            use sha2::{Digest, Sha256};
            let public = base64::engine::general_purpose::STANDARD.decode(&self.public_key)?;
            p256::PublicKey::from_public_key_der(&public)?;
            if hex::encode(Sha256::digest(&public)) != self.id {
                bail!("не совпадает идентификатор открытого ключа")
            }
            let private = base64::engine::general_purpose::STANDARD.decode(&self.private_key)?;
            let key = p256::SecretKey::from_pkcs8_der(&private)?;
            if key.public_key().to_public_key_der()?.as_bytes() != public {
                bail!("закрытый ключ не соответствует открытому")
            }
            Ok(())
        };
        validate()
            .context("IDENTITY_CORRUPT: повреждён локальный профиль; исходные ключи сохранены")
    }
    pub fn sign(&self, challenge: &str) -> Result<String> {
        use base64::Engine;
        use p256::{
            ecdsa::{Signature, SigningKey, signature::Signer},
            pkcs8::DecodePrivateKey,
        };
        let raw = base64::engine::general_purpose::STANDARD.decode(&self.private_key)?;
        let key = SigningKey::from_pkcs8_der(&raw)?;
        let signature: Signature = key.sign(challenge.as_bytes());
        Ok(base64::engine::general_purpose::STANDARD.encode(signature.to_bytes()))
    }
    pub fn verify(public_key: &str, challenge: &str, signature: &str) -> bool {
        use base64::Engine;
        use p256::{
            ecdsa::{Signature, VerifyingKey, signature::Verifier},
            pkcs8::DecodePublicKey,
        };
        let run = || -> Result<bool> {
            let key = VerifyingKey::from_public_key_der(
                &base64::engine::general_purpose::STANDARD.decode(public_key)?,
            )?;
            let signature = Signature::from_slice(
                &base64::engine::general_purpose::STANDARD.decode(signature)?,
            )?;
            Ok(key.verify(challenge.as_bytes(), &signature).is_ok())
        };
        run().unwrap_or(false)
    }
}
pub struct ClientProfile;
impl ClientProfile {
    pub fn lease(data: &Path) -> Result<Lease> {
        Lease::acquire(&data.join("profiles/default/.launcher-lease"))
    }
    pub fn prepare(data: &Path, source: &Path) -> Result<PathBuf> {
        let profile = data.join("profiles/default");
        no_links(&profile)?;
        let java = profile.join(".client-lease");
        if java.exists() {
            let _probe = Lease::acquire(&java)?;
        }
        let mods = profile.join("mods");
        fs::create_dir_all(&mods)?;
        let manifest = profile.join("bundled-mods.json");
        let previous: BTreeMap<String, String> = read_json(&manifest)?;
        let mut current = BTreeMap::new();
        let mut sources = std::collections::BTreeMap::new();
        for supplied in [component_base(source)?.join("mods"), source.join("mods")] {
            if supplied.is_dir() {
                no_links(&supplied)?;
                for e in fs::read_dir(&supplied)? {
                    let file = e?.path();
                    if file.extension().is_some_and(|x| x == "jar") {
                        sources.insert(file.file_name().unwrap().to_owned(), file);
                    }
                }
            }
        }
        {
            for (_, file) in sources {
                if file.extension().and_then(|p| p.to_str()) != Some("jar") {
                    continue;
                }
                no_links(&file)?;
                let name = file.file_name().unwrap().to_string_lossy().into_owned();
                let target = child(&mods, &name)?;
                let hash = sha(&file)?;
                if target.exists() {
                    let actual = sha(&target)?;
                    if actual != hash && previous.get(&name) != Some(&actual) {
                        bail!("MOD_CHANGED: player modified bundled mod {name}")
                    }
                }
                if !target.exists() || sha(&target)? != hash {
                    atomic_write(&target, &fs::read(&file)?)?;
                }
                current.insert(name, hash);
            }
        }
        for (name, hash) in previous {
            if !current.contains_key(&name) {
                let target = child(&mods, &name)?;
                if target.exists() && sha(&target)? == hash {
                    fs::remove_file(target)?;
                }
            }
        }
        write_json(&manifest, &current)?;
        Ok(profile)
    }
}

/// Capability marker in the authenticated server component, without loading game classes.
pub fn native_parallel_start_supported(source: &std::path::Path) -> anyhow::Result<bool> {
    use std::io::Read;
    let mut archive = zip::ZipArchive::new(std::fs::File::open(crate::component_file(
        source,
        "server.jar",
    )?)?)?;
    let mut entry = archive.by_name("szserver/net/LocalAccounts.class")?;
    if entry.size() > 1024 * 1024 {
        return Ok(false);
    }
    let mut bytes = Vec::new();
    entry.read_to_end(&mut bytes)?;
    Ok(bytes
        .windows(b"bscp-start-".len())
        .any(|part| part == b"bscp-start-"))
}
