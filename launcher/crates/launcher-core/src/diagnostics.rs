use crate::*;
use anyhow::Result;
use regex::Regex;
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::Mutex,
};
pub fn redact(text: &str) -> String {
    let bearer = Regex::new(r#"(?i)(Bearer\s+)[^\s\"<>]+"#).unwrap();
    let fields=Regex::new(r#"(?i)([\"']?(?:token|password|secret|privateKey|session)[\"']?\s*[:=]\s*[\"']?)[^\s,\"'<>}]+"#).unwrap();
    let mut s = fields
        .replace_all(
            &bearer.replace_all(text, "${1}[redacted]"),
            "${1}[redacted]",
        )
        .into_owned();
    if let Ok(home) = std::env::var("USERPROFILE") {
        if !home.is_empty() {
            s = Regex::new(&format!("(?i){}", regex::escape(&home)))
                .unwrap()
                .replace_all(&s, "%USERPROFILE%")
                .into_owned();
        }
    }
    s
}
pub struct RotatingLog {
    directory: PathBuf,
    name: String,
    limit: u64,
    gate: Mutex<()>,
}
impl RotatingLog {
    pub fn new(directory: impl AsRef<Path>, name: &str) -> Self {
        Self {
            directory: directory.as_ref().to_owned(),
            name: name.into(),
            limit: 8 * 1024 * 1024,
            gate: Mutex::new(()),
        }
    }
    pub fn line(&self, value: &str) -> Result<()> {
        let _guard = self.gate.lock().unwrap_or_else(|e| e.into_inner());
        validate_relative(&self.name)?;
        no_links(&self.directory)?;
        fs::create_dir_all(&self.directory)?;
        let path = self.directory.join(format!("{}.jsonl", self.name));
        no_links(&path)?;
        if path.exists() && fs::metadata(&path)?.len() > self.limit {
            for i in (1..=3).rev() {
                let from = if i == 1 {
                    path.clone()
                } else {
                    PathBuf::from(format!("{}.{}", path.display(), i - 1))
                };
                let to = PathBuf::from(format!("{}.{}", path.display(), i));
                no_links(&from)?;
                no_links(&to)?;
                if from.exists() {
                    if to.exists() {
                        fs::remove_file(&to)?;
                    }
                    fs::rename(from, to)?;
                }
            }
        }
        let bounded = value.chars().take(32768).collect::<String>();
        let line = serde_json::to_vec(
            &serde_json::json!({"time":chrono::Utc::now().to_rfc3339(),"message":redact(&bounded)}),
        )?;
        let mut f = OpenOptions::new().create(true).append(true).open(path)?;
        f.write_all(&line)?;
        f.write_all(b"\n")?;
        f.flush()?;
        Ok(())
    }
    pub fn redact(value: &str) -> String {
        redact(value)
    }
}
pub struct Reports;
impl Reports {
    pub fn create(
        layout: &Layout,
        description: &str,
        include_performance: bool,
    ) -> Result<PathBuf> {
        let id = format!(
            "{}-{}",
            chrono::Utc::now().format("%Y%m%d-%H%M%S"),
            &uuid::Uuid::new_v4().simple().to_string()[..6]
        );
        let path = layout.at(format!("reports/incident-{id}.zip"));
        no_links(&path)?;
        fs::create_dir_all(path.parent().unwrap())?;
        let mut zip = zip::ZipWriter::new(
            OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)?,
        );
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        zip.start_file("incident.json", options)?;
        zip.write_all(redact(&serde_json::to_string_pretty(&serde_json::json!({"schemaVersion":1,"incidentId":id,"created":chrono::Utc::now().to_rfc3339(),"description":description.chars().take(32768).collect::<String>(),"launcherVersion":"0.3.0-rust","os":std::env::consts::OS,"architecture":std::env::consts::ARCH}))?).as_bytes())?;
        let profile = layout
            .client_data(&layout.load()?)
            .join("profiles/default/game");
        let mut budget = 32 * 1024 * 1024u64;
        let mut recordings = 0;
        for (folder, label) in [
            (layout.at("logs"), "launcher"),
            (layout.at("state/sessions"), "sessions"),
            (profile.join("logs"), "game"),
            (profile.join("crash-reports"), "crashes"),
        ] {
            if !folder.is_dir() {
                continue;
            }
            let mut entries = files(&folder)?;
            entries.sort_by_key(|p| {
                std::cmp::Reverse(fs::metadata(p).and_then(|m| m.modified()).ok())
            });
            for file in entries {
                let name = file.file_name().unwrap().to_string_lossy();
                let ext = file.extension().and_then(|s| s.to_str()).unwrap_or("");
                let binary = ext == "jfr";
                if !(ext == "log"
                    || ext == "jsonl"
                    || name.starts_with("hs_err_")
                    || label == "crashes" && ext == "txt"
                    || include_performance && binary)
                    || secret(&name)
                {
                    continue;
                }
                let len = fs::metadata(&file)?.len();
                let limit = if binary {
                    16 * 1024 * 1024
                } else {
                    8 * 1024 * 1024
                };
                if len > limit || len > budget || binary && recordings >= 2 {
                    continue;
                }
                let mut data = vec![];
                if File::open(&file)
                    .and_then(|f| f.take(len).read_to_end(&mut data))
                    .is_err()
                {
                    continue;
                }
                zip.start_file(format!("{label}/{}", relative(&folder, &file)), options)?;
                if binary {
                    zip.write_all(&data)?;
                    recordings += 1;
                } else {
                    zip.write_all(redact(&String::from_utf8_lossy(&data)).as_bytes())?;
                }
                budget -= data.len() as u64;
            }
        }
        zip.finish()?.sync_all()?;
        Ok(path)
    }
}
