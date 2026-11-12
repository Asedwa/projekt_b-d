//! Application settings.
//!
//! Settings are stored as string key/value pairs through the
//! [`KeyValueStore`] port. Unknown or invalid stored values fall back to
//! defaults instead of failing, so a corrupted value never blocks startup.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use core_domain::{DomainError, DomainResult};
use serde::{Deserialize, Serialize};

pub trait KeyValueStore: Send + Sync {
    fn get(&self, key: &str) -> DomainResult<Option<String>>;
    fn set(&self, key: &str, value: &str) -> DomainResult<()>;
    fn remove(&self, key: &str) -> DomainResult<()>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Language {
    #[default]
    System,
    English,
    Turkish,
}

impl Language {
    pub fn as_key(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::English => "en",
            Self::Turkish => "tr",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        match key {
            "system" => Some(Self::System),
            "en" => Some(Self::English),
            "tr" => Some(Self::Turkish),
            _ => None,
        }
    }
}

pub const DEFAULT_THEME_ID: &str = "org.kde.breeze-dark";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Settings {
    pub theme_id: String,
    pub language: Language,
    /// Show the clearly-labeled development fixture provider.
    pub show_dev_fixtures: bool,
    pub downloads_wifi_only: bool,
    /// Upper bound for the download directory, in MiB (0 = unlimited).
    pub download_storage_limit_mib: u32,
    pub mods_enabled: bool,
    /// Optional Deezer User ID (numeric string) for syncing playlists and favorites.
    pub deezer_user_id: Option<String>,
    /// Optional Deezer Access Token for authenticated access (/user/me).
    pub deezer_access_token: Option<String>,
    /// Optional custom accent hex color (`#RRGGBB`) overriding the active theme's accent.
    pub custom_accent_color: Option<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme_id: DEFAULT_THEME_ID.into(),
            language: Language::System,
            show_dev_fixtures: true,
            downloads_wifi_only: true,
            download_storage_limit_mib: 0,
            mods_enabled: true,
            deezer_user_id: None,
            deezer_access_token: None,
            custom_accent_color: None,
        }
    }
}

mod keys {
    pub const THEME: &str = "ui.theme_id";
    pub const LANGUAGE: &str = "ui.language";
    pub const DEV_FIXTURES: &str = "providers.show_dev_fixtures";
    pub const WIFI_ONLY: &str = "downloads.wifi_only";
    pub const STORAGE_LIMIT: &str = "downloads.storage_limit_mib";
    pub const MODS_ENABLED: &str = "mods.enabled";
    pub const DEEZER_USER_ID: &str = "deezer.user_id";
    pub const DEEZER_ACCESS_TOKEN: &str = "deezer.access_token";
    pub const CUSTOM_ACCENT_COLOR: &str = "ui.custom_accent_color";
}

#[derive(Clone)]
pub struct SettingsService {
    store: Arc<dyn KeyValueStore>,
}

fn parse_bool(v: Option<String>, default: bool) -> bool {
    match v.as_deref() {
        Some("true") => true,
        Some("false") => false,
        _ => default,
    }
}

impl SettingsService {
    pub fn new(store: Arc<dyn KeyValueStore>) -> Self {
        Self { store }
    }

    pub fn load(&self) -> DomainResult<Settings> {
        let d = Settings::default();
        let theme_id = self
            .store
            .get(keys::THEME)?
            .filter(|t| is_valid_theme_id(t))
            .unwrap_or(d.theme_id);
        let language = self
            .store
            .get(keys::LANGUAGE)?
            .and_then(|l| Language::from_key(&l))
            .unwrap_or(d.language);
        Ok(Settings {
            theme_id,
            language,
            show_dev_fixtures: parse_bool(self.store.get(keys::DEV_FIXTURES)?, d.show_dev_fixtures),
            downloads_wifi_only: parse_bool(
                self.store.get(keys::WIFI_ONLY)?,
                d.downloads_wifi_only,
            ),
            download_storage_limit_mib: self
                .store
                .get(keys::STORAGE_LIMIT)?
                .and_then(|v| v.parse().ok())
                .unwrap_or(d.download_storage_limit_mib),
            mods_enabled: parse_bool(self.store.get(keys::MODS_ENABLED)?, d.mods_enabled),
            deezer_user_id: self
                .store
                .get(keys::DEEZER_USER_ID)?
                .filter(|s| !s.trim().is_empty()),
            deezer_access_token: self
                .store
                .get(keys::DEEZER_ACCESS_TOKEN)?
                .filter(|s| !s.trim().is_empty()),
            custom_accent_color: self
                .store
                .get(keys::CUSTOM_ACCENT_COLOR)?
                .filter(|c| is_valid_hex_color(c)),
        })
    }

    pub fn save(&self, s: &Settings) -> DomainResult<()> {
        if !is_valid_theme_id(&s.theme_id) {
            return Err(DomainError::InvalidInput("invalid theme id".into()));
        }
        if let Some(color) = &s.custom_accent_color {
            if !is_valid_hex_color(color) {
                return Err(DomainError::InvalidInput(
                    "accent color must be #RRGGBB".into(),
                ));
            }
        }
        self.store.set(keys::THEME, &s.theme_id)?;
        self.store.set(keys::LANGUAGE, s.language.as_key())?;
        self.store
            .set(keys::DEV_FIXTURES, &s.show_dev_fixtures.to_string())?;
        self.store
            .set(keys::WIFI_ONLY, &s.downloads_wifi_only.to_string())?;
        self.store.set(
            keys::STORAGE_LIMIT,
            &s.download_storage_limit_mib.to_string(),
        )?;
        self.store
            .set(keys::MODS_ENABLED, &s.mods_enabled.to_string())?;
        if let Some(uid) = &s.deezer_user_id {
            self.store.set(keys::DEEZER_USER_ID, uid)?;
        } else {
            self.store.remove(keys::DEEZER_USER_ID)?;
        }
        if let Some(token) = &s.deezer_access_token {
            self.store.set(keys::DEEZER_ACCESS_TOKEN, token)?;
        } else {
            self.store.remove(keys::DEEZER_ACCESS_TOKEN)?;
        }
        if let Some(color) = &s.custom_accent_color {
            self.store.set(keys::CUSTOM_ACCENT_COLOR, color)?;
        } else {
            self.store.remove(keys::CUSTOM_ACCENT_COLOR)?;
        }
        Ok(())
    }

    pub fn update(&self, f: impl FnOnce(&mut Settings)) -> DomainResult<Settings> {
        let mut s = self.load()?;
        f(&mut s);
        self.save(&s)?;
        Ok(s)
    }
}

/// Validates `#RRGGBB` hex color strings.
pub fn is_valid_hex_color(color: &str) -> bool {
    let Some(hex) = color.strip_prefix('#') else {
        return false;
    };
    hex.len() == 6 && hex.chars().all(|c| c.is_ascii_hexdigit())
}

/// Reverse-DNS style id: lowercase letters, digits, `.`, `-`, `_`.
pub fn is_valid_theme_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id.contains('.')
        && id
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '.' | '-' | '_'))
}

/// In-memory store for tests and for running without a database.
#[derive(Debug, Default)]
pub struct MemoryStore(Mutex<BTreeMap<String, String>>);

impl KeyValueStore for MemoryStore {
    fn get(&self, key: &str) -> DomainResult<Option<String>> {
        Ok(self.0.lock().map_err(poisoned)?.get(key).cloned())
    }
    fn set(&self, key: &str, value: &str) -> DomainResult<()> {
        self.0
            .lock()
            .map_err(poisoned)?
            .insert(key.into(), value.into());
        Ok(())
    }
    fn remove(&self, key: &str) -> DomainResult<()> {
        self.0.lock().map_err(poisoned)?.remove(key);
        Ok(())
    }
}

fn poisoned<T>(_: T) -> DomainError {
    DomainError::Internal("settings lock poisoned".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_when_empty() {
        let svc = SettingsService::new(Arc::new(MemoryStore::default()));
        assert_eq!(svc.load().unwrap(), Settings::default());
    }

    #[test]
    fn roundtrip_and_update() {
        let svc = SettingsService::new(Arc::new(MemoryStore::default()));
        let s = svc
            .update(|s| {
                s.language = Language::Turkish;
                s.theme_id = "org.example.mine".into();
                s.downloads_wifi_only = false;
                s.deezer_user_id = Some("5".into());
                s.deezer_access_token = Some("test_token_123".into());
            })
            .unwrap();
        assert_eq!(svc.load().unwrap(), s);
    }

    #[test]
    fn corrupt_values_fall_back() {
        let store = Arc::new(MemoryStore::default());
        store.set(keys::LANGUAGE, "klingon").unwrap();
        store.set(keys::THEME, "../../etc/passwd").unwrap();
        store.set(keys::WIFI_ONLY, "maybe").unwrap();
        let s = SettingsService::new(store).load().unwrap();
        assert_eq!(s.language, Language::System);
        assert_eq!(s.theme_id, DEFAULT_THEME_ID);
        assert!(s.downloads_wifi_only);
    }

    #[test]
    fn invalid_theme_rejected_on_save() {
        let svc = SettingsService::new(Arc::new(MemoryStore::default()));
        assert!(svc.update(|s| s.theme_id = "Bad Id".into()).is_err());
    }
}
