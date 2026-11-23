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
    #[serde(default)]
    pub disk_stage: String,
    #[serde(default)]
    pub disk_done_bytes: u64,
    #[serde(default)]
    pub disk_total_bytes: Option<u64>,
    pub files_done: usize,
    pub files_total: usize,
}
impl TransferSnapshot {
    pub fn fraction(&self) -> f32 {
        if !self.total_known {
            -1.0
        } else if self.total_bytes == 0 {
            1.0
        } else {
            (self.downloaded_bytes as f64 / self.total_bytes as f64).clamp(0.0, 1.0) as f32
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub enum DownloadEvent {
    Available(u64),
    Written {
        available: u64,
    },
    Activity {
        done: u64,
        total: Option<u64>,
        stage: &'static str,
    },
    NetworkWait {
        seconds: u64,
    },
    Received {
        bytes: u64,
        available: u64,
    },
}
pub type DownloadObserver<'a> = dyn Fn(DownloadEvent) + Send + Sync + 'a;

struct State {
    snapshot: TransferSnapshot,
    stage_title: String,
    waiting: bool,
    writing_download: bool,
    items: HashMap<String, (u64, u64, u64)>,
    samples: VecDeque<(Instant, u64)>,
    emitted: Option<Instant>,
}
impl State {
    fn disk(&mut self, stage: &str, done: u64, total: Option<u64>, writing_download: bool) {
        if self.snapshot.disk_stage != stage {
            self.snapshot.disk_stage.clear();
            self.snapshot.disk_stage.push_str(stage);
        }
        self.snapshot.disk_done_bytes = done;
        self.snapshot.disk_total_bytes = total;
        self.writing_download = writing_download;
    }
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
                stage_title: String::new(),
                waiting: false,
                writing_download: false,
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
            let streaming = s.snapshot.phase == "download"
                && !s.waiting
                && (s.snapshot.disk_stage.is_empty() || s.writing_download)
                && s.snapshot.remaining_bytes > 0;
            s.snapshot.bytes_per_second = if streaming { rate } else { 0.0 };
            s.snapshot.eta_seconds = if streaming && rate >= 1.0 {
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
            s.stage_title = title.into();
            s.waiting = false;
            s.disk("", 0, None, false);
            if phase == "checking" {
                s.snapshot.total_known = false;
            }
            s.snapshot.files_done = 0;
            s.snapshot.files_total = files;
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
            if s.waiting && !matches!(event, DownloadEvent::NetworkWait { .. }) {
                s.snapshot.title.clone_from(&s.stage_title);
                s.waiting = false;
            }
            match event {
                DownloadEvent::NetworkWait { seconds } => {
                    s.waiting = true;
                    s.snapshot.title = format!("Ожидаем данные от сервера · {seconds} с");
                    s.disk("", 0, None, false);
                }
                DownloadEvent::Activity { done, total, stage } => s.disk(stage, done, total, false),
                DownloadEvent::Written { available } => {
                    if let Some(&(size, _, _)) = s.items.get(id) {
                        s.disk("Запись загрузки", available.min(size), Some(size), true);
                    }
                }
                DownloadEvent::Available(value) => {
                    s.disk("", 0, None, false);
                    if let Some((size, available, cached)) = s.items.get_mut(id) {
                        *available = value.min(*size);
                        let retained = (*cached).min(*available);
                        s.snapshot.cached_bytes =
                            s.snapshot.cached_bytes.saturating_sub(*cached - retained);
                        *cached = retained;
                    }
                }
                DownloadEvent::Received {
                    bytes,
                    available: value,
                } => {
                    if !s.writing_download {
                        s.disk("", 0, None, false);
                    }
                    if let Some((size, available, _)) = s.items.get_mut(id) {
                        *available = value.min(*size);
                        s.snapshot.downloaded_bytes =
                            s.snapshot.downloaded_bytes.saturating_add(bytes);
                    }
                }
            }
        });
    }
    pub fn disk_stage(&self, stage: &str, total: Option<u64>) {
        self.update(false, |s| s.disk(stage, 0, total, false));
    }
    pub(crate) fn hash_file(&self, path: &std::path::Path, size: u64) -> crate::Result<String> {
        self.disk_stage("Проверка файла", Some(size));
        crate::sha_observed(path, |bytes| self.disk_advance(bytes))
    }
    pub(crate) fn link_or_copy(
        &self,
        source: &std::path::Path,
        target: &std::path::Path,
        size: u64,
    ) -> crate::Result<()> {
        use std::io::{Read, Write};
        if std::fs::hard_link(source, target).is_ok() {
            return Ok(());
        }
        self.disk_stage("Копирование файла", Some(size));
        let mut input = std::fs::File::open(source)?;
        let mut output = std::fs::File::create_new(target)?;
        let mut buffer = [0; 131072];
        loop {
            let count = input.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            output.write_all(&buffer[..count])?;
            self.disk_advance(count as u64);
        }
        output.set_permissions(input.metadata()?.permissions())?;
        Ok(())
    }
    pub fn disk_advance(&self, bytes: u64) {
        self.update(false, |s| {
            s.snapshot.disk_done_bytes = s.snapshot.disk_done_bytes.saturating_add(bytes);
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
            s.disk("", 0, None, false);
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
    fn network_wait_write_and_hash_have_independent_counters() {
        let tracker = TransferTracker::new(None);
        tracker.item("a", 100, 20);
        tracker.planned();
        tracker.stage("download", "Загрузка", 0);
        tracker.observe(
            "a",
            DownloadEvent::Received {
                bytes: 30,
                available: 50,
            },
        );
        tracker.observe("a", DownloadEvent::Written { available: 50 });
        let written = tracker.snapshot();
        assert_eq!(written.disk_total_bytes, Some(100));
        assert_eq!(written.disk_done_bytes, 50);
        assert_eq!(written.downloaded_bytes, 30);
        assert_eq!(written.fraction(), 30.0 / 80.0);
        tracker.observe("a", DownloadEvent::NetworkWait { seconds: 8 });
        assert!(tracker.snapshot().title.contains("8 с"));
        tracker.observe(
            "a",
            DownloadEvent::Received {
                bytes: 50,
                available: 100,
            },
        );
        tracker.observe(
            "a",
            DownloadEvent::Activity {
                stage: "Сохранение",
                done: 0,
                total: None,
            },
        );
        let saving = tracker.snapshot();
        assert_eq!(saving.title, "Загрузка");
        assert_eq!(saving.fraction(), 1.0);
        assert_eq!(saving.disk_total_bytes, None);
        tracker.stage("verify", "Проверяем", 4);
        tracker.disk_stage("Проверка файлов", Some(800));
        tracker.disk_advance(200);
        let verify = tracker.snapshot();
        assert_eq!(verify.disk_done_bytes, 200);
        assert_eq!(verify.fraction(), 1.0);
        assert_eq!(verify.downloaded_bytes, 80);
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
