//! Library domain services.
//!
//! [`LibraryRepository`] is the persistence port; `core-storage` provides the
//! SQLite adapter. [`LibraryService`] contains the business rules (validation,
//! favorite toggling, playlist editing) and is independent of storage.

use std::sync::Arc;

use core_domain::{
    validate_playlist_name, DomainError, DomainResult, Playlist, PlaylistId, ProviderId, Track,
    TrackId,
};

/// Persistence port for library data. Implementations must make each method
/// atomic (single transaction).
pub trait LibraryRepository: Send + Sync {
    fn upsert_tracks(&self, tracks: &[Track]) -> DomainResult<()>;
    fn track(&self, id: &TrackId) -> DomainResult<Option<Track>>;
    fn tracks_by_provider(&self, provider: &ProviderId) -> DomainResult<Vec<Track>>;
    fn delete_track(&self, id: &TrackId) -> DomainResult<()>;
    fn search_tracks(&self, query: &str, limit: u32) -> DomainResult<Vec<Track>>;

    fn set_favorite(&self, id: &TrackId, favorite: bool) -> DomainResult<()>;
    fn is_favorite(&self, id: &TrackId) -> DomainResult<bool>;
    fn favorites(&self) -> DomainResult<Vec<Track>>;

    fn create_playlist(&self, name: &str) -> DomainResult<Playlist>;
    fn rename_playlist(&self, id: &PlaylistId, name: &str) -> DomainResult<()>;
    fn delete_playlist(&self, id: &PlaylistId) -> DomainResult<()>;
    fn playlists(&self) -> DomainResult<Vec<Playlist>>;
    fn playlist(&self, id: &PlaylistId) -> DomainResult<Option<Playlist>>;
    fn playlist_tracks(&self, id: &PlaylistId) -> DomainResult<Vec<Track>>;
    fn append_to_playlist(&self, id: &PlaylistId, tracks: &[TrackId]) -> DomainResult<()>;
    fn remove_from_playlist(&self, id: &PlaylistId, position: u32) -> DomainResult<()>;
    fn move_in_playlist(&self, id: &PlaylistId, from: u32, to: u32) -> DomainResult<()>;
}

#[derive(Clone)]
pub struct LibraryService {
    repo: Arc<dyn LibraryRepository>,
}

impl LibraryService {
    pub fn new(repo: Arc<dyn LibraryRepository>) -> Self {
        Self { repo }
    }

    pub fn repository(&self) -> &Arc<dyn LibraryRepository> {
        &self.repo
    }

    /// Toggles a favorite. The track's metadata is stored first so favorites
    /// remain displayable offline. Returns the new favorite state.
    pub fn toggle_favorite(&self, track: &Track) -> DomainResult<bool> {
        let now = !self.repo.is_favorite(&track.id)?;
        if now {
            self.repo.upsert_tracks(std::slice::from_ref(track))?;
        }
        self.repo.set_favorite(&track.id, now)?;
        Ok(now)
    }

    pub fn is_favorite(&self, id: &TrackId) -> DomainResult<bool> {
        self.repo.is_favorite(id)
    }

    pub fn favorites(&self) -> DomainResult<Vec<Track>> {
        self.repo.favorites()
    }

    pub fn create_playlist(&self, name: &str) -> DomainResult<Playlist> {
        let name = validate_playlist_name(name)?;
        self.repo.create_playlist(&name)
    }

    pub fn rename_playlist(&self, id: &PlaylistId, name: &str) -> DomainResult<()> {
        let name = validate_playlist_name(name)?;
        self.require_local(id)?;
        self.repo.rename_playlist(id, &name)
    }

    pub fn delete_playlist(&self, id: &PlaylistId) -> DomainResult<()> {
        self.require_local(id)?;
        self.repo.delete_playlist(id)
    }

    pub fn playlists(&self) -> DomainResult<Vec<Playlist>> {
        self.repo.playlists()
    }

    pub fn playlist_tracks(&self, id: &PlaylistId) -> DomainResult<Vec<Track>> {
        self.repo.playlist_tracks(id)
    }

    /// Adds tracks to a local playlist, storing their metadata first.
    pub fn add_to_playlist(&self, id: &PlaylistId, tracks: &[Track]) -> DomainResult<()> {
        self.require_local(id)?;
        if tracks.is_empty() {
            return Ok(());
        }
        self.repo.upsert_tracks(tracks)?;
        let ids: Vec<TrackId> = tracks.iter().map(|t| t.id.clone()).collect();
        self.repo.append_to_playlist(id, &ids)
    }

    pub fn remove_from_playlist(&self, id: &PlaylistId, position: u32) -> DomainResult<()> {
        self.require_local(id)?;
        self.repo.remove_from_playlist(id, position)
    }

    pub fn move_in_playlist(&self, id: &PlaylistId, from: u32, to: u32) -> DomainResult<()> {
        self.require_local(id)?;
        self.repo.move_in_playlist(id, from, to)
    }

    pub fn search(&self, query: &str, limit: u32) -> DomainResult<Vec<Track>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        if q.chars().count() > 200 {
            return Err(DomainError::InvalidInput("search query too long".into()));
        }
        self.repo.search_tracks(q, limit.clamp(1, 200))
    }

    fn require_local(&self, id: &PlaylistId) -> DomainResult<()> {
        if !id.as_str().starts_with("local:") {
            return Err(DomainError::CapabilityUnavailable(
                "only local playlists can be edited".into(),
            ));
        }
        match self.repo.playlist(id)? {
            Some(_) => Ok(()),
            None => Err(DomainError::NotFound(format!("playlist {id}"))),
        }
    }
}
