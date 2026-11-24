use slint::platform::software_renderer::MinimalSoftwareWindow;
use slint::platform::{Clipboard, Key, Platform, PlatformError, WindowAdapter, WindowEvent};
use slint::{ComponentHandle, SharedString};
use std::rc::Rc;

const INVITE: &str = "bscp://invite-test";

slint::slint! {
    #[edition = "1.19"]
    import { Entry } from "../ui/controls.slint";

    export component PasteWindow inherits Window {
        in-out property <string> value <=> entry.value;
        public function focus_entry() { entry.focus(); }
        entry := Entry {}
    }
}

struct ClipboardPlatform;

impl Platform for ClipboardPlatform {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, PlatformError> {
        Ok(MinimalSoftwareWindow::new(Default::default()))
    }

    fn clipboard_text(&self, _clipboard: Clipboard) -> Option<String> {
        Some(INVITE.into())
    }
}

#[test]
fn entry_pastes_in_latin_and_russian_layouts() {
    slint::platform::set_platform(Box::new(ClipboardPlatform)).unwrap();
    let ui = PasteWindow::new().unwrap();
    ui.window()
        .dispatch_event(WindowEvent::WindowActiveChanged(true));
    ui.invoke_focus_entry();

    for text in ["v", "м", "М"] {
        ui.set_value(SharedString::default());
        ui.window().dispatch_event(WindowEvent::KeyPressed {
            text: Key::Control.into(),
        });
        ui.window()
            .dispatch_event(WindowEvent::KeyPressed { text: text.into() });
        ui.window()
            .dispatch_event(WindowEvent::KeyReleased { text: text.into() });
        ui.window().dispatch_event(WindowEvent::KeyReleased {
            text: Key::Control.into(),
        });
        assert_eq!(ui.get_value().as_str(), INVITE, "Ctrl+{text}");
    }

    ui.set_value(SharedString::default());
    ui.window()
        .dispatch_event(WindowEvent::KeyPressed { text: "м".into() });
    assert_eq!(ui.get_value().as_str(), "м");
}
