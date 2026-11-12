use core_domain::{DomainError, DomainResult};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

pub const CURRENT_MOD_API_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ModPermission {
    ReadMetadata,
    CustomTrackMenu,
    ShowNotification,
}

impl ModPermission {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::ReadMetadata => "read_metadata",
            Self::CustomTrackMenu => "custom_track_menu",
            Self::ShowNotification => "show_notification",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub author: String,
    pub description: String,
    pub api_version: u32,
    #[serde(default)]
    pub entry_point: String,
    #[serde(default)]
    pub permissions: BTreeSet<ModPermission>,
}

impl ModManifest {
    pub fn validate(&self) -> DomainResult<()> {
        if self.id.trim().is_empty() || self.id.len() > 128 || !self.id.contains('.') {
            return Err(DomainError::InvalidInput(format!(
                "invalid mod id '{}'; must be reverse-DNS style (e.g. org.example.enhancer)",
                self.id
            )));
        }
        if self.name.trim().is_empty() || self.name.len() > 64 {
            return Err(DomainError::InvalidInput("invalid mod name".into()));
        }
        if self.api_version != CURRENT_MOD_API_VERSION {
            return Err(DomainError::InvalidInput(format!(
                "incompatible mod API version {}; current host version is {}",
                self.api_version, CURRENT_MOD_API_VERSION
            )));
        }
        if self.entry_point.trim().is_empty() {
            return Err(DomainError::InvalidInput(
                "mod entry_point must not be empty".into(),
            ));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_manifest_validates() {
        let mut perms = BTreeSet::new();
        perms.insert(ModPermission::CustomTrackMenu);
        let m = ModManifest {
            id: "org.plasmawave.sample-mod".into(),
            name: "Sample Mod".into(),
            version: "1.0.0".into(),
            author: "PlasmaWave".into(),
            description: "Test mod".into(),
            api_version: 1,
            entry_point: "on_track_action".into(),
            permissions: perms,
        };
        assert!(m.validate().is_ok());
    }

    #[test]
    fn invalid_api_version_fails() {
        let m = ModManifest {
            id: "org.plasmawave.sample-mod".into(),
            name: "Sample Mod".into(),
            version: "1.0.0".into(),
            author: "PlasmaWave".into(),
            description: "Test mod".into(),
            api_version: 999,
            entry_point: "on_track_action".into(),
            permissions: Default::default(),
        };
        assert!(m.validate().is_err());
    }
}
