use async_trait::async_trait;
use core_domain::{
    Capability, CapabilitySet, CapabilityStatus, DataOrigin, DomainError, DomainResult, Page,
    PageRequest, PlaylistId, ProviderId, Track, TrackId,
};
use core_providers::{AuthState, MusicProvider};

use crate::{DeezerAppConfig, DeezerClient};

/// Deezer provider. In this build only public catalog features are enabled;
/// account features stay explicitly unavailable until authorized credentials
/// and a token-exchange backend exist.
#[derive(Debug)]
pub struct DeezerProvider {
    client: DeezerClient,
    config: DeezerAppConfig,
}

const NO_FULL_PLAYBACK: &str =
    "Deezer does not offer full-track playback to third-party apps. Only 30-second previews are available.";
const NO_DOWNLOADS: &str =
    "Deezer does not authorize offline downloads in third-party apps. Use the official Deezer app for offline listening.";

impl DeezerProvider {
    pub fn new(config: DeezerAppConfig) -> DomainResult<Self> {
        Ok(Self {
            client: DeezerClient::new()?,
            config,
        })
    }

    pub fn with_client(client: DeezerClient, config: DeezerAppConfig) -> Self {
        Self { client, config }
    }

    pub fn client(&self) -> &DeezerClient {
        &self.client
    }

    fn numeric_key(id: &str) -> DomainResult<u64> {
        id.parse()
            .map_err(|_| DomainError::InvalidInput(format!("invalid Deezer id `{id}`")))
    }

    pub async fn chart(&self, page: PageRequest) -> DomainResult<Page<Track>> {
        self.client.chart_tracks(page).await
    }
}

#[async_trait]
impl MusicProvider for DeezerProvider {
    fn id(&self) -> ProviderId {
        ProviderId::deezer()
    }

    fn display_name(&self) -> String {
        "Deezer (public catalog)".into()
    }

    fn origin(&self) -> DataOrigin {
        DataOrigin::PublicCatalog
    }

    fn capabilities(&self) -> CapabilitySet {
        // Account capabilities are only *potentially* available when the
        // app is fully configured; until a session exists they require auth.
        let account = if self.config.supports_user_auth() {
            CapabilityStatus::RequiresAuthentication
        } else {
            CapabilityStatus::unavailable(self.config.unavailable_reason())
        };
        let auth = if self.config.supports_user_auth() {
            CapabilityStatus::Available
        } else {
            CapabilityStatus::unavailable(self.config.unavailable_reason())
        };
        CapabilitySet::new()
            .with(Capability::Authentication, auth)
            .with(Capability::UserPlaylists, account.clone())
            .with(Capability::Favorites, account.clone())
            .with(Capability::PlaylistEditing, account)
            .with(Capability::CatalogSearch, CapabilityStatus::Available)
            .with(Capability::PreviewPlayback, CapabilityStatus::Available)
            .with(
                Capability::FullPlayback,
                CapabilityStatus::unavailable(NO_FULL_PLAYBACK),
            )
            .with(
                Capability::AuthorizedOfflineDownloads,
                CapabilityStatus::unavailable(NO_DOWNLOADS),
            )
    }

    fn auth_state(&self) -> AuthState {
        if self.config.supports_user_auth() {
            AuthState::SignedOut
        } else {
            AuthState::Unsupported {
                reason: self.config.unavailable_reason().into(),
            }
        }
    }

    async fn search_tracks(&self, query: &str, page: PageRequest) -> DomainResult<Page<Track>> {
        self.ensure(Capability::CatalogSearch)?;
        self.client.search_tracks(query, page).await
    }

    async fn track(&self, id: &TrackId) -> DomainResult<Track> {
        self.client.track(Self::numeric_key(id.key())?).await
    }

    /// Public playlists can be read without authentication (`deezer:<id>`).
    async fn playlist_tracks(
        &self,
        id: &PlaylistId,
        page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        let key = id
            .as_str()
            .strip_prefix("deezer:")
            .ok_or_else(|| DomainError::InvalidInput("not a Deezer playlist".into()))?;
        self.client
            .public_playlist_tracks(Self::numeric_key(key)?, page)
            .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn restricted_capabilities_are_explicitly_unavailable() {
        let p = DeezerProvider::with_client(
            DeezerClient::with_base("http://127.0.0.1:9").unwrap(),
            DeezerAppConfig::default(),
        );
        let caps = p.capabilities();
        assert!(caps.is_available(Capability::CatalogSearch));
        assert!(caps.is_available(Capability::PreviewPlayback));
        for c in [
            Capability::Authentication,
            Capability::UserPlaylists,
            Capability::Favorites,
            Capability::PlaylistEditing,
            Capability::FullPlayback,
            Capability::AuthorizedOfflineDownloads,
        ] {
            assert!(
                matches!(caps.status(c), CapabilityStatus::Unavailable { .. }),
                "{c:?} must be unavailable"
            );
        }
        assert!(matches!(p.auth_state(), AuthState::Unsupported { .. }));
        assert!(p.user_playlists(PageRequest::default()).await.is_err());
        assert!(p.favorite_tracks(PageRequest::default()).await.is_err());
    }

    #[test]
    fn downloads_never_available_even_when_configured() {
        let p = DeezerProvider::with_client(
            DeezerClient::with_base("http://127.0.0.1:9").unwrap(),
            DeezerAppConfig {
                app_id: Some("1".into()),
                redirect_uri: Some("plasmawave://auth/deezer".into()),
                token_exchange_endpoint: Some("https://x.example".into()),
            },
        );
        let caps = p.capabilities();
        assert!(!caps.is_available(Capability::AuthorizedOfflineDownloads));
        assert!(!caps.is_available(Capability::FullPlayback));
        assert_eq!(
            caps.status(Capability::UserPlaylists),
            CapabilityStatus::RequiresAuthentication
        );
    }
}
