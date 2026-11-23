//! Bounded local delivery queue. Network exporters acknowledge only accepted events.
use crate::*;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{fs, path::PathBuf, sync::Mutex};
static QUEUE_GATE: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TelemetryEvent {
    pub schema_version: u32,
    pub id: String,
    pub created: String,
    pub kind: String,
    pub launcher_version: String,
    pub payload: Value,
}
#[derive(Clone)]
pub struct TelemetryOutbox {
    directory: PathBuf,
}
impl TelemetryOutbox {
    pub fn new(layout: &Layout) -> Self {
        Self {
            directory: layout.at("state/telemetry/outbox"),
        }
    }
    pub fn enqueue(&self, kind: &str, payload: Value) -> Result<String> {
        let _guard = QUEUE_GATE.lock().unwrap_or_else(|e| e.into_inner());
        if kind.is_empty()
            || kind.len() > 64
            || !kind.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
        {
            bail!("TELEMETRY_KIND");
        }
        let id = uuid::Uuid::new_v4().simple().to_string();
        let event = TelemetryEvent {
            schema_version: 1,
            id: id.clone(),
            created: chrono::Utc::now().to_rfc3339(),
            kind: kind.into(),
            launcher_version: env!("CARGO_PKG_VERSION").into(),
            payload: scrub(payload),
        };
        if serde_json::to_vec(&event)?.len() > 65536 {
            bail!("TELEMETRY_LIMIT");
        }
        no_links(&self.directory)?;
        fs::create_dir_all(&self.directory)?;
        let mut files = self.entries()?;
        while files.len() >= 256 {
            fs::remove_file(files.remove(0))?;
        }
        write_json(&self.directory.join(format!("{id}.json")), &event)?;
        Ok(id)
    }
    fn entries(&self) -> Result<Vec<PathBuf>> {
        if !self.directory.exists() {
            return Ok(vec![]);
        }
        no_links(&self.directory)?;
        let mut entries = Vec::new();
        for entry in fs::read_dir(&self.directory)? {
            let path = entry?.path();
            no_links(&path)?;
            if path.extension().is_some_and(|s| s == "json")
                && path
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .is_some_and(|s| uuid::Uuid::parse_str(s).is_ok())
            {
                entries.push(path);
            }
        }
        entries.sort_by_key(|p| fs::metadata(p).and_then(|m| m.modified()).ok());
        Ok(entries)
    }
    pub fn pending(&self) -> Result<Vec<TelemetryEvent>> {
        let _guard = QUEUE_GATE.lock().unwrap_or_else(|e| e.into_inner());
        self.entries()?
            .into_iter()
            .take(256)
            .map(|path| {
                if fs::metadata(&path)?.len() > 65536 {
                    bail!("TELEMETRY_LIMIT");
                }
                let event: TelemetryEvent = serde_json::from_slice(&fs::read(&path)?)?;
                if event.id != path.file_stem().unwrap_or_default().to_string_lossy() {
                    bail!("TELEMETRY_ID");
                }
                Ok(event)
            })
            .collect()
    }
    pub fn acknowledge(&self, id: &str) -> Result<()> {
        let _guard = QUEUE_GATE.lock().unwrap_or_else(|e| e.into_inner());
        let id = uuid::Uuid::parse_str(id)?.simple().to_string();
        let path = self.directory.join(format!("{id}.json"));
        no_links(&path)?;
        if path.exists() {
            fs::remove_file(path)?;
        }
        Ok(())
    }
}
fn scrub(value: Value) -> Value {
    match value {
        Value::String(text) => Value::String(redact(&text)),
        Value::Array(values) => Value::Array(values.into_iter().map(scrub).collect()),
        Value::Object(fields) => Value::Object(
            fields
                .into_iter()
                .map(|(key, value)| {
                    let lower = key.to_ascii_lowercase();
                    let private = lower == "authorization"
                        || ["token", "password", "secret", "privatekey", "session"]
                            .iter()
                            .any(|suffix| lower.ends_with(suffix));
                    (
                        key,
                        if private {
                            Value::String("[redacted]".into())
                        } else {
                            scrub(value)
                        },
                    )
                })
                .collect(),
        ),
        value => value,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn queue_is_bounded_and_rejects_oversized_events() {
        let tmp = tempfile::tempdir().unwrap();
        let layout = Layout::new(tmp.path());
        layout.init().unwrap();
        let queue = TelemetryOutbox::new(&layout);
        assert!(
            queue
                .enqueue("oversized", Value::String("x".repeat(70000)))
                .is_err()
        );
        for index in 0..260 {
            queue
                .enqueue("performance", serde_json::json!({"sample":index}))
                .unwrap();
        }
        assert_eq!(queue.pending().unwrap().len(), 256);
    }
    #[test]
    fn queue_survives_reopen_redacts_and_acknowledges_only_its_own_id() {
        let tmp = tempfile::tempdir().unwrap();
        let layout = Layout::new(tmp.path());
        layout.init().unwrap();
        let queue = TelemetryOutbox::new(&layout);
        let id=queue.enqueue("exception",serde_json::json!({"accessToken":"private-value","message":"Bearer hidden-value","frames":["frame"]})).unwrap();
        let reopened = TelemetryOutbox::new(&layout).pending().unwrap();
        assert_eq!(reopened.len(), 1);
        let json = serde_json::to_string(&reopened).unwrap();
        assert!(!json.contains("private-value"));
        assert!(!json.contains("hidden-value"));
        assert!(queue.acknowledge("../outside").is_err());
        assert_eq!(queue.pending().unwrap().len(), 1);
        queue.acknowledge(&id).unwrap();
        assert!(queue.pending().unwrap().is_empty());
    }
}
