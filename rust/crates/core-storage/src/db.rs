use core_domain::{DomainError, DomainResult};
use rusqlite::Connection;
use std::path::Path;
use std::sync::Mutex;

pub const _CURRENT_SCHEMA_VERSION: i32 = 7;

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

        if version < 2 {
            let tx = conn
                .transaction()
                .map_err(|e| DomainError::Storage(format!("migration v2 transaction error: {e}")))?;

            tx.execute_batch(
                "CREATE TABLE IF NOT EXISTS listening_stats (
                    track_id TEXT PRIMARY KEY,
                    track_json TEXT NOT NULL,
                    play_count INTEGER NOT NULL DEFAULT 1,
                    total_listened_ms INTEGER NOT NULL DEFAULT 0,
                    last_played_at_ms INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_listening_stats_plays ON listening_stats(play_count DESC, total_listened_ms DESC);

                CREATE TABLE IF NOT EXISTS listening_history (
                    track_id TEXT PRIMARY KEY,
                    track_json TEXT NOT NULL,
                    play_count INTEGER NOT NULL DEFAULT 1,
                    played_at_ms INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_listening_history_time ON listening_history(played_at_ms DESC);

                CREATE TABLE IF NOT EXISTS resume_state (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    track_json TEXT NOT NULL,
                    position_ms INTEGER NOT NULL DEFAULT 0,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    updated_at_ms INTEGER NOT NULL DEFAULT 0,
                    queue_json TEXT NOT NULL DEFAULT '[]',
                    queue_index INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS user_custom_themes (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    background_hex TEXT NOT NULL,
                    card_hex TEXT NOT NULL,
                    accent_hex TEXT NOT NULL,
                    text_hex TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS pinned_items (
                    pin_id TEXT PRIMARY KEY,
                    pin_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_pinned_items_pos ON pinned_items(position ASC);

                CREATE TABLE IF NOT EXISTS playlist_entries_cache (
                    playlist_id TEXT PRIMARY KEY,
                    entries_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL DEFAULT 0
                );

                PRAGMA user_version = 2;",
            )
            .map_err(|e| DomainError::Storage(format!("failed to execute migration v2: {e}")))?;

            tx.commit()
                .map_err(|e| DomainError::Storage(format!("commit migration v2 error: {e}")))?;
        }

        if version < 3 {
            let tx = conn
                .transaction()
                .map_err(|e| DomainError::Storage(format!("migration v3 transaction error: {e}")))?;

            tx.execute_batch(
                "CREATE TABLE IF NOT EXISTS daily_listening_stats (
                    day_key TEXT PRIMARY KEY,
                    day_epoch INTEGER NOT NULL,
                    play_count INTEGER NOT NULL DEFAULT 0,
                    completed_count INTEGER NOT NULL DEFAULT 0,
                    skip_count INTEGER NOT NULL DEFAULT 0,
                    listened_ms INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_daily_listening_epoch ON daily_listening_stats(day_epoch DESC);

                CREATE TABLE IF NOT EXISTS hourly_listening_stats (
                    hour_of_day INTEGER PRIMARY KEY CHECK (hour_of_day >= 0 AND hour_of_day <= 23),
                    play_count INTEGER NOT NULL DEFAULT 0,
                    listened_ms INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS track_daily_plays (
                    day_epoch INTEGER NOT NULL,
                    track_id TEXT NOT NULL,
                    track_json TEXT NOT NULL,
                    play_count INTEGER NOT NULL DEFAULT 0,
                    listened_ms INTEGER NOT NULL DEFAULT 0,
                    last_played_at_ms INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (day_epoch, track_id)
                );
                CREATE INDEX IF NOT EXISTS idx_track_daily_epoch ON track_daily_plays(day_epoch DESC);

                PRAGMA user_version = 3;",
            )
            .map_err(|e| DomainError::Storage(format!("failed to execute migration v3: {e}")))?;

            tx.commit()
                .map_err(|e| DomainError::Storage(format!("commit migration v3 error: {e}")))?;
        }

        if version < 4 {
            let tx = conn
                .transaction()
                .map_err(|e| DomainError::Storage(format!("migration v4 transaction error: {e}")))?;

            tx.execute_batch(
                "CREATE INDEX IF NOT EXISTS idx_tracks_provider ON tracks(provider);
                CREATE INDEX IF NOT EXISTS idx_favorites_added_at ON favorites(added_at DESC);
                CREATE INDEX IF NOT EXISTS idx_downloads_state ON downloads(state);

                PRAGMA user_version = 4;",
            )
            .map_err(|e| DomainError::Storage(format!("failed to execute migration v4: {e}")))?;

            tx.commit()
                .map_err(|e| DomainError::Storage(format!("commit migration v4 error: {e}")))?;
        }

        if version < 5 {
            let tx = conn
                .transaction()
                .map_err(|e| DomainError::Storage(format!("migration v5 transaction error: {e}")))?;

            tx.execute_batch(
                "CREATE TABLE IF NOT EXISTS playlist_membership_cache (
                    playlist_id TEXT NOT NULL,
                    track_id TEXT NOT NULL,
                    PRIMARY KEY (playlist_id, track_id)
                );
                CREATE INDEX IF NOT EXISTS idx_playlist_membership_track
                    ON playlist_membership_cache(track_id);",
            )
            .map_err(|e| DomainError::Storage(format!("failed to execute migration v5: {e}")))?;

            // Backfill membership index from any existing cached playlist entries.
            {
                let mut select_stmt = tx
                    .prepare("SELECT playlist_id, entries_json FROM playlist_entries_cache;")
                    .map_err(|e| DomainError::Storage(format!("v5 backfill prepare: {e}")))?;
                let rows = select_stmt
                    .query_map([], |row| {
                        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
                    })
                    .map_err(|e| DomainError::Storage(format!("v5 backfill query: {e}")))?;

                let mut pairs: Vec<(String, String)> = Vec::new();
                for r in rows {
                    if let Ok((pid, json_str)) = r {
                        if let Ok(entries) =
                            serde_json::from_str::<Vec<crate::StoredPlaylistTrackEntry>>(&json_str)
                        {
                            for entry in entries {
                                pairs.push((pid.clone(), entry.track.id));
                            }
                        }
                    }
                }
                drop(select_stmt);

                if !pairs.is_empty() {
                    let mut insert_stmt = tx
                        .prepare(
                            "INSERT OR IGNORE INTO playlist_membership_cache (playlist_id, track_id)
                             VALUES (?1, ?2);",
                        )
                        .map_err(|e| DomainError::Storage(format!("v5 backfill insert prepare: {e}")))?;
                    for (pid, tid) in pairs {
                        let _ = insert_stmt.execute(rusqlite::params![pid, tid]);
                    }
                }
            }

            tx.execute_batch("PRAGMA user_version = 5;")
                .map_err(|e| DomainError::Storage(format!("v5 user_version: {e}")))?;

            tx.commit()
                .map_err(|e| DomainError::Storage(format!("commit migration v5 error: {e}")))?;
        }

        if version < 6 {
            let tx = conn
                .transaction()
                .map_err(|e| DomainError::Storage(format!("migration v6 transaction error: {e}")))?;

            tx.execute_batch(
                "CREATE TABLE IF NOT EXISTS lyrics_cache (
                    cache_key TEXT PRIMARY KEY,
                    payload_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_lyrics_cache_updated
                    ON lyrics_cache(updated_at_ms ASC);

                PRAGMA user_version = 6;",
            )
            .map_err(|e| DomainError::Storage(format!("failed to execute migration v6: {e}")))?;

            tx.commit()
                .map_err(|e| DomainError::Storage(format!("commit migration v6 error: {e}")))?;
        }

        if version < 7 {
            let tx = conn
                .transaction()
                .map_err(|e| DomainError::Storage(format!("migration v7 transaction error: {e}")))?;

            tx.execute_batch(
                "CREATE TABLE IF NOT EXISTS artwork_cache (
                    cache_key TEXT PRIMARY KEY,
                    artwork_url TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_artwork_cache_updated
                    ON artwork_cache(updated_at_ms ASC);

                PRAGMA user_version = 7;",
            )
            .map_err(|e| DomainError::Storage(format!("failed to execute migration v7: {e}")))?;

            tx.commit()
                .map_err(|e| DomainError::Storage(format!("commit migration v7 error: {e}")))?;
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
        assert_eq!(version, 7);
    }
}
