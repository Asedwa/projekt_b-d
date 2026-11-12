//! Download manager for **authorized** media.
//!
//! The only way to enqueue a download is with a
//! [`core_providers::AuthorizedDownload`], which a provider can only produce
//! when it holds the `AuthorizedOfflineDownloads` capability, or via
//! [`user_supplied_download`] for a direct HTTPS link the user provides.
//! There is no code path for stream extraction or DRM circumvention.

mod manager;
mod memory;
mod model;

pub use manager::{DownloadListener, DownloadManager, ManagerConfig};
pub use memory::MemoryDownloadRepository;
pub use model::{
    sanitize_file_name, user_supplied_download, DownloadRepository, DownloadState, DownloadTask,
    NewDownload,
};
