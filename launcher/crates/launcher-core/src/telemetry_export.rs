//! Grafana Faro HTTP adapter. Only the public application Collect URL belongs in a client.
use crate::*;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Read,
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
            environment: "production".into(),
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
    collect_launcher_panics(layout)?;
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
    event.payload["runGroup"]
        .as_str()
        .or_else(|| event.payload["context"]["runGroup"].as_str())
        .or_else(|| event.payload["run"].as_str())
        .unwrap_or("launcher")
        .replace('\\', "/")
}
fn faro_payload(config: &TelemetryConfig, events: &[TelemetryEvent]) -> Value {
    let mut logs = Vec::new();
    let mut measurements = Vec::new();
    let mut exceptions = Vec::new();
    for event in events {
        let role = event.payload["role"].as_str().unwrap_or("runtime");
        let lag = chrono::DateTime::parse_from_rfc3339(&event.created)
            .ok()
            .map(|t| {
                (chrono::Utc::now() - t.with_timezone(&chrono::Utc))
                    .num_milliseconds()
                    .max(0)
            })
            .unwrap_or(0);
        let mut context = json!({"event_id":event.id,"kind":event.kind,"role":role,"run_id":event.payload["run"].as_str().unwrap_or("launcher"),"run_group":event_run(event),"sample_time":event.created,"delivery_lag_ms":lag.to_string(),"file":event.payload["file"].as_str().unwrap_or(""),"game_branch":event.payload["gameBranch"].as_str().or_else(||event.payload["context"]["gameBranch"].as_str()).unwrap_or(""),"source_commit":event.payload["context"]["sourceCommit"].as_str().unwrap_or("")});
        let launcher_error = event.kind == "launcher-error";
        if launcher_error {
            for (field, key) in [
                ("component", "component"),
                ("operation", "operation"),
                ("error_code", "errorCode"),
            ] {
                context[field] = json!(event.payload[key].as_str().unwrap_or(""));
            }
        }
        if event.kind == "process-exit" {
            for (field, key) in [
                ("launch_phase", "phase"),
                ("exit_code", "exitCode"),
                ("exit_code_hex", "exitCodeHex"),
                ("process_exit_time", "exitTime"),
                ("process_uptime_ms", "uptimeMs"),
                ("process_pid", "pid"),
                ("readiness_reached", "readinessReached"),
            ] {
                let value = &event.payload["exit"][key];
                if !value.is_null() {
                    context[field] = json!(
                        value
                            .as_str()
                            .map_or_else(|| value.to_string(), str::to_owned)
                    );
                }
            }
        }
        let message = event.payload["text"]
            .as_str()
            .map(str::to_owned)
            .unwrap_or_else(|| serde_json::to_string(&event.payload).unwrap_or_default());
        let fatal = matches!(event.kind.as_str(), "native-crash" | "launcher-panic")
            || event.kind == "process-exit"
                && event.payload["exit"]["success"] != true
                && event.payload["hasNativeCrash"] != true;
        let is_exception =
            event.kind == "exception" && event.payload["event"]["type"] == "exception";
        logs.push(json!({"message":message,"level":if fatal||is_exception||launcher_error{"error"}else if event.kind=="performance-incident"{"warn"}else{"info"},"timestamp":event.created,"context":context}));
        if fatal || is_exception || launcher_error {
            let value = if launcher_error || event.kind == "launcher-panic" {
                event.payload["message"]
                    .as_str()
                    .unwrap_or("Launcher error")
            } else if event.kind == "native-crash" {
                event.payload["text"].as_str().unwrap_or("Native JVM crash")
            } else if is_exception {
                event.payload["event"]["message"]
                    .as_str()
                    .unwrap_or("Java exception")
            } else {
                event.payload["stderr"]
                    .as_str()
                    .filter(|text| !text.trim().is_empty())
                    .or_else(|| {
                        event.payload["stdout"]
                            .as_str()
                            .filter(|text| !text.trim().is_empty())
                    })
                    .or_else(|| event.payload["exit"]["error"].as_str())
                    .unwrap_or("JVM exited with a nonzero status")
            };
            exceptions.push(json!({"type":if launcher_error{"bscp.LauncherError"}else if event.kind=="launcher-panic"{"bscp.LauncherPanic"}else if event.kind=="native-crash"{"bscp.NativeCrash"}else if fatal{"bscp.ProcessExit"}else{"bscp.JavaException"},"value":value.chars().take(16000).collect::<String>(),"fatal":fatal,"timestamp":event.created,"context":context}));
        }
        if matches!(event.kind.as_str(), "performance" | "performance-incident") {
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
                    "heapUsagePct",
                    "recentFps",
                    "recentMaxFrameMs",
                    "frameP95UpperMs",
                    "frameWindowSeconds",
                    "frameAgeMs",
                    "diagnosticCaptureMs",
                    "rssBytes",
                    "privateBytes",
                    "nonHeapUsed",
                    "systemAvailableBytes",
                    "systemTotalBytes",
                ] {
                    if let Some(value) = event.payload[source].get(key).filter(|v| v.is_number()) {
                        values.insert(key.into(), value.clone());
                    }
                }
                for (field, name) in [
                    ("fps", "fps"),
                    ("gcMs", "gcWindowMs"),
                    ("gcTimePct", "gcTimePct"),
                    ("cpuCorePct", "cpuCorePct"),
                    ("slowFrames", "slowFramesWindow"),
                    ("sampleMs", "sampleMs"),
                    ("worldTps", "worldTps"),
                ] {
                    if let Some(v) = event.payload[source]["window"][field]
                        .as_f64()
                        .filter(|v| v.is_finite())
                    {
                        values.insert(name.into(), json!(v));
                    }
                }
                if !values.is_empty() {
                    let mut source_context = context.clone();
                    source_context["role"] = json!(source);
                    measurements.push(json!({"type":format!("bscp_{source}"),"values":values,"timestamp":event.created,"context":source_context}));
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
            for source in ["native", "chunks", "window", "actors"] {
                let values = event.payload["health"][source]
                    .as_object()
                    .map(|m| {
                        m.iter()
                            .filter(|(_, v)| v.is_number())
                            .map(|(k, v)| (k.clone(), v.clone()))
                            .collect::<serde_json::Map<_, _>>()
                    })
                    .unwrap_or_default();
                if !values.is_empty() {
                    let mut c = context.clone();
                    c["role"] = json!("server");
                    measurements.push(json!({"type":format!("bscp_{source}"),"values":values,"timestamp":event.created,"context":c}));
                }
            }
        }
    }
    let run = events.first().map(event_run).unwrap_or_default();
    let session = hex::encode(Sha256::digest(run.as_bytes()));
    json!({"meta":{"app":{"name":config.app_name,"version":env!("CARGO_PKG_VERSION"),"environment":config.environment},"sdk":{"name":"bscp-native-rust","version":env!("CARGO_PKG_VERSION")},"session":{"id":&session[..32]},"os":{"name":std::env::consts::OS}},"logs":logs,"measurements":measurements,"exceptions":exceptions})
}

/// Snapshot only technical text logs; binary recordings and user screenshots stay in manual reports.
fn native_crash_excerpt(raw: &str) -> String {
    let mut output = Vec::new();
    for line in raw.lines().take(80) {
        if line.starts_with("Registers:")
            || line.starts_with("VM Arguments:")
            || line.starts_with("Environment Variables:")
        {
            break;
        }
        if !line.starts_with("Command Line:") {
            output.push(line);
        }
    }
    let mut stack = false;
    let mut count = 0;
    for line in raw.lines() {
        if line.starts_with("Native frames:") || line.starts_with("Java frames:") {
            stack = true;
            count = 0;
        }
        if stack {
            if line.trim().is_empty() || count >= 64 {
                stack = false;
            } else {
                output.push(line);
                count += 1;
            }
        }
    }
    output.join("\n").chars().take(16000).collect()
}
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
    let context = telemetry_context(layout, folder);
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
    candidates.sort_by_key(|path| {
        let name = path.file_name().unwrap_or_default().to_string_lossy();
        let priority = if name.starts_with("hs_err_") {
            0
        } else if name == "performance-window.jsonl" {
            1
        } else if name.contains("stderr") {
            2
        } else if name.contains("threads") {
            3
        } else if name.contains("stdout") {
            4
        } else {
            5
        };
        (
            priority,
            std::cmp::Reverse(fs::metadata(path).and_then(|m| m.modified()).ok()),
        )
    });
    let mut budget = 4 * 1024 * 1024u64;
    for path in candidates.into_iter().take(32) {
        if budget == 0 {
            break;
        }
        let native = path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .starts_with("hs_err_");
        let bytes = if native {
            let mut bytes = Vec::new();
            fs::File::open(&path)?.take(48000).read_to_end(&mut bytes)?;
            bytes
        } else {
            diagnostic_tail(&path, budget.min(512 * 1024))?
        };
        budget = budget.saturating_sub(bytes.len() as u64);
        let raw = String::from_utf8_lossy(&bytes);
        let text = redact(&if native {
            native_crash_excerpt(&raw)
        } else {
            raw.into_owned()
        });
        let mut start = 0;
        let mut part = 0;
        while start < text.len() {
            let mut end = (start + 24000).min(text.len());
            while !text.is_char_boundary(end) {
                end -= 1;
            }
            queue.enqueue(if native&&part==0{"native-crash"}else{"session-log"},json!({"run":run,"runGroup":context["runGroup"],"context":context,"role":role,"gameBranch":settings.game_branch,"file":relative(folder,&path),"part":part,"text":&text[start..end]}))?;
            count += 1;
            part += 1;
            start = end;
        }
    }
    queue.enqueue(
        "run-ended",
        json!({"run":run,"runGroup":context["runGroup"],"context":context,"role":role,"gameBranch":settings.game_branch,"exit":exit}),
    )?;
    let last = fs::read_to_string(folder.join("performance-window.jsonl"))
        .ok()
        .and_then(|s| {
            s.lines()
                .rev()
                .filter_map(|l| serde_json::from_str::<Value>(l).ok())
                .find(|v| v[role].is_object())
        })
        .unwrap_or(Value::Null);
    let summary = json!({"run":run,"runGroup":context["runGroup"],"context":context,"role":role,"exit":exit,"logParts":count,"lastSampleTime":last["time"],"client":last["client"],"server":last["server"],"health":last["health"],"metricsErrors":last["errors"]});
    queue.enqueue("run-summary", summary)?;
    Ok(count + 2)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_crash_keeps_problem_and_stack_without_environment_or_register_dump() {
        let text = "# JVM fatal error\n# Problematic frame: Unsafe_PutLong\nCommand Line: private\nRegisters:\nPRIVATE REGISTER DATA\n\nNative frames:\nV Unsafe_PutLong\n\nJava frames:\nj bscp.qa.StressDriver.run\n\nVM Arguments:\nprivate arguments\nEnvironment Variables:\nprivate environment";
        let excerpt = native_crash_excerpt(text);
        assert!(excerpt.contains("Problematic frame"));
        assert!(excerpt.contains("StressDriver.run"));
        assert!(!excerpt.contains("private"));
        assert!(!excerpt.contains("PRIVATE REGISTER"));
    }
    #[test]
    fn interval_metrics_have_real_role_and_client_server_share_run_group() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        let queue = TelemetryOutbox::new(&layout);
        queue.enqueue("performance",json!({"run":"server-a","runGroup":"combined","client":{"heapUsed":123,"window":{"fps":42,"gcMs":30,"cpuCorePct":180}},"health":{"chunks":{"dirtyChunks":5},"window":{"nativeTps":19.5}}})).unwrap();
        let payload = faro_payload(&TelemetryConfig::default(), &queue.pending().unwrap());
        let m = payload["measurements"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["type"] == "bscp_client")
            .unwrap();
        assert_eq!(m["context"]["role"], "client");
        assert_eq!(m["values"]["fps"], 42.0);
        assert_eq!(m["values"]["gcWindowMs"], 30.0);
        assert!(
            payload["measurements"]
                .as_array()
                .unwrap()
                .iter()
                .any(|m| m["type"] == "bscp_chunks" && m["values"]["dirtyChunks"] == 5)
        );
        let session = payload["meta"]["session"]["id"].clone();
        for e in queue.pending().unwrap() {
            queue.acknowledge(&e.id).unwrap();
        }
        queue
            .enqueue(
                "run-ended",
                json!({"run":"client-b","runGroup":"combined","role":"client"}),
            )
            .unwrap();
        assert_eq!(
            faro_payload(&TelemetryConfig::default(), &queue.pending().unwrap())["meta"]["session"]
                ["id"],
            session
        );
    }
    fn receiver(status: u16) -> (String, std::thread::JoinHandle<Value>) {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let task = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(30)))
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
        let first = flush_telemetry(&layout, Duration::from_secs(30)).unwrap();
        assert_eq!(first.status, "retry-pending");
        assert_eq!(queue.pending().unwrap().len(), 1);
        server.join().unwrap();
        let (url, server) = receiver(204);
        config.faro_url = url;
        write_json(&layout.at("config/telemetry.json"), &config).unwrap();
        let second = flush_telemetry(&layout, Duration::from_secs(30)).unwrap();
        assert_eq!(second.sent_events, 1);
        assert!(queue.pending().unwrap().is_empty());
        let body = server.join().unwrap();
        assert_eq!(body["meta"]["app"]["name"], "BSCPCRAFT");
        assert!(!body.to_string().contains("private-value"));
        assert_eq!(body["logs"][0]["context"]["role"], "client");
    }
    #[test]
    fn launcher_failure_is_delivered_with_cause_and_cancellation_is_local() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        layout.init().unwrap();
        let (url, server) = receiver(204);
        let config = TelemetryConfig {
            enabled: true,
            faro_url: url,
            ..Default::default()
        };
        write_json(&layout.at("config/telemetry.json"), &config).unwrap();
        let failure = anyhow::Error::new(std::io::Error::from_raw_os_error(10048))
            .context("SERVER_PORT_BIND: game UDP port 29547");
        record_launcher_error(&layout, "ui", "start-server", &failure).unwrap();
        let cancelled = anyhow::anyhow!("CANCELLED: user cancelled installation");
        record_launcher_error(&layout, "ui", "install", &cancelled).unwrap();
        let queue = TelemetryOutbox::new(&layout);
        let pending = queue.pending().unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].payload["osError"], 10048);
        assert!(layout.at("logs/launcher-errors.jsonl").is_file());
        assert_eq!(
            flush_telemetry(&layout, Duration::from_secs(10))
                .unwrap()
                .sent_events,
            1
        );
        let body = server.join().unwrap();
        assert_eq!(body["logs"][0]["level"], "error");
        let context = &body["logs"][0]["context"];
        assert_eq!(context["role"], "launcher");
        assert_eq!(context["component"], "ui");
        assert_eq!(context["operation"], "start-server");
        assert_eq!(context["error_code"], "SERVER_PORT_BIND");
        assert_eq!(body["exceptions"][0]["type"], "bscp.LauncherError");
        assert!(
            body["exceptions"][0]["value"]
                .as_str()
                .unwrap()
                .contains("game UDP port 29547")
        );
        assert!(queue.pending().unwrap().is_empty());
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

    #[test]
    fn faro_process_exit_uses_correlated_phase_code_and_available_console_tail() {
        let event = TelemetryEvent {
            schema_version: 1,
            id: "exit-1".into(),
            created: chrono::Utc::now().to_rfc3339(),
            kind: "process-exit".into(),
            launcher_version: "0.5".into(),
            payload: json!({
                "role": "client",
                "run": "state/sessions/client-1",
                "runGroup": "state/sessions/server-1",
                "exit": {
                    "success": false, "exitCode": -1073741819, "exitCodeHex": "0xC0000005",
                    "phase": "jvm-starting", "pid": 42, "readinessReached": false
                },
                "stderr": " ",
                "stdout": "JVM probe"
            }),
        };
        let body = faro_payload(&TelemetryConfig::default(), &[event]);
        let error = &body["exceptions"][0];
        assert_eq!(error["value"], "JVM probe");
        assert_eq!(error["context"]["launch_phase"], "jvm-starting");
        assert_eq!(error["context"]["exit_code"], "-1073741819");
        assert_eq!(error["context"]["exit_code_hex"], "0xC0000005");
        assert_eq!(error["context"]["process_pid"], "42");
        assert_eq!(error["context"]["readiness_reached"], "false");
        assert_eq!(error["context"]["run_group"], "state/sessions/server-1");
    }
}
