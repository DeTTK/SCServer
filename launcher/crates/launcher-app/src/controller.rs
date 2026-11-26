use crate::{AppWindow, AttachmentRow, ChangeRow, ModRow, ServerRow, WorldRow};
use anyhow::{Context, Result, bail, ensure};
use bscp_launcher_core::*;
use bscp_launcher_host::{HostClient, ensure_host};
use slint::{ComponentHandle, Model, ModelRc, SharedString, VecModel};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    rc::Rc,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};

#[derive(Clone)]
struct Draft {
    game: String,
    data: String,
    nick: String,
    bundle: String,
    client: String,
    server: String,
    cmem: String,
    smem: String,
    port: String,
    feed: String,
    branch: String,
    world: String,
    desktop_shortcut: bool,
    relay_enabled: bool,
}
impl Draft {
    fn read(ui: &AppWindow) -> Self {
        Self {
            game: ui.get_game_path().to_string(),
            data: ui.get_data_path().to_string(),
            nick: ui.get_nickname().to_string(),
            bundle: ui.get_bundle_path().to_string(),
            client: ui.get_client_path().to_string(),
            server: ui.get_server_path().to_string(),
            cmem: ui.get_client_memory().to_string(),
            smem: ui.get_server_memory_setting().to_string(),
            port: ui.get_port().to_string(),
            feed: ui.get_feed_url().to_string(),
            branch: ui.get_game_branch().to_string(),
            world: ui.get_first_world_name().to_string(),
            desktop_shortcut: ui.get_desktop_shortcut(),
            relay_enabled: ui.get_relay_enabled(),
        }
    }
    fn settings(&self, old: &Settings, layout: &Layout) -> Result<Settings> {
        if self.data.trim().is_empty() {
            bail!("DATA_PATH: Укажите папку локалки и миров.")
        }
        if self.game.trim().is_empty() {
            bail!("GAME_PATH: Выберите папку установленной игры.")
        }
        let mut s = old.clone();
        s.game_path = layout.store(&layout.resolve(self.game.trim()));
        s.data_path = layout.store(&layout.resolve(self.data.trim()));
        s.nickname = self.nick.trim().into();
        s.client_path = self.client.trim().into();
        s.server_path = self.server.trim().into();
        s.client_memory_mb = self
            .cmem
            .parse()
            .context("MEMORY_INVALID: Укажите память клиента числом в МБ.")?;
        s.server_memory_mb = self
            .smem
            .parse()
            .context("SERVER_MEMORY_INVALID: Укажите память сервера числом в МБ.")?;
        if !(512..=65536).contains(&s.server_memory_mb) {
            bail!("SERVER_MEMORY_INVALID: Укажите память сервера от 512 до 65536 МБ.")
        }
        s.relay_enabled = self.relay_enabled;
        s.base_port = self
            .port
            .parse()
            .context("PORT_INVALID: Укажите порт числом от 1024 до 65000.")?;
        s.feed_url = self.feed.trim().into();
        s.game_branch = self.branch.trim().into();
        s.validate()?;
        Ok(s)
    }
}

enum Work {
    Init,
    Mods(String, String, Option<PathBuf>),
    Launch(bool),
    Connect(String, String),
    Select(String),
    Save(Draft),
    Setup(String, i32, Draft),
    Install(Option<PathBuf>, Draft),
    World(String, String, String, Option<PathBuf>),
    Favorite(String, String, String, String),
    Server(String),
    Update(String, String),
    Report(String, BugReportDraft),
}
impl Work {
    fn operation(&self) -> &'static str {
        match self {
            Self::Init => "initialize",
            Self::Mods(..) => "manage-mods",
            Self::Launch(false) => "start-server",
            Self::Launch(true) => "start-singleplayer",
            Self::Connect(..) => "connect",
            Self::Select(..) => "select-world",
            Self::Save(..) => "save-settings",
            Self::Setup(..) => "first-run",
            Self::Install(..) => "install",
            Self::World(..) => "manage-world",
            Self::Favorite(..) => "manage-favorites",
            Self::Server(..) => "manage-server",
            Self::Update(..) => "update-game",
            Self::Report(..) => "bug-report",
        }
    }
}

pub struct Binding {
    alive: Arc<AtomicBool>,
    cancel: Arc<AtomicBool>,
}
impl Drop for Binding {
    fn drop(&mut self) {
        self.alive.store(false, Ordering::Relaxed);
        self.cancel.store(true, Ordering::Relaxed);
    }
}

fn rows<T: Clone + 'static>(v: Vec<T>) -> ModelRc<T> {
    Rc::new(VecModel::from(v)).into()
}
fn set_draft(ui: &AppWindow, s: &Settings) {
    ui.set_game_path(s.game_path.clone().into());
    ui.set_data_path(s.data_path.clone().into());
    ui.set_nickname(s.nickname.clone().into());
    ui.set_client_path(s.client_path.clone().into());
    ui.set_server_path(s.server_path.clone().into());
    ui.set_client_memory(s.client_memory_mb.to_string().into());
    ui.set_server_memory_setting(s.server_memory_mb.to_string().into());
    ui.set_port(s.base_port.to_string().into());
    ui.set_relay_enabled(s.relay_enabled);
    ui.set_feed_url(s.feed_url.clone().into());
    ui.set_game_branch(s.game_branch.clone().into());
}
fn set_channels(ui: &AppWindow, channels: Vec<String>) {
    ui.set_branches(rows(channels.into_iter().map(SharedString::from).collect()));
}

fn load_bundle(layout: &Layout, s: &Settings) -> String {
    if let Ok(stored) = read_json::<BTreeMap<String, String>>(&layout.at("state/bootstrap.json")) {
        if let Some(path) = stored.get("path").or_else(|| stored.get("Path")) {
            if layout.resolve(path).is_dir() {
                return layout.resolve(path).to_string_lossy().into();
            }
        }
    }
    if !s.client_path.is_empty() {
        let p = layout.resolve(&s.client_path);
        if let Some(parent) = p.parent() {
            return parent.to_string_lossy().into();
        }
    }
    String::new()
}

fn refresh(ui: &slint::Weak<AppWindow>, layout: &Layout, drafts: bool) -> Result<()> {
    let s = layout.load()?;
    let worlds = WorldStore::new(layout.data(&s))?.list()?;
    let favorites: Vec<ServerEntry> = read_json(&layout.at("config/servers.json"))?;
    let update_hint = SelfUpdater::pending_version(layout)
        .ok()
        .flatten()
        .map(|version| format!("Лаунчер {version} готов · применится при следующем запуске"))
        .unwrap_or_default();
    let history: Vec<ServerEntry> = read_json(&layout.at("config/server-history.json"))?;
    let bundle = load_bundle(layout, &s);
    let channels = if drafts {
        Some(
            RepositoryChannel::cached_list(layout)
                .ok()
                .flatten()
                .unwrap_or_else(|| {
                    (!s.game_branch.is_empty())
                        .then(|| s.game_branch.clone())
                        .into_iter()
                        .collect()
                }),
        )
    } else {
        None
    };
    let selected = worlds
        .iter()
        .find(|w| w.id == s.selected_world)
        .map(|w| w.name.clone())
        .unwrap_or_default();
    let configured = s.setup_completed
        && GameLayout::resolve(layout.resolve(&s.game_path)).is_ok()
        && layout
            .bundle(&s, "client")
            .and_then(|c| Setup::check_components(&c, &layout.bundle(&s, "server")?))
            .is_ok();
    let available: ReleaseManifest =
        read_json(&layout.at("state/available.json")).unwrap_or_default();
    let channel = if s.game_branch.is_empty() {
        None
    } else {
        RepositoryChannel::new(layout)?
            .cached(&s.game_branch)
            .ok()
            .flatten()
    };
    let _ = ui.upgrade_in_event_loop(move |ui| {
        if let Some(channels) = channels {
            set_channels(&ui, channels);
            set_draft(&ui, &s);
            ui.set_bundle_path(bundle.into());
        }
        ui.set_configured(configured);
        ui.set_launcher_update_hint(update_hint.into());
        ui.set_selected_world_id(s.selected_world.into());
        ui.set_selected_world_title(selected.into());
        ui.set_worlds(rows(
            worlds
                .into_iter()
                .map(|w| WorldRow {
                    id: w.id.into(),
                    title: w.name.into(),
                    detail: w
                        .last_played
                        .map(|v| format!("Последний запуск: {}", v.split('T').next().unwrap_or(&v)))
                        .unwrap_or_else(|| "Ещё не запускался".into())
                        .into(),
                })
                .collect(),
        ));
        ui.set_favorites(rows(
            favorites
                .into_iter()
                .map(|s| ServerRow {
                    id: s.id.into(),
                    title: s.name.into(),
                    address: s.address.into(),
                    status: "Не проверен".into(),
                    online: false,
                })
                .collect(),
        ));
        ui.set_server_history(rows(
            history
                .into_iter()
                .map(|s| ServerRow {
                    id: s.id.into(),
                    title: s.name.into(),
                    address: s.address.into(),
                    status: "Не проверен".into(),
                    online: false,
                })
                .collect(),
        ));
        if let Some(channel) = channel {
            ui.set_changes(rows(vec![ChangeRow {
                title: format!(
                    "{} · {}",
                    channel.manifest.channel,
                    &channel.manifest.source_commit[..8]
                )
                .into(),
                body: channel.manifest.changelog.into(),
            }]));
        } else if !available.changelog.trim().is_empty() {
            ui.set_changes(rows(vec![ChangeRow {
                title: format!("Релиз {}", available.release_id).into(),
                body: available.changelog.into(),
            }]));
        }
    });
    let _ = refresh_mods(ui, layout);
    Ok(())
}
fn mod_row(v: ModView) -> ModRow {
    let read_only = v.source == "Конфликт ID" || v.version == "Некорректный JAR";
    ModRow {
        id: v.id.into(),
        title: v.title.into(),
        description: v.description.into(),
        version: v.version.into(),
        side: v.side.into(),
        read_only,
        source: v.source.into(),
        required: v.required,
        installed: v.installed,
        enabled: v.enabled,
        update_available: v.update_available,
    }
}
fn select_mod(ui: &AppWindow, id: &str) {
    let model = if ui.get_mods_tab() == "installed" {
        ui.get_installed_mods()
    } else {
        ui.get_catalog_mods()
    };
    let selected = model
        .iter()
        .find(|r| r.id == id)
        .or_else(|| model.row_data(0))
        .unwrap_or_default();
    ui.set_selected_mod(selected);
}
fn refresh_mods(ui: &slint::Weak<AppWindow>, layout: &Layout) -> Result<()> {
    let snapshot = ModManager::new(layout).snapshot()?;
    let _ = ui.upgrade_in_event_loop(move |ui| {
        let id = ui.get_selected_mod().id.to_string();
        ui.set_catalog_mods(rows(snapshot.catalog.into_iter().map(mod_row).collect()));
        ui.set_installed_mods(rows(snapshot.installed.into_iter().map(mod_row).collect()));
        select_mod(&ui, &id);
    });
    Ok(())
}
fn display_bytes(bytes: u64) -> String {
    let (divisor, unit) = if bytes >= 1024 * 1024 * 1024 {
        (1024.0 * 1024.0 * 1024.0, "ГБ")
    } else if bytes >= 1024 * 1024 {
        (1024.0 * 1024.0, "МБ")
    } else if bytes >= 1024 {
        (1024.0, "КБ")
    } else {
        return format!("{bytes} Б");
    };
    format!("{:.1} {unit}", bytes as f64 / divisor).replace('.', ",")
}
fn transfer_view(s: &TransferSnapshot) -> crate::TransferView {
    let fraction = s.fraction();
    let percent = if !s.total_known {
        "Сеть · расчёт объёма".to_owned()
    } else if s.total_bytes == 0 {
        "Скачивание не требуется".to_owned()
    } else if s.remaining_bytes == 0 {
        "Сеть · скачано".to_owned()
    } else {
        format!(
            "Сеть · {}%",
            u128::from(s.downloaded_bytes) * 100 / u128::from(s.total_bytes)
        )
    };
    let mut disk_label = String::new();
    let mut disk_fraction = -1.0;
    let mut disk_amount = String::new();
    if !s.disk_stage.is_empty() {
        disk_label = format!("Диск · {}", s.disk_stage);
        if let Some(total) = s.disk_total_bytes.filter(|&v| v > 0) {
            disk_fraction = (s.disk_done_bytes.min(total) as f64 / total as f64) as f32;
            disk_amount = format!(
                "{} / {}",
                display_bytes(s.disk_done_bytes),
                display_bytes(total)
            );
        }
    } else if s.files_total > 0 && matches!(s.phase.as_str(), "checking" | "install") {
        disk_label = "Диск · файлы".into();
        disk_fraction = s.files_done as f32 / s.files_total as f32;
        disk_amount = format!("{} / {}", s.files_done, s.files_total);
    } else if s.phase == "complete" {
        disk_label = "Диск · готово".into();
        disk_fraction = 1.0;
    }
    let speed =
        if s.phase == "download" && s.bytes_per_second >= 1.0 && s.bytes_per_second.is_finite() {
            format!("{}/с", display_bytes(s.bytes_per_second as u64))
        } else {
            "—".into()
        };
    let eta = if s.phase == "download" && s.remaining_bytes > 0 {
        match s.eta_seconds {
            Some(seconds) if seconds >= 3600 => {
                format!("≈ {} ч {} мин", seconds / 3600, seconds % 3600 / 60)
            }
            Some(seconds) if seconds >= 60 => format!("≈ {} мин {} с", seconds / 60, seconds % 60),
            Some(seconds) => format!("≈ {seconds} с"),
            None if s.bytes_per_second > 0.0 => "Расчёт…".into(),
            None => "—".into(),
        }
    } else {
        "—".into()
    };
    let mut detail = Vec::new();
    if s.reused_bytes > 0 {
        detail.push(format!(
            "Найдено файлов на диске: {}",
            display_bytes(s.reused_bytes)
        ));
    }
    if s.cached_bytes > 0 {
        detail.push(format!(
            "В кэше загрузки: {}",
            display_bytes(s.cached_bytes)
        ));
    }
    if s.files_total > 0 && !s.disk_stage.is_empty() {
        detail.push(format!("Файлы {} / {}", s.files_done, s.files_total));
    }
    crate::TransferView {
        visible: true,
        completed: s.phase == "complete",
        operation: s.operation.clone().into(),
        sequence: s.sequence.min(i32::MAX as u64) as i32,
        title: s.title.clone().into(),
        percent: percent.into(),
        fraction,
        disk_label: disk_label.into(),
        disk_fraction,
        disk_amount: disk_amount.into(),
        downloaded: if s.total_known {
            format!(
                "{} из {}",
                display_bytes(s.downloaded_bytes),
                display_bytes(s.total_bytes)
            )
        } else {
            "—".into()
        }
        .into(),
        remaining: if s.total_known {
            display_bytes(s.remaining_bytes)
        } else {
            "—".into()
        }
        .into(),
        speed: speed.into(),
        eta: eta.into(),
        detail: detail.join(" · ").into(),
    }
}
fn apply_progress(ui: &AppWindow, update: ProgressUpdate) {
    match update {
        ProgressUpdate::Message(text) => {
            ui.set_progress_text(text.into());
            ui.set_progress(-1.0);
            ui.set_transfer_view(Default::default());
        }
        ProgressUpdate::Transfer(snapshot) => {
            let current = ui.get_transfer_view();
            if current.operation.as_str() == snapshot.operation
                && current.sequence as u64 >= snapshot.sequence
            {
                return;
            }
            ui.set_progress(snapshot.fraction());
            ui.set_progress_text(snapshot.title.clone().into());
            ui.set_transfer_view(transfer_view(&snapshot));
        }
    }
}
fn operation_done(ui: &slint::Weak<AppWindow>, result: Result<String>) {
    let _ = ui.upgrade_in_event_loop(move |ui| {
        ui.set_busy(false);
        ui.set_progress(-1.0);
        ui.set_progress_text("".into());
        match result {
            Ok(text) => {
                if !ui.get_transfer_view().completed {
                    ui.set_transfer_view(Default::default());
                }
                ui.set_error("".into());
                ui.set_error_field("".into());
                if !text.is_empty() {
                    ui.set_status(text.into());
                }
            }
            Err(e) => {
                let mut transfer = ui.get_transfer_view();
                if transfer.visible {
                    transfer.title = "Загрузка остановлена".into();
                    transfer.speed = "—".into();
                    transfer.eta = "—".into();
                    ui.set_transfer_view(transfer);
                }
                let (field, message) = friendly_error(&e);
                ui.set_error_field(field.into());
                ui.set_error(message.into());
            }
        }
    });
}
fn friendly_error(error: &anyhow::Error) -> (String, String) {
    let text = redact(&format!("{error:#}"));
    let choices = [
        (
            "BACKEND_CLOCK_SKEW",
            "",
            "Время на вашем ПК и сервере расходится. Включите автоматическую синхронизацию времени на обоих ПК и повторите подключение.",
        ),
        (
            "BACKEND_UNAUTHORIZED",
            "",
            "Сервер отклонил вход (HTTP 401). Проверьте адрес подключения и версии лаунчера у вас и у хоста.",
        ),
        (
            "BACKEND_IDENTITY_REQUIRED",
            "",
            "Сервер не получил данные профиля лаунчера. Проверьте обновления лаунчера и повторите подключение.",
        ),
        (
            "BACKEND_IDENTITY_INVALID",
            "",
            "Сервер не смог проверить подпись профиля лаунчера. Проверьте обновления лаунчера у вас и у хоста.",
        ),
        (
            "BACKEND_REQUEST_REPLAYED",
            "",
            "Сервер отклонил повторный запрос входа. Попробуйте подключиться снова.",
        ),
        (
            "BACKEND_IDENTITY_BUSY",
            "",
            "Сервер сейчас обрабатывает слишком много запросов входа. Повторите подключение через несколько секунд.",
        ),
        (
            "BACKEND_ROUTE_INVALID",
            "",
            "Сервер отклонил адрес подключения. Проверьте адрес или попросите хоста прислать новое приглашение.",
        ),
        (
            "GAME_INVALID",
            "game",
            "Не найдены ресурсы игры. Выберите папку с assets, modassets и classes.jar.",
        ),
        ("GAME_PATH", "game", "Выберите папку установленной игры."),
        (
            "LOGIN_INVALID",
            "nick",
            "Введите ник от 1 до 64 символов без управляющих знаков.",
        ),
        (
            "PORT_INVALID",
            "port",
            "Введите свободный начальный порт от 1024 до 65000.",
        ),
        (
            "SERVER_MEMORY_INVALID",
            "smem",
            "Введите объём памяти сервера числом от 512 до 65536 МБ.",
        ),
        (
            "MEMORY_INVALID",
            "cmem",
            "Введите объём памяти числом от 512 до 65536 МБ.",
        ),
        (
            "BUNDLE_INCOMPLETE",
            "bundle",
            "В комплекте не хватает компонентов. Выберите корень Client / Server / Map или установите проверенный комплект.",
        ),
        (
            "BUNDLE_PATH",
            "bundle",
            "Сначала подключите комплект Client / Server / Map.",
        ),
        (
            "MAP_INVALID",
            "bundle",
            "Не найдена базовая карта с registry.json и папкой region.",
        ),
        (
            "WORLD_BUSY",
            "world",
            "Мир используется. Сначала сохраните и остановите сервер.",
        ),
        (
            "CLIENT_PROFILE_BUSY",
            "",
            "Этот профиль уже используется запущенной игрой. Закройте её перед повторным запуском.",
        ),
        (
            "SETUP_DATA",
            "data",
            "Выберите для локалки отдельную папку вне установленной игры.",
        ),
        (
            "UPDATE_KEY",
            "feed",
            "Получите доверенный публичный ключ у издателя канала и сохраните его в config/release-public.pem рядом с настройками лаунчера. Ключ не загружается из самого канала.",
        ),
        (
            "DATA_PATH",
            "data",
            "Укажите отдельную папку для локальных миров и данных.",
        ),
        (
            "FEED_UNSAVED",
            "feed",
            "Сначала сохраните изменённый адрес канала в настройках.",
        ),
        (
            "UPDATE_SIGNATURE",
            "feed",
            "Подпись обновления не прошла проверку. Установленная версия сохранена.",
        ),
        (
            "CANCELLED",
            "",
            "Операция отменена. Сохранения и установленная версия сохранены.",
        ),
    ];
    for (key, field, message) in choices {
        if text.contains(key) {
            return (field.into(), message.into());
        }
    }
    (String::new(), text)
}

pub fn bind(ui: &AppWindow, layout: Layout) -> Binding {
    let report_files = Arc::new(Mutex::new(Vec::<PathBuf>::new()));
    let (tx, rx) = mpsc::channel();
    let alive = Arc::new(AtomicBool::new(true));
    let cancel = Arc::new(AtomicBool::new(false));
    let local_work = Arc::new(AtomicBool::new(false));
    ui.set_version(env!("CARGO_PKG_VERSION").into());
    ui.set_status("Подготовка лаунчера…".into());
    ui.set_changes(rows(vec![
        ChangeRow {title:"Нативный лаунчер".into(),body:"Интерфейс и управление запуском перенесены на Rust. .NET для новой ветки не требуется.".into()},
        ChangeRow {title:"Ваши миры".into(),body:"Выбор мира, отдельные сохранения, резервные копии и восстановление.".into()},
        ChangeRow {title:"Первый запуск".into(),body:"Проверка путей, установка компонентов и подготовка первого мира в одном мастере.".into()},
    ]));
    let submit = {
        let tx = tx.clone();
        let weak = ui.as_weak();
        let cancel = cancel.clone();
        let local = local_work.clone();
        move |work: Work, is_local: bool| {
            if let Some(ui) = weak.upgrade() {
                if ui.get_busy() {
                    return;
                }
                ui.set_busy(true);
                ui.set_error("".into());
                ui.set_error_field("".into());
                cancel.store(false, Ordering::Relaxed);
                local.store(is_local, Ordering::Relaxed);
                if tx.send(work).is_err() {
                    ui.set_busy(false);
                    ui.set_error(
                        "Рабочий процесс лаунчера завершён. Откройте приложение снова.".into(),
                    );
                }
            }
        }
    };
    let submit: Rc<dyn Fn(Work, bool)> = Rc::new(submit);
    {
        let w = ui.as_weak();
        let send = submit.clone();
        ui.on_navigate(move |page| {
            if let Some(ui) = w.upgrade() {
                let is_mods = page == "mods";
                ui.set_page(page);
                if is_mods && !ui.get_busy() {
                    send(Work::Mods("scan".into(), String::new(), None), true);
                }
                ui.set_error("".into());
            }
        });
    }
    {
        let w = ui.as_weak();
        let send = submit.clone();
        ui.on_launch(move |client| {
            if let Some(ui) = w.upgrade() {
                if !ui.get_configured() {
                    ui.set_setup_visible(true);
                    ui.set_setup_step(0);
                    return;
                }
                send(Work::Launch(client), false);
            }
        });
    }
    {
        let w = ui.as_weak();
        let send = submit.clone();
        ui.on_connect(move |address| {
            if let Some(ui) = w.upgrade() {
                if !ui.get_configured() {
                    ui.set_setup_visible(true);
                    ui.set_setup_step(0);
                    return;
                }
                send(
                    Work::Connect(address.to_string(), ui.get_nickname().to_string()),
                    false,
                );
            }
        });
    }
    {
        let send = submit.clone();
        ui.on_select_world(move |id| send(Work::Select(id.to_string()), true));
    }
    {
        let w = ui.as_weak();
        let send = submit.clone();
        ui.on_save_settings(move || {
            if let Some(ui) = w.upgrade() {
                send(Work::Save(Draft::read(&ui)), true)
            }
        });
    }
    {
        let w = ui.as_weak();
        let send = submit.clone();
        let flag = cancel.clone();
        ui.on_setup_action(move |action| {
            if let Some(ui) = w.upgrade() {
                let a = action.as_str();
                if a == "cancel" {
                    flag.store(true, Ordering::Relaxed);
                    ui.set_progress_text("Отменяем операцию…".into());
                    let mut view = ui.get_transfer_view();
                    view.title = "Отменяем операцию…".into();
                    view.speed = "—".into();
                    view.eta = "—".into();
                    ui.set_transfer_view(view);
                    return;
                }
                if a == "close" {
                    if ui.get_busy() {
                        flag.store(true, Ordering::Relaxed);
                    } else {
                        ui.set_setup_visible(false);
                    }
                    return;
                }
                if a == "back" {
                    ui.set_setup_step((ui.get_setup_step() - 1).max(0));
                    ui.set_error("".into());
                    return;
                }
                send(
                    Work::Setup(action.to_string(), ui.get_setup_step(), Draft::read(&ui)),
                    true,
                );
            }
        });
    }
    {
        let w = ui.as_weak();
        ui.on_browse(move |field| {
            if let Some(ui) = w.upgrade() {
                if let Some(path) = rfd::FileDialog::new()
                    .set_title("Выберите папку")
                    .pick_folder()
                {
                    let s: SharedString = path.to_string_lossy().into_owned().into();
                    match field.as_str() {
                        "game" => ui.set_game_path(s),
                        "data" => ui.set_data_path(s),
                        "client" => ui.set_client_path(s),
                        "server" => ui.set_server_path(s),
                        "bundle" => ui.set_bundle_path(s),
                        _ => {}
                    }
                }
            }
        });
    }
    {
        let w = ui.as_weak();
        let send = submit.clone();
        ui.on_install_components(move |source| {
            if let Some(ui) = w.upgrade() {
                let file = if source.as_str() == "zip" {
                    let Some(file) = rfd::FileDialog::new()
                        .set_title("Проверенный комплект BSCPCRAFT")
                        .add_filter("Комплект", &["zip"])
                        .pick_file()
                    else {
                        return;
                    };
                    Some(file)
                } else {
                    None
                };
                send(Work::Install(file, Draft::read(&ui)), true);
            }
        });
    }
    {
        let send = submit.clone();
        ui.on_world_action(move |action, id, value| {
            let file = match action.as_str() {
                "import" => {
                    let Some(file) = rfd::FileDialog::new()
                        .add_filter("Архив мира", &["zip"])
                        .pick_file()
                    else {
                        return;
                    };
                    Some(file)
                }
                "export" => {
                    let Some(file) = rfd::FileDialog::new()
                        .add_filter("Архив мира", &["zip"])
                        .set_file_name("Мой мир.zip")
                        .save_file()
                    else {
                        return;
                    };
                    Some(file)
                }
                _ => None,
            };
            send(
                Work::World(action.to_string(), id.to_string(), value.to_string(), file),
                true,
            );
        });
    }
    {
        let send = submit.clone();
        ui.on_favorite_action(move |a, n, address, nick| {
            send(
                Work::Favorite(
                    a.to_string(),
                    n.to_string(),
                    address.to_string(),
                    nick.to_string(),
                ),
                true,
            )
        });
    }
    {
        let send = submit.clone();
        ui.on_server_action(move |a| send(Work::Server(a.to_string()), false));
    }
    {
        let send = submit.clone();
        let w = ui.as_weak();
        ui.on_update_action(move |a| {
            if let Some(ui) = w.upgrade() {
                send(
                    Work::Update(a.to_string(), ui.get_game_branch().to_string()),
                    a.as_str() == "check",
                );
            }
        });
    }
    {
        let send = submit.clone();
        let files = report_files.clone();
        ui.on_report(move |action, title, description, perf| {
            let draft = BugReportDraft {
                title: title.to_string(),
                description: description.to_string(),
                include_performance: perf,
                screenshots: files.lock().unwrap_or_else(|e| e.into_inner()).clone(),
            };
            send(Work::Report(action.to_string(), draft), true)
        });
    }
    {
        let weak = ui.as_weak();
        let files = report_files.clone();
        ui.on_add_screenshots(move || {
            let Some(selected) = rfd::FileDialog::new()
                .set_title("Выберите скриншоты")
                .add_filter("Скриншоты", &["png", "jpg", "jpeg", "webp"])
                .pick_files()
            else {
                return;
            };
            let mut paths = files.lock().unwrap_or_else(|e| e.into_inner());
            for path in selected {
                if paths.len() < 6 && !paths.contains(&path) {
                    paths.push(path);
                }
            }
            if let Some(ui) = weak.upgrade() {
                set_report_files(&ui, &paths);
            }
        });
    }
    {
        let weak = ui.as_weak();
        let files = report_files.clone();
        ui.on_remove_screenshot(move |id| {
            let mut paths = files.lock().unwrap_or_else(|e| e.into_inner());
            paths.retain(|p| p.to_string_lossy() != id.as_str());
            if let Some(ui) = weak.upgrade() {
                set_report_files(&ui, &paths);
            }
        });
    }
    ui.on_project_link(move |id| {
        let url = match id.as_str() {
            "telegram" => "https://t.me/bscp_podval",
            "feedback" => "https://t.me/stalzone_local",
            "github" => "https://github.com/DeTTK/SCServer",
            _ => return,
        };
        let _ = open_web(url);
    });
    {
        let w = ui.as_weak();
        let cancel = cancel.clone();
        let local = local_work.clone();
        ui.window().on_close_requested(move || {
            if let Some(ui) = w.upgrade() {
                if ui.get_busy() && local.load(Ordering::Relaxed) {
                    cancel.store(true, Ordering::Relaxed);
                    ui.set_progress_text(
                        "Отменяем операцию. Закройте окно после завершения.".into(),
                    );
                    return slint::CloseRequestResponse::KeepWindowShown;
                }
            }
            slint::CloseRequestResponse::HideWindow
        });
    }
    {
        let w = ui.as_weak();
        ui.on_select_mod(move |id| {
            if let Some(ui) = w.upgrade() {
                select_mod(&ui, &id);
            }
        });
        let send = submit.clone();
        ui.on_mod_action(move |action, id| {
            let file = if action == "import" {
                match rfd::FileDialog::new()
                    .set_title("Добавить мод")
                    .add_filter("Мод игры", &["jar"])
                    .pick_file()
                {
                    Some(path) => Some(path),
                    None => return,
                }
            } else {
                None
            };
            send(Work::Mods(action.to_string(), id.to_string(), file), true);
        });
    }
    let worker_ui = ui.as_weak();
    let worker_layout = layout.clone();
    let worker_cancel = cancel.clone();
    let worker_local = local_work.clone();
    let (delivery, wake_delivery) = mpsc::sync_channel(1);
    let delivery_layout = layout.clone();
    let delivery_alive = alive.clone();
    std::thread::spawn(move || {
        while delivery_alive.load(Ordering::Relaxed) {
            let _ = flush_telemetry(&delivery_layout, Duration::from_secs(4));
            if let Err(mpsc::RecvTimeoutError::Disconnected) =
                wake_delivery.recv_timeout(Duration::from_secs(30))
            {
                break;
            }
        }
    });
    std::thread::spawn(move || {
        while let Ok(work) = rx.recv() {
            let progress_ui = worker_ui.clone();
            let progress_cancel = worker_cancel.clone();
            let _ = worker_ui.upgrade_in_event_loop(|ui| {
                ui.set_transfer_view(Default::default());
                ui.set_progress(-1.0);
            });
            let progress = move |update: ProgressUpdate| {
                if progress_cancel.load(Ordering::Relaxed) {
                    return;
                }
                let _ = progress_ui.upgrade_in_event_loop(move |ui| apply_progress(&ui, update));
            };
            let operation = work.operation();
            let result = execute(work, &worker_layout, &worker_ui, &worker_cancel, &progress);
            match &result {
                Ok(message) => {
                    let _ = RotatingLog::new(worker_layout.at("logs"), "launcher")
                        .line(&format!("operation completed: {message}"));
                }
                Err(error) => {
                    let _ = record_launcher_error(&worker_layout, "ui", operation, error);
                }
            }
            let _ = delivery.try_send(());
            worker_local.store(false, Ordering::Relaxed);
            operation_done(&worker_ui, result);
        }
    });
    let poll_ui = ui.as_weak();
    let poll_alive = alive.clone();
    let probe_layout = layout.clone();
    let probe_ui = ui.as_weak();
    let probe_alive = alive.clone();
    // Health requests have their own worker and IPC connections. Slow remote
    // favorites must not delay local server metrics or the action queue.
    std::thread::spawn(move || {
        let host = HostClient::new(&probe_layout);
        // Let Init publish its rows and start the supervisor before the first
        // pass; later passes re-read both persisted lists.
        for _ in 0..3 {
            if !probe_alive.load(Ordering::Relaxed) {
                return;
            }
            std::thread::sleep(Duration::from_secs(1));
        }
        while probe_alive.load(Ordering::Relaxed) {
            let favorites: Vec<ServerEntry> =
                read_json(&probe_layout.at("config/servers.json")).unwrap_or_default();
            let history: Vec<ServerEntry> =
                read_json(&probe_layout.at("config/server-history.json")).unwrap_or_default();
            for (history, entries) in [(false, favorites), (true, history)] {
                for entry in entries.into_iter().take(32) {
                    if !probe_alive.load(Ordering::Relaxed) {
                        return;
                    }
                    let result = host.call("probe", &kv(&[("address", &entry.address)]));
                    if !probe_alive.load(Ordering::Relaxed) {
                        return;
                    }
                    let (online, status) = probe_status(result.ok().as_ref());
                    let _ = probe_ui.upgrade_in_event_loop(move |ui| {
                        let model = if history {
                            ui.get_server_history()
                        } else {
                            ui.get_favorites()
                        };
                        apply_probe(&model, &entry.id, &entry.address, online, &status);
                    });
                }
            }
            for _ in 0..30 {
                if !probe_alive.load(Ordering::Relaxed) {
                    return;
                }
                std::thread::sleep(Duration::from_secs(1));
            }
        }
    });
    std::thread::spawn(move || {
        let host = HostClient::new(&layout);
        let mut previous = String::new();
        let mut updating = false;
        while poll_alive.load(Ordering::Relaxed) {
            std::thread::sleep(if updating {
                Duration::from_millis(400)
            } else {
                Duration::from_secs(3)
            });
            if !poll_alive.load(Ordering::Relaxed) {
                break;
            }
            let Ok(view) = host.status() else {
                updating = false;
                continue;
            };
            updating = view.state == "Updating";
            if updating {
                let _ = poll_ui.upgrade_in_event_loop(move |ui| {
                    if ui.get_busy() {
                        if let Some(snapshot) = view.transfer {
                            apply_progress(&ui, ProgressUpdate::Transfer(snapshot));
                        } else {
                            apply_progress(&ui, ProgressUpdate::Message(view.detail.clone()));
                        }
                        ui.set_status("Обновляем игровые файлы…".into());
                    }
                    ui.set_server_state(view.detail.into());
                });
                continue;
            }
            let changed = view.detail != previous;
            previous = view.detail.clone();
            let metrics = if matches!(view.state.as_str(), "Ready" | "Starting") {
                host.call("metrics", &BTreeMap::new()).ok()
            } else {
                None
            };
            let s = layout.load().unwrap_or_default();
            let addresses = bscp_launcher_host::network::local_addresses();
            let address = format!(
                "{}:{}",
                addresses
                    .first()
                    .cloned()
                    .unwrap_or_else(|| "127.0.0.1".into()),
                s.base_port
            );
            let _ = poll_ui.upgrade_in_event_loop(move |ui| {
                ui.set_server_running(matches!(
                    view.state.as_str(),
                    "Ready" | "Starting" | "Stopping"
                ));
                ui.set_client_running(view.client_running);
                ui.set_server_state(view.detail.clone().into());
                ui.set_server_address(address.into());
                ui.set_relay_invite(view.relay_invite.into());
                ui.set_relay_status(view.relay_status.into());
                ui.set_server_uptime("Нет данных".into());
                ui.set_server_memory("Нет данных".into());
                if changed && !ui.get_busy() && ui.get_configured() {
                    ui.set_status(view.detail.into());
                }
                if let Some(metrics) = metrics {
                    let health = &metrics["health"];
                    let players = health["game"]["players"].as_array();
                    ui.set_players_known(players.is_some());
                    ui.set_players(rows(
                        players
                            .into_iter()
                            .flatten()
                            .filter_map(|p| p["name"].as_str().map(SharedString::from))
                            .collect(),
                    ));
                    let (uptime, memory) = metric_values(&metrics);
                    if let Some(ms) = uptime {
                        ui.set_server_uptime(format!("{} мин", ms / 60000).into());
                    }
                    if let Some(mem) = memory {
                        ui.set_server_memory(format!("{} МБ", mem / 1048576).into());
                    }
                } else {
                    ui.set_players(rows(Vec::<SharedString>::new()));
                    ui.set_players_known(false);
                }
            });
        }
    });
    // Init shares the same single worker as user actions. Reserve its busy
    // slot so its completion cannot clear a later operation's close guard.
    ui.set_busy(true);
    local_work.store(true, Ordering::Relaxed);
    let _ = tx.send(Work::Init);
    Binding { alive, cancel }
}

fn call(
    layout: &Layout,
    command: &str,
    args: BTreeMap<String, String>,
) -> Result<serde_json::Value> {
    let result = ensure_host(layout)?.call(command, &args);
    if result
        .as_ref()
        .is_err_and(|e| e.to_string().contains("HOST_EXITING:"))
    {
        return ensure_host(layout)?.call(command, &args);
    }
    result
}
fn kv(values: &[(&str, &str)]) -> BTreeMap<String, String> {
    values
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
}
fn close_dialog(ui: &slint::Weak<AppWindow>) {
    let _ = ui.upgrade_in_event_loop(|ui| ui.set_dialog("".into()));
}
fn set_report_files(ui: &AppWindow, paths: &[PathBuf]) {
    ui.set_report_attachments(rows(
        paths
            .iter()
            .map(|path| AttachmentRow {
                id: path.to_string_lossy().into_owned().into(),
                name: path
                    .file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
                    .into_owned()
                    .into(),
                detail: std::fs::metadata(path)
                    .map(|m| format!("{:.1} МБ", m.len() as f64 / 1048576.0))
                    .unwrap_or_else(|_| "Недоступен".into())
                    .into(),
            })
            .collect(),
    ));
}
fn open_web(url: &str) -> Result<()> {
    if ![
        "https://t.me/bscp_podval",
        "https://t.me/stalzone_local",
        "https://github.com/DeTTK/SCServer",
    ]
    .iter()
    .any(|prefix| url == *prefix || url.starts_with(&format!("{prefix}/")))
    {
        bail!("PROJECT_LINK: unsupported address");
    }
    let mut command = std::process::Command::new("explorer.exe");
    command.arg(url);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    command.spawn()?;
    Ok(())
}
fn open_path(path: &Path) -> Result<()> {
    no_links(path)?;
    let mut cmd = std::process::Command::new("explorer.exe");
    cmd.arg(path);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    cmd.spawn()?;
    Ok(())
}
fn backups(ui: &slint::Weak<AppWindow>, layout: &Layout, id: &str) -> Result<()> {
    let s = layout.load()?;
    let _ = WorldStore::new(layout.data(&s))?.get(id)?;
    let directory = child(&layout.data(&s).join("backups"), id)?;
    let mut list = if directory.is_dir() {
        files(&directory)?
            .into_iter()
            .filter(|p| p.extension().is_some_and(|e| e == "zip"))
            .collect::<Vec<_>>()
    } else {
        vec![]
    };
    list.sort();
    list.reverse();
    let rows_data = list
        .into_iter()
        .map(|p| {
            let name = p.file_name().unwrap().to_string_lossy().into_owned();
            let detail = format!(
                "{:.1} МБ",
                std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0) as f64 / 1048576.
            );
            (name, detail)
        })
        .collect::<Vec<_>>();
    let id = id.to_string();
    let _ = ui.upgrade_in_event_loop(move |ui| {
        ui.set_backups(rows(
            rows_data
                .into_iter()
                .map(|(name, detail)| WorldRow {
                    id: name.clone().into(),
                    title: name.into(),
                    detail: detail.into(),
                })
                .collect(),
        ));
        ui.set_dialog_id(id.into());
        ui.set_dialog("backups".into());
    });
    Ok(())
}

fn execute(
    work: Work,
    layout: &Layout,
    ui: &slint::Weak<AppWindow>,
    cancel: &Arc<AtomicBool>,
    progress: &Progress,
) -> Result<String> {
    let mut s = layout.load()?;
    match work {
        Work::Mods(action, id, file) => {
            let manager = ModManager::new(layout);
            match action.as_str() {
                "open-folder" => {
                    let folder = manager.folder()?;
                    no_links(&folder)?;
                    std::fs::create_dir_all(&folder)?;
                    open_path(&folder)?;
                }
                "scan" => {}
                "refresh" => {
                    if !s.game_branch.is_empty() {
                        progress("Проверяем каталог модов…".into());
                        if let Err(error) = RepositoryChannel::new(layout)?
                            .with_cancel(cancel.clone())
                            .check(&s.game_branch)
                        {
                            refresh_mods(ui, layout)?;
                            return Ok(format!(
                                "Показан сохранённый каталог. GitHub недоступен: {error}"
                            ));
                        }
                    }
                }
                "import" => manager.import(&file.context("MOD_FILE: выберите JAR")?)?,
                _ => manager.apply(&action, &id, cancel.clone(), Some(progress))?,
            }
            refresh_mods(ui, layout)?;
            Ok(match action.as_str() {
                "remove" => {
                    "Мод перенесён в архив. Изменения применятся при следующем запуске игры."
                }
                "scan" | "refresh" => "Список модов обновлён",
                "open-folder" => "Папка модов открыта",
                _ => "Моды сохранены. Изменения применятся при следующем запуске игры.",
            }
            .into())
        }
        Work::Init => {
            refresh(ui, layout, true)?;
            match SelfUpdater::stage_latest(layout, cancel.clone(), Some(progress)) {
                Ok(Some(version)) => {
                    let _ = ui.upgrade_in_event_loop(move |ui| {
                        ui.set_launcher_update_hint(
                            format!("Лаунчер {version} готов · применится при следующем запуске")
                                .into(),
                        )
                    });
                }
                Ok(None) => {}
                Err(error) => {
                    let _ =
                        record_launcher_error(layout, "updater", "check-launcher-update", &error);
                }
            }
            let _ = ui.upgrade_in_event_loop(|ui| {
                ui.set_setup_visible(!ui.get_configured());
                ui.set_setup_step(0);
            });
            let service = RepositoryChannel::new(layout)?.with_cancel(cancel.clone());
            if let Ok(branches) = service.list() {
                let retired = !s.game_branch.is_empty() && !branches.contains(&s.game_branch);
                let _ = ui.upgrade_in_event_loop(move |ui| set_channels(&ui, branches));
                if retired {
                    return Ok(format!(
                        "Ветка {} больше не публикуется. Выберите доступную ветку в настройках.",
                        s.game_branch
                    ));
                }
            }
            if !s.game_branch.is_empty() {
                progress(format!("Проверяем готовую сборку {}…", s.game_branch).into());
                match service.check(&s.game_branch) {
                    Ok(available) => {
                        let view = HostClient::new(layout).status().unwrap_or_default();
                        if s.setup_completed
                            && !service.is_current_observed(&available, Some(progress))?
                            && !view.client_running
                            && !matches!(
                                view.state.as_str(),
                                "Ready" | "Starting" | "Stopping" | "Preparing"
                            )
                        {
                            progress("Получаем изменившиеся игровые файлы…".into());
                            if let Err(e) = call(layout, "update", BTreeMap::new()) {
                                let _ = record_launcher_error(layout, "ui", "update-game", &e);
                                return Ok(format!(
                                    "Установленная игра доступна. Обновление: {}",
                                    friendly_error(&e).1
                                ));
                            }
                        }
                        refresh(ui, layout, false)?;
                        return Ok(format!(
                            "{} · сборка {}",
                            s.game_branch,
                            &available.manifest.source_commit[..8]
                        ));
                    }
                    Err(error) => {
                        let _ = record_launcher_error(layout, "ui", "check-game-update", &error);
                        return Ok(format!(
                            "Установленная игра доступна. Проверка {}: {}",
                            s.game_branch,
                            friendly_error(&error).1
                        ));
                    }
                }
            }
            if !s.feed_url.trim().is_empty() {
                progress("Проверяем подписанный канал обновлений…".into());
                match UpdateService::new(layout)?
                    .with_cancel(cancel.clone())
                    .check(&s.feed_url)
                {
                    Ok(release) => {
                        refresh(ui, layout, false)?;
                        return Ok(format!(
                            "Готов к запуску. Проверен подписанный релиз {}",
                            release.release_id
                        ));
                    }
                    Err(error) => {
                        let _ = record_launcher_error(layout, "ui", "check-game-update", &error);
                        let (_, message) = friendly_error(&error);
                        return Ok(format!("Игра доступна. Проверка обновлений: {message}"));
                    }
                }
            }
            Ok(if s.setup_completed {
                "Готов к запуску"
            } else {
                "Подготовим игру к первому запуску"
            }
            .into())
        }
        Work::Launch(client) => {
            call(
                layout,
                "start",
                kv(&[
                    ("world", &s.selected_world),
                    ("client", if client { "true" } else { "false" }),
                ]),
            )?;
            let _ = ui.upgrade_in_event_loop(|ui| ui.set_page("server".into()));
            refresh(ui, layout, false)?;
            Ok(if client {
                "Клиент запускается; подключение начнётся после готовности сервера"
            } else {
                "Сервер запускается"
            }
            .into())
        }
        Work::Connect(address, nickname) => {
            let favorites: Vec<ServerEntry> = read_json(&layout.at("config/servers.json"))?;
            let mut known = favorites.clone();
            known.extend(read_json::<Vec<ServerEntry>>(
                &layout.at("config/server-history.json"),
            )?);
            let nickname = connection_nickname(&known, &address, &nickname)?;
            call(
                layout,
                "connect",
                kv(&[("address", address.trim()), ("nickname", &nickname)]),
            )?;
            remember_server(layout, &favorites, &address, &nickname)?;
            refresh(ui, layout, false)?;
            Ok("Клиент запускается и подключается к серверу".into())
        }
        Work::Select(id) => {
            WorldStore::new(layout.data(&s))?.get(&id)?;
            s.selected_world = id;
            layout.save(&s)?;
            refresh(ui, layout, false)?;
            Ok("Мир выбран".into())
        }
        Work::Save(draft) => {
            let value = draft.settings(&s, layout)?;
            if value.game_branch != s.game_branch {
                let view = HostClient::new(layout).status().unwrap_or_default();
                if view.client_running
                    || matches!(
                        view.state.as_str(),
                        "Ready" | "Starting" | "Stopping" | "Preparing"
                    )
                {
                    bail!("CHANNEL_BUSY: перед сменой ветки закройте клиент и остановите сервер.")
                }
            }
            validate_paths(layout, &value)?;
            validate_data_change(layout, &s, &value)?;
            layout.save(&value)?;
            refresh(ui, layout, true)?;
            Ok("Настройки сохранены".into())
        }
        Work::Setup(action, step, draft) => {
            if step == 0 {
                let proposed = draft.settings(&s, layout)?;
                validate_paths(layout, &proposed)?;
                validate_data_change(layout, &s, &proposed)?;
                let _ = ui.upgrade_in_event_loop(move |ui| ui.set_setup_step(1));
                return Ok(String::new());
            }
            if draft.bundle.trim().is_empty() {
                bail!("BUNDLE_PATH: Сначала установите игру на шаге «Компоненты».")
            }
            let bundle = Setup::discover(layout.resolve(draft.bundle.trim()));
            if bundle.client.is_empty() || bundle.server.is_empty() || bundle.map.is_empty() {
                bail!(
                    "BUNDLE_PATH: Установка не завершена. Вернитесь к шагу «Компоненты» и установите игру."
                )
            }
            Setup::check_components(Path::new(&bundle.client), Path::new(&bundle.server))?;
            if step == 1 && action != "finish" {
                let _ = ui.upgrade_in_event_loop(|ui| ui.set_setup_step(2));
                return Ok(String::new());
            }
            let mut proposed = draft.settings(&s, layout)?;
            validate_paths(layout, &proposed)?;
            validate_data_change(layout, &s, &proposed)?;
            proposed.client_path = layout.store(Path::new(&bundle.client));
            proposed.server_path = layout.store(Path::new(&bundle.server));
            Setup::complete_cancellable(
                layout,
                proposed,
                Path::new(&bundle.map),
                &draft.world,
                Some(progress),
                cancel,
            )?;
            let branch = layout.load()?.game_branch;
            let mut completion = "Игра подготовлена. Можно запускать мир.".to_string();
            if !branch.is_empty() {
                progress("Получаем актуальные файлы выбранной ветки…".into());
                if let Err(e) = call(layout, "update", BTreeMap::new()) {
                    let _ = record_launcher_error(layout, "ui", "update-game", &e);
                    completion = format!(
                        "Основа установлена. Обновление ветки: {}",
                        friendly_error(&e).1
                    );
                }
            }
            if draft.desktop_shortcut {
                progress("Создаём ярлык на рабочем столе…".into());
                match create_launcher_shortcut(&std::env::current_exe()?, &layout.root, None) {
                    Ok(_) => completion.push_str(" Ярлык создан на рабочем столе."),
                    Err(error) => {
                        let _ = record_launcher_error(layout, "ui", "create-shortcut", &error);
                        completion.push_str(" Ярлык создать не удалось. Игра подготовлена; лаунчер можно открыть напрямую.");
                    }
                }
            }
            refresh(ui, layout, true)?;
            let _ = ui.upgrade_in_event_loop(|ui| {
                ui.set_setup_visible(false);
                ui.set_page("home".into());
            });
            Ok(completion)
        }
        Work::Install(archive, draft) => {
            if archive.is_none() && !draft.branch.is_empty() {
                let proposed = draft.settings(&s, layout)?;
                validate_paths(layout, &proposed)?;
                let directory = RepositoryChannel::new(layout)?
                    .with_cancel(cancel.clone())
                    .prepare_base(&draft.branch, &layout.data(&proposed), Some(progress))?;
                let _ = ui.upgrade_in_event_loop(move |ui| {
                    ui.set_bundle_path(directory.to_string_lossy().into_owned().into())
                });
                return Ok("Все компоненты ветки установлены. Продолжите подготовку мира.".into());
            }
            let proposed = draft.settings(&s, layout)?;
            validate_paths(layout, &proposed)?;
            validate_data_change(layout, &s, &proposed)?;
            let directory = BundleInstaller::install_cancellable(
                layout,
                &layout.resolve(draft.data.trim()),
                Some(progress),
                archive.as_deref(),
                cancel.clone(),
            )?;
            let _ = ui.upgrade_in_event_loop(move |ui| {
                ui.set_bundle_path(directory.to_string_lossy().into_owned().into())
            });
            Ok("Комплект установлен. Продолжите подготовку.".into())
        }
        Work::World(action, id, value, file) => {
            let worlds = WorldStore::new(layout.data(&s))?;
            if matches!(action.as_str(), "clone" | "export" | "backup" | "restore") {
                progress("Подготавливаем самостоятельный мир…".into());
                bscp_launcher_host::world_tools::prepare(layout, &id, cancel)?;
            }
            match action.as_str() {
                "create" => {
                    let bundled = Setup::discover(load_bundle(layout, &s));
                    let map = if !bundled.map.is_empty() {
                        PathBuf::from(bundled.map)
                    } else {
                        let w = worlds.get(&s.selected_world)?;
                        worlds.map(&w)?
                    };
                    progress("Создаём мир из шаблона…".into());
                    let w = worlds.create_cancellable(
                        &value,
                        &map,
                        &LocalIdentity::load(layout)?.id,
                        cancel,
                    )?;
                    s.selected_world = w.id;
                    layout.save(&s)?;
                }
                "rename" => worlds.rename(&id, &value)?,
                "clone" => {
                    worlds.clone_world(&id, &value)?;
                }
                "delete" => {
                    worlds.delete(&id, &value)?;
                    if s.selected_world == id {
                        s.selected_world = worlds
                            .list()?
                            .first()
                            .map(|w| w.id.clone())
                            .unwrap_or_default();
                        layout.save(&s)?;
                    }
                }
                "export" => worlds.export(&id, &file.context("Не выбран файл архива.")?)?,
                "import" => {
                    let w = worlds.import(&file.context("Не выбран файл архива.")?)?;
                    bscp_launcher_host::world_tools::prepare(layout, &w.id, cancel)?;
                    s.selected_world = w.id;
                    layout.save(&s)?;
                }
                "open" => {
                    open_path(&worlds.folder(&id)?)?;
                    return Ok(String::new());
                }
                "backups" => {
                    backups(ui, layout, &id)?;
                    return Ok(String::new());
                }
                "backup" => {
                    worlds.automatic_backup(&id, s.automatic_backups as usize)?;
                    backups(ui, layout, &id)?;
                    return Ok("Резервная копия создана".into());
                }
                "restore" => {
                    let archive = child(&child(&layout.data(&s).join("backups"), &id)?, &value)?;
                    worlds.restore(&id, &archive)?;
                }
                _ => bail!("Неизвестная операция с миром: {action}"),
            }
            close_dialog(ui);
            refresh(ui, layout, false)?;
            Ok("Операция с миром завершена".into())
        }
        Work::Favorite(action, name, address, nickname) => {
            let mut list: Vec<ServerEntry> = read_json(&layout.at("config/servers.json"))?;
            if action == "delete" {
                list.retain(|s| s.address != address);
            } else {
                let normalized = bscp_launcher_host::network::normalize_address(address.trim())?;
                let entry = ServerEntry {
                    id: uuid::Uuid::new_v4().simple().to_string(),
                    name: if name.trim().is_empty() {
                        if bscp_launcher_host::relay::is_invite(&address) {
                            "Сервер друзей".into()
                        } else {
                            address.trim().into()
                        }
                    } else {
                        name.trim().into()
                    },
                    address: normalized,
                    nickname: nickname.trim().into(),
                };
                if let Some(existing) = list.iter_mut().find(|s| s.address == entry.address) {
                    let id = existing.id.clone();
                    *existing = entry;
                    existing.id = id;
                } else {
                    list.push(entry)
                }
            }
            write_json(&layout.at("config/servers.json"), &list)?;
            close_dialog(ui);
            refresh(ui, layout, false)?;
            Ok("Список серверов сохранён".into())
        }
        Work::Server(action) => match action.as_str() {
            "save" => {
                call(layout, "checkpoint", BTreeMap::new())?;
                Ok("Мир сохранён".into())
            }
            "stop" => {
                call(layout, "stop", BTreeMap::new())?;
                Ok("Мир сохранён, сервер остановлен".into())
            }
            "refresh" => {
                let metrics = call(layout, "metrics", BTreeMap::new())?;
                update_metrics(ui, Some(metrics));
                Ok("Показатели обновлены".into())
            }
            _ => bail!("Неизвестная команда сервера"),
        },
        Work::Update(action, visible_feed) => {
            if !s.game_branch.is_empty() {
                if visible_feed.trim() != s.game_branch {
                    bail!("CHANNEL_UNSAVED: сначала сохраните выбранную ветку.")
                }
                match action.as_str() {
                    "check" => {
                        let service = RepositoryChannel::new(layout)?.with_cancel(cancel.clone());
                        if let Ok(branches) = service.list() {
                            let retired = !branches.contains(&s.game_branch);
                            let _ = ui.upgrade_in_event_loop(move |ui| set_channels(&ui, branches));
                            ensure!(
                                !retired,
                                "CHANNEL_UNAVAILABLE: выберите активную ветку в настройках"
                            );
                        }
                        let available = service.check(&s.game_branch)?;
                        refresh(ui, layout, false)?;
                        return Ok(format!(
                            "{}: доступна сборка {}",
                            s.game_branch,
                            &available.manifest.source_commit[..8]
                        ));
                    }
                    "install" | "rollback" => {
                        call(
                            layout,
                            if action == "install" {
                                "update"
                            } else {
                                "rollback"
                            },
                            BTreeMap::new(),
                        )?;
                        refresh(ui, layout, true)?;
                        return Ok("Игровые файлы ветки подготовлены. Можно запускать мир.".into());
                    }
                    _ => bail!("CHANNEL_ACTION: неизвестная операция"),
                }
            }
            if action != "rollback" && visible_feed.trim() != s.feed_url.trim() {
                bail!("FEED_UNSAVED: Сначала сохраните изменённый адрес канала в настройках.")
            }
            if !["check", "install", "rollback"].contains(&action.as_str()) {
                bail!("UPDATE_ACTION: Неизвестная операция обновления")
            }
            if action == "check" {
                let release = UpdateService::new(layout)?
                    .with_cancel(cancel.clone())
                    .check(&s.feed_url)?;
                refresh(ui, layout, false)?;
                Ok(format!("Проверен подписанный релиз {}", release.release_id))
            } else {
                call(
                    layout,
                    if action == "rollback" {
                        "rollback"
                    } else {
                        "update"
                    },
                    BTreeMap::new(),
                )?;
                refresh(ui, layout, true)?;
                Ok("Версия подготовлена. Перезапустите лаунчер.".into())
            }
        }
        Work::Report(action, draft) => {
            if action == "github"
                && (draft.title.trim().is_empty() || draft.description.trim().is_empty())
            {
                bail!("REPORT_DESCRIPTION: Заполните название и описание проблемы.");
            }
            progress("Собираем логи и скриншоты…".into());
            let archive = Reports::prepare(layout, &draft, Some(progress))?;
            open_path(archive.parent().unwrap())?;
            if action == "github" {
                open_web(&Reports::issue_url(&draft, &archive)?)?;
                Ok(format!(
                    "GitHub открыт. Прикрепите {} и подтвердите отправку.",
                    archive.file_name().unwrap().to_string_lossy()
                ))
            } else {
                Ok(format!(
                    "Отчёт сохранён: {}",
                    archive.file_name().unwrap().to_string_lossy()
                ))
            }
        }
    }
}

fn validate_paths(layout: &Layout, settings: &Settings) -> Result<()> {
    if settings.game_path.trim().is_empty() {
        bail!("GAME_PATH: Выберите папку установленной игры.")
    }
    let game = GameLayout::resolve(layout.resolve(&settings.game_path))?;
    let data = layout.data(settings);
    no_links(&data)?;
    if contains_path(&game.root, &data)? {
        bail!("SETUP_DATA: Выберите папку данных вне установленной игры.")
    }
    Ok(())
}
fn validate_data_change(layout: &Layout, old: &Settings, new: &Settings) -> Result<()> {
    let old_data = layout.data(old);
    let new_data = layout.data(new);
    if contains_path(&old_data, &new_data)? && contains_path(&new_data, &old_data)? {
        return Ok(());
    }
    let view = HostClient::new(layout).status().unwrap_or_default();
    if view.client_running
        || matches!(view.state.as_str(), "Ready" | "Starting" | "Stopping")
        || has_user_data(&old_data)?
    {
        bail!(
            "DATA_MOVE: Сначала остановите игру и перенесите папку данных целиком. Исходная папка должна быть освобождена перед сменой пути."
        )
    }
    Ok(())
}
fn has_user_data(data: &Path) -> Result<bool> {
    for directory in ["worlds", "profiles", "backups"] {
        let folder = data.join(directory);
        if folder.is_dir() {
            for path in files(&folder)? {
                let name = path.file_name().unwrap_or_default().to_string_lossy();
                if !name.ends_with(".lock") && !name.ends_with("-lease") {
                    return Ok(true);
                }
            }
        }
    }
    Ok(false)
}
fn remember_server(
    layout: &Layout,
    favorites: &[ServerEntry],
    address: &str,
    nickname: &str,
) -> Result<()> {
    let normalized = bscp_launcher_host::network::normalize_address(address.trim())?;
    let file = layout.at("config/server-history.json");
    let mut history: Vec<ServerEntry> = read_json(&file)?;
    let matches = |entry: &ServerEntry| {
        bscp_launcher_host::network::normalize_address(entry.address.trim())
            .is_ok_and(|url| url == normalized)
    };
    let previous = history.iter().find(|entry| matches(entry)).cloned();
    let favorite = favorites.iter().find(|entry| matches(entry));
    history.retain(|entry| !matches(entry));
    history.insert(
        0,
        ServerEntry {
            id: previous
                .as_ref()
                .map(|e| e.id.clone())
                .unwrap_or_else(|| uuid::Uuid::new_v4().simple().to_string()),
            name: favorite
                .map(|e| e.name.clone())
                .or_else(|| previous.map(|e| e.name))
                .unwrap_or_else(|| {
                    if bscp_launcher_host::relay::is_invite(address) {
                        "Сервер друзей".into()
                    } else {
                        address.trim().into()
                    }
                }),
            address: normalized,
            nickname: nickname.into(),
        },
    );
    history.truncate(32);
    write_json(&file, &history)
}

fn connection_nickname(favorites: &[ServerEntry], address: &str, fallback: &str) -> Result<String> {
    let normalized = bscp_launcher_host::network::normalize_address(address.trim())?;
    let nickname = favorites
        .iter()
        .find(|entry| {
            bscp_launcher_host::network::normalize_address(entry.address.trim())
                .is_ok_and(|url| url == normalized)
        })
        .map(|entry| entry.nickname.trim())
        .filter(|nick| !nick.is_empty())
        .unwrap_or(fallback.trim());
    if nickname.is_empty()
        || nickname.encode_utf16().count() > 64
        || nickname.chars().any(char::is_control)
    {
        bail!("LOGIN_INVALID: Введите ник от 1 до 64 символов.")
    }
    Ok(nickname.into())
}
fn update_metrics(ui: &slint::Weak<AppWindow>, metrics: Option<serde_json::Value>) {
    let _ = ui.upgrade_in_event_loop(move |ui| {
        ui.set_server_uptime("Нет данных".into());
        ui.set_server_memory("Нет данных".into());
        ui.set_players_known(false);
        ui.set_players(rows(Vec::<SharedString>::new()));
        if let Some(metrics) = metrics {
            let health = &metrics["health"];
            let players = health["game"]["players"].as_array();
            ui.set_players_known(players.is_some());
            ui.set_players(rows(
                players
                    .into_iter()
                    .flatten()
                    .filter_map(|p| p["name"].as_str().map(SharedString::from))
                    .collect(),
            ));
            let (uptime, memory) = metric_values(&metrics);
            if let Some(ms) = uptime {
                ui.set_server_uptime(format!("{} мин", ms / 60000).into());
            }
            if let Some(mem) = memory {
                ui.set_server_memory(format!("{} МБ", mem / 1048576).into());
            }
        }
    });
}

fn metric_values(metrics: &serde_json::Value) -> (Option<u64>, Option<u64>) {
    (
        metrics["server"]["uptimeMs"]
            .as_u64()
            .or_else(|| metrics["health"]["uptimeMs"].as_u64()),
        metrics["server"]["heapUsed"]
            .as_u64()
            .or_else(|| metrics["health"]["jvm"]["heapUsed"].as_u64()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bridge_metrics_are_used_and_missing_metrics_stay_unknown() {
        assert_eq!(
            metric_values(&serde_json::json!({"server":{"uptimeMs":120000,"heapUsed":2097152}})),
            (Some(120000), Some(2097152))
        );
        assert_eq!(metric_values(&serde_json::Value::Null), (None, None));
    }
    fn temporary() -> PathBuf {
        let p =
            std::env::temp_dir().join(format!("bscp-controller-{}", uuid::Uuid::new_v4().simple()));
        std::fs::create_dir_all(&p).unwrap();
        p
    }
    #[test]
    fn blank_game_is_not_normalized_to_launcher_root() {
        let temp = temporary();
        let layout = Layout::new(&temp);
        let settings = Settings::default();
        let result = validate_paths(&layout, &settings);
        assert!(result.unwrap_err().to_string().starts_with("GAME_PATH:"));
        std::fs::remove_dir(&temp).unwrap();
    }
    #[test]
    fn favorite_nickname_matches_normalized_endpoint_without_global_mutation() {
        let entry = ServerEntry {
            id: "one".into(),
            name: "Favorite".into(),
            address: "http://example.test:29545/".into(),
            nickname: "FavoritePlayer".into(),
        };
        assert_eq!(
            connection_nickname(&[entry.clone()], "example.test", "GlobalPlayer").unwrap(),
            "FavoritePlayer"
        );
        assert_eq!(
            connection_nickname(&[entry], "other.test", "GlobalPlayer").unwrap(),
            "GlobalPlayer"
        );
    }
    #[test]
    fn stored_profiles_are_data_even_when_no_world_exists() {
        let temp = temporary();
        assert!(!has_user_data(&temp).unwrap());
        std::fs::create_dir_all(temp.join("profiles/default/game")).unwrap();
        std::fs::write(
            temp.join("profiles/default/game/options.txt"),
            b"user-settings",
        )
        .unwrap();
        assert!(has_user_data(&temp).unwrap());
        std::fs::remove_dir_all(temp).unwrap();
    }
    #[test]
    fn malformed_favorite_nickname_cannot_reach_host() {
        let entry = ServerEntry {
            address: "example.test".into(),
            nickname: "bad\nname".into(),
            ..Default::default()
        };
        assert!(connection_nickname(&[entry], "example.test", "Global").is_err());
    }
}

fn probe_status(result: Option<&serde_json::Value>) -> (bool, String) {
    let Some(result) = result else {
        return (false, "Проверка недоступна".into());
    };
    let Some(online) = result["online"].as_bool() else {
        return (false, "Нет данных проверки".into());
    };
    let label = if online {
        "Доступен"
    } else {
        "Недоступен"
    };
    let status = if let Some(ms) = result["milliseconds"].as_u64() {
        format!("{label} · {ms} мс")
    } else {
        label.into()
    };
    (online, status)
}
fn apply_probe(
    model: &ModelRc<ServerRow>,
    id: &str,
    address: &str,
    online: bool,
    status: &str,
) -> bool {
    for index in 0..model.row_count() {
        if let Some(mut row) = model.row_data(index) {
            if row.id.as_str() == id && row.address.as_str() == address {
                row.online = online;
                row.status = status.into();
                model.set_row_data(index, row);
                return true;
            }
        }
    }
    false
}

#[cfg(test)]
mod probe_tests {
    use super::*;
    #[test]
    fn stale_probe_cannot_overwrite_changed_address_or_replaced_entry() {
        let model: ModelRc<ServerRow> = rows(vec![ServerRow {
            id: "favorite".into(),
            address: "new.test".into(),
            status: "Не проверен".into(),
            ..Default::default()
        }]);
        assert!(!apply_probe(
            &model,
            "favorite",
            "old.test",
            true,
            "Доступен"
        ));
        assert!(!apply_probe(
            &model,
            "removed",
            "new.test",
            true,
            "Доступен"
        ));
        assert_eq!(model.row_data(0).unwrap().status.as_str(), "Не проверен");
        assert!(apply_probe(
            &model,
            "favorite",
            "new.test",
            true,
            "Доступен · 17 мс"
        ));
        assert!(model.row_data(0).unwrap().online);
    }
    #[test]
    fn probe_uses_measured_latency_and_does_not_invent_online_state() {
        assert_eq!(
            probe_status(Some(&serde_json::json!({"online":true,"milliseconds":42}))),
            (true, "Доступен · 42 мс".into())
        );
        assert_eq!(probe_status(None), (false, "Проверка недоступна".into()));
        assert!(!probe_status(Some(&serde_json::json!({"milliseconds":1}))).0);
    }
}

#[cfg(test)]
mod transfer_tests {
    use super::*;
    #[test]
    fn transfer_card_formats_global_numbers_and_eta() {
        let s = TransferSnapshot {
            phase: "download".into(),
            total_known: true,
            downloaded_bytes: 1024 * 1024,
            total_bytes: 2560 * 1024,
            remaining_bytes: 1536 * 1024,
            bytes_per_second: 1536.0 * 1024.0,
            eta_seconds: Some(61),
            ..Default::default()
        };
        let view = transfer_view(&s);
        assert_eq!(view.percent, "Сеть · 40%");
        assert_eq!(view.downloaded, "1,0 МБ из 2,5 МБ");
        assert_eq!(view.speed, "1,5 МБ/с");
        assert_eq!(view.eta, "≈ 1 мин 1 с");
    }
    #[test]
    fn unknown_plan_has_no_fake_remaining_or_eta() {
        let view = transfer_view(&TransferSnapshot::default());
        assert_eq!(view.remaining, "—");
        assert_eq!(view.eta, "—");
        assert_eq!(view.fraction, -1.0);
    }
    #[test]
    fn disk_postprocessing_keeps_network_complete_and_unknown_disk_unquantified() {
        let mut snapshot = TransferSnapshot {
            phase: "verify".into(),
            total_known: true,
            total_bytes: 90,
            downloaded_bytes: 90,
            disk_stage: "Проверка файлов".into(),
            disk_done_bytes: 15,
            disk_total_bytes: Some(60),
            ..Default::default()
        };
        let view = transfer_view(&snapshot);
        assert_eq!(view.fraction, 1.0);
        assert_eq!(view.disk_fraction, 0.25);
        assert_eq!(view.percent, "Сеть · скачано");
        snapshot.disk_total_bytes = None;
        let view = transfer_view(&snapshot);
        assert_eq!(view.disk_fraction, -1.0);
        assert!(view.disk_amount.is_empty());
    }
}

#[cfg(test)]
mod history_tests {
    use super::*;
    #[test]
    fn history_survives_reload_merges_default_port_and_keeps_recent_servers() {
        let temp = tempfile::tempdir().unwrap();
        let layout = Layout::new(temp.path());
        layout.init().unwrap();
        let favorites = vec![ServerEntry {
            name: "Друзья".into(),
            address: "example.test".into(),
            ..Default::default()
        }];
        remember_server(&layout, &favorites, "example.test", "First").unwrap();
        remember_server(&layout, &[], "other.test", "Other").unwrap();
        remember_server(&layout, &favorites, "http://example.test:29545/", "Updated").unwrap();
        let saved: Vec<ServerEntry> = read_json(&layout.at("config/server-history.json")).unwrap();
        assert_eq!(saved.len(), 2);
        assert_eq!(saved[0].name, "Друзья");
        assert_eq!(saved[0].nickname, "Updated");
        assert_eq!(saved[0].address, "http://example.test:29545/");
        assert_eq!(
            connection_nickname(&saved, "example.test", "Default").unwrap(),
            "Updated"
        );
        for n in 0..40 {
            remember_server(&layout, &[], &format!("server{n}.test"), "Player").unwrap();
        }
        let saved: Vec<ServerEntry> = read_json(&layout.at("config/server-history.json")).unwrap();
        assert_eq!(saved.len(), 32);
        assert_eq!(saved[0].address, "http://server39.test:29545/");
        let unchanged: Vec<ServerEntry> = read_json(&layout.at("config/servers.json")).unwrap();
        assert!(unchanged.is_empty());
    }
}
