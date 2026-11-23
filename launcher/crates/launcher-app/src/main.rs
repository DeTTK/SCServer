#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod controller;
slint::include_modules!();

fn start() -> anyhow::Result<()> {
    use slint::ComponentHandle;
    let args: Vec<String> = std::env::args().collect();
    let root = args
        .iter()
        .position(|v| v == "--root")
        .and_then(|i| args.get(i + 1))
        .map(std::path::PathBuf::from)
        .unwrap_or(std::env::current_exe()?.parent().unwrap().to_path_buf());
    let layout = bscp_launcher_core::Layout::new(root);
    layout.init()?;
    if let Some(exe) = bscp_launcher_core::UpdateService::new(&layout)?.verified_launcher()? {
        let current = std::env::current_exe()?.canonicalize()?;
        if current != exe.canonicalize()? {
            std::process::Command::new(exe)
                .arg("--root")
                .arg(&layout.root)
                .spawn()?;
            return Ok(());
        }
    }
    let _lease = bscp_launcher_core::Lease::acquire(&layout.at("state/rust-ui.lock"))?;
    let capture = args
        .iter()
        .position(|v| v == "--capture")
        .and_then(|i| args.get(i + 1))
        .map(std::path::PathBuf::from);
    let renderer = args
        .iter()
        .position(|v| v == "--renderer")
        .and_then(|i| args.get(i + 1))
        .map(String::as_str)
        .unwrap_or("software");
    anyhow::ensure!(
        matches!(renderer, "software" | "femtovg"),
        "Unknown renderer"
    );
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name(renderer.into())
        .select()?;
    let ui = AppWindow::new()?;
    let _binding = controller::bind(&ui, layout);
    if let Some(output) = capture {
        ui.window()
            .set_position(slint::PhysicalPosition::new(-30000, -30000));
        let weak = ui.as_weak();
        slint::Timer::single_shot(std::time::Duration::from_secs(4), move || {
            let result = (|| -> anyhow::Result<()> {
                use std::io::Write;
                let snapshot = weak.unwrap().window().take_snapshot()?;
                let mut file = std::io::BufWriter::new(std::fs::File::create(output)?);
                write!(
                    file,
                    "P6\n{} {}\n255\n",
                    snapshot.width(),
                    snapshot.height()
                )?;
                for pixel in snapshot.as_bytes().chunks_exact(4) {
                    file.write_all(&pixel[..3])?;
                }
                file.flush()?;
                Ok(())
            })();
            if let Err(e) = result {
                eprintln!("capture: {e:#}");
                std::process::exit(2);
            }
            let _ = slint::quit_event_loop();
        });
    }
    ui.run()?;
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if bscp_launcher_host::runtime::is_service_mode(&args) {
        if let Err(error) = bscp_launcher_host::runtime::run(args) {
            eprintln!("{}", bscp_launcher_core::redact(&format!("{error:#}")));
            std::process::exit(1);
        }
        return;
    }
    if let Err(error) = start() {
        let message = bscp_launcher_core::redact(&format!("{error:#}"));
        rfd::MessageDialog::new()
            .set_title("BSCPCRAFT")
            .set_description(&message)
            .set_level(rfd::MessageLevel::Error)
            .show();
        std::process::exit(1);
    }
}
