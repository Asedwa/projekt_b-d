//! Development fixture provider.
//!
//! Supplies synthetic catalog data so the UI can be exercised without any
//! account. **Every item is labeled `DataOrigin::DevelopmentFixture`** and the
//! provider's display name says so; the UI must badge this data and never
//! present it as coming from a real Deezer account.

use async_trait::async_trait;
use core_domain::{
    Capability, CapabilitySet, CapabilityStatus, DataOrigin, DomainError, DomainResult, Page,
    PageRequest, Playlist, PlaylistId, PlaylistKind, ProviderId, Track, TrackId,
};
use core_providers::{AuthState, MusicProvider};

const FIXTURE_TRACKS: &[(&str, &str, &str, u64)] = &[
    (
        "Aurora Borealis",
        "Fixture Ensemble",
        "Northern Lights (Fixture)",
        214_000,
    ),
    (
        "Graphite Skies",
        "Breeze Collective",
        "Dark Theme (Fixture)",
        187_000,
    ),
    (
        "Plasma Drift",
        "Oxygen Sound",
        "Desktop Dreams (Fixture)",
        245_000,
    ),
    (
        "Kirigami Fold",
        "Breeze Collective",
        "Dark Theme (Fixture)",
        199_000,
    ),
    ("Konqueror", "The Widgets", "Panel Tales (Fixture)", 263_000),
    (
        "Dolphin Dive",
        "The Widgets",
        "Panel Tales (Fixture)",
        176_000,
    ),
    ("Wayland Waves", "Compositor", "Frames (Fixture)", 301_000),
    (
        "Activity Switch",
        "Oxygen Sound",
        "Desktop Dreams (Fixture)",
        222_000,
    ),
    (
        "Night Color",
        "Fixture Ensemble",
        "Northern Lights (Fixture)",
        238_000,
    ),
    ("Overview Effect", "Compositor", "Frames (Fixture)", 205_000),
    (
        "Krunner Run",
        "Shortcut Club",
        "Meta Key (Fixture)",
        168_000,
    ),
    ("Spectacle", "Shortcut Club", "Meta Key (Fixture)", 192_000),
];

const FIXTURE_PLAYLISTS: &[(&str, &str, &[usize])] = &[
    ("dev:pl-focus", "Focus Fixtures", &[1, 3, 6, 9]),
    ("dev:pl-evening", "Evening Fixtures", &[0, 2, 7, 8, 11]),
    (
        "dev:pl-all",
        "All Fixture Tracks",
        &[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11],
    ),
];

#[derive(Debug, Default, Clone)]
pub struct DevProvider;

impl DevProvider {
    pub fn new() -> Self {
        Self
    }

    fn track_at(i: usize) -> Track {
        let (title, artist, album, duration_ms) = FIXTURE_TRACKS[i];
        Track {
            id: TrackId::new(ProviderId::dev(), format!("fx{i}")).expect("valid fixture id"),
            title: title.into(),
            artist: artist.into(),
            album: album.into(),
            duration_ms,
            artwork_url: None,
            playback_uri: None,
            is_preview: false,
            origin: DataOrigin::DevelopmentFixture,
        }
    }

    pub fn all_tracks() -> Vec<Track> {
        (0..FIXTURE_TRACKS.len()).map(Self::track_at).collect()
    }

    fn playlist_at(i: usize) -> Playlist {
        let (id, name, tracks) = FIXTURE_PLAYLISTS[i];
        Playlist {
            id: PlaylistId::new(id).expect("valid fixture playlist id"),
            name: name.into(),
            description: Some("Development fixture — not from any account".into()),
            kind: PlaylistKind::Remote,
            track_count: tracks.len() as u32,
            artwork_url: None,
            origin: DataOrigin::DevelopmentFixture,
        }
    }
}

#[async_trait]
impl MusicProvider for DevProvider {
    fn id(&self) -> ProviderId {
        ProviderId::dev()
    }

    fn display_name(&self) -> String {
        "Development fixtures".into()
    }

    fn origin(&self) -> DataOrigin {
        DataOrigin::DevelopmentFixture
    }

    fn capabilities(&self) -> CapabilitySet {
        let no_audio = "Fixtures contain metadata only, no audio";
        CapabilitySet::new()
            .with(Capability::CatalogSearch, CapabilityStatus::Available)
            .with(Capability::UserPlaylists, CapabilityStatus::Available)
            .with(
                Capability::Authentication,
                CapabilityStatus::unavailable("Fixture provider has no accounts"),
            )
            .with(
                Capability::FullPlayback,
                CapabilityStatus::unavailable(no_audio),
            )
            .with(
                Capability::PreviewPlayback,
                CapabilityStatus::unavailable(no_audio),
            )
            .with(
                Capability::AuthorizedOfflineDownloads,
                CapabilityStatus::unavailable(no_audio),
            )
    }

    fn auth_state(&self) -> AuthState {
        AuthState::NotRequired
    }

    async fn search_tracks(&self, query: &str, page: PageRequest) -> DomainResult<Page<Track>> {
        let q = query.trim().to_lowercase();
        let hits: Vec<Track> = Self::all_tracks()
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
        id.key()
            .strip_prefix("fx")
            .and_then(|n| n.parse::<usize>().ok())
            .filter(|n| *n < FIXTURE_TRACKS.len())
            .map(Self::track_at)
            .ok_or_else(|| DomainError::NotFound(id.to_string()))
    }

    async fn user_playlists(&self, page: PageRequest) -> DomainResult<Page<Playlist>> {
        let all: Vec<Playlist> = (0..FIXTURE_PLAYLISTS.len())
            .map(Self::playlist_at)
            .collect();
        Ok(Page::from_slice(&all, page))
    }

    async fn playlist_tracks(
        &self,
        id: &PlaylistId,
        page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        let (_, _, idx) = FIXTURE_PLAYLISTS
            .iter()
            .find(|(pid, _, _)| *pid == id.as_str())
            .ok_or_else(|| DomainError::NotFound(id.to_string()))?;
        let tracks: Vec<Track> = idx.iter().map(|i| Self::track_at(*i)).collect();
        Ok(Page::from_slice(&tracks, page))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn all_data_is_labeled_as_fixture() {
        let p = DevProvider::new();
        let page = p.search_tracks("", PageRequest::first(100)).await.unwrap();
        assert_eq!(page.items.len(), FIXTURE_TRACKS.len());
        assert!(page
            .items
            .iter()
            .all(|t| t.origin == DataOrigin::DevelopmentFixture));
        let pls = p.user_playlists(PageRequest::default()).await.unwrap();
        assert!(pls
            .items
            .iter()
            .all(|pl| pl.origin == DataOrigin::DevelopmentFixture));
    }

    #[tokio::test]
    async fn search_filters_and_downloads_are_refused() {
        let p = DevProvider::new();
        let hits = p
            .search_tracks("widgets", PageRequest::default())
            .await
            .unwrap();
        assert_eq!(hits.items.len(), 2);
        let t = &hits.items[0];
        assert!(matches!(
            p.resolve_download(t).await,
            Err(DomainError::CapabilityUnavailable(_))
        ));
        assert_eq!(p.track(&t.id).await.unwrap(), *t);
    }

    #[tokio::test]
    async fn playlist_tracks_resolve() {
        let p = DevProvider::new();
        let id = PlaylistId::new("dev:pl-focus").unwrap();
        let tracks = p
            .playlist_tracks(&id, PageRequest::default())
            .await
            .unwrap();
        assert_eq!(tracks.items.len(), 4);
        assert!(p
            .playlist_tracks(
                &PlaylistId::new("dev:nope").unwrap(),
                PageRequest::default()
            )
            .await
            .is_err());
    }
}
