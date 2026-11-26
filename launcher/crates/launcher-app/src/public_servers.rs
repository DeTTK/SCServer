use crate::{AppWindow, PublicServerRow};
use anyhow::{Result, ensure};
use bscp_launcher_core::{Layout, read_json, write_json};
use bscp_launcher_host::directory::{
    Browser, ICON_PATH, ICON_SIDE, PublicServer, decode_icon, encode_icon,
};
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};
use std::{
    path::Path,
    rc::Rc,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
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
        let Ok(browser) = Browser::new() else { return };
        while alive.load(Ordering::Relaxed) {
            if !visible.load(Ordering::Relaxed) {
                let _ = change.recv_timeout(Duration::from_secs(1));
                continue;
            }
            let _ = ui
                .upgrade_in_event_loop(|ui| ui.set_directory_message("Загружаем каталог…".into()));
            match browser.list(&layout) {
                Ok(servers) => {
                    let display = servers.clone();
                    let _ = ui.upgrade_in_event_loop(move |ui| {
                        ui.set_directory_message("".into());
                        ui.set_public_servers(ModelRc::from(Rc::new(VecModel::from(
                            display.into_iter().map(server_row).collect::<Vec<_>>(),
                        ))));
                    });
                    browser.measure(&servers, |index, delay| {
                        let address = servers[index].invite.clone();
                        let _ = ui.upgrade_in_event_loop(move |ui| {
                            let model = ui.get_public_servers();
                            if let Some(mut row) = model.row_data(index) {
                                if row.address.as_str() == address {
                                    row.ping = delay.map_or_else(
                                        || "Нет ответа".into(),
                                        |ms| format!("{ms} мс").into(),
                                    );
                                    row.online = delay.is_some();
                                    model.set_row_data(index, row);
                                }
                            }
                        });
                    });
                }
                Err(_) => {
                    let _ = ui.upgrade_in_event_loop(|ui| {
                        ui.set_public_servers(ModelRc::default());
                        ui.set_directory_message("Каталог временно недоступен".into());
                    });
                }
            }
            let _ = change.recv_timeout(Duration::from_secs(20));
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
