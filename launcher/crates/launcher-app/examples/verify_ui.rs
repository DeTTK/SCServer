//! Real controller integration check, run only against a newly created owned fixture.
#[path = "../src/controller.rs"]
mod controller;
slint::include_modules!();
use anyhow::{Result, ensure};
use bscp_launcher_core::*;
use slint::{ComponentHandle, Timer};
use std::{
    cell::Cell,
    path::Path,
    rc::Rc,
    time::{Duration, Instant},
};

fn fixture(root: &Path) -> Result<Layout> {
    ensure!(!root.exists(), "UI fixture must not exist");
    ensure!(
        root.file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("ui-flow-owned-")
    );
    let layout = Layout::new(root);
    layout.init()?;
    for directory in [
        "game/assets",
        "game/modassets/assets/bundle",
        "bundle/Map/region",
        "bundle/Client/runtime/classes",
        "bundle/Client/runtime/dependencies",
        "bundle/Client/runtime/libs",
        "bundle/Client/runtime/natives",
        "bundle/Server/runtime/classes",
    ] {
        std::fs::create_dir_all(root.join(directory))?;
    }
    for file in [
        "game/classes.jar",
        "game/modassets/assets/bundle/configs.xeon",
        "bundle/Client/runtime/bootstrap/StandardJvmBootstrap.class",
        "bundle/Client/compat/game-resource-compat.jar",
        "bundle/Client/compat/startup-guard.jar",
        "bundle/Client/compat/weapon-events.jar",
        "bundle/Server/server.jar",
        "bundle/Server/server.template.properties",
        "bundle/Server/lib/gson-2.13.2.jar",
        "bundle/Server/runtime/dependencies/zstd-jni-1.5.0-5.jar",
        "bundle/Map/registry.json",
        "bundle/Map/region/0.bin",
    ] {
        let p = root.join(file);
        std::fs::create_dir_all(p.parent().unwrap())?;
        std::fs::write(p, b"{}")?;
    }
    Ok(layout)
}

fn main() -> Result<()> {
    let root = std::path::PathBuf::from(std::env::args().nth(1).expect("owned fixture root"));
    let layout = fixture(&root)?;
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name("software".into())
        .select()?;
    let ui = AppWindow::new()?;
    ui.window()
        .set_position(slint::PhysicalPosition::new(-30000, -30000));
    let _binding = controller::bind(&ui, layout.clone());
    let stage = Rc::new(Cell::new(0));
    let start = Instant::now();
    let weak = ui.as_weak();
    let timer = Timer::default();
    let test_layout = layout.clone();
    timer.start(slint::TimerMode::Repeated,Duration::from_millis(100),move||{
        assert!(start.elapsed()<Duration::from_secs(30),"UI flow timed out at {}",stage.get());
        let ui=weak.unwrap();if ui.get_busy(){return;}
        if stage.get()!=1 { assert!(ui.get_error().is_empty(),"stage {}: {}",stage.get(),ui.get_error()); }
        match stage.get(){
            0=>{assert!(ui.get_setup_visible());ui.invoke_setup_action("next".into());},
            1=>{assert_eq!(ui.get_error_field(),"game");assert!(!test_layout.load().unwrap().setup_completed);ui.set_game_path(root.join("game").to_string_lossy().into_owned().into());ui.set_bundle_path(root.join("bundle").to_string_lossy().into_owned().into());ui.set_nickname("ТестовыйИгрок".into());ui.invoke_setup_action("next".into());},
            2=>{assert_eq!(ui.get_setup_step(),1);ui.invoke_setup_action("next".into());},
            3=>{assert_eq!(ui.get_setup_step(),2);ui.set_first_world_name("Проверочный мир".into());ui.invoke_setup_action("finish".into());},
            4=>{assert!(ui.get_configured());assert!(!ui.get_setup_visible());let s=test_layout.load().unwrap();assert!(s.setup_completed);assert_eq!(s.nickname,"ТестовыйИгрок");LocalIdentity::load(&test_layout).unwrap().validate().unwrap();assert_eq!(WorldStore::new(test_layout.data(&s)).unwrap().get(&s.selected_world).unwrap().name,"Проверочный мир");ui.invoke_world_action("rename".into(),s.selected_world.into(),"Новое имя".into());},
            5=>{let s=test_layout.load().unwrap();assert_eq!(WorldStore::new(test_layout.data(&s)).unwrap().get(&s.selected_world).unwrap().name,"Новое имя");ui.invoke_favorite_action("save".into(),"Друг".into(),"127.0.0.1:29545".into(),"ДругойНик".into());},
            6=>{let entries:Vec<ServerEntry>=read_json(&test_layout.at("config/servers.json")).unwrap();assert_eq!(entries.len(),1);assert_eq!(entries[0].nickname,"ДругойНик");ui.set_client_memory("8192".into());ui.invoke_save_settings();},
            _=>{assert_eq!(test_layout.load().unwrap().client_memory_mb,8192);write_json(&test_layout.at("verification.json"),&serde_json::json!({"passed":true,"steps":7,"realController":true,"gameFixtureOnly":true})).unwrap();slint::quit_event_loop().unwrap();return;}
        }stage.set(stage.get()+1);
    });
    ui.run()?;
    let _ = bscp_launcher_host::HostClient::new(&layout).call("shutdown", &Default::default());
    ensure!(
        layout.at("verification.json").exists(),
        "UI verification did not complete"
    );
    println!("Native UI controller flow passed");
    Ok(())
}
