//! Provider abstraction.
//!
//! Every media source (local files, Deezer, development fixtures) implements
//! [`MusicProvider`]. All operations are gated by a [`CapabilitySet`]; the
//! default implementation of every optional operation returns
//! [`DomainError::CapabilityUnavailable`], so a provider only opts into what it
//! can genuinely and lawfully do.

use std::collections::BTreeMap;
use std::sync::Arc;

use async_trait::async_trait;
use core_domain::{
    Capability, CapabilitySet, CapabilityStatus, DataOrigin, DomainError, DomainResult, Page,
    PageRequest, Playlist, PlaylistId, ProviderId, Track, TrackId,
};
use serde::{Deserialize, Serialize};

/// Authentication state of a provider, as presented to the UI.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum AuthState {
    /// The provider needs no account (e.g. local files).
    NotRequired,
    SignedOut,
    SignedIn {
        display_name: String,
    },
    /// Sign-in is impossible in this build (e.g. no registered app credentials).
    Unsupported {
        reason: String,
    },
}

/// A media file that may be downloaded because the source explicitly
/// authorizes it (user-owned file, direct authorized URL, or a provider with
/// the `AuthorizedOfflineDownloads` capability).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthorizedDownload {
    pub track_id: TrackId,
    pub url: String,
    pub file_name: String,
    pub expected_size: Option<u64>,
    pub expected_sha256: Option<String>,
    pub supports_resume: bool,
}

/// Static description of a provider for the UI.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProviderInfo {
    pub id: ProviderId,
    pub display_name: String,
    pub origin: DataOrigin,
    pub auth: AuthState,
    pub capabilities: CapabilitySet,
}

#[async_trait]
pub trait MusicProvider: Send + Sync {
    fn id(&self) -> ProviderId;
    fn display_name(&self) -> String;
    /// Origin label attached to all data returned by this provider.
    fn origin(&self) -> DataOrigin;
    fn capabilities(&self) -> CapabilitySet;
    fn auth_state(&self) -> AuthState;

    fn info(&self) -> ProviderInfo {
        ProviderInfo {
            id: self.id(),
            display_name: self.display_name(),
            origin: self.origin(),
            auth: self.auth_state(),
            capabilities: self.capabilities(),
        }
    }

    /// Returns `Ok(())` if the capability is currently available, otherwise a
    /// descriptive `CapabilityUnavailable` / `NotAuthenticated` error.
    fn ensure(&self, cap: Capability) -> DomainResult<()> {
        match self.capabilities().status(cap) {
            CapabilityStatus::Available => Ok(()),
            CapabilityStatus::RequiresAuthentication => Err(DomainError::NotAuthenticated),
            CapabilityStatus::Unavailable { reason } => Err(DomainError::CapabilityUnavailable(
                format!("{}: {reason}", self.display_name()),
            )),
        }
    }

    async fn search_tracks(&self, _query: &str, _page: PageRequest) -> DomainResult<Page<Track>> {
        self.ensure(Capability::CatalogSearch)?;
        Err(unimplemented_op("search_tracks"))
    }

    async fn track(&self, _id: &TrackId) -> DomainResult<Track> {
        Err(unimplemented_op("track"))
    }

    async fn user_playlists(&self, _page: PageRequest) -> DomainResult<Page<Playlist>> {
        self.ensure(Capability::UserPlaylists)?;
        Err(unimplemented_op("user_playlists"))
    }

    async fn playlist_tracks(
        &self,
        _id: &PlaylistId,
        _page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        Err(unimplemented_op("playlist_tracks"))
    }

    async fn favorite_tracks(&self, _page: PageRequest) -> DomainResult<Page<Track>> {
        self.ensure(Capability::Favorites)?;
        Err(unimplemented_op("favorite_tracks"))
    }

    /// Resolve an authorized download for a track. Providers without the
    /// `AuthorizedOfflineDownloads` capability must never return `Ok`.
    async fn resolve_download(&self, _track: &Track) -> DomainResult<AuthorizedDownload> {
        self.ensure(Capability::AuthorizedOfflineDownloads)?;
        Err(unimplemented_op("resolve_download"))
    }
}

fn unimplemented_op(op: &str) -> DomainError {
    DomainError::CapabilityUnavailable(format!("operation `{op}` is not implemented"))
}

/// Registry of active providers. Immutable after construction; build a new one
/// to change providers (avoids shared mutable state).
#[derive(Default, Clone)]
pub struct ProviderRegistry {
    providers: BTreeMap<ProviderId, Arc<dyn MusicProvider>>,
}

impl ProviderRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with(mut self, provider: Arc<dyn MusicProvider>) -> Self {
        self.providers.insert(provider.id(), provider);
        self
    }

    pub fn get(&self, id: &ProviderId) -> DomainResult<Arc<dyn MusicProvider>> {
        self.providers
            .get(id)
            .cloned()
            .ok_or_else(|| DomainError::NotFound(format!("provider `{id}`")))
    }

    pub fn for_track(&self, track: &TrackId) -> DomainResult<Arc<dyn MusicProvider>> {
        self.get(track.provider())
    }

    pub fn infos(&self) -> Vec<ProviderInfo> {
        self.providers.values().map(|p| p.info()).collect()
    }

    pub fn ids(&self) -> Vec<ProviderId> {
        self.providers.keys().cloned().collect()
    }

    /// Capability status of a single track's provider.
    pub fn capability_for_track(&self, track: &TrackId, cap: Capability) -> CapabilityStatus {
        match self.for_track(track) {
            Ok(p) => p.capabilities().status(cap),
            Err(_) => CapabilityStatus::unavailable("Provider is not available"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Bare;

    #[async_trait]
    impl MusicProvider for Bare {
        fn id(&self) -> ProviderId {
            ProviderId::new("bare").unwrap()
        }
        fn display_name(&self) -> String {
            "Bare".into()
        }
        fn origin(&self) -> DataOrigin {
            DataOrigin::Real
        }
        fn capabilities(&self) -> CapabilitySet {
            CapabilitySet::new().with(
                Capability::Favorites,
                CapabilityStatus::RequiresAuthentication,
            )
        }
        fn auth_state(&self) -> AuthState {
            AuthState::SignedOut
        }
    }

    #[tokio::test]
    async fn default_operations_are_gated() {
        let p = Bare;
        let err = p
            .search_tracks("x", PageRequest::default())
            .await
            .unwrap_err();
        assert!(matches!(err, DomainError::CapabilityUnavailable(_)));
        assert_eq!(
            p.favorite_tracks(PageRequest::default()).await.unwrap_err(),
            DomainError::NotAuthenticated
        );
    }

    #[test]
    fn registry_lookup() {
        let reg = ProviderRegistry::new().with(Arc::new(Bare));
        let id = TrackId::parse("bare:1").unwrap();
        assert!(reg.for_track(&id).is_ok());
        assert!(reg.get(&ProviderId::deezer()).is_err());
        assert!(!reg
            .capability_for_track(&TrackId::parse("nope:1").unwrap(), Capability::FullPlayback)
            .is_available());
        assert_eq!(reg.infos().len(), 1);
    }
}
