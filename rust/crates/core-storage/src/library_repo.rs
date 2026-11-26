use std::time::{SystemTime, UNIX_EPOCH};

use core_domain::{
    DataOrigin, DomainError, DomainResult, Playlist, PlaylistId, PlaylistKind, ProviderId, Track,
    TrackId,
};
use core_library::LibraryRepository;
use rusqlite::{params, OptionalExtension, Row};

use crate::Database;

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn map_track(row: &Row<'_>) -> rusqlite::Result<Track> {
    let id_str: String = row.get(0)?;
    let title: String = row.get(2)?;
    let artist: String = row.get(3)?;
    let album: String = row.get(4)?;
    let duration_ms: u64 = row.get(5)?;
    let artwork_url: Option<String> = row.get(6)?;
    let playback_uri: Option<String> = row.get(7)?;
    let is_preview: bool = row.get::<_, i32>(8)? != 0;
    let origin_str: String = row.get(9)?;

    let id = TrackId::parse(&id_str).map_err(|e| {
        rusqlite::Error::FromSqlConversionFailure(
            0,
            rusqlite::types::Type::Text,
            Box::new(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                e.to_string(),
            )),
        )
    })?;

    let origin = match origin_str.as_str() {
        "public_catalog" => DataOrigin::PublicCatalog,
        "development_fixture" => DataOrigin::DevelopmentFixture,
        _ => DataOrigin::Real,
    };

    Ok(Track {
        id,
        title,
        artist,
        album,
        duration_ms,
        artwork_url,
        playback_uri,
        is_preview,
        origin,
    })
}

fn origin_to_str(origin: DataOrigin) -> &'static str {
    match origin {
        DataOrigin::Real => "real",
        DataOrigin::PublicCatalog => "public_catalog",
        DataOrigin::DevelopmentFixture => "development_fixture",
    }
}

impl LibraryRepository for Database {
    fn upsert_tracks(&self, tracks: &[Track]) -> DomainResult<()> {
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("transaction error: {e}")))?;

        {
            let mut stmt = tx
                .prepare_cached(
                    "INSERT INTO tracks (id, provider, title, artist, album, duration_ms, artwork_url, playback_uri, is_preview, origin)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
                     ON CONFLICT(id) DO UPDATE SET
                        title = excluded.title,
                        artist = excluded.artist,
                        album = excluded.album,
                        duration_ms = excluded.duration_ms,
                        artwork_url = excluded.artwork_url,
                        playback_uri = excluded.playback_uri,
                        is_preview = excluded.is_preview,
                        origin = excluded.origin;",
                )
                .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

            for t in tracks {
                stmt.execute(params![
                    t.id.to_string(),
                    t.id.provider().to_string(),
                    t.title,
                    t.artist,
                    t.album,
                    t.duration_ms,
                    t.artwork_url,
                    t.playback_uri,
                    if t.is_preview { 1 } else { 0 },
                    origin_to_str(t.origin),
                ])
                .map_err(|e| DomainError::Storage(format!("upsert track error: {e}")))?;
            }
        }

        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit error: {e}")))?;
        Ok(())
    }

    fn track(&self, id: &TrackId) -> DomainResult<Option<Track>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached(
                "SELECT id, provider, title, artist, album, duration_ms, artwork_url, playback_uri, is_preview, origin
                 FROM tracks WHERE id = ?1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let track = stmt
            .query_row(params![id.to_string()], map_track)
            .optional()
            .map_err(|e| DomainError::Storage(format!("query track error: {e}")))?;
        Ok(track)
    }

    fn tracks_by_provider(&self, provider: &ProviderId) -> DomainResult<Vec<Track>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached(
                "SELECT id, provider, title, artist, album, duration_ms, artwork_url, playback_uri, is_preview, origin
                 FROM tracks WHERE provider = ?1
                 ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, title COLLATE NOCASE;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let rows = stmt
            .query_map(params![provider.to_string()], map_track)
            .map_err(|e| DomainError::Storage(format!("query error: {e}")))?;

        let mut tracks = Vec::new();
        for r in rows {
            tracks.push(r.map_err(|e| DomainError::Storage(format!("read error: {e}")))?);
        }
        Ok(tracks)
    }

    fn delete_track(&self, id: &TrackId) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM tracks WHERE id = ?1;", params![id.to_string()])
            .map_err(|e| DomainError::Storage(format!("delete track error: {e}")))?;
        Ok(())
    }

    fn delete_tracks_batch(&self, ids: &[TrackId]) -> DomainResult<usize> {
        if ids.is_empty() {
            return Ok(0);
        }
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("transaction error: {e}")))?;
        let mut removed = 0usize;
        {
            let mut stmt = tx
                .prepare_cached("DELETE FROM tracks WHERE id = ?1;")
                .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;
            for id in ids {
                removed += stmt
                    .execute(params![id.to_string()])
                    .map_err(|e| DomainError::Storage(format!("delete track error: {e}")))?;
            }
        }
        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit error: {e}")))?;
        Ok(removed)
    }

    fn search_tracks(&self, query: &str, limit: u32) -> DomainResult<Vec<Track>> {
        let conn = self.lock()?;
        let pattern = format!("%{query}%");
        let mut stmt = conn
            .prepare_cached(
                "SELECT id, provider, title, artist, album, duration_ms, artwork_url, playback_uri, is_preview, origin
                 FROM tracks
                 WHERE title LIKE ?1 ESCAPE '\\' OR artist LIKE ?1 ESCAPE '\\' OR album LIKE ?1 ESCAPE '\\'
                 ORDER BY title COLLATE NOCASE
                 LIMIT ?2;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let rows = stmt
            .query_map(params![pattern, limit], map_track)
            .map_err(|e| DomainError::Storage(format!("query error: {e}")))?;

        let mut tracks = Vec::new();
        for r in rows {
            tracks.push(r.map_err(|e| DomainError::Storage(format!("read error: {e}")))?);
        }
        Ok(tracks)
    }

    fn set_favorite(&self, id: &TrackId, favorite: bool) -> DomainResult<()> {
        let conn = self.lock()?;
        if favorite {
            conn.execute(
                "INSERT INTO favorites (track_id, added_at) VALUES (?1, ?2)
                 ON CONFLICT(track_id) DO UPDATE SET added_at = excluded.added_at;",
                params![id.to_string(), now_secs()],
            )
            .map_err(|e| DomainError::Storage(format!("insert favorite error: {e}")))?;
        } else {
            conn.execute(
                "DELETE FROM favorites WHERE track_id = ?1;",
                params![id.to_string()],
            )
            .map_err(|e| DomainError::Storage(format!("delete favorite error: {e}")))?;
        }
        Ok(())
    }

    fn is_favorite(&self, id: &TrackId) -> DomainResult<bool> {
        let conn = self.lock()?;
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM favorites WHERE track_id = ?1;",
                params![id.to_string()],
                |r| r.get(0),
            )
            .map_err(|e| DomainError::Storage(format!("is_favorite error: {e}")))?;
        Ok(count > 0)
    }

    fn favorites(&self) -> DomainResult<Vec<Track>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached(
                "SELECT t.id, t.provider, t.title, t.artist, t.album, t.duration_ms, t.artwork_url, t.playback_uri, t.is_preview, t.origin
                 FROM tracks t
                 INNER JOIN favorites f ON t.id = f.track_id
                 ORDER BY f.added_at DESC;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let rows = stmt
            .query_map([], map_track)
            .map_err(|e| DomainError::Storage(format!("query error: {e}")))?;

        let mut tracks = Vec::new();
        for r in rows {
            tracks.push(r.map_err(|e| DomainError::Storage(format!("read error: {e}")))?);
        }
        Ok(tracks)
    }

    fn create_playlist(&self, name: &str) -> DomainResult<Playlist> {
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;

        let id_str = format!("local:{}", now_secs());
        let pid = PlaylistId::new(&id_str)?;

        tx.execute(
            "INSERT INTO playlists (id, name, description, kind, artwork_url, origin, created_at)
             VALUES (?1, ?2, NULL, 'local', NULL, 'real', ?3);",
            params![id_str, name, now_secs()],
        )
        .map_err(|e| DomainError::Storage(format!("create playlist error: {e}")))?;

        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit error: {e}")))?;

        Ok(Playlist {
            id: pid,
            name: name.to_string(),
            description: None,
            kind: PlaylistKind::Local,
            track_count: 0,
            artwork_url: None,
            origin: DataOrigin::Real,
        })
    }

    fn rename_playlist(&self, id: &PlaylistId, name: &str) -> DomainResult<()> {
        let conn = self.lock()?;
        let affected = conn
            .execute(
                "UPDATE playlists SET name = ?1 WHERE id = ?2;",
                params![name, id.to_string()],
            )
            .map_err(|e| DomainError::Storage(format!("rename playlist error: {e}")))?;

        if affected == 0 {
            return Err(DomainError::NotFound(format!("playlist {id}")));
        }
        Ok(())
    }

    fn delete_playlist(&self, id: &PlaylistId) -> DomainResult<()> {
        let conn = self.lock()?;
        let affected = conn
            .execute(
                "DELETE FROM playlists WHERE id = ?1;",
                params![id.to_string()],
            )
            .map_err(|e| DomainError::Storage(format!("delete playlist error: {e}")))?;

        if affected == 0 {
            return Err(DomainError::NotFound(format!("playlist {id}")));
        }
        Ok(())
    }

    fn playlists(&self) -> DomainResult<Vec<Playlist>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached(
                "SELECT p.id, p.name, p.description, p.kind, p.artwork_url, p.origin,
                        (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlist_id = p.id) as track_count
                 FROM playlists p
                 ORDER BY p.name COLLATE NOCASE;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let rows = stmt
            .query_map([], |row| {
                let id_str: String = row.get(0)?;
                let name: String = row.get(1)?;
                let description: Option<String> = row.get(2)?;
                let kind_str: String = row.get(3)?;
                let artwork_url: Option<String> = row.get(4)?;
                let origin_str: String = row.get(5)?;
                let track_count: u32 = row.get(6)?;

                let pid = PlaylistId::new(id_str).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        0,
                        rusqlite::types::Type::Text,
                        Box::new(std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            e.to_string(),
                        )),
                    )
                })?;

                let kind = if kind_str == "remote" {
                    PlaylistKind::Remote
                } else {
                    PlaylistKind::Local
                };

                let origin = match origin_str.as_str() {
                    "public_catalog" => DataOrigin::PublicCatalog,
                    "development_fixture" => DataOrigin::DevelopmentFixture,
                    _ => DataOrigin::Real,
                };

                Ok(Playlist {
                    id: pid,
                    name,
                    description,
                    kind,
                    track_count,
                    artwork_url,
                    origin,
                })
            })
            .map_err(|e| DomainError::Storage(format!("query error: {e}")))?;

        let mut list = Vec::new();
        for r in rows {
            list.push(r.map_err(|e| DomainError::Storage(format!("read error: {e}")))?);
        }
        Ok(list)
    }

    fn playlist(&self, id: &PlaylistId) -> DomainResult<Option<Playlist>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached(
                "SELECT p.id, p.name, p.description, p.kind, p.artwork_url, p.origin,
                        (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlist_id = p.id) as track_count
                 FROM playlists p
                 WHERE p.id = ?1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let row = stmt
            .query_row(params![id.to_string()], |row| {
                let id_str: String = row.get(0)?;
                let name: String = row.get(1)?;
                let description: Option<String> = row.get(2)?;
                let kind_str: String = row.get(3)?;
                let artwork_url: Option<String> = row.get(4)?;
                let origin_str: String = row.get(5)?;
                let track_count: u32 = row.get(6)?;

                let pid = PlaylistId::new(id_str).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        0,
                        rusqlite::types::Type::Text,
                        Box::new(std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            e.to_string(),
                        )),
                    )
                })?;

                let kind = if kind_str == "remote" {
                    PlaylistKind::Remote
                } else {
                    PlaylistKind::Local
                };

                let origin = match origin_str.as_str() {
                    "public_catalog" => DataOrigin::PublicCatalog,
                    "development_fixture" => DataOrigin::DevelopmentFixture,
                    _ => DataOrigin::Real,
                };

                Ok(Playlist {
                    id: pid,
                    name,
                    description,
                    kind,
                    track_count,
                    artwork_url,
                    origin,
                })
            })
            .optional()
            .map_err(|e| DomainError::Storage(format!("query error: {e}")))?;

        Ok(row)
    }

    fn playlist_tracks(&self, id: &PlaylistId) -> DomainResult<Vec<Track>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached(
                "SELECT t.id, t.provider, t.title, t.artist, t.album, t.duration_ms, t.artwork_url, t.playback_uri, t.is_preview, t.origin
                 FROM tracks t
                 INNER JOIN playlist_tracks pt ON t.id = pt.track_id
                 WHERE pt.playlist_id = ?1
                 ORDER BY pt.position ASC;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let rows = stmt
            .query_map(params![id.to_string()], map_track)
            .map_err(|e| DomainError::Storage(format!("query error: {e}")))?;

        let mut tracks = Vec::new();
        for r in rows {
            tracks.push(r.map_err(|e| DomainError::Storage(format!("read error: {e}")))?);
        }
        Ok(tracks)
    }

    fn append_to_playlist(&self, id: &PlaylistId, tracks: &[TrackId]) -> DomainResult<()> {
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;

        let current_max: i64 = tx
            .query_row(
                "SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlist_id = ?1;",
                params![id.to_string()],
                |r| r.get(0),
            )
            .unwrap_or(-1);

        {
            let mut stmt = tx
                .prepare_cached(
                    "INSERT INTO playlist_tracks (playlist_id, track_id, position)
                     VALUES (?1, ?2, ?3);",
                )
                .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

            for (idx, tid) in tracks.iter().enumerate() {
                stmt.execute(params![
                    id.to_string(),
                    tid.to_string(),
                    current_max + 1 + idx as i64,
                ])
                .map_err(|e| DomainError::Storage(format!("insert playlist track error: {e}")))?;
            }
        }

        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit error: {e}")))?;
        Ok(())
    }

    fn remove_from_playlist(&self, id: &PlaylistId, position: u32) -> DomainResult<()> {
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;

        tx.execute(
            "DELETE FROM playlist_tracks WHERE playlist_id = ?1 AND position = ?2;",
            params![id.to_string(), position],
        )
        .map_err(|e| DomainError::Storage(format!("delete error: {e}")))?;

        // Re-compact positions
        tx.execute(
            "UPDATE playlist_tracks SET position = position - 1
             WHERE playlist_id = ?1 AND position > ?2;",
            params![id.to_string(), position],
        )
        .map_err(|e| DomainError::Storage(format!("reindex error: {e}")))?;

        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit error: {e}")))?;
        Ok(())
    }

    fn remove_track_by_id_from_playlist(
        &self,
        id: &PlaylistId,
        track_id: &TrackId,
    ) -> DomainResult<bool> {
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;

        let deleted = tx
            .execute(
                "DELETE FROM playlist_tracks WHERE playlist_id = ?1 AND track_id = ?2;",
                params![id.to_string(), track_id.to_string()],
            )
            .map_err(|e| DomainError::Storage(format!("delete by track_id error: {e}")))?;

        if deleted > 0 {
            let rowids: Vec<(i64, i64)> = {
                let mut stmt = tx
                    .prepare_cached(
                        "SELECT rowid, position FROM playlist_tracks WHERE playlist_id = ?1 ORDER BY position ASC;",
                    )
                    .map_err(|e| DomainError::Storage(format!("prepare reindex error: {e}")))?;
                let rows = stmt
                    .query_map(params![id.to_string()], |r| Ok((r.get(0)?, r.get(1)?)))
                    .map_err(|e| DomainError::Storage(format!("query reindex error: {e}")))?;
                rows.collect::<Result<Vec<_>, _>>()
                    .map_err(|e| DomainError::Storage(format!("read reindex error: {e}")))?
            };
            let mut update_stmt = tx
                .prepare_cached("UPDATE playlist_tracks SET position = ?1 WHERE rowid = ?2;")
                .map_err(|e| DomainError::Storage(format!("prepare update pos error: {e}")))?;
            for (new_pos, (rowid, old_pos)) in rowids.into_iter().enumerate() {
                if old_pos != new_pos as i64 {
                    update_stmt
                        .execute(params![new_pos as i64, rowid])
                        .map_err(|e| DomainError::Storage(format!("update pos error: {e}")))?;
                }
            }
        }

        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit error: {e}")))?;
        Ok(deleted > 0)
    }

    fn move_in_playlist(&self, id: &PlaylistId, from: u32, to: u32) -> DomainResult<()> {
        if from == to {
            return Ok(());
        }
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("tx error: {e}")))?;

        // Move target to temporary position -1
        tx.execute(
            "UPDATE playlist_tracks SET position = -1 WHERE playlist_id = ?1 AND position = ?2;",
            params![id.to_string(), from],
        )
        .map_err(|e| DomainError::Storage(format!("move step 1 error: {e}")))?;

        if from < to {
            tx.execute(
                "UPDATE playlist_tracks SET position = position - 1
                 WHERE playlist_id = ?1 AND position > ?2 AND position <= ?3;",
                params![id.to_string(), from, to],
            )
            .map_err(|e| DomainError::Storage(format!("move shift down error: {e}")))?;
        } else {
            tx.execute(
                "UPDATE playlist_tracks SET position = position + 1
                 WHERE playlist_id = ?1 AND position >= ?2 AND position < ?3;",
                params![id.to_string(), to, from],
            )
            .map_err(|e| DomainError::Storage(format!("move shift up error: {e}")))?;
        }

        // Place target into desired position
        tx.execute(
            "UPDATE playlist_tracks SET position = ?2 WHERE playlist_id = ?1 AND position = -1;",
            params![id.to_string(), to],
        )
        .map_err(|e| DomainError::Storage(format!("move step 3 error: {e}")))?;

        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit error: {e}")))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_track(id_suffix: &str) -> Track {
        Track {
            id: TrackId::parse(&format!("local:t_{id_suffix}")).unwrap(),
            title: format!("Title {id_suffix}"),
            artist: "Artist A".into(),
            album: "Album B".into(),
            duration_ms: 180_000,
            artwork_url: None,
            playback_uri: Some(format!("content://media/{id_suffix}")),
            is_preview: false,
            origin: DataOrigin::Real,
        }
    }

    #[test]
    fn track_and_favorite_lifecycle() {
        let db = Database::in_memory().unwrap();
        let t1 = test_track("1");
        db.upsert_tracks(&[t1.clone()]).unwrap();

        assert_eq!(db.track(&t1.id).unwrap().unwrap(), t1);
        assert!(!db.is_favorite(&t1.id).unwrap());

        db.set_favorite(&t1.id, true).unwrap();
        assert!(db.is_favorite(&t1.id).unwrap());
        let favs = db.favorites().unwrap();
        assert_eq!(favs.len(), 1);
        assert_eq!(favs[0].id, t1.id);

        db.set_favorite(&t1.id, false).unwrap();
        assert!(!db.is_favorite(&t1.id).unwrap());
    }

    #[test]
    fn playlist_lifecycle_and_reorder() {
        let db = Database::in_memory().unwrap();
        let t1 = test_track("1");
        let t2 = test_track("2");
        let t3 = test_track("3");
        db.upsert_tracks(&[t1.clone(), t2.clone(), t3.clone()])
            .unwrap();

        let pl = db.create_playlist("Chill Wave").unwrap();
        db.append_to_playlist(&pl.id, &[t1.id.clone(), t2.id.clone(), t3.id.clone()])
            .unwrap();

        let tracks = db.playlist_tracks(&pl.id).unwrap();
        assert_eq!(tracks.len(), 3);
        assert_eq!(tracks[0].id, t1.id);
        assert_eq!(tracks[1].id, t2.id);
        assert_eq!(tracks[2].id, t3.id);

        // Move track 0 to position 2
        db.move_in_playlist(&pl.id, 0, 2).unwrap();
        let tracks = db.playlist_tracks(&pl.id).unwrap();
        assert_eq!(tracks[0].id, t2.id);
        assert_eq!(tracks[1].id, t3.id);
        assert_eq!(tracks[2].id, t1.id);

        // Remove track at pos 1 (t3)
        db.remove_from_playlist(&pl.id, 1).unwrap();
        let tracks = db.playlist_tracks(&pl.id).unwrap();
        assert_eq!(tracks.len(), 2);
        assert_eq!(tracks[0].id, t2.id);
        assert_eq!(tracks[1].id, t1.id);
    }
}
