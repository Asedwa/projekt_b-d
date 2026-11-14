//! SQLite persistence, migrations, and repositories for PlasmaWave.

mod db;
mod download_repo;
mod library_repo;
mod settings_repo;
mod user_data_repo;

pub use db::Database;
pub use user_data_repo::{
    now_epoch_ms, StoredHistoryEntry, StoredPinnedItem, StoredPlaylistRecord,
    StoredPlaylistTrackEntry, StoredResumePlaybackState, StoredTrackListeningStat,
    StoredTrackRecord,
};
