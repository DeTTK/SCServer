//! Reproducible world lifecycle measurements on a fresh, explicitly owned fixture.
use anyhow::{Result, ensure};
use bscp_launcher_core::*;
use std::{fs, path::PathBuf, time::Instant};
fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    ensure!(
        args.len() == 3,
        "world_probe <new-owned-root> <template-map>"
    );
    let root = PathBuf::from(&args[1]);
    ensure!(!root.exists(), "Benchmark root must not already exist");
    let map = PathBuf::from(&args[2]);
    let source_files = files(&map)?;
    let source_bytes: u64 = source_files
        .iter()
        .map(|p| fs::metadata(p).unwrap().len())
        .sum();
    let store = WorldStore::new(root.join("first"))?;
    let start = Instant::now();
    let world = store.create("Synthetic benchmark world", &map, "synthetic-owner")?;
    let create_ms = start.elapsed().as_secs_f64() * 1000.;
    fs::write(
        store.folder(&world.id)?.join("data/fixture.json"),
        b"{\"synthetic\":true}",
    )?;
    let start = Instant::now();
    let cloned = store.clone_world(&world.id, "Synthetic clone")?;
    let clone_ms = start.elapsed().as_secs_f64() * 1000.;
    let archive = root.join("world.zip");
    let start = Instant::now();
    store.export(&world.id, &archive)?;
    let export_ms = start.elapsed().as_secs_f64() * 1000.;
    let other = WorldStore::new(root.join("different-installation"))?;
    let start = Instant::now();
    let imported = other.import(&archive);
    let import_ms = start.elapsed().as_secs_f64() * 1000.;
    let portable_import = imported.is_ok();
    let import_error = imported.err().map(|e| e.to_string());
    let report = serde_json::json!({
        "schema":1,"launcherVersion":env!("CARGO_PKG_VERSION"),"syntheticState":true,
        "templateFiles":source_files.len(),"templateBytes":source_bytes,
        "worldSchema":world.schema_version,"createMs":create_ms,"cloneMs":clone_ms,
        "exportMs":export_ms,"archiveBytes":fs::metadata(&archive)?.len(),
        "differentInstallationImportMs":import_ms,"portableImportPassed":portable_import,
        "importError":import_error,"cloneSharesMapPath":store.map(&world)?==store.map(&cloned)?,
        "fixtureFiles":files(&root)?.len()
    });
    write_json(&root.join("measurement.json"), &report)?;
    println!("{}", serde_json::to_string_pretty(&report)?);
    Ok(())
}
