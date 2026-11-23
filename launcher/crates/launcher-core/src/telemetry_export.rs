//! Grafana Faro HTTP adapter. Only the public application Collect URL belongs in a client.
use crate::*;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    fs,
    time::{Duration, Instant},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct TelemetryConfig {
    pub schema_version: u32,
    pub enabled: bool,
    pub faro_url: String,
    pub app_name: String,
    pub environment: String,
}
impl Default for TelemetryConfig {
    fn default() -> Self {
        Self {
            schema_version: 1,
            enabled: false,
            faro_url: String::new(),
            app_name: "BSCPCRAFT".into(),
            environment: "launtest".into(),
        }
    }
}
impl TelemetryConfig {
    pub fn load(layout: &Layout) -> Result<Self> {
        let path = layout.at("config/telemetry.json");
        let config: Self = if path.is_file() {
            no_links(&path)?;
            serde_json::from_slice(&fs::read(path)?)?
        } else {
            serde_json::from_str(include_str!("../../../telemetry-public.json"))?
        };
        if config.enabled {
            config.endpoint()?;
        }
        Ok(config)
    }
    fn endpoint(&self) -> Result<reqwest::Url> {
        let url = reqwest::Url::parse(&self.faro_url)?;
        let local = url.scheme() == "http"
            && matches!(url.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"));
        if self.schema_version != 1
            || !(url.scheme() == "https" || local)
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || !(url.path() == "/collect" || url.path().starts_with("/collect/"))
        {
            bail!("TELEMETRY_ENDPOINT: expected a public Faro Collect URL");
        }
        Ok(url)
    }
}
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeliverySummary {
    pub status: String,
    pub sent_events: usize,
    pub remaining: usize,
    pub bytes_sent: usize,
    pub error: Option<String>,
}

/// Blocking network work; call from spawn_blocking, never from the UI/event loop.
pub fn flush_telemetry(layout: &Layout, budget: Duration) -> Result<DeliverySummary> {
    let config = TelemetryConfig::load(layout)?;
    let outbox = TelemetryOutbox::new(layout);
    let mut summary = DeliverySummary {
        status: if config.enabled {
            "queued"
        } else {
            "not-configured"
        }
        .into(),
        remaining: outbox.pending()?.len(),
        ..Default::default()
    };
    if !config.enabled || summary.remaining == 0 {
        return Ok(summary);
    }
    let endpoint = config.endpoint()?;
    fs::create_dir_all(layout.at("state/telemetry"))?;
    let Ok(_lease) = Lease::acquire(&layout.at("state/telemetry/upload.lock")) else {
        return Ok(summary);
    };
    let client = reqwest::blocking::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(3))
        .user_agent(concat!("BSCPCRAFT/", env!("CARGO_PKG_VERSION")))
        .build()?;
    let started = Instant::now();
    while started.elapsed() < budget {
        let pending = outbox.pending()?;
        let Some(first) = pending.first() else { break };
        let run = event_run(first);
        let mut batch = pending
            .into_iter()
            .filter(|event| event_run(event) == run)
            .take(8)
            .collect::<Vec<_>>();
        let mut body = serde_json::to_vec(&faro_payload(&config, &batch))?;
        while body.len() > 512 * 1024 && batch.len() > 1 {
            batch.truncate((batch.len() / 2).max(1));
            body = serde_json::to_vec(&faro_payload(&config, &batch))?;
        }
        let session = hex::encode(Sha256::digest(run.as_bytes()));
        let result = client
            .post(endpoint.clone())
            .header("Content-Type", "application/json")
            .header("X-Faro-Session-Id", &session[..32])
            .timeout(
                budget
                    .saturating_sub(started.elapsed())
                    .min(Duration::from_secs(3)),
            )
            .body(body.clone())
            .send();
        match result {
            Ok(response) if response.status().is_success() => {
                for event in &batch {
                    outbox.acknowledge(&event.id)?;
                }
                summary.sent_events += batch.len();
                summary.bytes_sent += body.len();
            }
            Ok(response) => {
                summary.error = Some(format!("HTTP {}", response.status().as_u16()));
                break;
            }
            Err(error) => {
                summary.error = Some(
                    if error.is_timeout() {
                        "request-timeout"
                    } else {
                        "network-unavailable"
                    }
                    .into(),
                );
                break;
            }
        }
    }
    summary.remaining = outbox.pending()?.len();
    summary.status = if summary.error.is_some() {
        "retry-pending"
    } else if summary.remaining == 0 {
        "sent"
    } else {
        "queued"
    }
    .into();
    write_json(&layout.at("state/telemetry/delivery.json"), &summary)?;
    Ok(summary)
}
fn event_run(event: &TelemetryEvent) -> String {
    event.payload["run"]
        .as_str()
        .unwrap_or("launcher")
        .replace('\\', "/")
}
fn faro_payload(config: &TelemetryConfig, events: &[TelemetryEvent]) -> Value {
    let mut logs = Vec::new();
    let mut measurements = Vec::new();
    let mut exceptions = Vec::new();
    for event in events {
        let role = event.payload["role"].as_str().unwrap_or("runtime");
        let context = json!({"event_id":event.id,"kind":event.kind,"role":role,"run_id":event_run(event),"file":event.payload["file"].as_str().unwrap_or(""),"game_branch":event.payload["gameBranch"].as_str().unwrap_or("")});
        let message = event.payload["text"]
            .as_str()
            .map(str::to_owned)
            .unwrap_or_else(|| serde_json::to_string(&event.payload).unwrap_or_default());
        let fatal = event.kind == "process-exit" && event.payload["exit"]["success"] != true;
        let is_exception =
            event.kind == "exception" && event.payload["event"]["type"] == "exception";
        logs.push(json!({"message":message,"level":if fatal||is_exception{"error"}else{"info"},"timestamp":event.created,"context":context}));
        if fatal || is_exception {
            let value = if is_exception {
                event.payload["event"]["message"]
                    .as_str()
                    .unwrap_or("Java exception")
            } else {
                event.payload["stderr"]
                    .as_str()
                    .unwrap_or("JVM exited with a nonzero status")
            };
            exceptions.push(json!({"type":if fatal{"bscp.ProcessExit"}else{"bscp.JavaException"},"value":value.chars().take(16000).collect::<String>(),"fatal":fatal,"timestamp":event.created,"context":context}));
        }
        if event.kind == "performance" {
            for source in ["server", "client"] {
                let mut values = serde_json::Map::new();
                for key in [
                    "heapUsed",
                    "heapMax",
                    "gcMs",
                    "gcCount",
                    "slowFrames",
                    "maxFrameMs",
                    "worldTicks",
                    "slowWorldTicks",
                    "maxWorldTickGapMs",
                    "cpuTimeNanos",
                ] {
                    if let Some(value) = event.payload[source].get(key).filter(|v| v.is_number()) {
                        values.insert(key.into(), value.clone());
                    }
                }
                if !values.is_empty() {
                    measurements.push(json!({"type":format!("bscp_{source}"),"values":values,"timestamp":event.created,"context":context}));
                }
            }
            let world = &event.payload["health"]["world"];
            let mut values = serde_json::Map::new();
            for key in [
                "maxTickMs",
                "skippedSlots",
                "failures",
                "queued",
                "maxOwnerWorkMs",
                "tick",
            ] {
                if let Some(value) = world.get(key).filter(|v| v.is_number()) {
                    values.insert(key.into(), value.clone());
                }
            }
            if !values.is_empty() {
                measurements.push(json!({"type":"bscp_world","values":values,"timestamp":event.created,"context":context}));
            }
        }
    }
    let run = events.first().map(event_run).unwrap_or_default();
    let session = hex::encode(Sha256::digest(run.as_bytes()));
    json!({"meta":{"app":{"name":config.app_name,"version":env!("CARGO_PKG_VERSION"),"environment":config.environment},"sdk":{"name":"bscp-native-rust","version":env!("CARGO_PKG_VERSION")},"session":{"id":&session[..32]},"os":{"name":std::env::consts::OS}},"logs":logs,"measurements":measurements,"exceptions":exceptions})
}

/// Snapshot only technical text logs; binary recordings and user screenshots stay in manual reports.
pub fn queue_session_logs(
    layout: &Layout,
    folder: &std::path::Path,
    role: &str,
    exit: Value,
) -> Result<usize> {
    no_links(folder)?;
    let queue = TelemetryOutbox::new(layout);
    let run = relative(&layout.root, folder);
    let mut count = 0;
    let settings = layout.load()?;
    let mut candidates = files(folder)?
        .into_iter()
        .filter(|p| {
            let name = p
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .to_ascii_lowercase();
            !secret(&name)
                && (name.ends_with(".log")
                    || name.ends_with(".jsonl")
                    || name.contains(".log.")
                    || name.contains(".jsonl."))
        })
        .collect::<Vec<_>>();
    candidates
        .sort_by_key(|path| std::cmp::Reverse(fs::metadata(path).and_then(|m| m.modified()).ok()));
    let mut budget = 4 * 1024 * 1024u64;
    for path in candidates.into_iter().take(32) {
        if budget == 0 {
            break;
        }
        let bytes = diagnostic_tail(&path, budget.min(512 * 1024))?;
        budget = budget.saturating_sub(bytes.len() as u64);
        let text = redact(&String::from_utf8_lossy(&bytes));
        let mut start = 0;
        let mut part = 0;
        while start < text.len() {
            let mut end = (start + 24000).min(text.len());
            while !text.is_char_boundary(end) {
                end -= 1;
            }
            queue.enqueue("session-log",json!({"run":run,"role":role,"gameBranch":settings.game_branch,"file":relative(folder,&path),"part":part,"text":&text[start..end]}))?;
            count += 1;
            part += 1;
            start = end;
        }
    }
    queue.enqueue(
        "run-ended",
        json!({"run":run,"role":role,"gameBranch":settings.game_branch,"exit":exit}),
    )?;
    Ok(count + 1)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn receiver(status: u16) -> (String, std::thread::JoinHandle<Value>) {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let task = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut header = Vec::new();
            while !header.ends_with(b"\r\n\r\n") {
                let mut byte = [0];
                stream.read_exact(&mut byte).unwrap();
                header.push(byte[0]);
                assert!(header.len() < 8192);
            }
            let text = String::from_utf8(header).unwrap();
            assert!(text.starts_with("POST /collect/test HTTP/1.1"));
            assert!(!text.to_ascii_lowercase().contains("authorization:"));
            let length = text
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length:")
                        .map(|v| v.trim().parse::<usize>().unwrap())
                })
                .unwrap();
            let mut body = vec![0; length];
            stream.read_exact(&mut body).unwrap();
            let body: Value = serde_json::from_slice(&body).unwrap();
            write!(
                stream,
                "HTTP/1.1 {status} Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            )
            .unwrap();
            body
        });
        (format!("http://127.0.0.1:{port}/collect/test"), task)
    }
    #[test]
    fn failed_receiver_keeps_events_and_successful_retry_acknowledges_them() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        layout.init().unwrap();
        let queue = TelemetryOutbox::new(&layout);
        queue.enqueue("session-log",json!({"run":"unit","role":"client","file":"client-stderr.log","text":format!("{}=private-value","token")})).unwrap();
        let (url, server) = receiver(503);
        let mut config = TelemetryConfig {
            enabled: true,
            faro_url: url,
            ..Default::default()
        };
        write_json(&layout.at("config/telemetry.json"), &config).unwrap();
        let first = flush_telemetry(&layout, Duration::from_secs(3)).unwrap();
        assert_eq!(first.status, "retry-pending");
        assert_eq!(queue.pending().unwrap().len(), 1);
        server.join().unwrap();
        let (url, server) = receiver(204);
        config.faro_url = url;
        write_json(&layout.at("config/telemetry.json"), &config).unwrap();
        let second = flush_telemetry(&layout, Duration::from_secs(3)).unwrap();
        assert_eq!(second.sent_events, 1);
        assert!(queue.pending().unwrap().is_empty());
        let body = server.join().unwrap();
        assert_eq!(body["meta"]["app"]["name"], "BSCPCRAFT");
        assert!(!body.to_string().contains("private-value"));
        assert_eq!(body["logs"][0]["context"]["role"], "client");
    }
    #[test]
    fn public_collect_url_is_required_and_embedded_config_has_no_secret() {
        let mut config = TelemetryConfig {
            enabled: true,
            faro_url: "https://example.grafana.net/collect/public-app".into(),
            ..Default::default()
        };
        assert!(config.endpoint().is_ok());
        config.faro_url = "https://user:private@host/collect/id".into();
        assert!(config.endpoint().is_err());
        config.faro_url = "http://remote.invalid/collect/id".into();
        assert!(config.endpoint().is_err());
    }
    #[test]
    fn native_faro_format_has_session_logs_errors_and_numeric_measurements() {
        let config = TelemetryConfig::default();
        let event = TelemetryEvent {
            schema_version: 1,
            id: "id".into(),
            created: chrono::Utc::now().to_rfc3339(),
            kind: "performance".into(),
            launcher_version: "0.5".into(),
            payload: json!({"run":"state/sessions/run","server":{"heapUsed":12},"health":{"world":{"skippedSlots":3}}}),
        };
        let body = faro_payload(&config, &[event]);
        assert_eq!(body["meta"]["app"]["name"], "BSCPCRAFT");
        assert_eq!(body["logs"].as_array().unwrap().len(), 1);
        assert_eq!(body["measurements"].as_array().unwrap().len(), 2);
        assert!(body["measurements"][0]["values"]["heapUsed"].is_number());
    }
}
