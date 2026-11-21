//! Application settings.
//!
//! Settings are stored as string key/value pairs through the
//! [`KeyValueStore`] port. Unknown or invalid stored values fall back to
//! defaults instead of failing, so a corrupted value never blocks startup.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, RwLock};

use core_domain::{DomainError, DomainResult};
use serde::{Deserialize, Serialize};

pub mod home_layout;
pub use home_layout::{
    canonical_home_widget_kind, default_home_layout_from_settings, home_widget_catalog,
    HomeWidgetCatalogEntry, HomeWidgetItem, HomeWidgetPatch, HomeWidgetSize, HomeWidgetStyle,
};

pub trait KeyValueStore: Send + Sync {
    fn get(&self, key: &str) -> DomainResult<Option<String>>;
    fn set(&self, key: &str, value: &str) -> DomainResult<()>;
    fn remove(&self, key: &str) -> DomainResult<()>;

    fn get_all(&self) -> DomainResult<BTreeMap<String, String>> {
        Ok(BTreeMap::new())
    }

    fn set_batch(&self, entries: &[(&str, Option<&str>)]) -> DomainResult<()> {
        for &(key, value) in entries {
            match value {
                Some(v) => self.set(key, v)?,
                None => self.remove(key)?,
            }
        }
        Ok(())
    }
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

pub fn default_home_widgets() -> Vec<String> {
    vec![
        "widget_stats".into(),
        "widget_quick_artist".into(),
        "widget_quick_theme".into(),
        "widget_lucky_pick".into(),
    ]
}

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
    pub high_refresh_rate: bool,
    pub dynamic_color_enabled: bool,
    pub swipe_to_skip: bool,
    pub seekbar_style: String,
    pub album_frame: String,
    pub header_logo_style: String,
    pub home_feed_type: String,
    pub flow_hero_card_enabled: bool,
    pub flow_quick_access_pinned: bool,
    pub flow_library_pinned: bool,
    pub downloads_quick_access_pinned: bool,
    pub downloads_library_pinned: bool,
    pub resume_playback_enabled: bool,
    pub collect_listening_stats: bool,
    pub collect_listening_history: bool,
    pub save_resume_position: bool,
    pub enabled_home_widgets: Vec<String>,
    pub home_edit_hold_seconds: u8,
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
            high_refresh_rate: true,
            dynamic_color_enabled: true,
            swipe_to_skip: true,
            seekbar_style: "CLASSIC".into(),
            album_frame: "MODERN_ROUNDED".into(),
            header_logo_style: "ORIGINAL".into(),
            home_feed_type: "FOR_YOU".into(),
            flow_hero_card_enabled: true,
            flow_quick_access_pinned: true,
            flow_library_pinned: true,
            downloads_quick_access_pinned: true,
            downloads_library_pinned: true,
            resume_playback_enabled: true,
            collect_listening_stats: true,
            collect_listening_history: true,
            save_resume_position: true,
            enabled_home_widgets: default_home_widgets(),
            home_edit_hold_seconds: 3,
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
    pub const HIGH_REFRESH_RATE: &str = "ui.high_refresh_rate";
    pub const DYNAMIC_COLOR_ENABLED: &str = "ui.dynamic_color_enabled";
    pub const SWIPE_TO_SKIP: &str = "ui.swipe_to_skip";
    pub const SEEKBAR_STYLE: &str = "ui.seekbar_style";
    pub const ALBUM_FRAME: &str = "ui.album_frame";
    pub const HEADER_LOGO_STYLE: &str = "ui.header_logo_style";
    pub const HOME_FEED_TYPE: &str = "ui.home_feed_type";
    pub const FLOW_HERO_CARD: &str = "ui.flow_hero_card_enabled";
    pub const FLOW_QUICK_ACCESS: &str = "ui.flow_quick_access_pinned";
    pub const FLOW_LIBRARY: &str = "ui.flow_library_pinned";
    pub const DOWNLOADS_QUICK_ACCESS: &str = "ui.downloads_quick_access_pinned";
    pub const DOWNLOADS_LIBRARY: &str = "ui.downloads_library_pinned";
    pub const RESUME_PLAYBACK: &str = "ui.resume_playback_enabled";
    pub const COLLECT_STATS: &str = "privacy.collect_listening_stats";
    pub const COLLECT_HISTORY: &str = "privacy.collect_listening_history";
    pub const SAVE_RESUME_POS: &str = "privacy.save_resume_position";
    pub const ENABLED_HOME_WIDGETS: &str = "ui.enabled_home_widgets";
    pub const HOME_EDIT_HOLD_SECONDS: &str = "ui.home_edit_hold_seconds";
}

#[derive(Clone)]
pub struct SettingsService {
    store: Arc<dyn KeyValueStore>,
    cache: Arc<RwLock<Option<Settings>>>,
    write_lock: Arc<Mutex<()>>,
}

fn parse_bool(v: Option<&str>, default: bool) -> bool {
    match v {
        Some("true") => true,
        Some("false") => false,
        _ => default,
    }
}

impl SettingsService {
    pub fn new(store: Arc<dyn KeyValueStore>) -> Self {
        Self {
            store,
            cache: Arc::new(RwLock::new(None)),
            write_lock: Arc::new(Mutex::new(())),
        }
    }

    fn parse_from_map(map: &BTreeMap<String, String>) -> Settings {
        let get = |k: &str| map.get(k).map(String::as_str);
        let d = Settings::default();
        let theme_id = get(keys::THEME)
            .filter(|t| is_valid_theme_id(t))
            .map(str::to_owned)
            .unwrap_or(d.theme_id);
        let language = get(keys::LANGUAGE)
            .and_then(Language::from_key)
            .unwrap_or(d.language);
        let enabled_home_widgets = match get(keys::ENABLED_HOME_WIDGETS) {
            Some(raw) => serde_json::from_str::<Vec<String>>(raw).unwrap_or(d.enabled_home_widgets),
            None => d.enabled_home_widgets,
        };
        Settings {
            theme_id,
            language,
            show_dev_fixtures: parse_bool(get(keys::DEV_FIXTURES), d.show_dev_fixtures),
            downloads_wifi_only: parse_bool(get(keys::WIFI_ONLY), d.downloads_wifi_only),
            download_storage_limit_mib: get(keys::STORAGE_LIMIT)
                .and_then(|v| v.parse().ok())
                .unwrap_or(d.download_storage_limit_mib),
            mods_enabled: parse_bool(get(keys::MODS_ENABLED), d.mods_enabled),
            deezer_user_id: get(keys::DEEZER_USER_ID)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned),
            deezer_access_token: get(keys::DEEZER_ACCESS_TOKEN)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned),
            custom_accent_color: get(keys::CUSTOM_ACCENT_COLOR)
                .filter(|c| is_valid_hex_color(c))
                .map(str::to_owned),
            high_refresh_rate: parse_bool(get(keys::HIGH_REFRESH_RATE), d.high_refresh_rate),
            dynamic_color_enabled: parse_bool(
                get(keys::DYNAMIC_COLOR_ENABLED),
                d.dynamic_color_enabled,
            ),
            swipe_to_skip: parse_bool(get(keys::SWIPE_TO_SKIP), d.swipe_to_skip),
            seekbar_style: get(keys::SEEKBAR_STYLE)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned)
                .unwrap_or(d.seekbar_style),
            album_frame: get(keys::ALBUM_FRAME)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned)
                .unwrap_or(d.album_frame),
            header_logo_style: get(keys::HEADER_LOGO_STYLE)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned)
                .unwrap_or(d.header_logo_style),
            home_feed_type: get(keys::HOME_FEED_TYPE)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned)
                .unwrap_or(d.home_feed_type),
            flow_hero_card_enabled: parse_bool(
                get(keys::FLOW_HERO_CARD),
                d.flow_hero_card_enabled,
            ),
            flow_quick_access_pinned: parse_bool(
                get(keys::FLOW_QUICK_ACCESS),
                d.flow_quick_access_pinned,
            ),
            flow_library_pinned: parse_bool(get(keys::FLOW_LIBRARY), d.flow_library_pinned),
            downloads_quick_access_pinned: parse_bool(
                get(keys::DOWNLOADS_QUICK_ACCESS),
                d.downloads_quick_access_pinned,
            ),
            downloads_library_pinned: parse_bool(
                get(keys::DOWNLOADS_LIBRARY),
                d.downloads_library_pinned,
            ),
            resume_playback_enabled: parse_bool(
                get(keys::RESUME_PLAYBACK),
                d.resume_playback_enabled,
            ),
            collect_listening_stats: parse_bool(
                get(keys::COLLECT_STATS),
                d.collect_listening_stats,
            ),
            collect_listening_history: parse_bool(
                get(keys::COLLECT_HISTORY),
                d.collect_listening_history,
            ),
            save_resume_position: parse_bool(get(keys::SAVE_RESUME_POS), d.save_resume_position),
            enabled_home_widgets,
            home_edit_hold_seconds: get(keys::HOME_EDIT_HOLD_SECONDS)
                .and_then(|v| v.parse::<u8>().ok())
                .map(|s| s.clamp(3, 10))
                .unwrap_or(d.home_edit_hold_seconds),
        }
    }

    pub fn load(&self) -> DomainResult<Settings> {
        if let Ok(guard) = self.cache.read() {
            if let Some(cached) = &*guard {
                return Ok(cached.clone());
            }
        }
        let map = self.store.get_all()?;
        let settings = Self::parse_from_map(&map);
        if let Ok(mut guard) = self.cache.write() {
            *guard = Some(settings.clone());
        }
        Ok(settings)
    }

    fn save_locked(&self, s: &Settings) -> DomainResult<()> {
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
        let dev_fixtures = s.show_dev_fixtures.to_string();
        let wifi_only = s.downloads_wifi_only.to_string();
        let storage_limit = s.download_storage_limit_mib.to_string();
        let mods_enabled = s.mods_enabled.to_string();
        let high_refresh_rate = s.high_refresh_rate.to_string();
        let dynamic_color = s.dynamic_color_enabled.to_string();
        let swipe_to_skip = s.swipe_to_skip.to_string();
        let flow_hero = s.flow_hero_card_enabled.to_string();
        let flow_qa = s.flow_quick_access_pinned.to_string();
        let flow_lib = s.flow_library_pinned.to_string();
        let dl_qa = s.downloads_quick_access_pinned.to_string();
        let dl_lib = s.downloads_library_pinned.to_string();
        let resume_pb = s.resume_playback_enabled.to_string();
        let collect_stats = s.collect_listening_stats.to_string();
        let collect_history = s.collect_listening_history.to_string();
        let save_resume = s.save_resume_position.to_string();
        let widgets_json =
            serde_json::to_string(&s.enabled_home_widgets).unwrap_or_else(|_| "[]".into());
        let hold_secs = s.home_edit_hold_seconds.clamp(3, 10).to_string();

        let entries: [(&str, Option<&str>); 27] = [
            (keys::THEME, Some(&s.theme_id)),
            (keys::LANGUAGE, Some(s.language.as_key())),
            (keys::DEV_FIXTURES, Some(&dev_fixtures)),
            (keys::WIFI_ONLY, Some(&wifi_only)),
            (keys::STORAGE_LIMIT, Some(&storage_limit)),
            (keys::MODS_ENABLED, Some(&mods_enabled)),
            (keys::DEEZER_USER_ID, s.deezer_user_id.as_deref()),
            (keys::DEEZER_ACCESS_TOKEN, s.deezer_access_token.as_deref()),
            (keys::CUSTOM_ACCENT_COLOR, s.custom_accent_color.as_deref()),
            (keys::HIGH_REFRESH_RATE, Some(&high_refresh_rate)),
            (keys::DYNAMIC_COLOR_ENABLED, Some(&dynamic_color)),
            (keys::SWIPE_TO_SKIP, Some(&swipe_to_skip)),
            (keys::SEEKBAR_STYLE, Some(&s.seekbar_style)),
            (keys::ALBUM_FRAME, Some(&s.album_frame)),
            (keys::HEADER_LOGO_STYLE, Some(&s.header_logo_style)),
            (keys::HOME_FEED_TYPE, Some(&s.home_feed_type)),
            (keys::FLOW_HERO_CARD, Some(&flow_hero)),
            (keys::FLOW_QUICK_ACCESS, Some(&flow_qa)),
            (keys::FLOW_LIBRARY, Some(&flow_lib)),
            (keys::DOWNLOADS_QUICK_ACCESS, Some(&dl_qa)),
            (keys::DOWNLOADS_LIBRARY, Some(&dl_lib)),
            (keys::RESUME_PLAYBACK, Some(&resume_pb)),
            (keys::COLLECT_STATS, Some(&collect_stats)),
            (keys::COLLECT_HISTORY, Some(&collect_history)),
            (keys::SAVE_RESUME_POS, Some(&save_resume)),
            (keys::ENABLED_HOME_WIDGETS, Some(&widgets_json)),
            (keys::HOME_EDIT_HOLD_SECONDS, Some(&hold_secs)),
        ];

        self.store.set_batch(&entries)?;
        if let Ok(mut guard) = self.cache.write() {
            *guard = Some(s.clone());
        }
        Ok(())
    }

    pub fn save(&self, s: &Settings) -> DomainResult<()> {
        let _guard = self.write_lock.lock().map_err(poisoned)?;
        self.save_locked(s)
    }

    pub fn update(&self, f: impl FnOnce(&mut Settings)) -> DomainResult<Settings> {
        let _guard = self.write_lock.lock().map_err(poisoned)?;
        let mut s = self.load()?;
        f(&mut s);
        self.save_locked(&s)?;
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
    fn get_all(&self) -> DomainResult<BTreeMap<String, String>> {
        Ok(self.0.lock().map_err(poisoned)?.clone())
    }
    fn set_batch(&self, entries: &[(&str, Option<&str>)]) -> DomainResult<()> {
        let mut guard = self.0.lock().map_err(poisoned)?;
        for &(key, value) in entries {
            match value {
                Some(v) => {
                    guard.insert(key.into(), v.into());
                }
                None => {
                    guard.remove(key);
                }
            }
        }
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
