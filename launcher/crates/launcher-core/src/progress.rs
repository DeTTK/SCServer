use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, VecDeque},
    sync::Mutex,
    time::{Duration, Instant},
};

#[derive(Clone, Debug)]
pub enum ProgressUpdate {
    Message(String),
    Transfer(TransferSnapshot),
}
impl From<String> for ProgressUpdate {
    fn from(s: String) -> Self {
        Self::Message(s)
    }
}
impl From<&str> for ProgressUpdate {
    fn from(s: &str) -> Self {
        Self::Message(s.into())
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferSnapshot {
    pub operation: String,
    pub sequence: u64,
    pub phase: String,
    pub title: String,
    pub total_known: bool,
    /// Response body bytes received during this operation, not resumed bytes.
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    pub remaining_bytes: u64,
    pub cached_bytes: u64,
    pub reused_bytes: u64,
    pub bytes_per_second: f64,
    pub eta_seconds: Option<u64>,
    pub files_done: usize,
    pub files_total: usize,
}
impl TransferSnapshot {
    pub fn fraction(&self) -> f32 {
        match self.phase.as_str() {
            "complete" => 1.0,
            "download" if self.total_known => {
                if self.total_bytes == 0 {
                    1.0
                } else {
                    self.downloaded_bytes as f32 / self.total_bytes as f32
                }
            }
            "install" if self.files_total > 0 => self.files_done as f32 / self.files_total as f32,
            _ => -1.0,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub enum DownloadEvent {
    Available(u64),
    Received { bytes: u64, available: u64 },
}
pub type DownloadObserver<'a> = dyn Fn(DownloadEvent) + Send + Sync + 'a;

struct State {
    snapshot: TransferSnapshot,
    items: HashMap<String, (u64, u64, u64)>,
    samples: VecDeque<(Instant, u64)>,
    emitted: Option<Instant>,
}
pub struct TransferTracker<'a> {
    state: Mutex<State>,
    callback: Option<&'a crate::Progress>,
}
impl<'a> TransferTracker<'a> {
    pub fn new(callback: Option<&'a crate::Progress>) -> Self {
        Self {
            callback,
            state: Mutex::new(State {
                snapshot: TransferSnapshot {
                    operation: uuid::Uuid::new_v4().simple().to_string(),
                    ..Default::default()
                },
                items: HashMap::new(),
                samples: VecDeque::new(),
                emitted: None,
            }),
        }
    }
    fn update(&self, force: bool, edit: impl FnOnce(&mut State)) {
        let value = {
            let mut s = self.state.lock().unwrap();
            edit(&mut s);
            if !force
                && s.emitted
                    .is_some_and(|v| v.elapsed() < Duration::from_millis(200))
            {
                return;
            }
            let now = Instant::now();
            s.emitted = Some(now);
            s.snapshot.remaining_bytes = s
                .items
                .values()
                .map(|(size, available, _)| size.saturating_sub(*available))
                .sum();
            s.snapshot.total_bytes = s
                .snapshot
                .downloaded_bytes
                .saturating_add(s.snapshot.remaining_bytes);
            let received = s.snapshot.downloaded_bytes;
            s.samples.push_back((now, received));
            while s.samples.len() > 2 && now.duration_since(s.samples[1].0) > Duration::from_secs(5)
            {
                s.samples.pop_front();
            }
            let rate = s
                .samples
                .front()
                .map(|(time, count)| {
                    let seconds = now.duration_since(*time).as_secs_f64();
                    if seconds >= 0.25 {
                        received.saturating_sub(*count) as f64 / seconds
                    } else {
                        0.0
                    }
                })
                .unwrap_or(0.0);
            s.snapshot.bytes_per_second = if s.snapshot.phase == "download" {
                rate
            } else {
                0.0
            };
            s.snapshot.eta_seconds = if s.snapshot.phase == "download" && rate >= 1.0 {
                Some((s.snapshot.remaining_bytes as f64 / rate).ceil() as u64)
            } else {
                None
            };
            s.snapshot.sequence += 1;
            s.snapshot.clone()
        };
        if let Some(callback) = self.callback {
            callback(ProgressUpdate::Transfer(value));
        }
    }
    pub fn stage(&self, phase: &str, title: &str, files: usize) {
        self.update(true, |s| {
            if s.snapshot.phase != "download" && phase == "download" {
                s.samples.clear();
                s.samples
                    .push_back((Instant::now(), s.snapshot.downloaded_bytes));
            }
            s.snapshot.phase = phase.into();
            s.snapshot.title = title.into();
            if phase != "verify" {
                s.snapshot.files_done = 0;
                s.snapshot.files_total = files;
            }
        });
    }
    pub fn item(&self, id: &str, size: u64, available: u64) {
        self.update(false, |s| {
            if !s.items.contains_key(id) {
                s.snapshot.cached_bytes += available.min(size);
                s.items
                    .insert(id.into(), (size, available.min(size), available.min(size)));
            }
        });
    }
    pub fn planned(&self) {
        self.update(true, |s| s.snapshot.total_known = true);
    }
    pub fn observe(&self, id: &str, event: DownloadEvent) {
        self.update(matches!(event, DownloadEvent::Available(_)), |s| {
            if let Some((size, available, cached)) = s.items.get_mut(id) {
                match event {
                    DownloadEvent::Available(value) => {
                        *available = value.min(*size);
                        let retained = (*cached).min(*available);
                        s.snapshot.cached_bytes =
                            s.snapshot.cached_bytes.saturating_sub(*cached - retained);
                        *cached = retained;
                    }
                    DownloadEvent::Received {
                        bytes,
                        available: value,
                    } => {
                        *available = value.min(*size);
                        s.snapshot.downloaded_bytes =
                            s.snapshot.downloaded_bytes.saturating_add(bytes);
                    }
                }
            }
        });
    }
    pub fn reused(&self, bytes: u64) {
        self.update(false, |s| {
            s.snapshot.reused_bytes = s.snapshot.reused_bytes.saturating_add(bytes)
        });
    }
    pub fn file_done(&self) {
        self.update(false, |s| {
            s.snapshot.files_done = (s.snapshot.files_done + 1).min(s.snapshot.files_total)
        });
    }
    pub fn complete(&self) {
        self.update(true, |s| {
            s.snapshot.total_known = true;
            s.snapshot.phase = "complete".into();
            s.snapshot.title = "Игра установлена".into();
            s.snapshot.files_done = s.snapshot.files_total;
        });
    }
    pub fn snapshot(&self) -> TransferSnapshot {
        let state = self.state.lock().unwrap();
        let mut value = state.snapshot.clone();
        value.remaining_bytes = state
            .items
            .values()
            .map(|(size, available, _)| size.saturating_sub(*available))
            .sum();
        value.total_bytes = value.downloaded_bytes.saturating_add(value.remaining_bytes);
        value
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn totals_are_global_and_disk_bytes_are_not_network_traffic() {
        let t = TransferTracker::new(None);
        t.item("a", 100, 20);
        t.item("b", 80, 80);
        t.planned();
        t.stage("download", "download", 0);
        t.observe("a", DownloadEvent::Available(20));
        t.observe(
            "a",
            DownloadEvent::Received {
                bytes: 30,
                available: 50,
            },
        );
        t.planned();
        let s = t.snapshot();
        assert_eq!(
            (
                s.downloaded_bytes,
                s.remaining_bytes,
                s.total_bytes,
                s.cached_bytes
            ),
            (30, 50, 80, 100)
        );
        t.observe("a", DownloadEvent::Available(0));
        t.planned();
        let s = t.snapshot();
        assert_eq!(
            (s.downloaded_bytes, s.remaining_bytes, s.total_bytes),
            (30, 100, 130)
        );
        t.observe(
            "a",
            DownloadEvent::Received {
                bytes: 100,
                available: 100,
            },
        );
        t.planned();
        let s = t.snapshot();
        assert_eq!(
            (s.downloaded_bytes, s.remaining_bytes, s.total_bytes),
            (130, 0, 130)
        );
        assert_eq!(s.fraction(), 1.0);
    }
    #[test]
    fn completed_cache_only_install_has_no_fictitious_traffic() {
        let t = TransferTracker::new(None);
        t.item("cached", 400, 400);
        t.planned();
        t.reused(1000);
        t.stage("install", "install", 2);
        t.file_done();
        t.file_done();
        t.complete();
        let s = t.snapshot();
        assert_eq!(
            (s.downloaded_bytes, s.remaining_bytes, s.reused_bytes),
            (0, 0, 1000)
        );
        assert_eq!(s.fraction(), 1.0);
        assert_eq!(s.eta_seconds, None);
    }
    #[test]
    fn speed_and_eta_use_the_remaining_global_bytes() {
        let t = TransferTracker::new(None);
        t.item("a", 100, 0);
        t.stage("download", "download", 0);
        {
            let mut state = t.state.lock().unwrap();
            state.samples.clear();
            state
                .samples
                .push_back((Instant::now() - Duration::from_secs(2), 0));
        }
        t.observe(
            "a",
            DownloadEvent::Received {
                bytes: 20,
                available: 20,
            },
        );
        t.planned();
        let s = t.snapshot();
        assert!(s.bytes_per_second > 9.0 && s.bytes_per_second < 11.0);
        assert!((8..=10).contains(&s.eta_seconds.unwrap()));
    }
}
