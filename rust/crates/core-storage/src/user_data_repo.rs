use std::time::{SystemTime, UNIX_EPOCH};

use core_domain::{DomainError, DomainResult};
use core_themes::CustomThemeSpec;
use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::Database;

const TOTAL_LISTENING_KEY: &str = "stats.total_listening_time_ms";

pub fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredTrackRecord {
    pub id: String,
    pub provider: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub artwork_url: Option<String>,
    pub playback_uri: Option<String>,
    pub is_preview: bool,
    pub origin: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredPlaylistRecord {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub is_local: bool,
    pub track_count: u32,
    pub artwork_url: Option<String>,
    pub origin: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredTrackListeningStat {
    pub track: StoredTrackRecord,
    pub play_count: u32,
    pub total_listened_ms: u64,
    pub last_played_at_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredHistoryEntry {
    pub track: StoredTrackRecord,
    pub play_count: u32,
    pub played_at_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredResumePlaybackState {
    pub track: StoredTrackRecord,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub updated_at_ms: u64,
    pub queue_tracks: Vec<StoredTrackRecord>,
    pub queue_index: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredPinnedItem {
    pub pin_id: String,
    pub pin_type: String,
    pub track: Option<StoredTrackRecord>,
    pub playlist: Option<StoredPlaylistRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredPlaylistTrackEntry {
    pub track: StoredTrackRecord,
    pub added_at: Option<i64>,
    pub original_index: u32,
}

impl Database {
    // ------------------------------------------------------------------------
    // Listening Statistics & History
    // ------------------------------------------------------------------------
    pub fn record_track_play_stat(
        &self,
        track: &StoredTrackRecord,
        now_ms: u64,
    ) -> DomainResult<()> {
        let track_json = serde_json::to_string(track)
            .map_err(|e| DomainError::Storage(format!("serialize track error: {e}")))?;
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO listening_stats (track_id, track_json, play_count, total_listened_ms, last_played_at_ms)
             VALUES (?1, ?2, 1, 0, ?3)
             ON CONFLICT(track_id) DO UPDATE SET
                track_json = excluded.track_json,
                play_count = listening_stats.play_count + 1,
                last_played_at_ms = excluded.last_played_at_ms;",
            params![track.id, track_json, now_ms as i64],
        )
        .map_err(|e| DomainError::Storage(format!("record_track_play_stat error: {e}")))?;
        Ok(())
    }

    pub fn record_listening_tick(
        &self,
        track: &StoredTrackRecord,
        delta_ms: u64,
        now_ms: u64,
    ) -> DomainResult<u64> {
        if delta_ms == 0 {
            return self.get_total_listening_time_ms();
        }
        let track_json = serde_json::to_string(track)
            .map_err(|e| DomainError::Storage(format!("serialize track error: {e}")))?;
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;

        tx.execute(
            "INSERT INTO listening_stats (track_id, track_json, play_count, total_listened_ms, last_played_at_ms)
             VALUES (?1, ?2, 1, ?3, ?4)
             ON CONFLICT(track_id) DO UPDATE SET
                track_json = excluded.track_json,
                total_listened_ms = listening_stats.total_listened_ms + ?3,
                last_played_at_ms = excluded.last_played_at_ms;",
            params![track.id, track_json, delta_ms as i64, now_ms as i64],
        )
        .map_err(|e| DomainError::Storage(format!("record_listening_tick stat error: {e}")))?;

        let current_total: u64 = tx
            .query_row(
                "SELECT value FROM settings WHERE key = ?1;",
                params![TOTAL_LISTENING_KEY],
                |row| row.get::<_, String>(0),
            )
            .optional()
            .map_err(|e| DomainError::Storage(format!("read total listening time error: {e}")))?
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0);

        let next_total = current_total.saturating_add(delta_ms);
        tx.execute(
            "INSERT INTO settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value;",
            params![TOTAL_LISTENING_KEY, next_total.to_string()],
        )
        .map_err(|e| DomainError::Storage(format!("write total listening time error: {e}")))?;

        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit tick error: {e}")))?;
        Ok(next_total)
    }

    pub fn get_listening_stats(&self, limit: u32) -> DomainResult<Vec<StoredTrackListeningStat>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT track_json, play_count, total_listened_ms, last_played_at_ms
                 FROM listening_stats
                 ORDER BY play_count DESC, total_listened_ms DESC, last_played_at_ms DESC
                 LIMIT ?1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare get_listening_stats: {e}")))?;

        let rows = stmt
            .query_map(params![limit], |row| {
                let raw: String = row.get(0)?;
                let play_count: i64 = row.get(1)?;
                let total_listened_ms: i64 = row.get(2)?;
                let last_played_at_ms: i64 = row.get(3)?;
                Ok((
                    raw,
                    play_count.max(0) as u32,
                    total_listened_ms.max(0) as u64,
                    last_played_at_ms.max(0) as u64,
                ))
            })
            .map_err(|e| DomainError::Storage(format!("query get_listening_stats: {e}")))?;

        let mut out = Vec::new();
        for r in rows {
            let (raw, play_count, total_listened_ms, last_played_at_ms) =
                r.map_err(|e| DomainError::Storage(format!("row get_listening_stats: {e}")))?;
            if let Ok(track) = serde_json::from_str::<StoredTrackRecord>(&raw) {
                out.push(StoredTrackListeningStat {
                    track,
                    play_count,
                    total_listened_ms,
                    last_played_at_ms,
                });
            }
        }
        Ok(out)
    }

    pub fn upsert_listening_stats_batch(
        &self,
        stats: &[StoredTrackListeningStat],
    ) -> DomainResult<()> {
        if stats.is_empty() {
            return Ok(());
        }
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;
        {
            let mut stmt = tx
                .prepare(
                    "INSERT INTO listening_stats (track_id, track_json, play_count, total_listened_ms, last_played_at_ms)
                     VALUES (?1, ?2, ?3, ?4, ?5)
                     ON CONFLICT(track_id) DO UPDATE SET
                        track_json = excluded.track_json,
                        play_count = MAX(listening_stats.play_count, excluded.play_count),
                        total_listened_ms = MAX(listening_stats.total_listened_ms, excluded.total_listened_ms),
                        last_played_at_ms = MAX(listening_stats.last_played_at_ms, excluded.last_played_at_ms);",
                )
                .map_err(|e| DomainError::Storage(format!("prepare batch stats: {e}")))?;

            for s in stats {
                if let Ok(json) = serde_json::to_string(&s.track) {
                    stmt.execute(params![
                        s.track.id,
                        json,
                        s.play_count as i64,
                        s.total_listened_ms as i64,
                        s.last_played_at_ms as i64,
                    ])
                    .map_err(|e| DomainError::Storage(format!("exec batch stats: {e}")))?;
                }
            }
        }
        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit batch stats: {e}")))?;
        Ok(())
    }

    pub fn get_total_listening_time_ms(&self) -> DomainResult<u64> {
        let conn = self.lock()?;
        let saved: Option<String> = conn
            .query_row(
                "SELECT value FROM settings WHERE key = ?1;",
                params![TOTAL_LISTENING_KEY],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| DomainError::Storage(format!("read total listening time: {e}")))?;

        if let Some(val) = saved.and_then(|s| s.parse::<u64>().ok()) {
            return Ok(val);
        }

        let sum: i64 = conn
            .query_row(
                "SELECT COALESCE(SUM(total_listened_ms), 0) FROM listening_stats;",
                [],
                |row| row.get(0),
            )
            .unwrap_or(0);
        Ok(sum.max(0) as u64)
    }

    pub fn set_total_listening_time_ms(&self, total_ms: u64) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value;",
            params![TOTAL_LISTENING_KEY, total_ms.to_string()],
        )
        .map_err(|e| DomainError::Storage(format!("set_total_listening_time_ms: {e}")))?;
        Ok(())
    }

    pub fn record_history_entry(
        &self,
        track: &StoredTrackRecord,
        now_ms: u64,
    ) -> DomainResult<()> {
        let track_json = serde_json::to_string(track)
            .map_err(|e| DomainError::Storage(format!("serialize track error: {e}")))?;
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO listening_history (track_id, track_json, play_count, played_at_ms)
             VALUES (?1, ?2, 1, ?3)
             ON CONFLICT(track_id) DO UPDATE SET
                track_json = excluded.track_json,
                play_count = listening_history.play_count + 1,
                played_at_ms = excluded.played_at_ms;",
            params![track.id, track_json, now_ms as i64],
        )
        .map_err(|e| DomainError::Storage(format!("record_history_entry error: {e}")))?;
        Ok(())
    }

    pub fn upsert_history_batch(&self, entries: &[StoredHistoryEntry]) -> DomainResult<()> {
        if entries.is_empty() {
            return Ok(());
        }
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;
        {
            let mut stmt = tx
                .prepare(
                    "INSERT INTO listening_history (track_id, track_json, play_count, played_at_ms)
                     VALUES (?1, ?2, ?3, ?4)
                     ON CONFLICT(track_id) DO UPDATE SET
                        track_json = excluded.track_json,
                        play_count = MAX(listening_history.play_count, excluded.play_count),
                        played_at_ms = MAX(listening_history.played_at_ms, excluded.played_at_ms);",
                )
                .map_err(|e| DomainError::Storage(format!("prepare batch history: {e}")))?;

            for e in entries {
                if let Ok(json) = serde_json::to_string(&e.track) {
                    stmt.execute(params![
                        e.track.id,
                        json,
                        e.play_count as i64,
                        e.played_at_ms as i64,
                    ])
                    .map_err(|err| DomainError::Storage(format!("exec batch history: {err}")))?;
                }
            }
        }
        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit batch history: {e}")))?;
        Ok(())
    }

    pub fn get_recent_tracks(&self, limit: u32) -> DomainResult<Vec<StoredTrackRecord>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT track_json FROM listening_history
                 ORDER BY played_at_ms DESC
                 LIMIT ?1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare get_recent_tracks: {e}")))?;

        let rows = stmt
            .query_map(params![limit], |row| row.get::<_, String>(0))
            .map_err(|e| DomainError::Storage(format!("query get_recent_tracks: {e}")))?;

        let mut out = Vec::new();
        for r in rows {
            let raw = r.map_err(|e| DomainError::Storage(format!("row get_recent_tracks: {e}")))?;
            if let Ok(track) = serde_json::from_str::<StoredTrackRecord>(&raw) {
                out.push(track);
            }
        }
        Ok(out)
    }

    pub fn get_most_played_tracks(&self, limit: u32) -> DomainResult<Vec<StoredTrackRecord>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT track_json FROM listening_stats
                 ORDER BY play_count DESC, total_listened_ms DESC, last_played_at_ms DESC
                 LIMIT ?1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare get_most_played_tracks: {e}")))?;

        let rows = stmt
            .query_map(params![limit], |row| row.get::<_, String>(0))
            .map_err(|e| DomainError::Storage(format!("query get_most_played_tracks: {e}")))?;

        let mut out = Vec::new();
        for r in rows {
            let raw =
                r.map_err(|e| DomainError::Storage(format!("row get_most_played_tracks: {e}")))?;
            if let Ok(track) = serde_json::from_str::<StoredTrackRecord>(&raw) {
                out.push(track);
            }
        }
        if !out.is_empty() {
            return Ok(out);
        }

        // Fallback to listening_history if listening_stats is empty
        let mut hist_stmt = conn
            .prepare(
                "SELECT track_json FROM listening_history
                 ORDER BY play_count DESC, played_at_ms DESC
                 LIMIT ?1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare fallback most played: {e}")))?;
        let hist_rows = hist_stmt
            .query_map(params![limit], |row| row.get::<_, String>(0))
            .map_err(|e| DomainError::Storage(format!("query fallback most played: {e}")))?;
        for r in hist_rows {
            let raw =
                r.map_err(|e| DomainError::Storage(format!("row fallback most played: {e}")))?;
            if let Ok(track) = serde_json::from_str::<StoredTrackRecord>(&raw) {
                out.push(track);
            }
        }
        Ok(out)
    }

    // ------------------------------------------------------------------------
    // Resume Playback State
    // ------------------------------------------------------------------------
    pub fn save_resume_state(&self, state: &StoredResumePlaybackState) -> DomainResult<()> {
        let track_json = serde_json::to_string(&state.track)
            .map_err(|e| DomainError::Storage(format!("serialize resume track: {e}")))?;
        let queue_json = serde_json::to_string(&state.queue_tracks)
            .map_err(|e| DomainError::Storage(format!("serialize resume queue: {e}")))?;
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO resume_state (id, track_json, position_ms, duration_ms, updated_at_ms, queue_json, queue_index)
             VALUES (1, ?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(id) DO UPDATE SET
                track_json = excluded.track_json,
                position_ms = excluded.position_ms,
                duration_ms = excluded.duration_ms,
                updated_at_ms = excluded.updated_at_ms,
                queue_json = excluded.queue_json,
                queue_index = excluded.queue_index;",
            params![
                track_json,
                state.position_ms as i64,
                state.duration_ms as i64,
                state.updated_at_ms as i64,
                queue_json,
                state.queue_index as i64,
            ],
        )
        .map_err(|e| DomainError::Storage(format!("save_resume_state: {e}")))?;
        Ok(())
    }

    pub fn get_resume_state(&self) -> DomainResult<Option<StoredResumePlaybackState>> {
        let conn = self.lock()?;
        let row: Option<(String, i64, i64, i64, String, i64)> = conn
            .query_row(
                "SELECT track_json, position_ms, duration_ms, updated_at_ms, queue_json, queue_index
                 FROM resume_state WHERE id = 1;",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?)),
            )
            .optional()
            .map_err(|e| DomainError::Storage(format!("get_resume_state: {e}")))?;

        if let Some((track_json, pos, dur, updated, queue_json, idx)) = row {
            if let Ok(track) = serde_json::from_str::<StoredTrackRecord>(&track_json) {
                let mut queue = serde_json::from_str::<Vec<StoredTrackRecord>>(&queue_json)
                    .unwrap_or_default();
                if queue.is_empty() {
                    queue.push(track.clone());
                }
                return Ok(Some(StoredResumePlaybackState {
                    track,
                    position_ms: pos.max(0) as u64,
                    duration_ms: dur.max(0) as u64,
                    updated_at_ms: updated.max(0) as u64,
                    queue_tracks: queue,
                    queue_index: idx.max(0) as u32,
                }));
            }
        }
        Ok(None)
    }

    pub fn clear_resume_state(&self) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM resume_state;", [])
            .map_err(|e| DomainError::Storage(format!("clear_resume_state: {e}")))?;
        Ok(())
    }

    pub fn clear_all_collected_user_data(&self) -> DomainResult<()> {
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;
        tx.execute("DELETE FROM listening_stats;", [])
            .map_err(|e| DomainError::Storage(format!("clear listening_stats: {e}")))?;
        tx.execute("DELETE FROM listening_history;", [])
            .map_err(|e| DomainError::Storage(format!("clear listening_history: {e}")))?;
        tx.execute("DELETE FROM resume_state;", [])
            .map_err(|e| DomainError::Storage(format!("clear resume_state: {e}")))?;
        tx.execute(
            "INSERT INTO settings (key, value) VALUES (?1, '0')
             ON CONFLICT(key) DO UPDATE SET value = '0';",
            params![TOTAL_LISTENING_KEY],
        )
        .map_err(|e| DomainError::Storage(format!("reset total listening time: {e}")))?;
        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit clear user data: {e}")))?;
        Ok(())
    }

    // ------------------------------------------------------------------------
    // User Custom Themes
    // ------------------------------------------------------------------------
    pub fn save_user_custom_theme(
        &self,
        spec: &CustomThemeSpec,
        now_ms: u64,
    ) -> DomainResult<()> {
        let norm = spec.normalized();
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO user_custom_themes (id, name, background_hex, card_hex, accent_hex, text_hex, updated_at_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                background_hex = excluded.background_hex,
                card_hex = excluded.card_hex,
                accent_hex = excluded.accent_hex,
                text_hex = excluded.text_hex,
                updated_at_ms = excluded.updated_at_ms;",
            params![
                norm.id,
                norm.name,
                norm.background_hex,
                norm.card_hex,
                norm.accent_hex,
                norm.text_hex,
                now_ms as i64,
            ],
        )
        .map_err(|e| DomainError::Storage(format!("save_user_custom_theme: {e}")))?;
        Ok(())
    }

    pub fn delete_user_custom_theme(&self, theme_id: &str) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute(
            "DELETE FROM user_custom_themes WHERE id = ?1;",
            params![theme_id],
        )
        .map_err(|e| DomainError::Storage(format!("delete_user_custom_theme: {e}")))?;
        Ok(())
    }

    pub fn list_user_custom_themes(&self) -> DomainResult<Vec<CustomThemeSpec>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT id, name, background_hex, card_hex, accent_hex, text_hex
                 FROM user_custom_themes
                 ORDER BY updated_at_ms ASC, id ASC;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare list_user_custom_themes: {e}")))?;

        let rows = stmt
            .query_map([], |row| {
                Ok(CustomThemeSpec {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    background_hex: row.get(2)?,
                    card_hex: row.get(3)?,
                    accent_hex: row.get(4)?,
                    text_hex: row.get(5)?,
                })
            })
            .map_err(|e| DomainError::Storage(format!("query list_user_custom_themes: {e}")))?;

        let mut out = Vec::new();
        for r in rows {
            out.push(
                r.map_err(|e| DomainError::Storage(format!("row list_user_custom_themes: {e}")))?,
            );
        }
        Ok(out)
    }

    // ------------------------------------------------------------------------
    // Pinned Items
    // ------------------------------------------------------------------------
    pub fn get_pinned_items(&self) -> DomainResult<Vec<StoredPinnedItem>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT payload_json FROM pinned_items
                 ORDER BY position ASC;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare get_pinned_items: {e}")))?;

        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .map_err(|e| DomainError::Storage(format!("query get_pinned_items: {e}")))?;

        let mut out = Vec::new();
        for r in rows {
            let raw = r.map_err(|e| DomainError::Storage(format!("row get_pinned_items: {e}")))?;
            if let Ok(item) = serde_json::from_str::<StoredPinnedItem>(&raw) {
                out.push(item);
            }
        }
        Ok(out)
    }

    pub fn set_pinned_items(&self, items: &[StoredPinnedItem]) -> DomainResult<()> {
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;
        tx.execute("DELETE FROM pinned_items;", [])
            .map_err(|e| DomainError::Storage(format!("clear pinned_items: {e}")))?;
        {
            let mut stmt = tx
                .prepare(
                    "INSERT INTO pinned_items (pin_id, pin_type, payload_json, position)
                     VALUES (?1, ?2, ?3, ?4);",
                )
                .map_err(|e| DomainError::Storage(format!("prepare insert pinned_items: {e}")))?;

            for (idx, item) in items.iter().enumerate() {
                if let Ok(payload) = serde_json::to_string(item) {
                    stmt.execute(params![item.pin_id, item.pin_type, payload, idx as i64])
                        .map_err(|e| {
                            DomainError::Storage(format!("exec insert pinned_items: {e}"))
                        })?;
                }
            }
        }
        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit set_pinned_items: {e}")))?;
        Ok(())
    }

    pub fn toggle_pinned_item(
        &self,
        item: StoredPinnedItem,
    ) -> DomainResult<Vec<StoredPinnedItem>> {
        let mut current = self.get_pinned_items()?;
        if let Some(pos) = current.iter().position(|x| x.pin_id == item.pin_id) {
            current.remove(pos);
        } else {
            current.push(item);
        }
        self.set_pinned_items(&current)?;
        Ok(current)
    }

    pub fn remove_pinned_item(&self, pin_id: &str) -> DomainResult<Vec<StoredPinnedItem>> {
        let mut current = self.get_pinned_items()?;
        current.retain(|x| x.pin_id != pin_id);
        self.set_pinned_items(&current)?;
        Ok(current)
    }

    // ------------------------------------------------------------------------
    // Fast Playlist Track Entries Cache
    // ------------------------------------------------------------------------
    pub fn get_cached_playlist_entries(
        &self,
        playlist_id: &str,
    ) -> DomainResult<Option<Vec<StoredPlaylistTrackEntry>>> {
        let conn = self.lock()?;
        let raw: Option<String> = conn
            .query_row(
                "SELECT entries_json FROM playlist_entries_cache WHERE playlist_id = ?1;",
                params![playlist_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| DomainError::Storage(format!("get_cached_playlist_entries: {e}")))?;

        if let Some(json_str) = raw {
            if let Ok(entries) = serde_json::from_str::<Vec<StoredPlaylistTrackEntry>>(&json_str) {
                if !entries.is_empty() {
                    return Ok(Some(entries));
                }
            }
        }
        Ok(None)
    }

    pub fn set_cached_playlist_entries(
        &self,
        playlist_id: &str,
        entries: &[StoredPlaylistTrackEntry],
        now_ms: u64,
    ) -> DomainResult<()> {
        let trimmed: Vec<&StoredPlaylistTrackEntry> = entries.iter().take(400).collect();
        let entries_json = serde_json::to_string(&trimmed)
            .map_err(|e| DomainError::Storage(format!("serialize playlist cache: {e}")))?;
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO playlist_entries_cache (playlist_id, entries_json, updated_at_ms)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(playlist_id) DO UPDATE SET
                entries_json = excluded.entries_json,
                updated_at_ms = excluded.updated_at_ms;",
            params![playlist_id, entries_json, now_ms as i64],
        )
        .map_err(|e| DomainError::Storage(format!("set_cached_playlist_entries: {e}")))?;
        Ok(())
    }

    pub fn get_all_cached_playlist_entries(
        &self,
    ) -> DomainResult<Vec<(String, Vec<StoredPlaylistTrackEntry>)>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare("SELECT playlist_id, entries_json FROM playlist_entries_cache;")
            .map_err(|e| DomainError::Storage(format!("prepare all playlist cache: {e}")))?;

        let rows = stmt
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| DomainError::Storage(format!("query all playlist cache: {e}")))?;

        let mut out = Vec::new();
        for r in rows {
            let (pid, raw) =
                r.map_err(|e| DomainError::Storage(format!("row all playlist cache: {e}")))?;
            if let Ok(entries) = serde_json::from_str::<Vec<StoredPlaylistTrackEntry>>(&raw) {
                if !entries.is_empty() {
                    out.push((pid, entries));
                }
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_track(id: &str, title: &str, artist: &str) -> StoredTrackRecord {
        StoredTrackRecord {
            id: id.into(),
            provider: "deezer".into(),
            title: title.into(),
            artist: artist.into(),
            album: "Test Album".into(),
            duration_ms: 200_000,
            artwork_url: None,
            playback_uri: None,
            is_preview: false,
            origin: "real".into(),
        }
    }

    #[test]
    fn stats_history_and_resume_roundtrip() {
        let db = Database::in_memory().unwrap();
        let t1 = sample_track("deezer:101", "Runaway", "AURORA");
        let t2 = sample_track("deezer:102", "Impostor Syndrome", "Sidney Gish");

        db.record_track_play_stat(&t1, 1000).unwrap();
        db.record_track_play_stat(&t1, 2000).unwrap();
        db.record_track_play_stat(&t2, 3000).unwrap();

        let total = db.record_listening_tick(&t1, 45_000, 4000).unwrap();
        assert_eq!(total, 45_000);

        let stats = db.get_listening_stats(10).unwrap();
        assert_eq!(stats.len(), 2);
        assert_eq!(stats[0].track.title, "Runaway");
        assert_eq!(stats[0].play_count, 2);
        assert_eq!(stats[0].total_listened_ms, 45_000);

        db.record_history_entry(&t1, 1000).unwrap();
        db.record_history_entry(&t2, 2000).unwrap();
        let recent = db.get_recent_tracks(10).unwrap();
        assert_eq!(recent[0].title, "Impostor Syndrome");

        let resume = StoredResumePlaybackState {
            track: t1.clone(),
            position_ms: 82_000,
            duration_ms: 200_000,
            updated_at_ms: 5000,
            queue_tracks: vec![t1, t2],
            queue_index: 0,
        };
        db.save_resume_state(&resume).unwrap();
        let loaded_resume = db.get_resume_state().unwrap().unwrap();
        assert_eq!(loaded_resume.position_ms, 82_000);
        assert_eq!(loaded_resume.queue_tracks.len(), 2);

        db.clear_all_collected_user_data().unwrap();
        assert!(db.get_listening_stats(10).unwrap().is_empty());
        assert!(db.get_resume_state().unwrap().is_none());
        assert_eq!(db.get_total_listening_time_ms().unwrap(), 0);
    }

    #[test]
    fn custom_themes_and_pins_roundtrip() {
        let db = Database::in_memory().unwrap();
        let spec = CustomThemeSpec {
            id: "custom.theme.1".into(),
            name: "Benim Temam".into(),
            background_hex: "#141224".into(),
            card_hex: "#282348".into(),
            accent_hex: "#ED438D".into(),
            text_hex: "#F5EEF8".into(),
        };
        db.save_user_custom_theme(&spec, 1000).unwrap();
        let themes = db.list_user_custom_themes().unwrap();
        assert_eq!(themes.len(), 1);
        assert_eq!(themes[0].name, "Benim Temam");

        let pin = StoredPinnedItem {
            pin_id: "flow:deezer".into(),
            pin_type: "flow".into(),
            track: None,
            playlist: None,
        };
        let after_add = db.toggle_pinned_item(pin.clone()).unwrap();
        assert_eq!(after_add.len(), 1);
        let after_remove = db.toggle_pinned_item(pin).unwrap();
        assert!(after_remove.is_empty());
    }
}
