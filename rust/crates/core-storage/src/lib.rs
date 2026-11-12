//! SQLite persistence, migrations, and repositories for PlasmaWave.

mod db;
mod download_repo;
mod library_repo;
mod settings_repo;

pub use db::Database;
