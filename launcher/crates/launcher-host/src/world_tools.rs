//! Offline world adoption without starting a persistent supervisor.
use anyhow::{Context, Result, bail};
use bscp_launcher_core::*;
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

pub fn prepare(layout: &Layout, id: &str, cancel: &Arc<AtomicBool>) -> Result<WorldInfo> {
    let settings = layout.load()?;
    let worlds = WorldStore::new(layout.data(&settings))?;
    let world = worlds.get(id)?;
    if world.schema_version == 2 {
        return Ok(world);
    }
    let _lease = worlds.lease(id)?;
    let map = worlds.map(&world)?;
    if map_digest(&map)? != world.map_hash {
        bail!("MAP_CHANGED: исходная карта старого мира повреждена");
    }
    let source = layout.bundle(&settings, "server")?;
    require_portable_server(&component_file(&source, "server.jar")?)?;
    let bootstrap = layout.bundle(&settings, "bootstrap")?;
    let java = crate::launch::java(&component_file(&bootstrap, "java17")?)?;
    // PortableWorld locates the target registry beside these server codecs.
    // Source-map codecs are discovered independently inside the copied Map.
    let codecs = component_file(&source, "registries/live-object-codecs.json")?;
    let cp = std::env::join_paths([
        component_file(&source, "server.jar")?,
        component_file(&source, "lib/gson-2.13.2.jar")?,
        runtime_root(&source)?.join("dependencies/zstd-jni-1.5.0-5.jar"),
    ])?;
    let stage = worlds
        .root
        .join(format!(".portable-{}", uuid::Uuid::new_v4()));
    let log = layout.at("logs/world-migration.log");
    std::fs::create_dir_all(log.parent().unwrap())?;
    let mut cmd = std::process::Command::new(java);
    cmd.arg("-Djava.awt.headless=true")
        .arg("-cp")
        .arg(cp)
        .arg("szserver.world.PortableWorld")
        .arg("migrate")
        .arg(&stage)
        .arg(map)
        .arg(codecs)
        .arg(worlds.folder(id)?.join("data"))
        .stdin(std::process::Stdio::null())
        .stdout(std::fs::File::create(&log)?)
        .stderr(std::fs::OpenOptions::new().append(true).open(&log)?);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    let mut process = cmd
        .spawn()
        .context("WORLD_MIGRATION: запуск инструмента сервера")?;
    let start = Instant::now();
    loop {
        if let Some(status) = process.try_wait()? {
            if !status.success() {
                bail!(
                    "WORLD_MIGRATION: сервер не смог подготовить мир; исходные данные сохранены, подробности logs/world-migration.log"
                );
            }
            break;
        }
        if cancel.load(Ordering::Relaxed) || start.elapsed() > Duration::from_secs(1800) {
            let _ = process.kill();
            let _ = process.wait();
            bail!("CANCELLED: подготовка мира остановлена; исходные данные сохранены");
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    worlds.install_portable(id, &PathBuf::from(stage))?;
    worlds.get(id)
}
