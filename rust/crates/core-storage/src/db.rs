use core_domain::{DomainError, DomainResult};
use rusqlite::Connection;
use std::path::Path;
use std::sync::Mutex;

pub const _CURRENT_SCHEMA_VERSION: i32 = 1;

pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    pub fn open(path: impl AsRef<Path>) -> DomainResult<Self> {
        let conn = Connection::open(path)
            .map_err(|e| DomainError::Storage(format!("failed to open sqlite database: {e}")))?;
        Self::init(conn)
    }

    pub fn in_memory() -> DomainResult<Self> {
        let conn = Connection::open_in_memory()
            .map_err(|e| DomainError::Storage(format!("failed to open in-memory sqlite: {e}")))?;
        Self::init(conn)
    }

    fn init(mut conn: Connection) -> DomainResult<Self> {
        conn.execute_batch(
            "PRAGMA foreign_keys = ON;
             PRAGMA busy_timeout = 5000;",
        )
        .map_err(|e| DomainError::Storage(format!("failed to set pragmas: {e}")))?;

        // WAL mode if not in memory
        let _ = conn.execute_batch("PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;");

        Self::migrate(&mut conn)?;

        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub(crate) fn lock(&self) -> DomainResult<std::sync::MutexGuard<'_, Connection>> {
        self.conn
            .lock()
            .map_err(|_| DomainError::Internal("database mutex poisoned".into()))
    }

    fn migrate(conn: &mut Connection) -> DomainResult<()> {
        let version: i32 = conn
            .query_row("PRAGMA user_version;", [], |row| row.get(0))
            .unwrap_or(0);

        if version < 1 {
            let tx = conn
                .transaction()
                .map_err(|e| DomainError::Storage(format!("migration transaction error: {e}")))?;

            tx.execute_batch(
                "CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS tracks (
                    id TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    artwork_url TEXT,
                    playback_uri TEXT,
                    is_preview INTEGER NOT NULL,
                    origin TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist);
                CREATE INDEX IF NOT EXISTS idx_tracks_title ON tracks(title);

                CREATE TABLE IF NOT EXISTS favorites (
                    track_id TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
                    added_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS playlists (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    kind TEXT NOT NULL,
                    artwork_url TEXT,
                    origin TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS playlist_tracks (
                    playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                    track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    PRIMARY KEY(playlist_id, position)
                );
                CREATE INDEX IF NOT EXISTS idx_playlist_tracks_tid ON playlist_tracks(track_id);

                CREATE TABLE IF NOT EXISTS downloads (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    track_id TEXT NOT NULL,
                    url TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    state TEXT NOT NULL,
                    bytes_done INTEGER NOT NULL,
                    total_bytes INTEGER,
                    expected_sha256 TEXT,
                    supports_resume INTEGER NOT NULL,
                    error TEXT,
                    attempts INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_downloads_track ON downloads(track_id);

                PRAGMA user_version = 1;",
            )
            .map_err(|e| DomainError::Storage(format!("failed to execute migration v1: {e}")))?;

            tx.commit()
                .map_err(|e| DomainError::Storage(format!("commit migration v1 error: {e}")))?;
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn in_memory_database_initializes() {
        let db = Database::in_memory().unwrap();
        let conn = db.lock().unwrap();
        let version: i32 = conn
            .query_row("PRAGMA user_version;", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, 1);
    }
}
