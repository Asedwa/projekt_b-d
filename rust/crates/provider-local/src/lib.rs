//! Local library provider.
//!
//! Android discovers user-owned audio (MediaStore / Storage Access Framework)
//! and hands Rust plain metadata through [`LocalTrackInput`]. Rust validates and
//! owns the catalog; playback URIs are `content://` or `file://` URIs that the
//! platform player opens directly.

use std::collections::BTreeMap;
use std::sync::{Arc, RwLock};

use async_trait::async_trait;
use core_domain::{
    Capability, CapabilitySet, CapabilityStatus, DataOrigin, DomainError, DomainResult, Page,
    PageRequest, ProviderId, Track, TrackId,
};
use core_providers::{AuthState, AuthorizedDownload, MusicProvider};
use serde::{Deserialize, Serialize};

/// Metadata for a local audio file as reported by the platform.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LocalTrackInput {
    pub uri: String,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub duration_ms: u64,
    pub artwork_uri: Option<String>,
    /// Display name of the file, used as a fallback title.
    pub file_name: Option<String>,
}

const ALLOWED_SCHEMES: [&str; 2] = ["content://", "file://"];

impl LocalTrackInput {
    pub fn into_track(self) -> DomainResult<Track> {
        if !ALLOWED_SCHEMES.iter().any(|s| self.uri.starts_with(s)) {
            return Err(DomainError::InvalidInput(
                "local track URI must use content:// or file://".into(),
            ));
        }
        let clean = |s: Option<String>| {
            s.map(|v| v.trim().to_owned())
                .filter(|v| !v.is_empty() && v != "<unknown>")
        };
        let title = clean(self.title)
            .or_else(|| clean(self.file_name).map(|f| strip_extension(&f)))
            .unwrap_or_else(|| "Unknown title".into());
        Ok(Track {
            id: TrackId::new(ProviderId::local(), self.uri.clone())?,
            title,
            artist: clean(self.artist).unwrap_or_else(|| "Unknown artist".into()),
            album: clean(self.album).unwrap_or_else(|| "Unknown album".into()),
            duration_ms: self.duration_ms,
            artwork_url: self.artwork_uri,
            playback_uri: Some(self.uri),
            is_preview: false,
            origin: DataOrigin::Real,
        })
    }
}

fn strip_extension(name: &str) -> String {
    match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() && ext.len() <= 5 => stem.to_owned(),
        _ => name.to_owned(),
    }
}

/// In-memory index of local tracks. Persistence lives in `core-storage`; the
/// app reloads this index from the database on startup.
#[derive(Debug, Default, Clone)]
pub struct LocalProvider {
    tracks: Arc<RwLock<BTreeMap<TrackId, Track>>>,
}

impl LocalProvider {
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds or updates tracks. Invalid entries are skipped and counted.
    pub fn upsert(&self, inputs: Vec<LocalTrackInput>) -> (Vec<Track>, usize) {
        let mut added = Vec::new();
        let mut rejected = 0;
        let mut map = self.tracks.write().unwrap_or_else(|e| e.into_inner());
        for input in inputs {
            match input.into_track() {
                Ok(t) => {
                    map.insert(t.id.clone(), t.clone());
                    added.push(t);
                }
                Err(_) => rejected += 1,
            }
        }
        (added, rejected)
    }

    pub fn load(&self, tracks: Vec<Track>) {
        let mut map = self.tracks.write().unwrap_or_else(|e| e.into_inner());
        for t in tracks
            .into_iter()
            .filter(|t| t.id.provider().as_str() == ProviderId::LOCAL)
        {
            map.insert(t.id.clone(), t);
        }
    }

    pub fn remove(&self, id: &TrackId) -> bool {
        self.tracks
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .remove(id)
            .is_some()
    }

    pub fn all(&self) -> Vec<Track> {
        let map = self.tracks.read().unwrap_or_else(|e| e.into_inner());
        let mut v: Vec<Track> = map.values().cloned().collect();
        v.sort_by(|a, b| {
            a.artist
                .to_lowercase()
                .cmp(&b.artist.to_lowercase())
                .then_with(|| a.album.to_lowercase().cmp(&b.album.to_lowercase()))
                .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
        });
        v
    }

    pub fn len(&self) -> usize {
        self.tracks.read().map(|m| m.len()).unwrap_or(0)
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[async_trait]
impl MusicProvider for LocalProvider {
    fn id(&self) -> ProviderId {
        ProviderId::local()
    }

    fn display_name(&self) -> String {
        "On this device".into()
    }

    fn origin(&self) -> DataOrigin {
        DataOrigin::Real
    }

    fn capabilities(&self) -> CapabilitySet {
        CapabilitySet::new()
            .with(Capability::CatalogSearch, CapabilityStatus::Available)
            .with(Capability::FullPlayback, CapabilityStatus::Available)
            .with(
                Capability::AuthorizedOfflineDownloads,
                CapabilityStatus::unavailable("File is already stored on this device"),
            )
            .with(
                Capability::Authentication,
                CapabilityStatus::unavailable("Local files need no account"),
            )
    }

    fn auth_state(&self) -> AuthState {
        AuthState::NotRequired
    }

    async fn search_tracks(&self, query: &str, page: PageRequest) -> DomainResult<Page<Track>> {
        let q = query.trim().to_lowercase();
        let hits: Vec<Track> = self
            .all()
            .into_iter()
            .filter(|t| {
                q.is_empty()
                    || t.title.to_lowercase().contains(&q)
                    || t.artist.to_lowercase().contains(&q)
                    || t.album.to_lowercase().contains(&q)
            })
            .collect();
        Ok(Page::from_slice(&hits, page))
    }

    async fn track(&self, id: &TrackId) -> DomainResult<Track> {
        self.tracks
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(id)
            .cloned()
            .ok_or_else(|| DomainError::NotFound(id.to_string()))
    }

    async fn resolve_download(&self, _track: &Track) -> DomainResult<AuthorizedDownload> {
        self.ensure(Capability::AuthorizedOfflineDownloads)?;
        Err(DomainError::CapabilityUnavailable(
            "local files are already offline".into(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(uri: &str, title: Option<&str>, file: Option<&str>) -> LocalTrackInput {
        LocalTrackInput {
            uri: uri.into(),
            title: title.map(Into::into),
            artist: Some("<unknown>".into()),
            album: None,
            duration_ms: 1000,
            artwork_uri: None,
            file_name: file.map(Into::into),
        }
    }

    #[test]
    fn metadata_fallbacks() {
        let t = input("content://media/1", None, Some("my song.flac"))
            .into_track()
            .unwrap();
        assert_eq!(t.title, "my song");
        assert_eq!(t.artist, "Unknown artist");
        assert_eq!(t.album, "Unknown album");
        assert_eq!(t.origin, DataOrigin::Real);
        assert_eq!(t.playback_uri.as_deref(), Some("content://media/1"));
    }

    #[test]
    fn rejects_non_local_schemes() {
        assert!(input("https://evil.example/a.mp3", Some("x"), None)
            .into_track()
            .is_err());
    }

    #[tokio::test]
    async fn upsert_and_search() {
        let p = LocalProvider::new();
        let (added, rejected) = p.upsert(vec![
            input("content://media/1", Some("Alpha"), None),
            input("content://media/2", Some("Beta"), None),
            input("http://bad", Some("Bad"), None),
        ]);
        assert_eq!((added.len(), rejected), (2, 1));
        let hits = p
            .search_tracks("alp", PageRequest::default())
            .await
            .unwrap();
        assert_eq!(hits.items.len(), 1);
        assert!(p.track(&added[1].id).await.is_ok());
        assert!(p.remove(&added[1].id));
        assert_eq!(p.len(), 1);
        assert!(p.resolve_download(&added[0]).await.is_err());
    }
}
