use bscp_launcher_core::*;
use std::{
    fs,
    io::{Read, Write},
};
use tempfile::tempdir;

#[test]
fn report_includes_latest_large_and_rotated_logs_and_real_build_version() {
    let temp = tempdir().unwrap();
    let layout = Layout::new(temp.path());
    layout.init().unwrap();
    let mut log = fs::File::create(layout.at("logs/server.log")).unwrap();
    for _ in 0..150_000 {
        writeln!(log, "old diagnostic line").unwrap();
    }
    writeln!(log, "LATEST-FAILURE {}=sensitive-value", "token").unwrap();
    drop(log);
    fs::write(layout.at("logs/bridge.jsonl.1"), b"rotated marker").unwrap();
    let report = Reports::create(&layout, "reproduce", false).unwrap();
    let mut zip = zip::ZipArchive::new(fs::File::open(report).unwrap()).unwrap();
    let mut tail = String::new();
    zip.by_name("launcher/server.log")
        .unwrap()
        .read_to_string(&mut tail)
        .unwrap();
    assert!(tail.contains("LATEST-FAILURE"));
    assert!(!tail.contains("sensitive-value"));
    assert!(tail.len() < 600_000);
    assert!(zip.by_name("launcher/bridge.jsonl.1").is_ok());
    let meta: serde_json::Value =
        serde_json::from_reader(zip.by_name("incident.json").unwrap()).unwrap();
    assert_eq!(meta["launcherVersion"], env!("CARGO_PKG_VERSION"));
    assert_eq!(meta["schemaVersion"], 2);
    let collection: serde_json::Value =
        serde_json::from_reader(zip.by_name("collection.json").unwrap()).unwrap();
    assert!(
        collection["skipped"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v["reason"] == "tail_only")
    );
}

#[test]
fn screenshot_is_decoded_and_reencoded_without_source_metadata_or_paths() {
    let temp = tempdir().unwrap();
    let layout = Layout::new(temp.path().join("profile"));
    layout.init().unwrap();
    let source = temp.path().join("private-source.png");
    image::RgbImage::from_pixel(64, 32, image::Rgb([60, 80, 100]))
        .save(&source)
        .unwrap();
    fs::OpenOptions::new()
        .append(true)
        .open(&source)
        .unwrap()
        .write_all(b"HIDDEN-ORIGINAL-METADATA")
        .unwrap();
    let original = fs::read(&source).unwrap();
    let draft = BugReportDraft {
        title: "Broken action".into(),
        description: "steps".into(),
        screenshots: vec![source.clone()],
        ..Default::default()
    };
    let report = Reports::prepare(&layout, &draft, None).unwrap();
    let mut zip = zip::ZipArchive::new(fs::File::open(&report).unwrap()).unwrap();
    let mut data = Vec::new();
    zip.by_name("screenshots/01.jpg")
        .unwrap()
        .read_to_end(&mut data)
        .unwrap();
    assert!(!String::from_utf8_lossy(&data).contains("HIDDEN-ORIGINAL-METADATA"));
    let decoded = image::load_from_memory(&data).unwrap();
    assert_eq!((decoded.width(), decoded.height()), (64, 32));
    assert_eq!(fs::read(&source).unwrap(), original);
    let url = reqwest::Url::parse(&Reports::issue_url(&draft, &report).unwrap()).unwrap();
    let query = url
        .query_pairs()
        .collect::<std::collections::HashMap<_, _>>();
    assert_eq!(url.host_str(), Some("github.com"));
    assert_eq!(query["labels"], "player-report");
    assert!(query["body"].contains("bscp-player-report:v1"));
    assert!(!query["body"].contains(&temp.path().to_string_lossy().to_string()));
}

#[test]
fn invalid_screenshot_does_not_publish_partial_report() {
    let temp = tempdir().unwrap();
    let layout = Layout::new(temp.path());
    layout.init().unwrap();
    let image = temp.path().join("not-an-image.png");
    fs::write(&image, b"this is not a screenshot").unwrap();
    let draft = BugReportDraft {
        screenshots: vec![image],
        ..Default::default()
    };
    assert!(Reports::prepare(&layout, &draft, None).is_err());
    assert!(
        !layout.at("reports").exists()
            || fs::read_dir(layout.at("reports")).unwrap().next().is_none()
    );
}
