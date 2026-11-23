//! Native launcher persistence, safe world operations and authenticated installation.
pub mod channels;
pub mod diagnostics;
pub mod foundation;
pub mod profile;
pub mod progress;
pub mod setup;
pub mod storage;
pub mod updates;
pub mod worlds;
pub use anyhow::Result;
pub use channels::*;
pub use diagnostics::*;
pub use foundation::*;
pub use profile::*;
pub use progress::*;
pub use setup::*;
pub use storage::*;
pub use updates::*;
pub use worlds::*;
pub type Progress = dyn Fn(ProgressUpdate) + Send + Sync;
pub fn progress(callback: Option<&Progress>, text: impl Into<String>) {
    if let Some(f) = callback {
        f(ProgressUpdate::Message(text.into()));
    }
}
