//! Native launcher persistence, safe world operations and authenticated installation.
pub mod channels;
pub mod diagnostics;
pub mod foundation;
pub mod mods;
pub mod profile;
pub mod progress;
pub mod performance;
pub mod setup;
pub mod self_update;
pub mod shortcut;
pub mod storage;
pub mod telemetry;
pub mod telemetry_export;
pub mod updates;
pub mod worlds;
pub use anyhow::Result;
pub use channels::*;
pub use diagnostics::*;
pub use foundation::*;
pub use mods::*;
pub use profile::*;
pub use progress::*;
pub use performance::*;
pub use setup::*;
pub use self_update::*;
pub use shortcut::*;
pub use storage::*;
pub use telemetry::*;
pub use telemetry_export::*;
pub use updates::*;
pub use worlds::*;
pub type Progress = dyn Fn(ProgressUpdate) + Send + Sync;
pub fn progress(callback: Option<&Progress>, text: impl Into<String>) {
    if let Some(f) = callback {
        f(ProgressUpdate::Message(text.into()));
    }
}
