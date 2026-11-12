use std::time::Duration;

use core_domain::{
    Album, Artist, DataOrigin, DomainError, DomainResult, Page, PageRequest, Playlist, PlaylistId,
    PlaylistKind, ProviderId, Track, TrackId,
};
use serde::de::DeserializeOwned;
use serde::Deserialize;

use crate::rate_limit::RateLimiter;

pub const DEFAULT_API_BASE: &str = "https://api.deezer.com";
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);
const MAX_QUERY_LEN: usize = 200;
/// Deezer error code for quota exceeded.
const QUOTA_EXCEEDED: i64 = 4;

/// Client for Deezer's **public, unauthenticated** catalog API.
#[derive(Debug)]
pub struct DeezerClient {
    http: reqwest::Client,
    base: String,
    limiter: RateLimiter,
}

#[derive(Debug, Deserialize)]
struct ApiErrorEnvelope {
    error: ApiError,
}

#[derive(Debug, Deserialize)]
struct ApiError {
    #[serde(default, rename = "type")]
    kind: String,
    #[serde(default)]
    message: String,
    #[serde(default)]
    code: i64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzList<T> {
    pub data: Vec<T>,
    #[serde(default)]
    pub total: Option<u32>,
    #[serde(default)]
    pub next: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzArtist {
    pub name: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzAlbum {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub cover_medium: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzUser {
    pub id: u64,
    pub name: String,
    #[serde(default)]
    pub email: Option<String>,
    #[serde(default)]
    pub status: Option<i32>,
    #[serde(default)]
    pub link: Option<String>,
    #[serde(default)]
    pub picture_medium: Option<String>,
    #[serde(default)]
    pub picture_big: Option<String>,
    #[serde(default)]
    pub nb_playlists: Option<u32>,
    #[serde(default)]
    pub country: Option<String>,
    #[serde(default)]
    pub is_kid: Option<bool>,
}

impl DzUser {
    pub fn is_premium(&self) -> bool {
        matches!(self.status, Some(1) | Some(2))
    }

    pub fn status_label(&self) -> String {
        match self.status {
            Some(1) => "Deezer Premium".to_string(),
            Some(2) => "Deezer Family".to_string(),
            Some(0) => "Deezer Free".to_string(),
            Some(n) => format!("Deezer Plan ({n})"),
            None => "Deezer Member".to_string(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzPlaylistSimple {
    pub id: u64,
    pub title: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub nb_tracks: Option<u32>,
    #[serde(default)]
    pub picture_medium: Option<String>,
}

impl DzPlaylistSimple {
    pub fn into_playlist(self) -> DomainResult<Playlist> {
        Ok(Playlist {
            id: PlaylistId::new(format!("deezer:{}", self.id))?,
            name: self.title,
            description: self.description,
            kind: PlaylistKind::Remote,
            track_count: self.nb_tracks.unwrap_or(0),
            artwork_url: self.picture_medium,
            origin: DataOrigin::PublicCatalog,
        })
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzAlbumSimple {
    pub id: u64,
    pub title: String,
    #[serde(default)]
    pub cover_medium: Option<String>,
    #[serde(default)]
    pub release_date: Option<String>,
    #[serde(default)]
    pub nb_tracks: Option<u32>,
    #[serde(default)]
    pub artist: Option<DzArtist>,
}

impl DzAlbumSimple {
    pub fn into_album(self) -> Album {
        Album {
            id: format!("deezer:{}", self.id),
            title: self.title,
            artist_name: self.artist.map(|a| a.name).unwrap_or_default(),
            cover_url: self.cover_medium,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzArtistDetail {
    pub id: u64,
    pub name: String,
    #[serde(default)]
    pub picture_medium: Option<String>,
    #[serde(default)]
    pub nb_album: Option<u32>,
    #[serde(default)]
    pub nb_fan: Option<u32>,
}

impl DzArtistDetail {
    pub fn into_artist(self) -> Artist {
        Artist {
            id: format!("deezer:{}", self.id),
            name: self.name,
            picture_url: self.picture_medium,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzAlbumDetail {
    pub id: u64,
    pub title: String,
    #[serde(default)]
    pub cover_medium: Option<String>,
    #[serde(default)]
    pub release_date: Option<String>,
    #[serde(default)]
    pub nb_tracks: Option<u32>,
    #[serde(default)]
    pub artist: Option<DzArtist>,
    #[serde(default)]
    pub tracks: Option<DzList<DzTrack>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DzTrack {
    pub id: u64,
    pub title: String,
    /// Seconds.
    #[serde(default)]
    pub duration: u64,
    #[serde(default)]
    pub preview: Option<String>,
    #[serde(default = "default_true")]
    pub readable: bool,
    pub artist: DzArtist,
    #[serde(default)]
    pub album: Option<DzAlbum>,
}

fn default_true() -> bool {
    true
}

impl DzTrack {
    pub fn into_track(self) -> DomainResult<Track> {
        let preview = self
            .preview
            .filter(|p| self.readable && p.starts_with("https://"));
        let (album, artwork) = self
            .album
            .map(|a| (a.title, a.cover_medium))
            .unwrap_or_default();
        Ok(Track {
            id: TrackId::new(ProviderId::deezer(), self.id.to_string())?,
            title: self.title,
            artist: self.artist.name,
            album,
            duration_ms: self.duration.saturating_mul(1000),
            artwork_url: artwork,
            is_preview: preview.is_some(),
            playback_uri: preview,
            origin: DataOrigin::PublicCatalog,
        })
    }
}

impl DeezerClient {
    pub fn new() -> DomainResult<Self> {
        Self::with_base(DEFAULT_API_BASE)
    }

    /// Custom base URL (used by tests). Non-HTTPS bases are only accepted for
    /// loopback addresses.
    pub fn with_base(base: &str) -> DomainResult<Self> {
        let base = base.trim_end_matches('/').to_owned();
        let loopback = base.starts_with("http://127.0.0.1") || base.starts_with("http://localhost");
        if !base.starts_with("https://") && !loopback {
            return Err(DomainError::InvalidInput(
                "Deezer API base must use HTTPS".into(),
            ));
        }
        let http = reqwest::Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            .https_only(!loopback)
            .user_agent(concat!("PlasmaWave/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| DomainError::Internal(format!("http client: {e}")))?;
        Ok(Self {
            http,
            base,
            limiter: RateLimiter::deezer_default(),
        })
    }

    async fn get<T: DeserializeOwned>(
        &self,
        path: &str,
        query: &[(&str, String)],
    ) -> DomainResult<T> {
        self.limiter.acquire().await;
        let resp = self
            .http
            .get(format!("{}{}", self.base, path))
            .query(query)
            .send()
            .await
            .map_err(|e| DomainError::Network(redact(&e)))?;
        let status = resp.status();
        if status == reqwest::StatusCode::TOO_MANY_REQUESTS {
            return Err(DomainError::RateLimited {
                retry_after_ms: 5_000,
            });
        }
        if !status.is_success() {
            return Err(DomainError::Provider(format!(
                "Deezer HTTP {}",
                status.as_u16()
            )));
        }
        let body = resp
            .bytes()
            .await
            .map_err(|e| DomainError::Network(redact(&e)))?;
        // Deezer reports API errors with HTTP 200 and an `error` object.
        if let Ok(env) = serde_json::from_slice::<ApiErrorEnvelope>(&body) {
            return Err(map_api_error(env.error));
        }
        serde_json::from_slice(&body)
            .map_err(|e| DomainError::Provider(format!("unexpected Deezer response: {e}")))
    }

    pub async fn search_tracks(&self, query: &str, page: PageRequest) -> DomainResult<Page<Track>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Page::single(Vec::new()));
        }
        if q.chars().count() > MAX_QUERY_LEN {
            return Err(DomainError::InvalidInput("search query too long".into()));
        }
        let list: DzList<DzTrack> = self
            .get(
                "/search/track",
                &[
                    ("q", q.to_owned()),
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        to_page(list, page)
    }

    pub async fn track(&self, id: u64) -> DomainResult<Track> {
        let t: DzTrack = self.get(&format!("/track/{id}"), &[]).await?;
        t.into_track()
    }

    pub async fn chart_tracks(&self, page: PageRequest) -> DomainResult<Page<Track>> {
        let list: DzList<DzTrack> = self
            .get(
                "/chart/0/tracks",
                &[
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        to_page(list, page)
    }

    pub async fn public_playlist_tracks(
        &self,
        id: u64,
        page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        let list: DzList<DzTrack> = self
            .get(
                &format!("/playlist/{id}/tracks"),
                &[
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        to_page(list, page)
    }

    pub async fn get_user(&self, user_id: &str) -> DomainResult<DzUser> {
        let clean_id = user_id.trim();
        self.get(&format!("/user/{clean_id}"), &[]).await
    }

    pub async fn get_user_playlists(
        &self,
        user_id: &str,
        page: PageRequest,
    ) -> DomainResult<Page<Playlist>> {
        let clean_id = user_id.trim();
        let list: DzList<DzPlaylistSimple> = self
            .get(
                &format!("/user/{clean_id}/playlists"),
                &[
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        let has_more = list.next.is_some();
        let items = list
            .data
            .into_iter()
            .filter_map(|p| p.into_playlist().ok())
            .collect();
        Ok(Page {
            items,
            offset: page.offset,
            total: list.total,
            has_more,
        })
    }

    pub async fn get_user_favorites(
        &self,
        user_id: &str,
        page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        let clean_id = user_id.trim();
        let list: DzList<DzTrack> = self
            .get(
                &format!("/user/{clean_id}/tracks"),
                &[
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        to_page(list, page)
    }

    pub async fn get_current_user(&self, access_token: &str) -> DomainResult<DzUser> {
        let token = access_token.trim();
        if token.is_empty() {
            return Err(DomainError::InvalidInput("empty access token".into()));
        }
        self.get("/user/me", &[("access_token", token.to_owned())]).await
    }

    pub async fn get_my_favorites(
        &self,
        access_token: &str,
        page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        let token = access_token.trim();
        if token.is_empty() {
            return Err(DomainError::InvalidInput("empty access token".into()));
        }
        let list: DzList<DzTrack> = self
            .get(
                "/user/me/tracks",
                &[
                    ("access_token", token.to_owned()),
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        to_page(list, page)
    }

    pub async fn get_my_playlists(
        &self,
        access_token: &str,
        page: PageRequest,
    ) -> DomainResult<Page<Playlist>> {
        let token = access_token.trim();
        if token.is_empty() {
            return Err(DomainError::InvalidInput("empty access token".into()));
        }
        let list: DzList<DzPlaylistSimple> = self
            .get(
                "/user/me/playlists",
                &[
                    ("access_token", token.to_owned()),
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        let has_more = list.next.is_some();
        let items = list
            .data
            .into_iter()
            .filter_map(|p| p.into_playlist().ok())
            .collect();
        Ok(Page {
            items,
            offset: page.offset,
            total: list.total,
            has_more,
        })
    }

    pub async fn get_my_flow(&self, access_token: &str) -> DomainResult<Vec<Track>> {
        let token = access_token.trim();
        if token.is_empty() {
            return Err(DomainError::InvalidInput("empty access token".into()));
        }
        let list: DzList<DzTrack> = self
            .get("/user/me/flow", &[("access_token", token.to_owned())])
            .await?;
        let mut tracks = Vec::new();
        for t in list.data {
            if let Ok(track) = t.into_track() {
                tracks.push(track);
            }
        }
        Ok(tracks)
    }

    pub async fn get_album(&self, album_id: u64) -> DomainResult<DzAlbumDetail> {
        self.get(&format!("/album/{album_id}"), &[]).await
    }

    pub async fn get_album_tracks(
        &self,
        album_id: u64,
        page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        let list: DzList<DzTrack> = self
            .get(
                &format!("/album/{album_id}/tracks"),
                &[
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        to_page(list, page)
    }

    pub async fn get_artist(&self, artist_id: u64) -> DomainResult<DzArtistDetail> {
        self.get(&format!("/artist/{artist_id}"), &[]).await
    }

    pub async fn get_artist_top_tracks(
        &self,
        artist_id: u64,
        page: PageRequest,
    ) -> DomainResult<Page<Track>> {
        let list: DzList<DzTrack> = self
            .get(
                &format!("/artist/{artist_id}/top"),
                &[
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        to_page(list, page)
    }

    pub async fn get_artist_albums(
        &self,
        artist_id: u64,
        page: PageRequest,
    ) -> DomainResult<Page<Album>> {
        let list: DzList<DzAlbumSimple> = self
            .get(
                &format!("/artist/{artist_id}/albums"),
                &[
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        let has_more = list.next.is_some();
        let items = list.data.into_iter().map(|a| a.into_album()).collect();
        Ok(Page {
            items,
            offset: page.offset,
            total: list.total,
            has_more,
        })
    }

    pub async fn search_albums(&self, query: &str, page: PageRequest) -> DomainResult<Page<Album>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Page::single(Vec::new()));
        }
        if q.chars().count() > MAX_QUERY_LEN {
            return Err(DomainError::InvalidInput("search query too long".into()));
        }
        let list: DzList<DzAlbumSimple> = self
            .get(
                "/search/album",
                &[
                    ("q", q.to_owned()),
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        let has_more = list.next.is_some();
        let items = list.data.into_iter().map(|a| a.into_album()).collect();
        Ok(Page {
            items,
            offset: page.offset,
            total: list.total,
            has_more,
        })
    }

    pub async fn search_artists(
        &self,
        query: &str,
        page: PageRequest,
    ) -> DomainResult<Page<Artist>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Page::single(Vec::new()));
        }
        if q.chars().count() > MAX_QUERY_LEN {
            return Err(DomainError::InvalidInput("search query too long".into()));
        }
        let list: DzList<DzArtistDetail> = self
            .get(
                "/search/artist",
                &[
                    ("q", q.to_owned()),
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        let has_more = list.next.is_some();
        let items = list.data.into_iter().map(|a| a.into_artist()).collect();
        Ok(Page {
            items,
            offset: page.offset,
            total: list.total,
            has_more,
        })
    }

    pub async fn search_playlists(
        &self,
        query: &str,
        page: PageRequest,
    ) -> DomainResult<Page<Playlist>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Page::single(Vec::new()));
        }
        if q.chars().count() > MAX_QUERY_LEN {
            return Err(DomainError::InvalidInput("search query too long".into()));
        }
        let list: DzList<DzPlaylistSimple> = self
            .get(
                "/search/playlist",
                &[
                    ("q", q.to_owned()),
                    ("index", page.offset.to_string()),
                    ("limit", page.limit.to_string()),
                ],
            )
            .await?;
        let has_more = list.next.is_some();
        let items = list
            .data
            .into_iter()
            .filter_map(|p| p.into_playlist().ok())
            .collect();
        Ok(Page {
            items,
            offset: page.offset,
            total: list.total,
            has_more,
        })
    }
}

fn to_page(list: DzList<DzTrack>, page: PageRequest) -> DomainResult<Page<Track>> {
    let has_more = list.next.is_some();
    let items = list
        .data
        .into_iter()
        .filter_map(|t| t.into_track().ok())
        .collect();
    Ok(Page {
        items,
        offset: page.offset,
        total: list.total,
        has_more,
    })
}

fn map_api_error(e: ApiError) -> DomainError {
    match e.code {
        QUOTA_EXCEEDED => DomainError::RateLimited {
            retry_after_ms: 5_000,
        },
        800 => DomainError::NotFound("Deezer item".into()),
        200 | 300 => DomainError::NotAuthenticated,
        _ => DomainError::Provider(format!("Deezer {} ({}): {}", e.kind, e.code, e.message)),
    }
}

/// Network errors can contain full URLs; strip query strings so nothing
/// sensitive (tokens, if ever added) reaches logs or UI.
fn redact(e: &reqwest::Error) -> String {
    let kind = if e.is_timeout() {
        "timeout"
    } else if e.is_connect() {
        "connection failed"
    } else {
        "request failed"
    };
    match e.url() {
        Some(u) => format!("{kind} ({})", u.host_str().unwrap_or("?")),
        None => kind.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    const SEARCH_JSON: &str = r#"{
      "data": [
        {"id": 3135556, "title": "Harder, Better, Faster, Stronger", "duration": 224,
         "readable": true,
         "preview": "https://cdnt-preview.dzcdn.net/api/1/1/a/b/c/0/abc.mp3?hdnea=exp",
         "artist": {"id": 27, "name": "Daft Punk"},
         "album": {"id": 302127, "title": "Discovery", "cover_medium": "https://e-cdns-images.dzcdn.net/images/cover/x/250x250.jpg"}},
        {"id": 1, "title": "No preview", "duration": 10, "readable": false,
         "preview": "https://x/y.mp3", "artist": {"name": "A"}}
      ],
      "total": 120,
      "next": "https://api.deezer.com/search/track?q=daft&index=2"
    }"#;

    #[test]
    fn rejects_insecure_base() {
        assert!(DeezerClient::with_base("http://api.deezer.com").is_err());
    }

    #[tokio::test]
    async fn search_maps_tracks() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search/track"))
            .and(query_param("q", "daft"))
            .respond_with(ResponseTemplate::new(200).set_body_string(SEARCH_JSON))
            .mount(&server)
            .await;
        let c = DeezerClient::with_base(&server.uri()).unwrap();
        let page = c
            .search_tracks("daft", PageRequest::first(2))
            .await
            .unwrap();
        assert_eq!(page.items.len(), 2);
        assert!(page.has_more);
        assert_eq!(page.total, Some(120));
        let t = &page.items[0];
        assert_eq!(t.id.to_string(), "deezer:3135556");
        assert_eq!(t.duration_ms, 224_000);
        assert!(t.is_preview);
        assert_eq!(t.origin, DataOrigin::PublicCatalog);
        assert!(!page.items[1].is_preview, "unreadable track has no preview");
        assert!(page.items[1].playback_uri.is_none());
    }

    #[tokio::test]
    async fn api_errors_are_mapped() {
        let server = MockServer::start().await;
        Mock::given(path("/track/9"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{"error":{"type":"QuotaException","message":"Quota limit exceeded","code":4}}"#,
            ))
            .mount(&server)
            .await;
        Mock::given(path("/track/8"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{"error":{"type":"DataException","message":"no data","code":800}}"#,
            ))
            .mount(&server)
            .await;
        Mock::given(path("/track/7"))
            .respond_with(ResponseTemplate::new(503))
            .mount(&server)
            .await;
        let c = DeezerClient::with_base(&server.uri()).unwrap();
        assert!(matches!(
            c.track(9).await,
            Err(DomainError::RateLimited { .. })
        ));
        assert!(matches!(c.track(8).await, Err(DomainError::NotFound(_))));
        assert!(matches!(c.track(7).await, Err(DomainError::Provider(_))));
    }

    #[tokio::test]
    async fn empty_and_long_queries() {
        let c = DeezerClient::with_base("http://127.0.0.1:9").unwrap();
        assert!(c
            .search_tracks("  ", PageRequest::default())
            .await
            .unwrap()
            .items
            .is_empty());
        let long = "x".repeat(MAX_QUERY_LEN + 1);
        assert!(c
            .search_tracks(&long, PageRequest::default())
            .await
            .is_err());
    }

    #[tokio::test]
    async fn user_and_album_lookups() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/user/5"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{"id":5,"name":"Daniel Marhely","picture_medium":"https://cdnt/u5.jpg","nb_playlists":12}"#,
            ))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/album/302127"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{"id":302127,"title":"Discovery","cover_medium":"https://cdnt/a.jpg","artist":{"name":"Daft Punk"}}"#,
            ))
            .mount(&server)
            .await;

        let c = DeezerClient::with_base(&server.uri()).unwrap();
        let u = c.get_user("5").await.unwrap();
        assert_eq!(u.id, 5);
        assert_eq!(u.name, "Daniel Marhely");
        assert_eq!(u.nb_playlists, Some(12));

        let a = c.get_album(302127).await.unwrap();
        assert_eq!(a.id, 302127);
        assert_eq!(a.title, "Discovery");
        assert_eq!(a.artist.unwrap().name, "Daft Punk");
    }
}
