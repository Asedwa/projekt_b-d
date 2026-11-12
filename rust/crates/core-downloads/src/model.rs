use core_domain::{DomainError, DomainResult, ProviderId, TrackId};
use core_providers::AuthorizedDownload;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DownloadState {
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

impl DownloadState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Running => "running",
            Self::Paused => "paused",
            Self::Completed => "completed",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "queued" => Self::Queued,
            "running" => Self::Running,
            "paused" => Self::Paused,
            "completed" => Self::Completed,
            "failed" => Self::Failed,
            "cancelled" => Self::Cancelled,
            _ => return None,
        })
    }

    pub fn is_active(self) -> bool {
        matches!(self, Self::Queued | Self::Running | Self::Paused)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NewDownload {
    pub track_id: TrackId,
    pub url: String,
    pub file_name: String,
    pub total_bytes: Option<u64>,
    pub expected_sha256: Option<String>,
    pub supports_resume: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DownloadTask {
    pub id: i64,
    pub track_id: TrackId,
    pub url: String,
    pub file_name: String,
    pub state: DownloadState,
    pub bytes_done: u64,
    pub total_bytes: Option<u64>,
    pub expected_sha256: Option<String>,
    pub supports_resume: bool,
    pub error: Option<String>,
    pub attempts: u32,
}

impl DownloadTask {
    pub fn progress_percent(&self) -> Option<u8> {
        match (self.state, self.total_bytes) {
            (DownloadState::Completed, _) => Some(100),
            (_, Some(total)) if total > 0 => {
                Some(((self.bytes_done.min(total) * 100) / total) as u8)
            }
            _ => None,
        }
    }
}

/// Persistence port for download tasks.
pub trait DownloadRepository: Send + Sync {
    fn insert(&self, new: &NewDownload) -> DomainResult<DownloadTask>;
    fn update(&self, task: &DownloadTask) -> DomainResult<()>;
    fn get(&self, id: i64) -> DomainResult<Option<DownloadTask>>;
    fn list(&self) -> DomainResult<Vec<DownloadTask>>;
    fn delete(&self, id: i64) -> DomainResult<()>;
    fn find_by_track(&self, track: &TrackId) -> DomainResult<Option<DownloadTask>>;
}

/// Restricts file names to a safe character set and strips path components,
/// preventing path traversal.
pub fn sanitize_file_name(raw: &str) -> DomainResult<String> {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or_default();
    let cleaned: String = base
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, '.' | '-' | '_' | ' ' | '(' | ')') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let cleaned = cleaned.trim().trim_start_matches('.').to_owned();
    if cleaned.is_empty() || cleaned.len() > 180 {
        return Err(DomainError::InvalidInput("invalid file name".into()));
    }
    Ok(cleaned)
}

impl TryFrom<AuthorizedDownload> for NewDownload {
    type Error = DomainError;
    fn try_from(a: AuthorizedDownload) -> DomainResult<Self> {
        if let Some(h) = &a.expected_sha256 {
            if h.len() != 64 || !h.chars().all(|c| c.is_ascii_hexdigit()) {
                return Err(DomainError::InvalidInput("invalid sha256 checksum".into()));
            }
        }
        Ok(Self {
            track_id: a.track_id,
            url: a.url,
            file_name: sanitize_file_name(&a.file_name)?,
            total_bytes: a.expected_size,
            expected_sha256: a.expected_sha256.map(|h| h.to_ascii_lowercase()),
            supports_resume: a.supports_resume,
        })
    }
}

/// A direct link supplied by the user (e.g. their own file on a server or a
/// Creative-Commons source). The user is responsible for having the right to
/// download it. Only HTTPS URLs are accepted.
pub fn user_supplied_download(
    url: &str,
    sha256: Option<String>,
) -> DomainResult<AuthorizedDownload> {
    let parsed = url::Url::parse(url)
        .map_err(|_| DomainError::InvalidInput("invalid download URL".into()))?;
    if parsed.scheme() != "https" {
        return Err(DomainError::InvalidInput(
            "only HTTPS download links are allowed".into(),
        ));
    }
    let name = parsed
        .path_segments()
        .and_then(|mut s| s.next_back().map(str::to_owned))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "download.bin".into());
    Ok(AuthorizedDownload {
        track_id: TrackId::new(ProviderId::local(), format!("download:{url}"))?,
        url: url.to_owned(),
        file_name: name,
        expected_size: None,
        expected_sha256: sha256,
        supports_resume: true,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_names_are_sanitized() {
        assert_eq!(sanitize_file_name("../../etc/passwd").unwrap(), "passwd");
        assert_eq!(sanitize_file_name("a/b\\c:d?.mp3").unwrap(), "c_d_.mp3");
        assert_eq!(sanitize_file_name("..hidden").unwrap(), "hidden");
        assert!(sanitize_file_name("..").is_err());
        assert!(sanitize_file_name("dir/").is_err());
    }

    #[test]
    fn user_links_must_be_https() {
        assert!(user_supplied_download("http://x.example/a.mp3", None).is_err());
        let d = user_supplied_download("https://x.example/music/a.ogg", None).unwrap();
        assert_eq!(d.file_name, "a.ogg");
    }

    #[test]
    fn bad_checksum_rejected() {
        let mut d = user_supplied_download("https://x.example/a.ogg", None).unwrap();
        d.expected_sha256 = Some("zz".into());
        assert!(NewDownload::try_from(d).is_err());
    }

    #[test]
    fn progress() {
        let mut t = DownloadTask {
            id: 1,
            track_id: TrackId::parse("local:x").unwrap(),
            url: String::new(),
            file_name: "a".into(),
            state: DownloadState::Running,
            bytes_done: 50,
            total_bytes: Some(200),
            expected_sha256: None,
            supports_resume: false,
            error: None,
            attempts: 0,
        };
        assert_eq!(t.progress_percent(), Some(25));
        t.total_bytes = None;
        assert_eq!(t.progress_percent(), None);
        t.state = DownloadState::Completed;
        assert_eq!(t.progress_percent(), Some(100));
    }
}
