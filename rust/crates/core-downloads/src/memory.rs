use std::sync::Mutex;

use core_domain::{DomainError, DomainResult, TrackId};

use crate::{DownloadRepository, DownloadState, DownloadTask, NewDownload};

/// In-memory repository used in tests and when the database is unavailable.
#[derive(Debug, Default)]
pub struct MemoryDownloadRepository {
    inner: Mutex<(i64, Vec<DownloadTask>)>,
}

fn poisoned<T>(_: T) -> DomainError {
    DomainError::Internal("download repository lock poisoned".into())
}

impl DownloadRepository for MemoryDownloadRepository {
    fn insert(&self, new: &NewDownload) -> DomainResult<DownloadTask> {
        let mut g = self.inner.lock().map_err(poisoned)?;
        g.0 += 1;
        let task = DownloadTask {
            id: g.0,
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
        };
        g.1.push(task.clone());
        Ok(task)
    }

    fn update(&self, task: &DownloadTask) -> DomainResult<()> {
        let mut g = self.inner.lock().map_err(poisoned)?;
        let slot =
            g.1.iter_mut()
                .find(|t| t.id == task.id)
                .ok_or_else(|| DomainError::NotFound(format!("download {}", task.id)))?;
        *slot = task.clone();
        Ok(())
    }

    fn get(&self, id: i64) -> DomainResult<Option<DownloadTask>> {
        let g = self.inner.lock().map_err(poisoned)?;
        Ok(g.1.iter().find(|t| t.id == id).cloned())
    }

    fn list(&self) -> DomainResult<Vec<DownloadTask>> {
        Ok(self.inner.lock().map_err(poisoned)?.1.clone())
    }

    fn delete(&self, id: i64) -> DomainResult<()> {
        self.inner
            .lock()
            .map_err(poisoned)?
            .1
            .retain(|t| t.id != id);
        Ok(())
    }

    fn find_by_track(&self, track: &TrackId) -> DomainResult<Option<DownloadTask>> {
        let g = self.inner.lock().map_err(poisoned)?;
        Ok(g.1.iter().rev().find(|t| &t.track_id == track).cloned())
    }
}
