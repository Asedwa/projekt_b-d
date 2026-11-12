use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use core_domain::{DomainError, DomainResult, TrackId};
use core_providers::AuthorizedDownload;
use futures_util::StreamExt;
use reqwest::header::{CONTENT_LENGTH, RANGE};
use reqwest::StatusCode;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::{DownloadRepository, DownloadState, DownloadTask, NewDownload};

const CTRL_RUN: u8 = 0;
const CTRL_PAUSE: u8 = 1;
const CTRL_CANCEL: u8 = 2;
const PROGRESS_INTERVAL: Duration = Duration::from_millis(400);

#[derive(Debug, Clone)]
pub struct ManagerConfig {
    pub dir: PathBuf,
    /// Maximum bytes the download directory may use (None = unlimited).
    pub storage_limit_bytes: Option<u64>,
    /// Allow plain HTTP (only for tests against a loopback server).
    pub allow_insecure_loopback: bool,
}

impl ManagerConfig {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self {
            dir: dir.into(),
            storage_limit_bytes: None,
            allow_insecure_loopback: false,
        }
    }
}

/// Receives task updates (progress, state changes). Called from the download
/// task; implementations must be cheap and non-blocking.
pub trait DownloadListener: Send + Sync {
    fn on_update(&self, task: &DownloadTask);
}

pub struct DownloadManager {
    repo: Arc<dyn DownloadRepository>,
    http: reqwest::Client,
    cfg: ManagerConfig,
    controls: Mutex<HashMap<i64, Arc<AtomicU8>>>,
    listener: Mutex<Option<Arc<dyn DownloadListener>>>,
}

impl DownloadManager {
    pub fn new(repo: Arc<dyn DownloadRepository>, cfg: ManagerConfig) -> DomainResult<Self> {
        std::fs::create_dir_all(&cfg.dir)
            .map_err(|e| DomainError::Storage(format!("create download dir: {}", e.kind())))?;
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(30))
            .https_only(!cfg.allow_insecure_loopback)
            .build()
            .map_err(|e| DomainError::Internal(format!("http client: {e}")))?;
        Ok(Self {
            repo,
            http,
            cfg,
            controls: Mutex::new(HashMap::new()),
            listener: Mutex::new(None),
        })
    }

    pub fn set_listener(&self, listener: Option<Arc<dyn DownloadListener>>) {
        if let Ok(mut l) = self.listener.lock() {
            *l = listener;
        }
    }

    fn notify(&self, task: &DownloadTask) {
        let listener = self.listener.lock().ok().and_then(|l| l.clone());
        if let Some(l) = listener {
            l.on_update(task);
        }
    }

    fn save(&self, task: &DownloadTask) -> DomainResult<()> {
        self.repo.update(task)?;
        self.notify(task);
        Ok(())
    }

    fn require(&self, id: i64) -> DomainResult<DownloadTask> {
        self.repo
            .get(id)?
            .ok_or_else(|| DomainError::NotFound(format!("download {id}")))
    }

    fn part_path(&self, id: i64) -> PathBuf {
        self.cfg.dir.join(format!("{id}.part"))
    }

    pub fn completed_path(&self, task: &DownloadTask) -> PathBuf {
        self.cfg.dir.join(format!("{}_{}", task.id, task.file_name))
    }

    pub fn list(&self) -> DomainResult<Vec<DownloadTask>> {
        self.repo.list()
    }

    /// Enqueues an authorized download. If an active or completed task for the
    /// same track exists it is returned instead of creating a duplicate.
    pub fn enqueue(&self, auth: AuthorizedDownload) -> DomainResult<DownloadTask> {
        if let Some(existing) = self.repo.find_by_track(&auth.track_id)? {
            if existing.state.is_active() || existing.state == DownloadState::Completed {
                return Ok(existing);
            }
        }
        let new = NewDownload::try_from(auth)?;
        self.validate_url(&new.url)?;
        let task = self.repo.insert(&new)?;
        self.notify(&task);
        Ok(task)
    }

    fn validate_url(&self, raw: &str) -> DomainResult<()> {
        let url = url::Url::parse(raw)
            .map_err(|_| DomainError::InvalidInput("invalid download URL".into()))?;
        let loopback = matches!(url.host_str(), Some("127.0.0.1") | Some("localhost"));
        match url.scheme() {
            "https" => Ok(()),
            "http" if self.cfg.allow_insecure_loopback && loopback => Ok(()),
            _ => Err(DomainError::InvalidInput(
                "only HTTPS downloads are allowed".into(),
            )),
        }
    }

    /// After process death, tasks left `Running` are re-queued.
    pub fn recover_after_restart(&self) -> DomainResult<usize> {
        let mut n = 0;
        for mut t in self.repo.list()? {
            if t.state == DownloadState::Running {
                t.state = DownloadState::Queued;
                self.repo.update(&t)?;
                n += 1;
            }
        }
        Ok(n)
    }

    fn signal(&self, id: i64, ctrl: u8) -> bool {
        match self.controls.lock().ok().and_then(|c| c.get(&id).cloned()) {
            Some(flag) => {
                flag.store(ctrl, Ordering::SeqCst);
                true
            }
            None => false,
        }
    }

    pub fn pause(&self, id: i64) -> DomainResult<DownloadTask> {
        let mut t = self.require(id)?;
        match t.state {
            DownloadState::Running => {
                self.signal(id, CTRL_PAUSE);
            }
            DownloadState::Queued => {
                t.state = DownloadState::Paused;
                self.save(&t)?;
            }
            _ => {}
        }
        Ok(t)
    }

    pub fn resume(&self, id: i64) -> DomainResult<DownloadTask> {
        let mut t = self.require(id)?;
        if t.state == DownloadState::Paused {
            t.state = DownloadState::Queued;
            self.save(&t)?;
        }
        Ok(t)
    }

    pub fn cancel(&self, id: i64) -> DomainResult<DownloadTask> {
        let mut t = self.require(id)?;
        if t.state == DownloadState::Running {
            self.signal(id, CTRL_CANCEL);
        } else if t.state.is_active() {
            let _ = std::fs::remove_file(self.part_path(id));
            t.state = DownloadState::Cancelled;
            t.bytes_done = 0;
            self.save(&t)?;
        }
        Ok(t)
    }

    pub fn retry(&self, id: i64) -> DomainResult<DownloadTask> {
        let mut t = self.require(id)?;
        if matches!(t.state, DownloadState::Failed | DownloadState::Cancelled) {
            t.state = DownloadState::Queued;
            t.error = None;
            self.save(&t)?;
        }
        Ok(t)
    }

    /// Deletes the task and any files belonging to it.
    pub fn remove(&self, id: i64) -> DomainResult<()> {
        let t = self.require(id)?;
        if t.state == DownloadState::Running {
            self.signal(id, CTRL_CANCEL);
        }
        let _ = std::fs::remove_file(self.part_path(id));
        let _ = std::fs::remove_file(self.completed_path(&t));
        self.repo.delete(id)
    }

    /// Offline availability: completed task whose file still exists with the
    /// expected size.
    pub fn offline_path(&self, track: &TrackId) -> DomainResult<Option<PathBuf>> {
        let Some(t) = self.repo.find_by_track(track)? else {
            return Ok(None);
        };
        if t.state != DownloadState::Completed {
            return Ok(None);
        }
        let path = self.completed_path(&t);
        match std::fs::metadata(&path) {
            Ok(m) if t.total_bytes.map_or(true, |n| n == m.len()) => Ok(Some(path)),
            _ => Ok(None),
        }
    }

    /// Bytes currently used by the download directory.
    pub fn used_bytes(&self) -> u64 {
        std::fs::read_dir(&self.cfg.dir)
            .map(|rd| {
                rd.filter_map(Result::ok)
                    .filter_map(|e| e.metadata().ok())
                    .filter(|m| m.is_file())
                    .map(|m| m.len())
                    .sum()
            })
            .unwrap_or(0)
    }

    /// Runs queued tasks one after another until none remain.
    pub async fn run_pending(&self) -> DomainResult<Vec<DownloadTask>> {
        let mut done = Vec::new();
        loop {
            let next = self
                .repo
                .list()?
                .into_iter()
                .filter(|t| t.state == DownloadState::Queued)
                .min_by_key(|t| t.id);
            match next {
                Some(t) => done.push(self.run(t.id).await?),
                None => return Ok(done),
            }
        }
    }

    /// Runs a single queued task to completion, pause, cancellation or failure.
    /// Transfer errors are recorded on the task, not returned.
    pub async fn run(&self, id: i64) -> DomainResult<DownloadTask> {
        let mut task = self.require(id)?;
        if task.state != DownloadState::Queued {
            return Ok(task);
        }
        let ctrl = Arc::new(AtomicU8::new(CTRL_RUN));
        self.controls
            .lock()
            .map_err(|_| DomainError::Internal("lock poisoned".into()))?
            .insert(id, ctrl.clone());
        task.state = DownloadState::Running;
        task.attempts += 1;
        task.error = None;
        self.save(&task)?;

        let result = self.transfer(&mut task, &ctrl).await;
        if let Ok(mut c) = self.controls.lock() {
            c.remove(&id);
        }
        match result {
            Ok(()) => {}
            Err(e) => {
                task.state = DownloadState::Failed;
                task.error = Some(e.to_string());
            }
        }
        self.save(&task)?;
        Ok(task)
    }

    async fn transfer(&self, task: &mut DownloadTask, ctrl: &AtomicU8) -> DomainResult<()> {
        self.validate_url(&task.url)?;
        let part = self.part_path(task.id);
        let mut offset = if task.supports_resume {
            tokio::fs::metadata(&part)
                .await
                .map(|m| m.len())
                .unwrap_or(0)
        } else {
            0
        };

        let mut req = self.http.get(&task.url);
        if offset > 0 {
            req = req.header(RANGE, format!("bytes={offset}-"));
        }
        let resp = req
            .send()
            .await
            .map_err(|e| DomainError::Network(net_kind(&e).into()))?;

        let status = resp.status();
        let append = match status {
            StatusCode::PARTIAL_CONTENT if offset > 0 => true,
            s if s.is_success() => {
                offset = 0;
                false
            }
            StatusCode::TOO_MANY_REQUESTS => {
                return Err(DomainError::RateLimited {
                    retry_after_ms: 30_000,
                })
            }
            s => {
                return Err(DomainError::Network(format!(
                    "server returned HTTP {}",
                    s.as_u16()
                )))
            }
        };
        let remaining = resp
            .headers()
            .get(CONTENT_LENGTH)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<u64>().ok());
        if let Some(r) = remaining {
            let total = offset + r;
            if task.total_bytes.is_some_and(|t| t != total) {
                return Err(DomainError::Provider("unexpected file size".into()));
            }
            task.total_bytes = Some(total);
            if let Some(limit) = self.cfg.storage_limit_bytes {
                if self.used_bytes().saturating_sub(offset) + total > limit {
                    return Err(DomainError::Storage(
                        "download storage limit reached".into(),
                    ));
                }
            }
        }

        let mut file = tokio::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .append(append)
            .truncate(!append)
            .open(&part)
            .await
            .map_err(|e| DomainError::Storage(format!("open: {}", e.kind())))?;

        task.bytes_done = offset;
        let mut last_report = Instant::now();
        let mut stream = resp.bytes_stream();
        while let Some(chunk) = stream.next().await {
            match ctrl.load(Ordering::SeqCst) {
                CTRL_PAUSE => {
                    file.flush().await.ok();
                    task.state = if task.supports_resume {
                        DownloadState::Paused
                    } else {
                        // Without resume support a pause would lose data;
                        // the next run restarts from zero.
                        let _ = tokio::fs::remove_file(&part).await;
                        task.bytes_done = 0;
                        DownloadState::Paused
                    };
                    return Ok(());
                }
                CTRL_CANCEL => {
                    drop(file);
                    let _ = tokio::fs::remove_file(&part).await;
                    task.state = DownloadState::Cancelled;
                    task.bytes_done = 0;
                    return Ok(());
                }
                _ => {}
            }
            let chunk = chunk.map_err(|e| DomainError::Network(net_kind(&e).into()))?;
            file.write_all(&chunk)
                .await
                .map_err(|e| DomainError::Storage(format!("write: {}", e.kind())))?;
            task.bytes_done += chunk.len() as u64;
            if task.total_bytes.is_some_and(|t| task.bytes_done > t) {
                return Err(DomainError::Provider(
                    "received more data than expected".into(),
                ));
            }
            if last_report.elapsed() >= PROGRESS_INTERVAL {
                last_report = Instant::now();
                self.save(task)?;
            }
        }
        file.flush()
            .await
            .map_err(|e| DomainError::Storage(format!("flush: {}", e.kind())))?;
        drop(file);

        if let Some(total) = task.total_bytes {
            if task.bytes_done != total {
                return Err(DomainError::Network("connection closed early".into()));
            }
        }
        if let Some(expected) = &task.expected_sha256 {
            let actual = sha256_file(&part).await?;
            if &actual != expected {
                let _ = tokio::fs::remove_file(&part).await;
                task.bytes_done = 0;
                return Err(DomainError::Provider(
                    "integrity check failed (sha256 mismatch)".into(),
                ));
            }
        }
        tokio::fs::rename(&part, self.completed_path(task))
            .await
            .map_err(|e| DomainError::Storage(format!("finalize: {}", e.kind())))?;
        task.total_bytes = Some(task.bytes_done);
        task.state = DownloadState::Completed;
        Ok(())
    }
}

fn net_kind(e: &reqwest::Error) -> &'static str {
    if e.is_timeout() {
        "network timeout"
    } else if e.is_connect() {
        "could not connect"
    } else {
        "transfer interrupted"
    }
}

async fn sha256_file(path: &Path) -> DomainResult<String> {
    let mut f = tokio::fs::File::open(path)
        .await
        .map_err(|e| DomainError::Storage(format!("open: {}", e.kind())))?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f
            .read(&mut buf)
            .await
            .map_err(|e| DomainError::Storage(format!("read: {}", e.kind())))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

#[cfg(test)]
mod tests;
