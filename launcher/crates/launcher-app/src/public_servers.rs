use crate::{AppWindow, PublicServerRow};
use anyhow::{Result, ensure};
use bscp_launcher_core::{Layout, RotatingLog, read_json, redact, write_json};
use bscp_launcher_host::directory::{
    Browser, ICON_PATH, ICON_SIDE, PublicServer, decode_icon, encode_icon,
};
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};
use std::{
    collections::HashMap,
    path::Path,
    rc::Rc,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};

fn pixels(encoded: Option<&str>) -> Option<Image> {
    let bytes = decode_icon(encoded?).ok()?;
    let buffer = SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(&bytes, ICON_SIDE, ICON_SIDE);
    Some(Image::from_rgba8(buffer))
}

pub fn show_icon(ui: &AppWindow, layout: &Layout) {
    let value = read_json::<String>(&layout.at(ICON_PATH)).ok();
    let icon = pixels(value.as_deref());
    ui.set_has_server_icon(icon.is_some());
    ui.set_server_icon(icon.unwrap_or_default());
}

pub fn save_icon(layout: &Layout, source: Option<&Path>) -> Result<()> {
    let value = match source {
        Some(path) => {
            ensure!(
                path.metadata()?.len() <= 16 * 1024 * 1024,
                "Иконка должна занимать до 16 МБ."
            );
            let mut reader = image::ImageReader::open(path)?.with_guessed_format()?;
            let mut limits = image::Limits::default();
            limits.max_image_width = Some(4096);
            limits.max_image_height = Some(4096);
            limits.max_alloc = Some(80 * 1024 * 1024);
            reader.limits(limits);
            let mut decoder = reader.into_decoder()?;
            let orientation = image::ImageDecoder::orientation(&mut decoder)?;
            let mut picture = image::DynamicImage::from_decoder(decoder)?;
            picture.apply_orientation(orientation);
            let icon = picture
                .resize_to_fill(ICON_SIDE, ICON_SIDE, image::imageops::FilterType::Lanczos3)
                .into_rgba8();
            encode_icon(icon.as_raw())?
        }
        None => String::new(),
    };
    write_json(&layout.at(ICON_PATH), &value)
}

fn server_row(server: PublicServer) -> PublicServerRow {
    let icon = pixels(server.icon.as_deref());
    PublicServerRow {
        title: server.name.into(),
        address: server.invite.into(),
        players: format!("Игроков: {}", server.players).into(),
        ping: "…".into(),
        online: false,
        has_icon: icon.is_some(),
        icon: icon.unwrap_or_default(),
    }
}

pub fn bind(ui: &AppWindow, layout: Layout, alive: Arc<AtomicBool>) {
    show_icon(ui, &layout);
    let visible = Arc::new(AtomicBool::new(false));
    let (wake, change) = mpsc::sync_channel(1);
    let show = visible.clone();
    ui.on_open_directory(move |open| {
        show.store(open, Ordering::Relaxed);
        let _ = wake.try_send(());
    });
    let ui = ui.as_weak();
    std::thread::spawn(move || {
        let logger = RotatingLog::new(layout.at("logs"), "public-directory");
        let mut browser = match Browser::new() {
            Ok(browser) => browser,
            Err(error) => {
                let _ = logger.line(&format!("Catalog browser initialization failed: {error:#}"));
                let _ = ui.upgrade_in_event_loop(|ui| {
                    ui.set_directory_message("Каталог временно недоступен".into())
                });
                return;
            }
        };
        let mut last_success = None::<Instant>;
        let mut probe_times = HashMap::<String, Instant>::new();
        while alive.load(Ordering::Relaxed) {
            if !visible.load(Ordering::Relaxed) {
                let _ = change.recv_timeout(Duration::from_secs(1));
                continue;
            }
            let _ = ui.upgrade_in_event_loop(|ui| {
                if ui.get_public_servers().row_count() == 0 && ui.get_directory_message().is_empty()
                {
                    ui.set_directory_message("Загружаем каталог…".into());
                }
            });
            let delay = match browser.list(&layout) {
                Ok(servers) => {
                    last_success = Some(Instant::now());
                    let display = servers.clone();
                    let _ = ui.upgrade_in_event_loop(move |ui| {
                        ui.set_directory_message("".into());
                        let existing: HashMap<_, _> = ui
                            .get_public_servers()
                            .iter()
                            .map(|row| (row.address.to_string(), (row.ping, row.online)))
                            .collect();
                        ui.set_public_servers(ModelRc::from(Rc::new(VecModel::from(
                            display
                                .into_iter()
                                .map(|server| {
                                    let mut row = server_row(server);
                                    if let Some((ping, online)) = existing.get(row.address.as_str())
                                    {
                                        row.ping = ping.clone();
                                        row.online = *online;
                                    }
                                    row
                                })
                                .collect::<Vec<_>>(),
                        ))));
                    });
                    probe_times.retain(|address, _| {
                        servers.iter().any(|server| &server.invite == address)
                    });
                    let now = Instant::now();
                    let probes: Vec<_> = servers
                        .into_iter()
                        .filter(|server| {
                            if probe_times.get(&server.invite).is_some_and(|last| {
                                now.duration_since(*last) < Duration::from_secs(15)
                            }) {
                                return false;
                            }
                            probe_times.insert(server.invite.clone(), now);
                            true
                        })
                        .collect();
                    let ping_ui = ui.clone();
                    browser.measure(&probes, move |address, delay| {
                        let _ = ping_ui.upgrade_in_event_loop(move |ui| {
                            let model = ui.get_public_servers();
                            if let Some((index, mut row)) = model
                                .iter()
                                .enumerate()
                                .find(|(_, row)| row.address.as_str() == address)
                            {
                                row.ping = delay.map_or_else(
                                    || "Нет ответа".into(),
                                    |ms| format!("{ms} мс").into(),
                                );
                                row.online = delay.is_some();
                                model.set_row_data(index, row);
                            }
                        });
                    });
                    Duration::from_secs(5)
                }
                Err(error) => {
                    let _ = logger.line(&redact(&format!("List refresh failed: {error:#}")));
                    let recent =
                        last_success.is_some_and(|last| last.elapsed() < Duration::from_secs(70));
                    let _ = ui.upgrade_in_event_loop(move |ui| {
                        if recent && ui.get_public_servers().row_count() > 0 {
                            ui.set_directory_message(
                                "Нет связи с каталогом. Показан последний список.".into(),
                            );
                        } else {
                            ui.set_public_servers(ModelRc::default());
                            ui.set_directory_message("Каталог временно недоступен".into());
                        }
                    });
                    Duration::from_secs(2)
                }
            };
            let _ = change.recv_timeout(delay);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_icon_is_center_cropped_and_keeps_alpha() {
        let folder = tempfile::tempdir().unwrap();
        let layout = Layout::new(folder.path());
        layout.init().unwrap();
        let source = folder.path().join("wide.png");
        let picture = image::RgbaImage::from_fn(240, 80, |x, _| {
            if (64..176).contains(&x) {
                image::Rgba([28, 74, 136, 192])
            } else {
                image::Rgba([220, 45, 52, 255])
            }
        });
        picture.save(&source).unwrap();
        save_icon(&layout, Some(&source)).unwrap();
        let value: String = read_json(&layout.at(ICON_PATH)).unwrap();
        let bytes = decode_icon(&value).unwrap();
        assert!(bytes.chunks_exact(4).all(|p| p == [28, 74, 136, 192]));
        save_icon(&layout, None).unwrap();
        assert_eq!(read_json::<String>(&layout.at(ICON_PATH)).unwrap(), "");
    }
}
