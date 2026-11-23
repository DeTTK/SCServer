use bscp_launcher_core::*;
use std::{fs, sync::atomic::AtomicBool};
fn fixture() -> (tempfile::TempDir, WorldStore, WorldInfo) {
    let temp = tempfile::tempdir().unwrap();
    let map = temp.path().join("template");
    fs::create_dir_all(map.join("region")).unwrap();
    fs::write(map.join("registry.json"), b"{}").unwrap();
    fs::write(map.join("object-codecs.json"), b"{}").unwrap();
    fs::write(map.join("region/r.0.0.szr"), b"original").unwrap();
    let store = WorldStore::new(temp.path().join("installation")).unwrap();
    let world = store.create("World", &map, "synthetic-owner").unwrap();
    (temp, store, world)
}
#[test]
fn interrupted_legacy_publication_finishes_without_replacing_original_data() {
    let (_temp, store, prepared) = fixture();
    let legacy = WorldInfo {
        name: "Legacy".into(),
        map_path: "maps/legacy".into(),
        map_hash: prepared.map_hash.clone(),
        ..Default::default()
    };
    let folder = store.folder(&legacy.id).unwrap();
    fs::create_dir_all(folder.join("data")).unwrap();
    fs::write(folder.join("data/state"), b"original data").unwrap();
    store.save(&legacy).unwrap();
    let stage_name = format!(".portable-{}", uuid::Uuid::new_v4());
    let stage = store.root.join(&stage_name);
    fs::rename(store.folder(&prepared.id).unwrap(), &stage).unwrap();
    fs::write(stage.join("data/state"), b"rollback snapshot").unwrap();
    write_json(
        &folder.join("portable.pending.json"),
        &serde_json::json!({"stage":stage_name,"original":legacy}),
    )
    .unwrap();
    fs::rename(stage.join("Map"), folder.join("Map")).unwrap();
    let reopened = WorldStore::new(store.root.parent().unwrap()).unwrap();
    assert_eq!(reopened.get(&legacy.id).unwrap().schema_version, 2);
    assert!(folder.join("world.properties").is_file());
    assert!(!folder.join("portable.pending.json").exists());
    assert_eq!(
        fs::read(folder.join("data/state")).unwrap(),
        b"original data"
    );
    assert_eq!(
        fs::read(stage.join("data/state")).unwrap(),
        b"rollback snapshot"
    );
}
#[test]
fn complete_world_imports_without_original_installation_and_clone_is_independent() {
    let (temp, store, w) = fixture();
    let original = store.map(&w).unwrap();
    fs::write(store.folder(&w.id).unwrap().join("data/state"), b"progress").unwrap();
    for name in ["secret-location.bak", "private-object.tmp"] {
        fs::write(original.join(name), b"opaque-map-metadata").unwrap();
    }
    let clone = store.clone_world(&w.id, "Copy").unwrap();
    let cloned = store.map(&clone).unwrap();
    assert_ne!(cloned, original);
    assert_eq!(
        fs::read(cloned.join("private-object.tmp")).unwrap(),
        b"opaque-map-metadata"
    );
    fs::write(cloned.join("region/r.0.0.szr"), b"clone change").unwrap();
    assert_eq!(
        fs::read(original.join("region/r.0.0.szr")).unwrap(),
        b"original"
    );
    let archive = temp.path().join("world.zip");
    store.export(&w.id, &archive).unwrap();
    fs::rename(
        temp.path().join("installation"),
        temp.path().join("disconnected-installation"),
    )
    .unwrap();
    fs::rename(
        temp.path().join("template"),
        temp.path().join("disconnected-template"),
    )
    .unwrap();
    let other = WorldStore::new(temp.path().join("other")).unwrap();
    let imported = other.import(&archive).unwrap();
    assert_eq!(imported.schema_version, 2);
    assert_eq!(
        fs::read(other.map(&imported).unwrap().join("secret-location.bak")).unwrap(),
        b"opaque-map-metadata"
    );
    assert_eq!(
        fs::read(other.map(&imported).unwrap().join("region/r.0.0.szr")).unwrap(),
        b"original"
    );
    assert_eq!(
        fs::read(other.folder(&imported.id).unwrap().join("data/state")).unwrap(),
        b"progress"
    );
    assert!(
        other
            .folder(&imported.id)
            .unwrap()
            .join("world.properties")
            .is_file()
    );
}
#[test]
fn restore_replaces_map_and_state_together() {
    let (temp, store, w) = fixture();
    let map = store.map(&w).unwrap();
    let data = store.folder(&w.id).unwrap().join("data/state");
    fs::write(&data, b"old state").unwrap();
    let archive = temp.path().join("world.zip");
    store.export(&w.id, &archive).unwrap();
    fs::write(map.join("region/r.0.0.szr"), b"new map").unwrap();
    fs::write(&data, b"new state").unwrap();
    store.restore(&w.id, &archive).unwrap();
    assert_eq!(fs::read(map.join("region/r.0.0.szr")).unwrap(), b"original");
    assert_eq!(fs::read(data).unwrap(), b"old state");
}
#[test]
fn manifest_digest_uses_java_utf16_order_and_big_endian_lengths() {
    use sha2::{Digest, Sha256};
    let temp = tempfile::tempdir().unwrap();
    fs::create_dir(temp.path().join("d")).unwrap();
    let rows = [
        ("a.txt", b"A"),
        ("d/é", b"B"),
        ("z", b"C"),
        ("😀", b"D"),
        ("\u{e000}", b"E"),
    ];
    let mut expected = Sha256::new();
    for (name, value) in rows {
        fs::write(temp.path().join(name), value).unwrap();
        expected.update((name.len() as u32).to_be_bytes());
        expected.update(name.as_bytes());
        expected.update(Sha256::digest(value));
    }
    assert_eq!(
        portable_map_digest(temp.path(), &AtomicBool::new(false)).unwrap(),
        hex::encode(expected.finalize())
    );
}
#[test]
fn interrupted_restore_recovers_map_metadata_and_data_as_one_generation() {
    for committed in [false, true] {
        let (_temp, store, w) = fixture();
        let root = store.folder(&w.id).unwrap();
        fs::write(root.join("data/state"), b"old").unwrap();
        let imported = store.clone_world(&w.id, "snapshot").unwrap();
        let source = store.folder(&imported.id).unwrap();
        fs::write(source.join("Map/region/r.0.0.szr"), b"new").unwrap();
        fs::write(source.join("data/state"), b"new").unwrap();
        write_json(
            &source.join("restore-source.json"),
            &serde_json::json!({"target":w.id}),
        )
        .unwrap();
        for name in ["data", "Map", "world.properties"] {
            fs::rename(root.join(name), root.join(format!("{name}.before-restore"))).unwrap();
            fs::rename(source.join(name), root.join(name)).unwrap();
        }
        let restored = WorldInfo {
            id: w.id.clone(),
            name: w.name.clone(),
            ..imported.clone()
        };
        write_json(&root.join("restore.pending.json"),&serde_json::json!({"ImportedId":imported.id,"Phase":if committed{"committed"}else{"prepared"},"Map":true,"Original":w,"Restored":restored})).unwrap();
        let _lease = store.lease(&w.id).unwrap();
        assert_eq!(
            fs::read(root.join("data/state")).unwrap(),
            if committed { b"new" } else { b"old" }
        );
        assert_eq!(
            fs::read(root.join("Map/region/r.0.0.szr"))
                .unwrap()
                .as_slice(),
            if committed {
                b"new".as_slice()
            } else {
                b"original".as_slice()
            }
        );
        assert!(!source.exists());
        assert!(!root.join("Map.before-restore").exists());
    }
}

#[test]
fn template_without_codecs_adopts_the_server_bundle_fallback() {
    let temp = tempfile::tempdir().unwrap();
    let base = temp.path().join("bundle");
    let template = base.join("Map");
    fs::create_dir_all(template.join("region")).unwrap();
    fs::create_dir_all(base.join("Server/registries")).unwrap();
    fs::write(template.join("region/registry.json"), b"{}").unwrap();
    fs::write(template.join("region/r.0.0.szr"), b"fixture").unwrap();
    fs::write(
        base.join("Server/registries/live-object-codecs.json"),
        b"saved codec",
    )
    .unwrap();
    let store = WorldStore::new(temp.path().join("installation")).unwrap();
    let world = store.create("World", &template, "owner").unwrap();
    fs::rename(base, temp.path().join("disconnected")).unwrap();
    assert_eq!(
        fs::read(store.map(&world).unwrap().join("object-codecs.json")).unwrap(),
        b"saved codec"
    );
}
