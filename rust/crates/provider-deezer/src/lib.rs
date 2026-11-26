//! Deezer provider.
//!
//! ## Verified status (2026-11)
//!
//! * Deezer is **not accepting new developer application registrations**.
//!   Without an App ID + secret, user authorization (OAuth) is impossible.
//! * Public catalog endpoints (`/search`, `/track/{id}`, `/album/{id}`,
//!   `/artist/{id}`, `/playlist/{id}`, `/chart`) work without authentication
//!   and include 30-second preview URLs.
//! * Deezer's OAuth flow (`connect.deezer.com/oauth/auth.php` →
//!   `access_token.php`) requires the **app secret** for the token exchange and
//!   does not support PKCE. The secret must never ship in an APK, so token
//!   exchange requires a separately operated backend. This build has none.
//! * Full-track playback and offline downloads are not offered to third-party
//!   apps. These capabilities are permanently reported as unavailable.
//!
//! Nothing in this crate uses undocumented endpoints, cookies (ARL) or
//! session-token interception.

pub mod api;
pub mod artwork;
pub mod crypto;
pub mod gateway;
mod config;
mod oauth;
mod provider;
mod rate_limit;

pub use api::{
    DeezerClient, DzAlbumDetail, DzAlbumSimple, DzArtistDetail, DzPlaylistSimple, DzUser,
    DEFAULT_API_BASE,
};
pub use config::DeezerAppConfig;
pub use oauth::{AuthorizationRequest, CallbackError, PendingAuthorization};
pub use provider::DeezerProvider;
