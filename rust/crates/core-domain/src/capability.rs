use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// A feature a provider may (or may not) be able to offer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Capability {
    Authentication,
    UserPlaylists,
    Favorites,
    CatalogSearch,
    PlaylistEditing,
    PreviewPlayback,
    FullPlayback,
    AuthorizedOfflineDownloads,
}

impl Capability {
    pub const ALL: [Capability; 8] = [
        Capability::Authentication,
        Capability::UserPlaylists,
        Capability::Favorites,
        Capability::CatalogSearch,
        Capability::PlaylistEditing,
        Capability::PreviewPlayback,
        Capability::FullPlayback,
        Capability::AuthorizedOfflineDownloads,
    ];
}

/// Whether a capability is usable right now, with a user-facing reason when it
/// is not.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum CapabilityStatus {
    Available,
    /// Provider supports it, but the user must sign in first.
    RequiresAuthentication,
    Unavailable {
        reason: String,
    },
}

impl CapabilityStatus {
    pub fn unavailable(reason: impl Into<String>) -> Self {
        Self::Unavailable {
            reason: reason.into(),
        }
    }

    pub fn is_available(&self) -> bool {
        matches!(self, Self::Available)
    }
}

/// Complete capability matrix of a provider. Capabilities not explicitly set
/// are treated as unavailable (secure default).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CapabilitySet {
    entries: BTreeMap<Capability, CapabilityStatus>,
}

impl CapabilitySet {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with(mut self, cap: Capability, status: CapabilityStatus) -> Self {
        self.entries.insert(cap, status);
        self
    }

    pub fn set(&mut self, cap: Capability, status: CapabilityStatus) {
        self.entries.insert(cap, status);
    }

    pub fn status(&self, cap: Capability) -> CapabilityStatus {
        self.entries
            .get(&cap)
            .cloned()
            .unwrap_or_else(|| CapabilityStatus::unavailable("Not supported by this provider"))
    }

    pub fn is_available(&self, cap: Capability) -> bool {
        self.status(cap).is_available()
    }

    /// All capabilities, in canonical order, with their status.
    pub fn all(&self) -> Vec<(Capability, CapabilityStatus)> {
        Capability::ALL
            .iter()
            .map(|c| (*c, self.status(*c)))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_capability_is_unavailable() {
        let set = CapabilitySet::new().with(Capability::CatalogSearch, CapabilityStatus::Available);
        assert!(set.is_available(Capability::CatalogSearch));
        assert!(!set.is_available(Capability::FullPlayback));
        assert!(matches!(
            set.status(Capability::AuthorizedOfflineDownloads),
            CapabilityStatus::Unavailable { .. }
        ));
        assert_eq!(set.all().len(), Capability::ALL.len());
    }
}
