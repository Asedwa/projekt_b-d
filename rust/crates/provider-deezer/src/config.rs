use std::fmt;

/// Deezer application registration. Supplied at runtime from local,
/// uncommitted configuration — never hardcoded.
#[derive(Clone, Default, PartialEq, Eq)]
pub struct DeezerAppConfig {
    pub app_id: Option<String>,
    pub redirect_uri: Option<String>,
    /// HTTPS endpoint of a backend that performs the secret-bearing token
    /// exchange on behalf of the app. Required because Deezer's OAuth has no
    /// PKCE / public-client flow.
    pub token_exchange_endpoint: Option<String>,
}

impl DeezerAppConfig {
    /// True when every piece required for user authorization is present.
    pub fn supports_user_auth(&self) -> bool {
        let nonempty = |v: &Option<String>| v.as_deref().is_some_and(|s| !s.trim().is_empty());
        nonempty(&self.app_id)
            && nonempty(&self.redirect_uri)
            && self
                .token_exchange_endpoint
                .as_deref()
                .is_some_and(|e| e.starts_with("https://"))
    }

    pub fn unavailable_reason(&self) -> &'static str {
        if self.app_id.is_none() {
            "Deezer is not accepting new developer apps and no app credentials are configured \
             in this build. Signing in to deezer.com does not authorize this app."
        } else if self.token_exchange_endpoint.is_none() {
            "Deezer sign-in requires a secure token-exchange server, which is not configured."
        } else {
            "Deezer sign-in is not fully configured."
        }
    }
}

// Avoid leaking configuration values into logs.
impl fmt::Debug for DeezerAppConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DeezerAppConfig")
            .field("app_id", &self.app_id.as_ref().map(|_| "<set>"))
            .field("redirect_uri", &self.redirect_uri)
            .field(
                "token_exchange_endpoint",
                &self.token_exchange_endpoint.as_ref().map(|_| "<set>"),
            )
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_cannot_authenticate() {
        assert!(!DeezerAppConfig::default().supports_user_auth());
    }

    #[test]
    fn http_exchange_endpoint_rejected() {
        let c = DeezerAppConfig {
            app_id: Some("1".into()),
            redirect_uri: Some("plasmawave://auth".into()),
            token_exchange_endpoint: Some("http://insecure".into()),
        };
        assert!(!c.supports_user_auth());
        let c = DeezerAppConfig {
            token_exchange_endpoint: Some("https://auth.example".into()),
            ..c
        };
        assert!(c.supports_user_auth());
        assert!(!format!("{c:?}").contains("auth.example"));
    }
}
