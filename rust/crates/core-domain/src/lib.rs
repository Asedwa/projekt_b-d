//! Shared domain models for PlasmaWave.
//!
//! This crate has no I/O and no platform dependencies. Every other crate
//! depends on it; it depends on nothing internal.

mod capability;
mod error;
mod ids;
mod models;

pub use capability::{Capability, CapabilitySet, CapabilityStatus};
pub use error::{DomainError, DomainResult};
pub use ids::{PlaylistId, ProviderId, TrackId};
pub use models::{
    Album, Artist, DataOrigin, Page, PageRequest, Playlist, PlaylistKind, Track, MAX_PAGE_SIZE,
};

/// Business rule: maximum length of a user-entered playlist name.
pub const MAX_PLAYLIST_NAME_LEN: usize = 120;

/// Validates and normalises a playlist name entered by the user.
pub fn validate_playlist_name(raw: &str) -> DomainResult<String> {
    let name = raw.trim();
    if name.is_empty() {
        return Err(DomainError::InvalidInput(
            "playlist name must not be empty".into(),
        ));
    }
    if name.chars().count() > MAX_PLAYLIST_NAME_LEN {
        return Err(DomainError::InvalidInput(format!(
            "playlist name must be at most {MAX_PLAYLIST_NAME_LEN} characters"
        )));
    }
    if name.chars().any(|c| c.is_control()) {
        return Err(DomainError::InvalidInput(
            "playlist name contains control characters".into(),
        ));
    }
    Ok(name.to_owned())
}

/// Formats a duration in milliseconds as `m:ss` or `h:mm:ss`.
pub fn format_duration_ms(ms: u64) -> String {
    let total = ms / 1000;
    let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playlist_name_is_trimmed() {
        assert_eq!(validate_playlist_name("  Chill  ").unwrap(), "Chill");
    }

    #[test]
    fn empty_playlist_name_rejected() {
        assert!(validate_playlist_name("   ").is_err());
    }

    #[test]
    fn overlong_playlist_name_rejected() {
        let long = "a".repeat(MAX_PLAYLIST_NAME_LEN + 1);
        assert!(validate_playlist_name(&long).is_err());
    }

    #[test]
    fn control_chars_rejected() {
        assert!(validate_playlist_name("bad\u{0007}name").is_err());
    }

    #[test]
    fn durations_format() {
        assert_eq!(format_duration_ms(0), "0:00");
        assert_eq!(format_duration_ms(61_000), "1:01");
        assert_eq!(format_duration_ms(3_725_000), "1:02:05");
    }
}
