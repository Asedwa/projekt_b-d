//! Rust-owned home screen layout, widget catalog, validation, and persistence.

use std::collections::BTreeSet;

use core_domain::{DomainError, DomainResult};
use serde::{Deserialize, Serialize};

use super::{is_valid_hex_color, keys, Settings, SettingsService};

pub const HOME_LAYOUT_KEY: &str = "ui.home_layout_v1";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum HomeWidgetSize {
    Compact,
    #[default]
    Normal,
    Expanded,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum HomeWidgetStyle {
    #[default]
    Card,
    Tinted,
    Minimal,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct HomeWidgetItem {
    pub instance_id: String,
    pub kind: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    pub order: u32,
    #[serde(default)]
    pub size: HomeWidgetSize,
    #[serde(default)]
    pub style: HomeWidgetStyle,
    #[serde(default = "default_max_items")]
    pub max_items: u8,
    #[serde(default = "default_true")]
    pub show_subtitle: bool,
    #[serde(default)]
    pub custom_title: Option<String>,
    #[serde(default)]
    pub accent_hex: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HomeWidgetCatalogEntry {
    pub kind: String,
    pub category: String,
    pub title: String,
    pub description: String,
    pub default_size: HomeWidgetSize,
    pub default_style: HomeWidgetStyle,
    pub default_max_items: u8,
    pub min_items: u8,
    pub max_items_limit: u8,
    pub supports_item_count: bool,
    pub supports_accent: bool,
    pub allow_multiple: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HomeWidgetPatch {
    pub enabled: Option<bool>,
    pub size: Option<HomeWidgetSize>,
    pub style: Option<HomeWidgetStyle>,
    pub max_items: Option<u8>,
    pub show_subtitle: Option<bool>,
    pub clear_custom_title: bool,
    pub custom_title: Option<String>,
    pub clear_accent_hex: bool,
    pub accent_hex: Option<String>,
}

fn default_true() -> bool {
    true
}

fn default_max_items() -> u8 {
    6
}

/// Maps both legacy widget identifiers (e.g. `"stats"`) and canonical kinds (`"widget_stats"`)
/// to their canonical kind string.
pub fn canonical_home_widget_kind(raw: &str) -> Option<&'static str> {
    match raw.trim() {
        "block_flow_hero" | "flow_hero" => Some("block_flow_hero"),
        "block_resume_playback" | "resume_playback" => Some("block_resume_playback"),
        "block_quick_access" | "quick_access" => Some("block_quick_access"),
        "block_feed" | "feed" => Some("block_feed"),
        "widget_stats" | "stats" => Some("widget_stats"),
        "widget_quick_artist" | "quick_artist" => Some("widget_quick_artist"),
        "widget_quick_theme" | "quick_theme" => Some("widget_quick_theme"),
        "widget_lucky_pick" | "lucky_pick" => Some("widget_lucky_pick"),
        "widget_sleep_timer" | "sleep_timer" => Some("widget_sleep_timer"),
        "widget_mood_mix" | "mood_mix" => Some("widget_mood_mix"),
        "widget_recent_tracks" | "recent_tracks" => Some("widget_recent_tracks"),
        "widget_listening_summary" | "listening_summary" => Some("widget_listening_summary"),
        _ => None,
    }
}

/// Returns the complete catalog of Home blocks and widgets managed by Rust.
pub fn home_widget_catalog() -> Vec<HomeWidgetCatalogEntry> {
    vec![
        HomeWidgetCatalogEntry {
            kind: "block_flow_hero".into(),
            category: "block".into(),
            title: "Deezer Flow".into(),
            description: "Kişisel sonsuz müzik akışını tek dokunuşla başlatan ana kart".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Tinted,
            default_max_items: 1,
            min_items: 1,
            max_items_limit: 1,
            supports_item_count: false,
            supports_accent: true,
            allow_multiple: false,
        },
        HomeWidgetCatalogEntry {
            kind: "block_resume_playback".into(),
            category: "block".into(),
            title: "Kaldığın Yerden Devam Et".into(),
            description: "Son dinlediğin şarkıyı kaldığın saniyeden tek tıkla sürdür".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 1,
            min_items: 1,
            max_items_limit: 1,
            supports_item_count: false,
            supports_accent: true,
            allow_multiple: false,
        },
        HomeWidgetCatalogEntry {
            kind: "block_quick_access".into(),
            category: "block".into(),
            title: "Hızlı Erişim".into(),
            description: "Sabitlediğin çalma listeleri, favoriler ve indirilenler".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 12,
            min_items: 2,
            max_items_limit: 24,
            supports_item_count: true,
            supports_accent: false,
            allow_multiple: false,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_stats".into(),
            category: "widget".into(),
            title: "Şarkı ve Dinleme İstatistikleri".into(),
            description: "En çok çaldığın şarkılar ve toplam dinleme süresi".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 3,
            min_items: 1,
            max_items_limit: 10,
            supports_item_count: true,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_quick_artist".into(),
            category: "widget".into(),
            title: "Hızlıca Sanatçıya Git".into(),
            description: "Dinlediğin sanatçılara yatay şeritten tek dokunuşla ulaş".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 12,
            min_items: 4,
            max_items_limit: 20,
            supports_item_count: true,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_quick_theme".into(),
            category: "widget".into(),
            title: "Hızlıca Tema Değiştir".into(),
            description: "Ana sayfadan çıkmadan yerleşik ve özel temalar arasında geçiş yap".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 8,
            min_items: 3,
            max_items_limit: 16,
            supports_item_count: false,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_lucky_pick".into(),
            category: "widget".into(),
            title: "Şanslı Şarkı Seçici".into(),
            description: "Kitaplığından rastgele sürpriz parça önerisi".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 1,
            min_items: 1,
            max_items_limit: 1,
            supports_item_count: false,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_sleep_timer".into(),
            category: "widget".into(),
            title: "Hızlı Uyku Zamanlayıcısı".into(),
            description: "15/30/45 dk veya şarkı sonunda otomatik duraklatma kontrolü".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 1,
            min_items: 1,
            max_items_limit: 1,
            supports_item_count: false,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_mood_mix".into(),
            category: "widget".into(),
            title: "Ruh Haline Göre Anında Miks".into(),
            description: "Enerjik, Gece Modu, Odaklanma ve Karışık tek dokunuş miksleri".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 4,
            min_items: 2,
            max_items_limit: 4,
            supports_item_count: false,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_recent_tracks".into(),
            category: "widget".into(),
            title: "Son Dinlenenler Şeridi".into(),
            description: "Son çaldığın parçaları kapaklarıyla yatay şeritte hızlıca tekrar çal".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 8,
            min_items: 3,
            max_items_limit: 20,
            supports_item_count: true,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "widget_listening_summary".into(),
            category: "widget".into(),
            title: "Mini Dinleme Özeti".into(),
            description: "Toplam dinleme süresi, çalma sayısı ve en sevdiğin sanatçı özeti".into(),
            default_size: HomeWidgetSize::Compact,
            default_style: HomeWidgetStyle::Tinted,
            default_max_items: 1,
            min_items: 1,
            max_items_limit: 1,
            supports_item_count: false,
            supports_accent: true,
            allow_multiple: true,
        },
        HomeWidgetCatalogEntry {
            kind: "block_feed".into(),
            category: "block".into(),
            title: "Ana Sayfa Müzik Akışı".into(),
            description: "Senin İçin, Son Dinlenenler, En Çok Çalınanlar ve yerel favoriler".into(),
            default_size: HomeWidgetSize::Normal,
            default_style: HomeWidgetStyle::Card,
            default_max_items: 6,
            min_items: 3,
            max_items_limit: 20,
            supports_item_count: true,
            supports_accent: false,
            allow_multiple: false,
        },
    ]
}

fn find_catalog(kind: &str) -> Option<HomeWidgetCatalogEntry> {
    let canonical = canonical_home_widget_kind(kind)?;
    home_widget_catalog()
        .into_iter()
        .find(|entry| entry.kind == canonical)
}

fn new_item_from_entry(
    entry: &HomeWidgetCatalogEntry,
    instance_id: String,
    order: u32,
) -> HomeWidgetItem {
    HomeWidgetItem {
        instance_id,
        kind: entry.kind.clone(),
        enabled: true,
        order,
        size: entry.default_size,
        style: entry.default_style,
        max_items: entry.default_max_items,
        show_subtitle: true,
        custom_title: None,
        accent_hex: None,
    }
}

fn allocate_instance_id(kind: &str, used: &BTreeSet<String>) -> String {
    let mut seq = 1u32;
    loop {
        let candidate = format!("{kind}_{seq}");
        if !used.contains(&candidate) {
            return candidate;
        }
        seq += 1;
    }
}

fn sanitize_custom_title(raw: Option<String>) -> Option<String> {
    raw.and_then(|s| {
        let trimmed = s.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.chars().take(48).collect())
        }
    })
}

fn normalize_items(raw_items: Vec<HomeWidgetItem>) -> Vec<HomeWidgetItem> {
    let mut used_ids = BTreeSet::new();
    let mut seen_singletons = BTreeSet::new();
    let mut out = Vec::with_capacity(raw_items.len());

    let mut sorted = raw_items;
    sorted.sort_by_key(|item| item.order);

    for mut item in sorted {
        let Some(entry) = find_catalog(&item.kind) else {
            continue;
        };
        if !entry.allow_multiple && !seen_singletons.insert(entry.kind.clone()) {
            continue;
        }
        item.kind = entry.kind.clone();
        let clean_id = item.instance_id.trim();
        let instance_id = if clean_id.is_empty() || used_ids.contains(clean_id) {
            allocate_instance_id(&item.kind, &used_ids)
        } else {
            clean_id.to_string()
        };
        used_ids.insert(instance_id.clone());

        item.instance_id = instance_id;
        item.order = out.len() as u32;
        item.max_items = item.max_items.clamp(entry.min_items, entry.max_items_limit);
        item.custom_title = sanitize_custom_title(item.custom_title);
        item.accent_hex = item.accent_hex.filter(|hex| is_valid_hex_color(hex));
        out.push(item);
    }
    out
}

pub fn default_home_layout_from_settings(s: &Settings) -> Vec<HomeWidgetItem> {
    let mut items = Vec::new();
    let mut used = BTreeSet::new();

    let mut push_kind = |kind: &str| {
        if let Some(entry) = find_catalog(kind) {
            let id = allocate_instance_id(&entry.kind, &used);
            used.insert(id.clone());
            items.push(new_item_from_entry(&entry, id, items.len() as u32));
        }
    };

    if s.flow_hero_card_enabled {
        push_kind("block_flow_hero");
    }
    if s.resume_playback_enabled {
        push_kind("block_resume_playback");
    }
    push_kind("block_quick_access");

    let canonical_enabled: BTreeSet<&'static str> = s
        .enabled_home_widgets
        .iter()
        .filter_map(|raw| canonical_home_widget_kind(raw))
        .collect();

    // Keep standard widget order when migrating from Settings::enabled_home_widgets
    for kind in [
        "widget_stats",
        "widget_quick_artist",
        "widget_quick_theme",
        "widget_lucky_pick",
        "widget_sleep_timer",
        "widget_mood_mix",
        "widget_recent_tracks",
        "widget_listening_summary",
    ] {
        if canonical_enabled.contains(kind) {
            push_kind(kind);
        }
    }

    push_kind("block_feed");
    items
}

impl SettingsService {
    /// Loads the Rust-managed Home layout, seamlessly migrating legacy settings on first run.
    pub fn load_home_layout(&self) -> DomainResult<Vec<HomeWidgetItem>> {
        if let Some(raw) = self.store.get(HOME_LAYOUT_KEY)? {
            if let Ok(parsed) = serde_json::from_str::<Vec<HomeWidgetItem>>(&raw) {
                if !parsed.is_empty() {
                    return Ok(normalize_items(parsed));
                }
            }
        }
        let settings = self.load()?;
        Ok(default_home_layout_from_settings(&settings))
    }

    /// Validates, normalizes, persists, and syncs Home layout with legacy Settings fields.
    pub fn save_home_layout(&self, items: Vec<HomeWidgetItem>) -> DomainResult<Vec<HomeWidgetItem>> {
        let normalized = normalize_items(items);
        let serialized = serde_json::to_string(&normalized)
            .map_err(|e| DomainError::Internal(format!("home layout serialize error: {e}")))?;
        self.store.set(HOME_LAYOUT_KEY, &serialized)?;
        self.sync_settings_from_layout(&normalized)?;
        Ok(normalized)
    }

    fn sync_settings_from_layout(&self, layout: &[HomeWidgetItem]) -> DomainResult<()> {
        let active_kinds: BTreeSet<&str> = layout
            .iter()
            .filter(|i| i.enabled)
            .map(|i| i.kind.as_str())
            .collect();

        let flow_enabled = active_kinds.contains("block_flow_hero");
        let resume_enabled = active_kinds.contains("block_resume_playback");
        let enabled_widgets: Vec<String> = active_kinds
            .iter()
            .copied()
            .filter(|k| k.starts_with("widget_"))
            .map(|k| k.to_string())
            .collect();

        self.store
            .set(keys::FLOW_HERO_CARD, &flow_enabled.to_string())?;
        self.store
            .set(keys::RESUME_PLAYBACK, &resume_enabled.to_string())?;
        let widgets_json =
            serde_json::to_string(&enabled_widgets).unwrap_or_else(|_| "[]".into());
        self.store.set(keys::ENABLED_HOME_WIDGETS, &widgets_json)?;
        Ok(())
    }

    pub fn add_home_widget(
        &self,
        kind: &str,
        insert_at: Option<u32>,
    ) -> DomainResult<Vec<HomeWidgetItem>> {
        let entry = find_catalog(kind)
            .ok_or_else(|| DomainError::InvalidInput(format!("unknown widget kind: {kind}")))?;
        let mut layout = self.load_home_layout()?;

        if !entry.allow_multiple {
            if let Some(existing) = layout.iter_mut().find(|i| i.kind == entry.kind) {
                existing.enabled = true;
                return self.save_home_layout(layout);
            }
        }

        let used: BTreeSet<String> = layout.iter().map(|i| i.instance_id.clone()).collect();
        let instance_id = allocate_instance_id(&entry.kind, &used);
        let default_pos = layout
            .iter()
            .position(|i| i.kind == "block_feed")
            .unwrap_or(layout.len());
        let target_idx = insert_at
            .map(|idx| (idx as usize).min(layout.len()))
            .unwrap_or(default_pos);

        let new_item = new_item_from_entry(&entry, instance_id, target_idx as u32);
        layout.insert(target_idx, new_item);
        for (idx, item) in layout.iter_mut().enumerate() {
            item.order = idx as u32;
        }
        self.save_home_layout(layout)
    }

    pub fn remove_home_widget(&self, instance_id: &str) -> DomainResult<Vec<HomeWidgetItem>> {
        let mut layout = self.load_home_layout()?;
        let before = layout.len();
        layout.retain(|i| i.instance_id != instance_id);
        if layout.len() == before {
            return Err(DomainError::NotFound(format!(
                "widget instance not found: {instance_id}"
            )));
        }
        for (idx, item) in layout.iter_mut().enumerate() {
            item.order = idx as u32;
        }
        self.save_home_layout(layout)
    }

    pub fn move_home_widget(
        &self,
        instance_id: &str,
        to_index: u32,
    ) -> DomainResult<Vec<HomeWidgetItem>> {
        let mut layout = self.load_home_layout()?;
        let Some(from_idx) = layout.iter().position(|i| i.instance_id == instance_id) else {
            return Err(DomainError::NotFound(format!(
                "widget instance not found: {instance_id}"
            )));
        };
        if layout.is_empty() {
            return Ok(layout);
        }
        let target_idx = (to_index as usize).min(layout.len().saturating_sub(1));
        if from_idx != target_idx {
            let item = layout.remove(from_idx);
            layout.insert(target_idx, item);
            for (idx, it) in layout.iter_mut().enumerate() {
                it.order = idx as u32;
            }
        }
        self.save_home_layout(layout)
    }

    pub fn reorder_home_widgets(
        &self,
        ordered_instance_ids: Vec<String>,
    ) -> DomainResult<Vec<HomeWidgetItem>> {
        let mut layout = self.load_home_layout()?;
        let mut reordered = Vec::with_capacity(layout.len());

        for id in ordered_instance_ids {
            if let Some(pos) = layout.iter().position(|i| i.instance_id == id) {
                reordered.push(layout.remove(pos));
            }
        }
        // Append any remaining items not listed in ordered_instance_ids to prevent accidental loss.
        reordered.append(&mut layout);
        for (idx, item) in reordered.iter_mut().enumerate() {
            item.order = idx as u32;
        }
        self.save_home_layout(reordered)
    }

    pub fn customize_home_widget(
        &self,
        instance_id: &str,
        patch: HomeWidgetPatch,
    ) -> DomainResult<Vec<HomeWidgetItem>> {
        let mut layout = self.load_home_layout()?;
        let Some(item) = layout.iter_mut().find(|i| i.instance_id == instance_id) else {
            return Err(DomainError::NotFound(format!(
                "widget instance not found: {instance_id}"
            )));
        };
        let entry = find_catalog(&item.kind).ok_or_else(|| {
            DomainError::Internal(format!("missing catalog entry for {}", item.kind))
        })?;

        if let Some(enabled) = patch.enabled {
            item.enabled = enabled;
        }
        if let Some(size) = patch.size {
            item.size = size;
        }
        if let Some(style) = patch.style {
            item.style = style;
        }
        if let Some(max_items) = patch.max_items {
            item.max_items = max_items.clamp(entry.min_items, entry.max_items_limit);
        }
        if let Some(show_subtitle) = patch.show_subtitle {
            item.show_subtitle = show_subtitle;
        }
        if patch.clear_custom_title {
            item.custom_title = None;
        } else if let Some(title) = patch.custom_title {
            item.custom_title = sanitize_custom_title(Some(title));
        }
        if patch.clear_accent_hex {
            item.accent_hex = None;
        } else if let Some(hex) = patch.accent_hex {
            let trimmed = hex.trim();
            if trimmed.is_empty() {
                item.accent_hex = None;
            } else if is_valid_hex_color(trimmed) {
                item.accent_hex = Some(trimmed.to_uppercase());
            } else {
                return Err(DomainError::InvalidInput(
                    "accent_hex must be #RRGGBB".into(),
                ));
            }
        }

        self.save_home_layout(layout)
    }

    pub fn set_home_widget_kind_enabled(
        &self,
        kind: &str,
        enabled: bool,
    ) -> DomainResult<Vec<HomeWidgetItem>> {
        let canonical = canonical_home_widget_kind(kind)
            .ok_or_else(|| DomainError::InvalidInput(format!("unknown widget kind: {kind}")))?;
        let mut layout = self.load_home_layout()?;
        if enabled {
            if let Some(existing) = layout.iter_mut().find(|i| i.kind == canonical) {
                existing.enabled = true;
                self.save_home_layout(layout)
            } else {
                self.add_home_widget(canonical, None)
            }
        } else {
            layout.retain(|i| i.kind != canonical);
            for (idx, item) in layout.iter_mut().enumerate() {
                item.order = idx as u32;
            }
            self.save_home_layout(layout)
        }
    }

    pub fn reset_home_layout(&self) -> DomainResult<Vec<HomeWidgetItem>> {
        self.store.remove(HOME_LAYOUT_KEY)?;
        let defaults = default_home_layout_from_settings(&Settings::default());
        self.save_home_layout(defaults)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::MemoryStore;

    fn make_service() -> SettingsService {
        SettingsService::new(Arc::new(MemoryStore::default()))
    }

    #[test]
    fn migrates_legacy_settings_and_unifies_ids() {
        let svc = make_service();
        svc.update(|s| {
            s.flow_hero_card_enabled = false;
            s.resume_playback_enabled = true;
            s.enabled_home_widgets = vec!["stats".into(), "widget_mood_mix".into()];
        })
        .unwrap();

        let layout = svc.load_home_layout().unwrap();
        let kinds: Vec<&str> = layout.iter().map(|i| i.kind.as_str()).collect();
        assert!(!kinds.contains(&"block_flow_hero"));
        assert!(kinds.contains(&"block_resume_playback"));
        assert!(kinds.contains(&"block_quick_access"));
        assert!(kinds.contains(&"widget_stats"));
        assert!(kinds.contains(&"widget_mood_mix"));
        assert_eq!(kinds.last().copied(), Some("block_feed"));
    }

    #[test]
    fn add_multiple_move_customize_and_remove() {
        let svc = make_service();
        let initial = svc.load_home_layout().unwrap();
        let initial_len = initial.len();

        // Allow adding a second Stats widget and moving it to index 0
        let after_add = svc.add_home_widget("widget_stats", Some(0)).unwrap();
        assert_eq!(after_add.len(), initial_len + 1);
        assert_eq!(after_add[0].kind, "widget_stats");
        let new_id = after_add[0].instance_id.clone();

        // Customize with clamping + accent validation
        let after_custom = svc
            .customize_home_widget(
                &new_id,
                HomeWidgetPatch {
                    size: Some(HomeWidgetSize::Expanded),
                    style: Some(HomeWidgetStyle::Tinted),
                    max_items: Some(99), // clamped to 10
                    show_subtitle: Some(false),
                    custom_title: Some("  Favori İstatistiklerim  ".into()),
                    accent_hex: Some("#ed438d".into()),
                    ..Default::default()
                },
            )
            .unwrap();
        let edited = after_custom
            .iter()
            .find(|i| i.instance_id == new_id)
            .unwrap();
        assert_eq!(edited.size, HomeWidgetSize::Expanded);
        assert_eq!(edited.style, HomeWidgetStyle::Tinted);
        assert_eq!(edited.max_items, 10);
        assert!(!edited.show_subtitle);
        assert_eq!(edited.custom_title.as_deref(), Some("Favori İstatistiklerim"));
        assert_eq!(edited.accent_hex.as_deref(), Some("#ED438D"));

        // Invalid hex fails cleanly
        assert!(svc
            .customize_home_widget(
                &new_id,
                HomeWidgetPatch {
                    accent_hex: Some("not-a-color".into()),
                    ..Default::default()
                }
            )
            .is_err());

        // Move and remove
        let moved = svc.move_home_widget(&new_id, 2).unwrap();
        assert_eq!(moved[2].instance_id, new_id);

        let removed = svc.remove_home_widget(&new_id).unwrap();
        assert_eq!(removed.len(), initial_len);
    }

    #[test]
    fn singleton_blocks_do_not_duplicate_and_sync_settings() {
        let svc = make_service();
        let _ = svc.load_home_layout().unwrap();
        let before = svc.load_home_layout().unwrap().len();
        let after_dup = svc.add_home_widget("block_quick_access", None).unwrap();
        assert_eq!(after_dup.len(), before);

        let _ = svc.set_home_widget_kind_enabled("block_flow_hero", false).unwrap();
        assert!(!svc.load().unwrap().flow_hero_card_enabled);

        let _ = svc.set_home_widget_kind_enabled("block_flow_hero", true).unwrap();
        assert!(svc.load().unwrap().flow_hero_card_enabled);
    }

    #[test]
    fn reset_restores_defaults() {
        let svc = make_service();
        let _ = svc.add_home_widget("widget_recent_tracks", Some(0)).unwrap();
        let reset = svc.reset_home_layout().unwrap();
        assert!(reset.iter().all(|i| i.kind != "widget_recent_tracks"));
        assert!(reset.iter().any(|i| i.kind == "block_flow_hero"));
    }
}
