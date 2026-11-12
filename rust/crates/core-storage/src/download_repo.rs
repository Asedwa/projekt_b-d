use std::time::{SystemTime, UNIX_EPOCH};

use core_domain::{DomainError, DomainResult, TrackId};
use core_downloads::{DownloadRepository, DownloadState, DownloadTask, NewDownload};
use rusqlite::{params, OptionalExtension, Row};

use crate::Database;

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn map_download_task(row: &Row<'_>) -> rusqlite::Result<DownloadTask> {
    let id: i64 = row.get(0)?;
    let track_id_str: String = row.get(1)?;
    let url: String = row.get(2)?;
    let file_name: String = row.get(3)?;
    let state_str: String = row.get(4)?;
    let bytes_done: u64 = row.get(5)?;
    let total_bytes: Option<u64> = row.get(6)?;
    let expected_sha256: Option<String> = row.get(7)?;
    let supports_resume: bool = row.get::<_, i32>(8)? != 0;
    let error: Option<String> = row.get(9)?;
    let attempts: u32 = row.get(10)?;

    let track_id = TrackId::parse(&track_id_str).map_err(|e| {
        rusqlite::Error::FromSqlConversionFailure(
            1,
            rusqlite::types::Type::Text,
            Box::new(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                e.to_string(),
            )),
        )
    })?;

    let state = DownloadState::parse(&state_str).unwrap_or(DownloadState::Failed);

    Ok(DownloadTask {
        id,
        track_id,
        url,
        file_name,
        state,
        bytes_done,
        total_bytes,
        expected_sha256,
        supports_resume,
        error,
        attempts,
    })
}

impl DownloadRepository for Database {
    fn insert(&self, new: &NewDownload) -> DomainResult<DownloadTask> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO downloads (track_id, url, file_name, state, bytes_done, total_bytes, expected_sha256, supports_resume, error, attempts, created_at)
             VALUES (?1, ?2, ?3, 'queued', 0, ?4, ?5, ?6, NULL, 0, ?7);",
            params![
                new.track_id.to_string(),
                new.url,
                new.file_name,
                new.total_bytes,
                new.expected_sha256,
                if new.supports_resume { 1 } else { 0 },
                now_secs(),
            ],
        )
        .map_err(|e| DomainError::Storage(format!("insert download task error: {e}")))?;

        let id = conn.last_insert_rowid();

        Ok(DownloadTask {
            id,
            track_id: new.track_id.clone(),
            url: new.url.clone(),
            file_name: new.file_name.clone(),
            state: DownloadState::Queued,
            bytes_done: 0,
            total_bytes: new.total_bytes,
            expected_sha256: new.expected_sha256.clone(),
            supports_resume: new.supports_resume,
            error: None,
            attempts: 0,
        })
    }

    fn update(&self, task: &DownloadTask) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute(
            "UPDATE downloads
             SET state = ?1, bytes_done = ?2, total_bytes = ?3, error = ?4, attempts = ?5
             WHERE id = ?6;",
            params![
                task.state.as_str(),
                task.bytes_done,
                task.total_bytes,
                task.error,
                task.attempts,
                task.id,
            ],
        )
        .map_err(|e| DomainError::Storage(format!("update download task error: {e}")))?;
        Ok(())
    }

    fn get(&self, id: i64) -> DomainResult<Option<DownloadTask>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT id, track_id, url, file_name, state, bytes_done, total_bytes, expected_sha256, supports_resume, error, attempts
                 FROM downloads WHERE id = ?1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let task = stmt
            .query_row(params![id], map_download_task)
            .optional()
            .map_err(|e| DomainError::Storage(format!("get download task error: {e}")))?;
        Ok(task)
    }

    fn list(&self) -> DomainResult<Vec<DownloadTask>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT id, track_id, url, file_name, state, bytes_done, total_bytes, expected_sha256, supports_resume, error, attempts
                 FROM downloads ORDER BY id ASC;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let rows = stmt
            .query_map([], map_download_task)
            .map_err(|e| DomainError::Storage(format!("query error: {e}")))?;

        let mut list = Vec::new();
        for r in rows {
            list.push(r.map_err(|e| DomainError::Storage(format!("read error: {e}")))?);
        }
        Ok(list)
    }

    fn delete(&self, id: i64) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM downloads WHERE id = ?1;", params![id])
            .map_err(|e| DomainError::Storage(format!("delete download error: {e}")))?;
        Ok(())
    }

    fn find_by_track(&self, track: &TrackId) -> DomainResult<Option<DownloadTask>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT id, track_id, url, file_name, state, bytes_done, total_bytes, expected_sha256, supports_resume, error, attempts
                 FROM downloads WHERE track_id = ?1 ORDER BY id DESC LIMIT 1;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let task = stmt
            .query_row(params![track.to_string()], map_download_task)
            .optional()
            .map_err(|e| DomainError::Storage(format!("find download error: {e}")))?;
        Ok(task)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn download_task_crud() {
        let db = Database::in_memory().unwrap();
        let new_dl = NewDownload {
            track_id: TrackId::parse("local:dl_1").unwrap(),
            url: "https://example.com/audio.mp3".into(),
            file_name: "audio.mp3".into(),
            total_bytes: Some(1024),
            expected_sha256: None,
            supports_resume: true,
        };

        let mut task = db.insert(&new_dl).unwrap();
        assert_eq!(task.state, DownloadState::Queued);
        assert_eq!(task.total_bytes, Some(1024));

        task.state = DownloadState::Running;
        task.bytes_done = 512;
        db.update(&task).unwrap();

        let fetched = db.get(task.id).unwrap().unwrap();
        assert_eq!(fetched.state, DownloadState::Running);
        assert_eq!(fetched.bytes_done, 512);

        let by_track = db.find_by_track(&new_dl.track_id).unwrap().unwrap();
        assert_eq!(by_track.id, task.id);

        let list = db.list().unwrap();
        assert_eq!(list.len(), 1);

        db.delete(task.id).unwrap();
        assert!(db.get(task.id).unwrap().is_none());
    }
}
