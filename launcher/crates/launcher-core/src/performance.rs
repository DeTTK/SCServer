//! Interval measurements and bounded technical context, shared by local logs and Faro.
use crate::*;
use serde_json::{Value, json};
use std::collections::BTreeMap;
#[derive(Default)]
pub struct PerformanceSampler {
    previous: BTreeMap<String, Value>,
}
fn number(v: &Value, key: &str) -> Option<f64> {
    v[key].as_f64().filter(|x| x.is_finite())
}
impl PerformanceSampler {
    pub fn sample(&mut self, role: &str, mut current: Value) -> Value {
        if !current.is_object() {
            return current;
        }
        let key = format!("{role}:{}", current["pid"]);
        if let Some(old) = self.previous.get(&key) {
            if let (Some(now), Some(before)) =
                (number(&current, "uptimeMs"), number(old, "uptimeMs"))
            {
                let elapsed = now - before;
                if elapsed > 0.0 {
                    let mut window = serde_json::Map::new();
                    window.insert("sampleMs".into(), json!(elapsed));
                    for field in [
                        "frames",
                        "slowFrames",
                        "gcMs",
                        "gcCount",
                        "worldTicks",
                        "slowWorldTicks",
                        "cpuTimeNanos",
                    ] {
                        if let (Some(a), Some(b)) = (number(&current, field), number(old, field)) {
                            if a >= b {
                                window.insert(field.into(), json!(a - b));
                            }
                        }
                    }
                    if let Some(frames) = window.get("frames").and_then(Value::as_f64) {
                        window.insert("fps".into(), json!(frames * 1000.0 / elapsed));
                    }
                    if let Some(ticks) = window.get("worldTicks").and_then(Value::as_f64) {
                        window.insert("worldTps".into(), json!(ticks * 1000.0 / elapsed));
                    }
                    if let Some(cpu) = window.get("cpuTimeNanos").and_then(Value::as_f64) {
                        window.insert("cpuCorePct".into(), json!(cpu / elapsed / 10_000.0));
                    }
                    if let Some(gc) = window.get("gcMs").and_then(Value::as_f64) {
                        window.insert("gcTimePct".into(), json!(gc * 100.0 / elapsed));
                    }
                    current["window"] = Value::Object(window);
                }
            }
        }
        if let (Some(used), Some(max)) = (number(&current, "heapUsed"), number(&current, "heapMax"))
        {
            if max > 0.0 {
                current["heapUsagePct"] = json!(used * 100.0 / max);
            }
        }
        self.previous
            .retain(|k, _| !k.starts_with(&format!("{role}:")) || *k == key);
        self.previous.insert(key, current.clone());
        current
    }
}
pub fn telemetry_context(layout: &Layout, folder: &std::path::Path) -> Value {
    let context: Value = read_json(&folder.join("telemetry-context.json")).unwrap_or(Value::Null);
    if context.is_object() {
        context
    } else {
        json!({"runGroup":relative(&layout.root,folder)})
    }
}
pub fn write_telemetry_context(
    layout: &Layout,
    folder: &std::path::Path,
    group: &str,
) -> Result<()> {
    let settings = layout.load()?;
    let branch = hex::encode(sha2::Sha256::digest(settings.game_branch.as_bytes()));
    let installed: Value =
        read_json(&layout.at(&format!("state/channels/{}/installed.json", &branch[..20])))?;
    let mods = ModManager::new(layout)
        .snapshot()
        .map(|s| {
            s.installed
                .into_iter()
                .take(32)
                .map(|m| json!({"id":m.id,"version":m.version,"enabled":m.enabled}))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    write_json(
        &folder.join("telemetry-context.json"),
        &json!({"runGroup":group,"gameBranch":settings.game_branch,"sourceCommit":installed["sourceCommit"],"worldId":settings.selected_world,"mods":mods,"clientHeapMb":settings.client_memory_mb,"serverHeapMb":settings.server_memory_mb,"launcherVersion":env!("CARGO_PKG_VERSION")}),
    )
}
use sha2::Digest;
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn intervals_do_not_reuse_lifetime_peaks_or_a_previous_process() {
        let mut sampler = PerformanceSampler::default();
        let a = json!({"pid":1,"uptimeMs":1000,"frames":100,"slowFrames":9,"gcMs":50,"gcCount":5,"cpuTimeNanos":1000000000,"heapUsed":80,"heapMax":100});
        assert!(sampler.sample("client", a)["window"].is_null());
        let b=sampler.sample("client",json!({"pid":1,"uptimeMs":6000,"frames":400,"slowFrames":11,"gcMs":100,"gcCount":6,"cpuTimeNanos":3500000000u64,"heapUsed":85,"heapMax":100}));
        assert_eq!(b["window"]["fps"], 60.0);
        assert_eq!(b["window"]["gcTimePct"], 1.0);
        assert_eq!(b["window"]["cpuCorePct"], 50.0);
        assert_eq!(b["window"]["slowFrames"], 2.0);
        assert_eq!(b["heapUsagePct"], 85.0);
        assert!(
            sampler.sample("client", json!({"pid":2,"uptimeMs":9000,"frames":1}))["window"]
                .is_null()
        );
    }
}
