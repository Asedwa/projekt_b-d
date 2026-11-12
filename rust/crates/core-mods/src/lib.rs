//! Sandboxed WebAssembly mod runtime for PlasmaWave.
//!
//! Mods run in a strictly sandboxed `wasmi` environment with memory limits,
//! explicit permissions, and capability checks. A crashing or trapping mod
//! cannot crash the host application.

mod host;
mod manager;
mod manifest;
pub mod sample;

pub use host::ModExecutionResult;
pub use manager::{ModInfo, ModManager, ModState};
pub use manifest::{ModManifest, ModPermission, CURRENT_MOD_API_VERSION};
