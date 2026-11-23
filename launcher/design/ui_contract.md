# Native Slint UI contract

The public root is `AppWindow` in `crates/launcher-app/ui/main.slint`. All properties and callbacks are declared directly on AppWindow. Model structs are declared in `ui/api.slint`. The generated Rust API uses underscores for hyphens. Native window chrome owns minimize, maximize, dragging, and close.

## Data

| Property | Meaning |
| --- | --- |
| `page` | `home`, `worlds`, `server`, `diagnostics`, `settings`. The navigation handler assigns it after accepting a request. |
| `version, status, error, error-field, progress-text` | Display strings. Empty strings hide optional output. Field errors use browse keys below or `nick, cmem, smem, port, host, feed, world`. Error messages should identify a correction. |
| `busy, configured` | Pending operation and completed game setup. Launch actions on unconfigured installs must route to setup. |
| `progress` | Fraction 0–1. A negative value means unknown. No fictitious percentages. |
| `worlds, backups` | Arrays of WorldRow { id, title, detail } strings. Backup id is the backend identifier for restore. |
| `selected-world-id, selected-world-title` | Authoritative world selection. |
| `favorites, project-servers` | Arrays of ServerRow { id, title, address, status: string, online: bool }. Empty status means unknown. |
| `changes` | Array of ChangeRow { title, body } strings drawn from local release notes or a verified release. |
| `server-running, client-running` | Actual process state. |
| `server-state, server-address, server-uptime, server-memory` | Localized display strings, empty if unknown. |
| `players, players-known` | Array of player names, plus whether a complete live roster is available. |
| `setup-visible, setup-step` | Dedicated wizard; steps are zero-based (0 paths, 1 components, 2 prepare). |
| `settings-tab` | `basic, client, server, updates`. |
| `game-path, data-path, nickname, bundle-path, client-path, server-path, client-memory, server-memory-setting, advertised-host, port, feed-url, first-world-name, address` | String drafts. Load once before showing and read on submit. Polling host state must preserve unsaved edits. |
| `dialog, dialog-id, dialog-value, favorite-name, favorite-nick` | Dialog kind and edit buffers. Empty kind dismisses. The handler clears the kind only on success; clear stale operation errors when opening or abandoning a form. |

## Commands

| Callback | Arguments and result |
| --- | --- |
| `navigate(page)` | Change page. |
| `launch(with_client)` | True starts singleplayer, false starts only the server. |
| `connect(address)` | Address from the field or selected server. |
| `select_world(id)` | Persist active world and update selected id/title. |
| `world_action(action,id,value)` | `create, rename, clone` use value as name. `delete` receives the exact world name typed in the confirmation field as value. `export, import` ask for archive path natively. `backups` loads backups for id and opens dialog="backups". `backup` creates one and refreshes list. `restore` receives backup id as value after confirmation. `open` opens world folder. Ignore unused empty arguments. |
| `favorite_action(action,title,address,nick)` | `save` upserts favorite server; title and nickname are optional. |
| `server_action(action)` | `save, stop, refresh`. |
| `browse(field)` | Open native directory picker for `game, data, client, server, bundle`. Write the matching draft only if accepted. |
| `setup_action(action)` | `next, back, finish, close, cancel`. Validate and advance in Rust. Cancel signals active work. |
| `save_settings()` | Read, validate, persist draft. |
| `install_components(source)` | `download` for verified remote package or `zip` to choose local archive natively. Populate bundle-path on success. |
| `update_action(action)` | `check, install`. Rust validates signed release data before applying. |
| `report(description, performance)` | Explicit request to create local archive. Checkbox enables optional JFR. |

World create/rename/clone/delete/restore confirmations and favorite editor use in-window Slint dialogs built from the same controls. Diagnostic drafts and selection of the displayed servers tab are view-local. Modal kinds are `create, rename, clone, delete, backups, restore, favorite, stop, about`. The stop confirmation submits server_action("stop"). About is view-local.




## Diagnostic capture

The Windows render check used Slint's public snapshot API. Set `SLINT_BACKEND=winit-software` and `SLINT_SCALE_FACTOR=1` in the capture process environment before constructing its window. Populate an isolated instance with the intended models, size it in logical pixels, and give the event loop time to lay out the first frame.

```rust
ui.window().set_size(slint::LogicalSize::new(width, height));
ui.show()?;
let weak = ui.as_weak();
slint::Timer::single_shot(std::time::Duration::from_millis(450), move || {
    if let Some(window) = weak.upgrade() {
        if let Ok(snapshot) = window.window().take_snapshot() {
            // Copy or encode snapshot.as_bytes() as RGBA8 using
            // snapshot.width() and snapshot.height() as physical dimensions.
        }
    }
    let _ = slint::quit_event_loop();
});
slint::run_event_loop()?;
```

Production capture should report a snapshot or encoding error to its caller. The isolated check used `Window::dispatch_event` with pointer press/release, Tab, Return, Escape and Unicode input. This exercised worlds/settings navigation, settings tab activation, modal focus, text editing and cancellation without starting any game process.

