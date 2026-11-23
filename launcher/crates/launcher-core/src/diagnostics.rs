use crate::*;
use anyhow::{Context, Result, bail};
use regex::Regex;
use std::{
    fs::{self, File, OpenOptions},
    io::{BufReader, Read, Seek, SeekFrom, Write},
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
    let private_key = Regex::new(r"(?s)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----").unwrap();
    private_key
        .replace_all(&s, "[private key redacted]")
        .into_owned()
}
pub fn install_panic_logger(layout: &Layout) {
    let directory = layout.at("logs");
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let trace = std::backtrace::Backtrace::force_capture();
        let _ = RotatingLog::new(&directory, "panic").line(&format!("{info}\n{trace}"));
        previous(info);
    }));
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
#[derive(Debug, Clone, Default)]
pub struct BugReportDraft {
    pub title: String,
    pub description: String,
    pub screenshots: Vec<PathBuf>,
    pub include_performance: bool,
}

pub struct Reports;
impl Reports {
    pub fn create(
        layout: &Layout,
        description: &str,
        include_performance: bool,
    ) -> Result<PathBuf> {
        Self::prepare(
            layout,
            &BugReportDraft {
                title: "Диагностика BSCPCRAFT".into(),
                description: description.into(),
                include_performance,
                ..Default::default()
            },
            None,
        )
    }

    pub fn prepare(
        layout: &Layout,
        draft: &BugReportDraft,
        callback: Option<&Progress>,
    ) -> Result<PathBuf> {
        if draft.screenshots.len() > 6 {
            bail!("REPORT_IMAGES: Можно приложить до шести скриншотов.");
        }
        let mut images = Vec::new();
        for (index, source) in draft.screenshots.iter().enumerate() {
            progress(
                callback,
                format!(
                    "Готовим скриншот {} из {}…",
                    index + 1,
                    draft.screenshots.len()
                ),
            );
            images.push(prepare_screenshot(source)?);
        }
        let id = format!(
            "{}-{}",
            chrono::Utc::now().format("%Y%m%d-%H%M%S"),
            &uuid::Uuid::new_v4().simple().to_string()[..8]
        );
        let path = layout.at(format!("reports/incident-{id}.zip"));
        let partial = path.with_extension("zip.part");
        no_links(&path)?;
        no_links(&partial)?;
        fs::create_dir_all(path.parent().unwrap())?;
        let result = (|| -> Result<()> {
            let mut zip = zip::ZipWriter::new(
                OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&partial)?,
            );
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);
            let settings = layout.load()?;
            let title = draft.title.chars().take(140).collect::<String>();
            let description = draft.description.chars().take(32768).collect::<String>();
            zip.start_file("incident.json", options)?;
            zip.write_all(redact(&serde_json::to_string_pretty(&serde_json::json!({
                "schemaVersion":2,"incidentId":id,"created":chrono::Utc::now().to_rfc3339(),
                "title":title,"description":description,"launcherVersion":env!("CARGO_PKG_VERSION"),
                "gameBranch":settings.game_branch,"os":std::env::consts::OS,"architecture":std::env::consts::ARCH,
                "screenshotCount":images.len(),"performanceRequested":draft.include_performance
            }))?).as_bytes())?;
            zip.start_file("report.md", options)?;
            zip.write_all(
                redact(&format!(
                    "# {title}\n\n{description}\n\nLauncher: {}\nBranch: {}\nIncident: {id}\n",
                    env!("CARGO_PKG_VERSION"),
                    settings.game_branch
                ))
                .as_bytes(),
            )?;
            let mut budget = 22 * 1024 * 1024u64;
            let mut included = Vec::new();
            let mut skipped = Vec::new();
            for (index, data) in images.iter().enumerate() {
                let name = format!("screenshots/{:02}.jpg", index + 1);
                zip.start_file(&name, options)?;
                zip.write_all(data)?;
                budget -= data.len() as u64;
                included.push(serde_json::json!({"path":name,"bytes":data.len()}));
            }
            let profile = layout.client_data(&settings).join("profiles/default/game");
            let mut candidates = Vec::new();
            for (folder, label) in [
                (layout.at("logs"), "launcher"),
                (layout.at("state/sessions"), "sessions"),
                (profile.join("logs"), "game"),
                (profile.join("crash-reports"), "crashes"),
            ] {
                if !folder.is_dir() {
                    continue;
                }
                let entries = match files(&folder) {
                    Ok(entries) => entries,
                    Err(_) => {
                        skipped.push(
                            serde_json::json!({"path":label,"reason":"directory_unavailable"}),
                        );
                        continue;
                    }
                };
                for file in entries {
                    let name = file
                        .file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .into_owned();
                    let ext = file.extension().and_then(|v| v.to_str()).unwrap_or("");
                    let rotated = [".log.", ".jsonl."].iter().any(|suffix| {
                        name.rsplit_once(suffix).is_some_and(|(_, n)| {
                            !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit())
                        })
                    });
                    if secret(&name)
                        || !(ext == "log"
                            || ext == "jsonl"
                            || name.starts_with("hs_err_")
                            || rotated
                            || label == "crashes" && ext == "txt"
                            || draft.include_performance && ext == "jfr")
                    {
                        continue;
                    }
                    let archive_name = format!("{label}/{}", relative(&folder, &file));
                    let modified = fs::metadata(&file).and_then(|m| m.modified()).ok();
                    let binary = ext == "jfr";
                    candidates.push((file, archive_name, binary, modified));
                }
            }
            candidates.sort_by_key(|(_, _, _, time)| std::cmp::Reverse(*time));
            let mut recordings = 0;
            let mut text_budget = 4 * 1024 * 1024u64;
            progress(
                callback,
                "Добавляем свежие логи клиента, сервера и лаунчера…",
            );
            for (file, name, binary, _) in candidates.into_iter().take(192) {
                let size = match fs::metadata(&file) {
                    Ok(m) => m.len(),
                    Err(_) => continue,
                };
                let data = if binary {
                    if recordings >= 1 || size > 12 * 1024 * 1024 || size + 512 * 1024 > budget {
                        skipped
                            .push(serde_json::json!({"path":name,"reason":"performance_budget"}));
                        continue;
                    }
                    let mut bytes = Vec::new();
                    File::open(&file)?.take(size).read_to_end(&mut bytes)?;
                    recordings += 1;
                    bytes
                } else {
                    let limit = std::cmp::min(512 * 1024, std::cmp::min(text_budget, budget));
                    if limit == 0 {
                        skipped.push(serde_json::json!({"path":name,"reason":"text_budget"}));
                        continue;
                    }
                    let bytes = match diagnostic_tail(&file, limit) {
                        Ok(bytes) => bytes,
                        Err(_) => {
                            skipped
                                .push(serde_json::json!({"path":name,"reason":"file_unavailable"}));
                            continue;
                        }
                    };
                    if size > limit {
                        skipped.push(serde_json::json!({"path":name,"reason":"tail_only","originalBytes":size}));
                    }
                    let bytes = redact(&String::from_utf8_lossy(&bytes)).into_bytes();
                    text_budget = text_budget.saturating_sub(bytes.len() as u64);
                    bytes
                };
                if data.len() as u64 > budget {
                    continue;
                }
                zip.start_file(&name, options)?;
                zip.write_all(&data)?;
                budget -= data.len() as u64;
                included.push(serde_json::json!({"path":name,"bytes":data.len()}));
            }
            zip.start_file("collection.json", options)?;
            zip.write_all(serde_json::to_string_pretty(&serde_json::json!({"included":included,"skipped":skipped,"secretsRedacted":true,"imageMetadataRemoved":true} ))?.as_bytes())?;
            zip.finish()?.sync_all()?;
            fs::rename(&partial, &path)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&partial);
        }
        result?;
        Ok(path)
    }

    pub fn issue_url(draft: &BugReportDraft, archive: &Path) -> Result<String> {
        let mut url = reqwest::Url::parse("https://github.com/DeTTK/SCServer/issues/new")?;
        let description = draft.description.chars().take(1400).collect::<String>();
        let body = format!(
            "<!-- bscp-player-report:v1 -->\n### Что произошло\n{description}\n\n### Диагностика\nBSCPCRAFT {}\nАрхив: `{}`\n\nПеретащите подготовленный ZIP в эту форму: он содержит полное описание, скриншоты и автоматически собранные логи.\n",
            env!("CARGO_PKG_VERSION"),
            archive.file_name().unwrap_or_default().to_string_lossy()
        );
        url.query_pairs_mut()
            .append_pair(
                "title",
                &format!(
                    "[Игрок] {}",
                    draft.title.chars().take(140).collect::<String>()
                ),
            )
            .append_pair("body", &body)
            .append_pair("labels", "player-report");
        Ok(url.to_string())
    }
}

pub fn diagnostic_tail(path: &Path, limit: u64) -> Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let length = file.metadata()?.len();
    let start = length.saturating_sub(limit);
    file.seek(SeekFrom::Start(start))?;
    let mut bytes = Vec::new();
    file.take(limit).read_to_end(&mut bytes)?;
    if start > 0 {
        if let Some(newline) = bytes.iter().position(|b| *b == b'\n') {
            bytes.drain(..=newline);
        } else {
            bytes = b"[Log line exceeded report tail limit]\n".to_vec();
        }
    }
    Ok(bytes)
}

fn prepare_screenshot(path: &Path) -> Result<Vec<u8>> {
    let path = fs::canonicalize(path).context("REPORT_IMAGES: Скриншот недоступен.")?;
    no_links(&path)?;
    if fs::metadata(&path)?.len() > 8 * 1024 * 1024 {
        bail!("REPORT_IMAGES: Скриншот должен быть не больше 8 МБ.");
    }
    let mut reader =
        image::ImageReader::new(BufReader::new(File::open(&path)?)).with_guessed_format()?;
    if !matches!(
        reader.format(),
        Some(image::ImageFormat::Png | image::ImageFormat::Jpeg | image::ImageFormat::WebP)
    ) {
        bail!("REPORT_IMAGES: Выберите PNG, JPEG или WebP.");
    }
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(7680);
    limits.max_image_height = Some(4320);
    limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    let decoded = reader
        .decode()
        .context("REPORT_IMAGES: Не удалось прочитать изображение.")?;
    let image = if decoded.width() > 2560 || decoded.height() > 1440 {
        decoded.thumbnail(2560, 1440)
    } else {
        decoded
    }
    .to_rgb8();
    for quality in [88, 75, 60] {
        let mut bytes = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, quality)
            .encode_image(&image)?;
        if bytes.len() <= 1024 * 1024 {
            return Ok(bytes);
        }
    }
    bail!("REPORT_IMAGES: Изображение слишком большое для отчёта.")
}

#[cfg(test)]
mod panic_tests {
    use super::*;
    #[test]
    fn panic_hook_preserves_redacted_diagnostics_before_process_exit() {
        if let Some(root) = std::env::var_os("BSCP_PANIC_TEST_ROOT") {
            install_panic_logger(&Layout::new(root));
            panic!("diagnostic fixture {}={}", "token", "fixture-value");
        }
        let temp = tempfile::tempdir().unwrap();
        let output=std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact","diagnostics::panic_tests::panic_hook_preserves_redacted_diagnostics_before_process_exit","--nocapture"])
            .env("BSCP_PANIC_TEST_ROOT",temp.path()).output().unwrap();
        assert!(!output.status.success());
        let text = std::fs::read_to_string(temp.path().join("logs/panic.jsonl")).unwrap();
        assert!(text.contains("diagnostic fixture"));
        assert!(!text.contains("fixture-value"));
    }
}
