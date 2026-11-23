//! Offscreen screenshot of the compiled server settings, without opening a user profile.
slint::include_modules!();
use slint::ComponentHandle;
fn main()->anyhow::Result<()> {
    let output=std::env::args().nth(1).expect("output.ppm");
    slint::BackendSelector::new().backend_name("winit".into()).renderer_name("software".into()).select()?;
    let ui=AppWindow::new()?;ui.set_page("settings".into());ui.set_settings_tab("server".into());
    ui.set_configured(true);ui.set_version(env!("CARGO_PKG_VERSION").into());ui.set_port("29545".into());ui.set_server_memory_setting("2048".into());
    ui.window().set_position(slint::PhysicalPosition::new(-30000,-30000));let weak=ui.as_weak();
    slint::Timer::single_shot(std::time::Duration::from_secs(1),move||{
        use std::io::Write;let image=weak.unwrap().window().take_snapshot().unwrap();
        let mut file=std::io::BufWriter::new(std::fs::File::create(output).unwrap());write!(file,"P6\n{} {}\n255\n",image.width(),image.height()).unwrap();
        for pixel in image.as_bytes().chunks_exact(4){file.write_all(&pixel[..3]).unwrap();}file.flush().unwrap();slint::quit_event_loop().unwrap();
    });ui.run()?;Ok(())
}
