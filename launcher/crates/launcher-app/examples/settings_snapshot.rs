//! Offscreen screenshot of a compiled launcher view, without opening a user profile.
slint::include_modules!();
use slint::ComponentHandle;
fn main() -> anyhow::Result<()> {
    let output = std::env::args().nth(1).expect("output.ppm");
    let view = std::env::args().nth(2).unwrap_or_else(|| "settings".into());
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name("software".into())
        .select()?;
    let ui = AppWindow::new()?;
    ui.set_page("settings".into());
    ui.set_settings_tab("server".into());
    ui.set_configured(true);
    ui.set_version(env!("CARGO_PKG_VERSION").into());
    ui.set_port("29545".into());
    ui.set_server_memory_setting("2048".into());
    if view == "home" {
        ui.set_page("home".into());
        ui.set_nickname("Player".into());
        ui.set_selected_world_title("Основной мир".into());
        ui.set_favorites(slint::ModelRc::new(slint::VecModel::from(vec![
            ServerRow {
                id: "visual-fixture".into(),
                title: "Сервер друзей".into(),
                address: "127.0.0.1".into(),
                status: "Не проверен".into(),
                online: false,
            },
        ])));
    }
    if view == "mods" || view == "mods-installed" || view == "mods-empty" {
        ui.set_page("mods".into());
        if view != "mods-empty" {
            let bundled = ModRow {
                id: "command-completion".into(),
                title: "Подсказки команд".into(),
                description: "Показывает варианты команд при вводе в игровом чате.".into(),
                version: "1.0".into(),
                side: "Клиент".into(),
                source: "Канал сборки".into(),
                installed: true,
                enabled: true,
                required: true,
                ..Default::default()
            };
            let local = ModRow {
                id: "visual-fixture".into(),
                title: "Настройки интерфейса".into(),
                description: "Пользовательские настройки игрового интерфейса.".into(),
                version: "1.2".into(),
                side: "Клиент".into(),
                source: "interface-settings.jar".into(),
                installed: true,
                enabled: true,
                ..Default::default()
            };
            ui.set_catalog_mods(slint::ModelRc::new(slint::VecModel::from(vec![
                bundled.clone(),
            ])));
            ui.set_installed_mods(slint::ModelRc::new(slint::VecModel::from(vec![
                bundled.clone(),
                local.clone(),
            ])));
            if view == "mods-installed" {
                ui.set_mods_tab("installed".into());
                ui.set_selected_mod(local);
            } else {
                ui.set_selected_mod(bundled);
            }
        }
    }
    if view == "relay" {
        ui.set_page("server".into());
        ui.set_server_running(true);
        ui.set_server_state("Мир запущен".into());
        ui.set_server_address("192.168.1.20:29545".into());
        ui.set_relay_status(
            "Приглашение доступно. Прямое соединение или ретранслятор выбираются автоматически."
                .into(),
        );
        ui.set_relay_invite("bscp://example-invitation-for-visual-verification".into());
    }
    if view == "report" {
        ui.set_page("diagnostics".into());
    }
    if view == "about" {
        ui.set_dialog("about".into());
    }
    if view.starts_with("setup") {
        ui.set_setup_visible(true);
        ui.set_configured(false);
        ui.set_setup_step(if view == "setup2" { 2 } else { 0 });
        ui.set_game_path("C:\\Games\\STALCRAFT".into());
        ui.set_data_path("GameData".into());
        ui.set_nickname("Player".into());
        ui.set_game_branch("launtest".into());
    }
    if let Some(width) = std::env::args().nth(3) {
        ui.window()
            .set_size(slint::PhysicalSize::new(width.parse()?, 688));
    }
    ui.window()
        .set_position(slint::PhysicalPosition::new(-30000, -30000));
    let weak = ui.as_weak();
    slint::Timer::single_shot(std::time::Duration::from_secs(1), move || {
        use std::io::Write;
        let image = weak.unwrap().window().take_snapshot().unwrap();
        let mut file = std::io::BufWriter::new(std::fs::File::create(output).unwrap());
        write!(file, "P6\n{} {}\n255\n", image.width(), image.height()).unwrap();
        for pixel in image.as_bytes().chunks_exact(4) {
            file.write_all(&pixel[..3]).unwrap();
        }
        file.flush().unwrap();
        slint::quit_event_loop().unwrap();
    });
    ui.run()?;
    Ok(())
}
