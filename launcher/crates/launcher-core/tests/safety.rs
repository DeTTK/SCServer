use bscp_launcher_core::*;
use std::{collections::BTreeMap, fs, io::Write, sync::atomic::AtomicBool};
use tempfile::TempDir;
fn fixture() -> (TempDir, WorldStore, WorldInfo) {
    let temp = TempDir::new().unwrap();
    let map = temp.path().join("external-map");
    fs::create_dir_all(map.join("region")).unwrap();
    fs::write(map.join("registry.json"), b"{}").unwrap();
    fs::write(map.join("object-codecs.json"), b"{}").unwrap();
    fs::write(map.join("region/r.0.0.szr"), b"base-data").unwrap();
    let store = WorldStore::new(temp.path().join("managed")).unwrap();
    let world = store.create("World", &map, "owner").unwrap();
    (temp, store, world)
}
#[test]
fn settings_migrates_existing_schema_and_uses_backup() {
    let temp = TempDir::new().unwrap();
    let l = Layout::new(temp.path());
    l.init().unwrap();
    let p = l.at("config/launcher.json");
    fs::write(
        &p,
        r#"{"schemaVersion":1,"gamePath":"X:\\Game","clientMemoryMb":6144,"nickname":"OldPlayer"}"#,
    )
    .unwrap();
    let s = l.load().unwrap();
    assert_eq!(s.nickname, "OldPlayer");
    assert_eq!(s.client_memory_mb, 6144);
    l.save(&s).unwrap();
    fs::write(&p, b"{broken").unwrap();
    assert_eq!(l.load().unwrap().game_path, "X:\\Game");
}
#[test]
fn map_digest_matches_csharp_binary_framing() {
    use sha2::{Digest, Sha256};
    let temp = TempDir::new().unwrap();
    fs::write(temp.path().join("a"), b"abc").unwrap();
    let mut expected = Sha256::new();
    expected.update(1i32.to_le_bytes());
    expected.update(b"a");
    expected.update(3i64.to_le_bytes());
    expected.update(b"abc");
    assert_eq!(
        map_digest(temp.path()).unwrap(),
        hex::encode(expected.finalize())
    );
}
#[test]
fn managed_map_is_copy_and_clone_excludes_secrets() {
    let (temp, store, w) = fixture();
    fs::write(
        temp.path().join("external-map/region/r.0.0.szr"),
        b"changed",
    )
    .unwrap();
    assert_eq!(
        fs::read(store.map(&w).unwrap().join("region/r.0.0.szr")).unwrap(),
        b"base-data"
    );
    let data = store.folder(&w.id).unwrap().join("data");
    fs::write(data.join("players.json"), b"player").unwrap();
    fs::write(data.join("admin-token.txt"), b"sensitive").unwrap();
    fs::write(data.join("identity-private.pem"), b"key").unwrap();
    let cloned = store.clone_world(&w.id, "Copy").unwrap();
    let cloned = store.folder(&cloned.id).unwrap().join("data");
    assert!(cloned.join("players.json").is_file());
    assert!(!cloned.join("admin-token.txt").exists());
    assert!(!cloned.join("identity-private.pem").exists());
}
#[test]
fn world_lease_excludes_concurrent_operations() {
    let (_temp, store, w) = fixture();
    let _lease = store.lease(&w.id).unwrap();
    assert!(store.lease(&w.id).is_err());
    assert!(store.rename(&w.id, "oops").is_err());
}
#[test]
fn native_world_lease_blocks_launcher() {
    let (_temp, store, w) = fixture();
    let _lease = Lease::acquire(&store.folder(&w.id).unwrap().join("data/.server-lease")).unwrap();
    assert!(store.lease(&w.id).is_err());
}
#[test]
fn export_restore_roundtrip_and_corruption_preserves_data() {
    let (temp, store, w) = fixture();
    let data = store.folder(&w.id).unwrap().join("data/players.json");
    fs::write(&data, b"before").unwrap();
    let zip = temp.path().join("world.zip");
    store.export(&w.id, &zip).unwrap();
    fs::write(&data, b"after").unwrap();
    store.restore(&w.id, &zip).unwrap();
    assert_eq!(fs::read(&data).unwrap(), b"before");
    let bad = temp.path().join("bad.zip");
    fs::write(&bad, b"broken archive").unwrap();
    assert!(store.restore(&w.id, &bad).is_err());
    assert_eq!(fs::read(data).unwrap(), b"before");
    assert_eq!(store.list().unwrap().len(), 1);
}
#[test]
fn empty_world_archive_imports_as_empty_world() {
    let (temp, store, w) = fixture();
    let zip = temp.path().join("empty.zip");
    store.export(&w.id, &zip).unwrap();
    let imported = store.import(&zip).unwrap();
    assert!(store.folder(&imported.id).unwrap().join("data").is_dir());
}
#[test]
fn prepared_restore_rolls_back_and_committed_finishes() {
    for committed in [false, true] {
        let (_temp, store, w) = fixture();
        let f = store.folder(&w.id).unwrap();
        fs::write(f.join("data/value"), b"old").unwrap();
        let imported = store.clone_world(&w.id, "temp").unwrap();
        let source = store.folder(&imported.id).unwrap();
        write_json(
            &source.join("restore-source.json"),
            &serde_json::json!({"target":w.id}),
        )
        .unwrap();
        fs::rename(f.join("data"), f.join("data.before-restore")).unwrap();
        fs::rename(source.join("data"), f.join("data")).unwrap();
        fs::write(f.join("data/value"), b"new").unwrap();
        write_json(&f.join("restore.pending.json"),&serde_json::json!({"ImportedId":imported.id,"Phase":if committed{"committed"}else{"prepared"}})).unwrap();
        let _lease = store.lease(&w.id).unwrap();
        assert_eq!(
            fs::read(f.join("data/value")).unwrap(),
            if committed { b"new" } else { b"old" }
        );
        assert!(!f.join("data.before-restore").exists());
        assert!(!source.exists());
    }
}
#[test]
fn failed_restore_recovery_never_creates_empty_data() {
    let (_temp, store, w) = fixture();
    let f = store.folder(&w.id).unwrap();
    fs::remove_dir(f.join("data")).unwrap();
    let id = uuid::Uuid::new_v4().simple().to_string();
    write_json(
        &f.join("restore.pending.json"),
        &serde_json::json!({"ImportedId":id,"Phase":"prepared"}),
    )
    .unwrap();
    assert!(store.lease(&w.id).is_err());
    assert!(!f.join("data").exists());
    assert!(f.join("restore.pending.json").exists());
}
#[test]
fn rejects_zip_traversal_case_duplicates_and_budgets() {
    for (names, budget) in [
        (vec!["../outside"], 100),
        (vec!["file", "FILE"], 100),
        (vec!["normal"], 1),
        (vec!["COM9.txt"], 100),
    ] {
        let temp = TempDir::new().unwrap();
        let path = temp.path().join("test.zip");
        let mut zip = zip::ZipWriter::new(fs::File::create(&path).unwrap());
        for name in names {
            zip.start_file(name, zip::write::SimpleFileOptions::default())
                .unwrap();
            zip.write_all(b"data").unwrap();
        }
        zip.finish().unwrap();
        let target = temp.path().join("extracted");
        fs::create_dir(&target).unwrap();
        assert!(safe_extract(&path, &target, budget, |_| true).is_err());
        assert!(!temp.path().join("outside").exists());
    }
}
#[test]
fn profile_updates_preserve_player_changes() {
    let temp = TempDir::new().unwrap();
    let source = temp.path().join("source");
    fs::create_dir_all(source.join("mods")).unwrap();
    fs::write(source.join("mods/mod.jar"), b"one").unwrap();
    let data = temp.path().join("data");
    let profile = ClientProfile::prepare(&data, &source).unwrap();
    fs::write(profile.join("mods/mod.jar"), b"player-edit").unwrap();
    fs::write(source.join("mods/mod.jar"), b"two").unwrap();
    assert!(ClientProfile::prepare(&data, &source).is_err());
    assert_eq!(
        fs::read(profile.join("mods/mod.jar")).unwrap(),
        b"player-edit"
    );
}
#[test]
fn index_requires_exact_files_and_valid_hashes() {
    let temp = TempDir::new().unwrap();
    fs::write(temp.path().join("allowed"), b"x").unwrap();
    let index = BTreeMap::from([("allowed".into(), sha(&temp.path().join("allowed")).unwrap())]);
    verify_inventory(temp.path(), &index, None).unwrap();
    fs::write(temp.path().join("injected"), b"x").unwrap();
    assert!(verify_inventory(temp.path(), &index, None).is_err());
}
#[test]
fn identity_persists_and_signature_detects_changes() {
    let temp = TempDir::new().unwrap();
    let l = Layout::new(temp.path());
    l.init().unwrap();
    let a = LocalIdentity::load(&l).unwrap();
    let b = LocalIdentity::load(&l).unwrap();
    assert_eq!(a.id, b.id);
    let sig = a.sign("challenge").unwrap();
    assert!(LocalIdentity::verify(&a.public_key, "challenge", &sig));
    assert!(!LocalIdentity::verify(&a.public_key, "tampered", &sig));
}
#[test]
fn cancellation_does_not_commit_world() {
    let temp = TempDir::new().unwrap();
    let map = temp.path().join("map");
    fs::create_dir_all(map.join("region")).unwrap();
    fs::write(map.join("registry.json"), b"{}").unwrap();
    fs::write(map.join("object-codecs.json"), b"{}").unwrap();
    let store = WorldStore::new(temp.path().join("data")).unwrap();
    assert!(
        store
            .create_cancellable("World", &map, "owner", &AtomicBool::new(true))
            .is_err()
    );
    assert!(store.list().unwrap().is_empty());
}
#[test]
fn blank_data_path_is_rejected_before_resolving_to_launcher_root() {
    let temp = TempDir::new().unwrap();
    let layout = Layout::new(temp.path());
    layout.init().unwrap();
    let mut settings = layout.load().unwrap();
    settings.data_path = "  ".into();
    assert!(
        settings
            .validate()
            .unwrap_err()
            .to_string()
            .starts_with("DATA_PATH:")
    );
    assert!(layout.save(&settings).is_err());
    fs::write(layout.at("config/launcher.json"), r#"{"DataPath":""}"#).unwrap();
    assert!(
        layout
            .load()
            .unwrap_err()
            .to_string()
            .starts_with("DATA_PATH:")
    );
}
#[test]
fn invalid_local_bootstrap_archive_cleans_only_its_prepared_stage() {
    let temp = TempDir::new().unwrap();
    let layout = Layout::new(temp.path().join("launcher"));
    layout.init().unwrap();
    let data = temp.path().join("data");
    let unrelated = data.join("bundles/unrelated-qa-fixture");
    fs::create_dir_all(&unrelated).unwrap();
    fs::write(unrelated.join("keep"), b"preserved").unwrap();
    let zip = temp.path().join("wrong.zip");
    fs::write(&zip, b"not-the-pinned-distribution").unwrap();
    assert!(BundleInstaller::install(&layout, &data, None, Some(&zip)).is_err());
    let stages = fs::read_dir(data.join("bundles"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.starts_with(".incoming-"))
        .count();
    assert_eq!(stages, 0);
    assert!(unrelated.join("keep").is_file());
    assert!(!layout.at("state/bootstrap.json").exists());
}
#[test]
fn corrupt_identity_fails_without_replacing_account_keys() {
    let temp = TempDir::new().unwrap();
    let layout = Layout::new(temp.path());
    layout.init().unwrap();
    let mut identity = LocalIdentity::load(&layout).unwrap();
    identity.id = "a".repeat(64);
    let path = layout.at("config/identity/profile.json");
    write_json(&path, &identity).unwrap();
    assert!(
        LocalIdentity::load(&layout)
            .unwrap_err()
            .to_string()
            .starts_with("IDENTITY_CORRUPT:")
    );
    let persisted: LocalIdentity = read_json(&path).unwrap();
    assert_eq!(persisted.id, identity.id);
    assert_eq!(persisted.private_key, identity.private_key);
}
#[test]
fn diagnostics_redacts_and_excludes_jfr_without_opt_in() {
    let temp = TempDir::new().unwrap();
    let l = Layout::new(temp.path());
    l.init().unwrap();
    fs::write(
        l.at("logs/out.log"),
        format!("Bearer abcdef {}=supersecret {}='hidden'", "token", "password"),
    )
    .unwrap();
    fs::write(l.at("logs/flight.jfr"), b"performance").unwrap();
    let report = Reports::create(&l, "session=secret", false).unwrap();
    let mut zip = zip::ZipArchive::new(fs::File::open(report).unwrap()).unwrap();
    assert!(zip.by_name("launcher/flight.jfr").is_err());
    let mut text = String::new();
    use std::io::Read;
    zip.by_name("launcher/out.log")
        .unwrap()
        .read_to_string(&mut text)
        .unwrap();
    assert!(!text.contains("supersecret"));
    assert!(!text.contains("hidden"));
    assert!(!text.contains("abcdef"));
}
