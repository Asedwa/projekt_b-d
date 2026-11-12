use std::time::{Duration, SystemTime, UNIX_EPOCH};

use url::Url;

use crate::DeezerAppConfig;

const AUTHORIZE_URL: &str = "https://connect.deezer.com/oauth/auth.php";
/// Pending authorizations expire after this long.
pub const AUTH_REQUEST_TTL: Duration = Duration::from_secs(10 * 60);
/// Minimal permissions needed for the planned features.
pub const DEFAULT_PERMS: &str = "basic_access,manage_library";

/// URL to open in Custom Tabs / the external browser, plus the state that must
/// be kept to validate the callback.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthorizationRequest {
    pub url: String,
    pub pending: PendingAuthorization,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingAuthorization {
    pub state: String,
    pub created_at_secs: u64,
    pub redirect_uri: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CallbackError {
    NotConfigured,
    MalformedCallback,
    RedirectMismatch,
    StateMismatch,
    Expired,
    Denied(String),
    MissingCode,
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn random_state() -> Result<String, CallbackError> {
    let mut buf = [0u8; 32];
    getrandom::getrandom(&mut buf).map_err(|_| CallbackError::NotConfigured)?;
    Ok(hex::encode(buf))
}

/// Constant-time comparison to avoid leaking the state via timing.
fn ct_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

impl AuthorizationRequest {
    /// Builds an authorization request. Fails unless the configuration
    /// supports user authorization end-to-end.
    pub fn build(config: &DeezerAppConfig) -> Result<Self, CallbackError> {
        if !config.supports_user_auth() {
            return Err(CallbackError::NotConfigured);
        }
        let app_id = config.app_id.as_deref().unwrap_or_default();
        let redirect_uri = config.redirect_uri.clone().unwrap_or_default();
        let state = random_state()?;
        let mut url = Url::parse(AUTHORIZE_URL).map_err(|_| CallbackError::NotConfigured)?;
        url.query_pairs_mut()
            .append_pair("app_id", app_id)
            .append_pair("redirect_uri", &redirect_uri)
            .append_pair("perms", DEFAULT_PERMS)
            .append_pair("state", &state);
        Ok(Self {
            url: url.into(),
            pending: PendingAuthorization {
                state,
                created_at_secs: now_secs(),
                redirect_uri,
            },
        })
    }
}

impl PendingAuthorization {
    /// Validates the redirect callback and returns the authorization `code`.
    /// The code must then be exchanged by the configured backend.
    pub fn validate_callback(&self, callback: &str) -> Result<String, CallbackError> {
        self.validate_callback_at(callback, now_secs())
    }

    fn validate_callback_at(&self, callback: &str, now: u64) -> Result<String, CallbackError> {
        if now.saturating_sub(self.created_at_secs) > AUTH_REQUEST_TTL.as_secs() {
            return Err(CallbackError::Expired);
        }
        let url = Url::parse(callback).map_err(|_| CallbackError::MalformedCallback)?;
        let expected = Url::parse(&self.redirect_uri).map_err(|_| CallbackError::NotConfigured)?;
        if url.scheme() != expected.scheme()
            || url.host_str() != expected.host_str()
            || url.path() != expected.path()
        {
            return Err(CallbackError::RedirectMismatch);
        }
        let param = |k: &str| {
            url.query_pairs()
                .find(|(key, _)| key == k)
                .map(|(_, v)| v.into_owned())
        };
        let state = param("state").ok_or(CallbackError::StateMismatch)?;
        if !ct_eq(&state, &self.state) {
            return Err(CallbackError::StateMismatch);
        }
        if let Some(reason) = param("error_reason").or_else(|| param("error")) {
            return Err(CallbackError::Denied(reason));
        }
        param("code")
            .filter(|c| !c.is_empty())
            .ok_or(CallbackError::MissingCode)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> DeezerAppConfig {
        DeezerAppConfig {
            app_id: Some("123".into()),
            redirect_uri: Some("plasmawave://auth/deezer".into()),
            token_exchange_endpoint: Some("https://auth.example/exchange".into()),
        }
    }

    #[test]
    fn unconfigured_build_fails() {
        assert_eq!(
            AuthorizationRequest::build(&DeezerAppConfig::default()),
            Err(CallbackError::NotConfigured)
        );
    }

    #[test]
    fn builds_url_with_state() {
        let req = AuthorizationRequest::build(&cfg()).unwrap();
        assert!(req.url.starts_with(AUTHORIZE_URL));
        assert!(req.url.contains("app_id=123"));
        assert!(req.url.contains(&req.pending.state));
        assert_eq!(req.pending.state.len(), 64);
    }

    #[test]
    fn callback_validation() {
        let p = AuthorizationRequest::build(&cfg()).unwrap().pending;
        let ok = format!("plasmawave://auth/deezer?code=abc&state={}", p.state);
        assert_eq!(p.validate_callback(&ok).unwrap(), "abc");

        let bad_state = "plasmawave://auth/deezer?code=abc&state=nope";
        assert_eq!(
            p.validate_callback(bad_state),
            Err(CallbackError::StateMismatch)
        );

        let wrong_host = format!("plasmawave://evil/deezer?code=abc&state={}", p.state);
        assert_eq!(
            p.validate_callback(&wrong_host),
            Err(CallbackError::RedirectMismatch)
        );

        let denied = format!(
            "plasmawave://auth/deezer?error_reason=user_denied&state={}",
            p.state
        );
        assert_eq!(
            p.validate_callback(&denied),
            Err(CallbackError::Denied("user_denied".into()))
        );

        assert_eq!(
            p.validate_callback_at(&ok, p.created_at_secs + AUTH_REQUEST_TTL.as_secs() + 1),
            Err(CallbackError::Expired)
        );
    }
}
