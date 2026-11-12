use std::fmt;

use serde::{Deserialize, Serialize};

use crate::{DomainError, DomainResult};

/// Identifies a media provider, e.g. `local`, `deezer`, `dev`.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ProviderId(String);

impl ProviderId {
    pub const LOCAL: &'static str = "local";
    pub const DEEZER: &'static str = "deezer";
    pub const DEV: &'static str = "dev";

    pub fn new(raw: impl Into<String>) -> DomainResult<Self> {
        let raw = raw.into();
        let valid = !raw.is_empty()
            && raw.len() <= 32
            && raw
                .chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
        if valid {
            Ok(Self(raw))
        } else {
            Err(DomainError::InvalidInput(format!(
                "invalid provider id `{raw}`"
            )))
        }
    }

    pub fn local() -> Self {
        Self(Self::LOCAL.into())
    }
    pub fn deezer() -> Self {
        Self(Self::DEEZER.into())
    }
    pub fn dev() -> Self {
        Self(Self::DEV.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for ProviderId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// Globally unique track identifier: `<provider>:<provider-local-key>`.
///
/// Example: `deezer:3135556`, `local:content://media/external/audio/media/42`.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct TrackId {
    provider: ProviderId,
    key: String,
}

impl TrackId {
    pub fn new(provider: ProviderId, key: impl Into<String>) -> DomainResult<Self> {
        let key = key.into();
        if key.is_empty() || key.len() > 2048 || key.chars().any(char::is_control) {
            return Err(DomainError::InvalidInput("invalid track key".into()));
        }
        Ok(Self { provider, key })
    }

    pub fn parse(raw: &str) -> DomainResult<Self> {
        let (provider, key) = raw
            .split_once(':')
            .ok_or_else(|| DomainError::InvalidInput(format!("malformed track id `{raw}`")))?;
        Self::new(ProviderId::new(provider)?, key)
    }

    pub fn provider(&self) -> &ProviderId {
        &self.provider
    }

    pub fn key(&self) -> &str {
        &self.key
    }
}

impl fmt::Display for TrackId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.provider, self.key)
    }
}

impl TryFrom<String> for TrackId {
    type Error = DomainError;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        Self::parse(&value)
    }
}

impl From<TrackId> for String {
    fn from(value: TrackId) -> Self {
        value.to_string()
    }
}

/// Identifier of a playlist. Local playlists use `local:<n>`; provider
/// playlists use `<provider>:<remote id>`.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct PlaylistId(String);

impl PlaylistId {
    pub fn new(raw: impl Into<String>) -> DomainResult<Self> {
        let raw = raw.into();
        if raw.is_empty() || raw.len() > 256 || raw.chars().any(char::is_control) {
            return Err(DomainError::InvalidInput("invalid playlist id".into()));
        }
        Ok(Self(raw))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for PlaylistId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn track_id_roundtrip() {
        let id = TrackId::parse("local:content://media/external/audio/media/42").unwrap();
        assert_eq!(id.provider().as_str(), "local");
        assert_eq!(id.key(), "content://media/external/audio/media/42");
        assert_eq!(
            TrackId::parse(&id.to_string()).unwrap(),
            id,
            "display/parse must roundtrip"
        );
    }

    #[test]
    fn track_id_serde_is_string() {
        let id = TrackId::parse("deezer:3135556").unwrap();
        let json = serde_json::to_string(&id).unwrap();
        assert_eq!(json, "\"deezer:3135556\"");
        let back: TrackId = serde_json::from_str(&json).unwrap();
        assert_eq!(back, id);
    }

    #[test]
    fn invalid_provider_rejected() {
        assert!(ProviderId::new("Deezer").is_err());
        assert!(ProviderId::new("").is_err());
        assert!(TrackId::parse("nocolon").is_err());
        assert!(TrackId::parse("dev:").is_err());
    }
}
