//! UniFFI exports and typed interfaces for Android integration.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, RwLock};

use core_domain::{
    Capability, DataOrigin, DomainError, PageRequest, Playlist, PlaylistId, ProviderId, Track,
    TrackId,
};
use core_downloads::{
    sanitize_file_name, user_supplied_download, DownloadManager, DownloadRepository, DownloadState,
    DownloadTask, ManagerConfig,
};
use core_library::{LibraryRepository, LibraryService};
use core_mods::{ModManager, ModPermission, ModState};
use core_providers::{AuthorizedDownload, MusicProvider};
use core_queue::{AdvanceReason, PreviousOutcome, Queue, RepeatMode};
use core_settings::{
    home_widget_catalog, HomeWidgetCatalogEntry, HomeWidgetItem, HomeWidgetPatch, HomeWidgetSize,
    HomeWidgetStyle, Language, Settings, SettingsService,
};
use core_storage::{
    now_epoch_ms, AlbumStatSummary, ArtistStatSummary, DailyListeningPoint, Database,
    HourlyListeningBucket, ImportBackupReport, ListeningAnalyticsSummary, StoredHistoryEntry,
    StoredPinnedItem, StoredPlaylistRecord, StoredPlaylistTrackEntry, StoredResumePlaybackState,
    StoredTrackListeningStat, StoredTrackRecord,
};
use core_themes::{CustomThemeSpec, ThemeManifest, ThemeRegistry};
use provider_deezer::{DeezerAppConfig, DeezerProvider};
use provider_dev::DevProvider;
use provider_local::{LocalProvider, LocalSortOrder, LocalSyncStats, LocalTrackInput};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("{reason}")]
    OperationFailed { reason: String },
}

impl From<DomainError> for FfiError {
    fn from(err: DomainError) -> Self {
        Self::OperationFailed {
            reason: err.to_string(),
        }
    }
}

pub type FfiResult<T> = Result<T, FfiError>;

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiTrack {
    pub id: String,
    pub provider: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub artwork_url: Option<String>,
    pub playback_uri: Option<String>,
    pub is_preview: bool,
    pub origin: String,
}

impl From<Track> for FfiTrack {
    fn from(t: Track) -> Self {
        Self {
            id: t.id.to_string(),
            provider: t.id.provider().to_string(),
            title: t.title,
            artist: t.artist,
            album: t.album,
            duration_ms: t.duration_ms,
            artwork_url: t.artwork_url,
            playback_uri: t.playback_uri,
            is_preview: t.is_preview,
            origin: match t.origin {
                DataOrigin::Real => "real".into(),
                DataOrigin::PublicCatalog => "public_catalog".into(),
                DataOrigin::DevelopmentFixture => "development_fixture".into(),
            },
        }
    }
}

impl TryFrom<FfiTrack> for Track {
    type Error = FfiError;

    fn try_from(f: FfiTrack) -> Result<Self, Self::Error> {
        let id = TrackId::parse(&f.id).map_err(|e| FfiError::OperationFailed {
            reason: e.to_string(),
        })?;
        let origin = match f.origin.as_str() {
            "public_catalog" => DataOrigin::PublicCatalog,
            "development_fixture" => DataOrigin::DevelopmentFixture,
            _ => DataOrigin::Real,
        };
        Ok(Self {
            id,
            title: f.title,
            artist: f.artist,
            album: f.album,
            duration_ms: f.duration_ms,
            artwork_url: f.artwork_url,
            playback_uri: f.playback_uri,
            is_preview: f.is_preview,
            origin,
        })
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiPlaylist {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub is_local: bool,
    pub track_count: u32,
    pub artwork_url: Option<String>,
    pub origin: String,
}

impl From<Playlist> for FfiPlaylist {
    fn from(p: Playlist) -> Self {
        Self {
            id: p.id.to_string(),
            name: p.name,
            description: p.description,
            is_local: p.kind == core_domain::PlaylistKind::Local,
            track_count: p.track_count,
            artwork_url: p.artwork_url,
            origin: match p.origin {
                DataOrigin::Real => "real".into(),
                DataOrigin::PublicCatalog => "public_catalog".into(),
                DataOrigin::DevelopmentFixture => "development_fixture".into(),
            },
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiQueueEntry {
    pub entry_id: u64,
    pub track: FfiTrack,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiRepeatMode {
    Off,
    One,
    All,
}

impl From<RepeatMode> for FfiRepeatMode {
    fn from(m: RepeatMode) -> Self {
        match m {
            RepeatMode::Off => Self::Off,
            RepeatMode::One => Self::One,
            RepeatMode::All => Self::All,
        }
    }
}

impl From<FfiRepeatMode> for RepeatMode {
    fn from(m: FfiRepeatMode) -> Self {
        match m {
            FfiRepeatMode::Off => Self::Off,
            FfiRepeatMode::One => Self::One,
            FfiRepeatMode::All => Self::All,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiQueueSnapshot {
    pub entries: Vec<FfiQueueEntry>,
    pub current_index: Option<u32>,
    pub repeat_mode: FfiRepeatMode,
    pub is_shuffled: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiProviderInfo {
    pub id: String,
    pub display_name: String,
    pub origin: String,
    pub auth_state: String,
    pub can_search: bool,
    pub can_full_playback: bool,
    pub can_preview_playback: bool,
    pub can_download: bool,
    pub can_user_playlists: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDownloadTask {
    pub id: i64,
    pub track_id: String,
    pub url: String,
    pub file_name: String,
    pub state: String,
    pub bytes_done: u64,
    pub total_bytes: Option<u64>,
    pub progress_percent: Option<u8>,
    pub error: Option<String>,
}

impl From<DownloadTask> for FfiDownloadTask {
    fn from(t: DownloadTask) -> Self {
        let progress_percent = t.progress_percent();
        Self {
            id: t.id,
            track_id: t.track_id.to_string(),
            url: t.url,
            file_name: t.file_name,
            state: t.state.as_str().into(),
            bytes_done: t.bytes_done,
            total_bytes: t.total_bytes,
            progress_percent,
            error: t.error,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiTheme {
    pub id: String,
    pub name: String,
    pub author: String,
    pub is_dark: bool,
    pub background_color: String,
    pub surface_color: String,
    pub card_color: String,
    pub accent_color: String,
    pub text_color: String,
    pub text_secondary_color: String,
    pub border_color: String,
    pub small_radius: u32,
    pub medium_radius: u32,
    pub large_radius: u32,
}

impl From<ThemeManifest> for FfiTheme {
    fn from(m: ThemeManifest) -> Self {
        Self {
            id: m.id,
            name: m.name,
            author: m.author,
            is_dark: m.dark,
            background_color: m.colors.background,
            surface_color: m.colors.surface,
            card_color: m.colors.card,
            accent_color: m.colors.accent,
            text_color: m.colors.text,
            text_secondary_color: m.colors.text_secondary,
            border_color: m.colors.border,
            small_radius: m.shapes.small_radius,
            medium_radius: m.shapes.medium_radius,
            large_radius: m.shapes.large_radius,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiModInfo {
    pub id: String,
    pub name: String,
    pub version: String,
    pub author: String,
    pub description: String,
    pub is_enabled: bool,
    pub is_builtin: bool,
    pub has_custom_track_menu: bool,
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDeezerUser {
    pub id: String,
    pub name: String,
    pub email: Option<String>,
    pub status_label: String,
    pub is_premium: bool,
    pub link: Option<String>,
    pub picture_medium: Option<String>,
    pub nb_playlists: u32,
    pub is_authenticated: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAlbum {
    pub id: String,
    pub title: String,
    pub artist_name: String,
    pub cover_url: Option<String>,
    pub release_date: Option<String>,
    pub nb_tracks: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiArtist {
    pub id: String,
    pub name: String,
    pub picture_url: Option<String>,
    pub nb_albums: u32,
    pub nb_fans: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSettings {
    pub theme_id: String,
    pub language: String,
    pub show_dev_fixtures: bool,
    pub downloads_wifi_only: bool,
    pub download_storage_limit_mib: u32,
    pub mods_enabled: bool,
    pub deezer_user_id: Option<String>,
    pub deezer_access_token: Option<String>,
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
    pub local_filter_short_tracks: bool,
}

impl From<Settings> for FfiSettings {
    fn from(s: Settings) -> Self {
        Self {
            theme_id: s.theme_id,
            language: s.language.as_key().into(),
            show_dev_fixtures: s.show_dev_fixtures,
            downloads_wifi_only: s.downloads_wifi_only,
            download_storage_limit_mib: s.download_storage_limit_mib,
            mods_enabled: s.mods_enabled,
            deezer_user_id: s.deezer_user_id,
            deezer_access_token: s.deezer_access_token,
            custom_accent_color: s.custom_accent_color,
            high_refresh_rate: s.high_refresh_rate,
            dynamic_color_enabled: s.dynamic_color_enabled,
            swipe_to_skip: s.swipe_to_skip,
            seekbar_style: s.seekbar_style,
            album_frame: s.album_frame,
            header_logo_style: s.header_logo_style,
            home_feed_type: s.home_feed_type,
            flow_hero_card_enabled: s.flow_hero_card_enabled,
            flow_quick_access_pinned: s.flow_quick_access_pinned,
            flow_library_pinned: s.flow_library_pinned,
            downloads_quick_access_pinned: s.downloads_quick_access_pinned,
            downloads_library_pinned: s.downloads_library_pinned,
            resume_playback_enabled: s.resume_playback_enabled,
            collect_listening_stats: s.collect_listening_stats,
            collect_listening_history: s.collect_listening_history,
            save_resume_position: s.save_resume_position,
            enabled_home_widgets: s.enabled_home_widgets,
            home_edit_hold_seconds: s.home_edit_hold_seconds,
            local_filter_short_tracks: s.local_filter_short_tracks,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiHomeWidgetSize {
    Compact,
    Normal,
    Expanded,
}

impl From<HomeWidgetSize> for FfiHomeWidgetSize {
    fn from(v: HomeWidgetSize) -> Self {
        match v {
            HomeWidgetSize::Compact => Self::Compact,
            HomeWidgetSize::Normal => Self::Normal,
            HomeWidgetSize::Expanded => Self::Expanded,
        }
    }
}

impl From<FfiHomeWidgetSize> for HomeWidgetSize {
    fn from(v: FfiHomeWidgetSize) -> Self {
        match v {
            FfiHomeWidgetSize::Compact => Self::Compact,
            FfiHomeWidgetSize::Normal => Self::Normal,
            FfiHomeWidgetSize::Expanded => Self::Expanded,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiHomeWidgetStyle {
    Card,
    Tinted,
    Minimal,
}

impl From<HomeWidgetStyle> for FfiHomeWidgetStyle {
    fn from(v: HomeWidgetStyle) -> Self {
        match v {
            HomeWidgetStyle::Card => Self::Card,
            HomeWidgetStyle::Tinted => Self::Tinted,
            HomeWidgetStyle::Minimal => Self::Minimal,
        }
    }
}

impl From<FfiHomeWidgetStyle> for HomeWidgetStyle {
    fn from(v: FfiHomeWidgetStyle) -> Self {
        match v {
            FfiHomeWidgetStyle::Card => Self::Card,
            FfiHomeWidgetStyle::Tinted => Self::Tinted,
            FfiHomeWidgetStyle::Minimal => Self::Minimal,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiHomeWidgetItem {
    pub instance_id: String,
    pub kind: String,
    pub enabled: bool,
    pub order: u32,
    pub size: FfiHomeWidgetSize,
    pub style: FfiHomeWidgetStyle,
    pub max_items: u8,
    pub show_subtitle: bool,
    pub custom_title: Option<String>,
    pub accent_hex: Option<String>,
}

impl From<HomeWidgetItem> for FfiHomeWidgetItem {
    fn from(i: HomeWidgetItem) -> Self {
        Self {
            instance_id: i.instance_id,
            kind: i.kind,
            enabled: i.enabled,
            order: i.order,
            size: i.size.into(),
            style: i.style.into(),
            max_items: i.max_items,
            show_subtitle: i.show_subtitle,
            custom_title: i.custom_title,
            accent_hex: i.accent_hex,
        }
    }
}

impl From<FfiHomeWidgetItem> for HomeWidgetItem {
    fn from(i: FfiHomeWidgetItem) -> Self {
        Self {
            instance_id: i.instance_id,
            kind: i.kind,
            enabled: i.enabled,
            order: i.order,
            size: i.size.into(),
            style: i.style.into(),
            max_items: i.max_items,
            show_subtitle: i.show_subtitle,
            custom_title: i.custom_title,
            accent_hex: i.accent_hex,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiHomeWidgetCatalogEntry {
    pub kind: String,
    pub category: String,
    pub title: String,
    pub description: String,
    pub default_size: FfiHomeWidgetSize,
    pub default_style: FfiHomeWidgetStyle,
    pub default_max_items: u8,
    pub min_items: u8,
    pub max_items_limit: u8,
    pub supports_item_count: bool,
    pub supports_accent: bool,
    pub allow_multiple: bool,
}

impl From<HomeWidgetCatalogEntry> for FfiHomeWidgetCatalogEntry {
    fn from(e: HomeWidgetCatalogEntry) -> Self {
        Self {
            kind: e.kind,
            category: e.category,
            title: e.title,
            description: e.description,
            default_size: e.default_size.into(),
            default_style: e.default_style.into(),
            default_max_items: e.default_max_items,
            min_items: e.min_items,
            max_items_limit: e.max_items_limit,
            supports_item_count: e.supports_item_count,
            supports_accent: e.supports_accent,
            allow_multiple: e.allow_multiple,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiHomeWidgetPatch {
    pub enabled: Option<bool>,
    pub size: Option<FfiHomeWidgetSize>,
    pub style: Option<FfiHomeWidgetStyle>,
    pub max_items: Option<u8>,
    pub show_subtitle: Option<bool>,
    pub clear_custom_title: bool,
    pub custom_title: Option<String>,
    pub clear_accent_hex: bool,
    pub accent_hex: Option<String>,
}

impl From<FfiHomeWidgetPatch> for HomeWidgetPatch {
    fn from(p: FfiHomeWidgetPatch) -> Self {
        Self {
            enabled: p.enabled,
            size: p.size.map(Into::into),
            style: p.style.map(Into::into),
            max_items: p.max_items,
            show_subtitle: p.show_subtitle,
            clear_custom_title: p.clear_custom_title,
            custom_title: p.custom_title,
            clear_accent_hex: p.clear_accent_hex,
            accent_hex: p.accent_hex,
        }
    }
}

impl From<FfiTrack> for StoredTrackRecord {
    fn from(f: FfiTrack) -> Self {
        Self {
            id: f.id,
            provider: f.provider,
            title: f.title,
            artist: f.artist,
            album: f.album,
            duration_ms: f.duration_ms,
            artwork_url: f.artwork_url,
            playback_uri: f.playback_uri,
            is_preview: f.is_preview,
            origin: f.origin,
        }
    }
}

impl From<StoredTrackRecord> for FfiTrack {
    fn from(s: StoredTrackRecord) -> Self {
        Self {
            id: s.id,
            provider: s.provider,
            title: s.title,
            artist: s.artist,
            album: s.album,
            duration_ms: s.duration_ms,
            artwork_url: s.artwork_url,
            playback_uri: s.playback_uri,
            is_preview: s.is_preview,
            origin: s.origin,
        }
    }
}

impl From<FfiPlaylist> for StoredPlaylistRecord {
    fn from(f: FfiPlaylist) -> Self {
        Self {
            id: f.id,
            name: f.name,
            description: f.description,
            is_local: f.is_local,
            track_count: f.track_count,
            artwork_url: f.artwork_url,
            origin: f.origin,
        }
    }
}

impl From<StoredPlaylistRecord> for FfiPlaylist {
    fn from(s: StoredPlaylistRecord) -> Self {
        Self {
            id: s.id,
            name: s.name,
            description: s.description,
            is_local: s.is_local,
            track_count: s.track_count,
            artwork_url: s.artwork_url,
            origin: s.origin,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiCustomThemeSpec {
    pub id: String,
    pub name: String,
    pub background_hex: String,
    pub card_hex: String,
    pub accent_hex: String,
    pub text_hex: String,
}

impl From<CustomThemeSpec> for FfiCustomThemeSpec {
    fn from(s: CustomThemeSpec) -> Self {
        Self {
            id: s.id,
            name: s.name,
            background_hex: s.background_hex,
            card_hex: s.card_hex,
            accent_hex: s.accent_hex,
            text_hex: s.text_hex,
        }
    }
}

impl From<FfiCustomThemeSpec> for CustomThemeSpec {
    fn from(f: FfiCustomThemeSpec) -> Self {
        Self {
            id: f.id,
            name: f.name,
            background_hex: f.background_hex,
            card_hex: f.card_hex,
            accent_hex: f.accent_hex,
            text_hex: f.text_hex,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiTrackListeningStat {
    pub track: FfiTrack,
    pub play_count: u32,
    pub total_listened_ms: u64,
    pub last_played_at_ms: u64,
}

impl From<StoredTrackListeningStat> for FfiTrackListeningStat {
    fn from(r: StoredTrackListeningStat) -> Self {
        Self {
            track: FfiTrack::from(r.track),
            play_count: r.play_count,
            total_listened_ms: r.total_listened_ms,
            last_played_at_ms: r.last_played_at_ms,
        }
    }
}

impl From<FfiTrackListeningStat> for StoredTrackListeningStat {
    fn from(f: FfiTrackListeningStat) -> Self {
        Self {
            track: StoredTrackRecord::from(f.track),
            play_count: f.play_count,
            total_listened_ms: f.total_listened_ms,
            last_played_at_ms: f.last_played_at_ms,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiResumePlaybackState {
    pub track: FfiTrack,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub updated_at_ms: u64,
    pub queue_tracks: Vec<FfiTrack>,
    pub queue_index: u32,
}

impl From<StoredResumePlaybackState> for FfiResumePlaybackState {
    fn from(r: StoredResumePlaybackState) -> Self {
        Self {
            track: FfiTrack::from(r.track),
            position_ms: r.position_ms,
            duration_ms: r.duration_ms,
            updated_at_ms: r.updated_at_ms,
            queue_tracks: r.queue_tracks.into_iter().map(FfiTrack::from).collect(),
            queue_index: r.queue_index,
        }
    }
}

impl From<FfiResumePlaybackState> for StoredResumePlaybackState {
    fn from(f: FfiResumePlaybackState) -> Self {
        Self {
            track: StoredTrackRecord::from(f.track),
            position_ms: f.position_ms,
            duration_ms: f.duration_ms,
            updated_at_ms: f.updated_at_ms,
            queue_tracks: f
                .queue_tracks
                .into_iter()
                .map(StoredTrackRecord::from)
                .collect(),
            queue_index: f.queue_index,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiPinnedItem {
    pub pin_id: String,
    pub pin_type: String,
    pub track: Option<FfiTrack>,
    pub playlist: Option<FfiPlaylist>,
}

impl From<StoredPinnedItem> for FfiPinnedItem {
    fn from(p: StoredPinnedItem) -> Self {
        Self {
            pin_id: p.pin_id,
            pin_type: p.pin_type,
            track: p.track.map(FfiTrack::from),
            playlist: p.playlist.map(FfiPlaylist::from),
        }
    }
}

impl From<FfiPinnedItem> for StoredPinnedItem {
    fn from(f: FfiPinnedItem) -> Self {
        Self {
            pin_id: f.pin_id,
            pin_type: f.pin_type,
            track: f.track.map(StoredTrackRecord::from),
            playlist: f.playlist.map(StoredPlaylistRecord::from),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiPlaylistTrackEntry {
    pub track: FfiTrack,
    pub added_at: Option<i64>,
    pub original_index: u32,
}

impl From<StoredPlaylistTrackEntry> for FfiPlaylistTrackEntry {
    fn from(e: StoredPlaylistTrackEntry) -> Self {
        Self {
            track: FfiTrack::from(e.track),
            added_at: e.added_at,
            original_index: e.original_index,
        }
    }
}

impl From<FfiPlaylistTrackEntry> for StoredPlaylistTrackEntry {
    fn from(f: FfiPlaylistTrackEntry) -> Self {
        Self {
            track: StoredTrackRecord::from(f.track),
            added_at: f.added_at,
            original_index: f.original_index,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiPlaylistTrackSort {
    Default,
    NewestFirst,
    OldestFirst,
    TitleAz,
    ArtistAz,
    AlbumAz,
    DurationAsc,
    DurationDesc,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiLibraryPlaylistEntry {
    pub playlist: FfiPlaylist,
    pub is_pinned: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiPlaylistPickerItem {
    pub playlist: FfiPlaylist,
    pub is_member: bool,
    pub is_compatible: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiMqttPublish {
    pub topic: String,
    pub payload: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSavedDownload {
    pub id: String,
    pub track: FfiTrack,
    pub quality: String,
    pub state: String,
    pub progress: i32,
    pub uri: Option<String>,
    pub error: Option<String>,
    pub partial_uri: Option<String>,
    pub attempts: i32,
    pub playlist_id: Option<String>,
    pub playlist_name: Option<String>,
}

impl From<core_storage::StoredSavedDownload> for FfiSavedDownload {
    fn from(d: core_storage::StoredSavedDownload) -> Self {
        Self {
            id: d.id,
            track: FfiTrack::from(d.track),
            quality: d.quality,
            state: d.state,
            progress: d.progress,
            uri: d.uri,
            error: d.error,
            partial_uri: d.partial_uri,
            attempts: d.attempts,
            playlist_id: d.playlist_id,
            playlist_name: d.playlist_name,
        }
    }
}

impl From<FfiSavedDownload> for core_storage::StoredSavedDownload {
    fn from(d: FfiSavedDownload) -> Self {
        Self {
            id: d.id,
            track: StoredTrackRecord::from(d.track),
            quality: d.quality,
            state: d.state,
            progress: d.progress,
            uri: d.uri,
            error: d.error,
            partial_uri: d.partial_uri,
            attempts: d.attempts,
            playlist_id: d.playlist_id,
            playlist_name: d.playlist_name,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAppUpdateRelease {
    pub version: String,
    pub tag_name: String,
    pub title: String,
    pub notes: String,
    pub apk_url: String,
    pub apk_size_bytes: u64,
    pub html_url: String,
}

impl From<core_updater::AppUpdateRelease> for FfiAppUpdateRelease {
    fn from(r: core_updater::AppUpdateRelease) -> Self {
        Self {
            version: r.version,
            tag_name: r.tag_name,
            title: r.title,
            notes: r.notes,
            apk_url: r.apk_url,
            apk_size_bytes: r.apk_size_bytes,
            html_url: r.html_url,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiLyricLine {
    pub time_ms: u64,
    pub text: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiLyricsStatus {
    Synced,
    Plain,
    Instrumental,
    NotFound,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiLyricsResult {
    pub status: FfiLyricsStatus,
    pub lines: Vec<FfiLyricLine>,
    pub plain_text: Option<String>,
}

impl From<core_lyrics::LyricsResult> for FfiLyricsResult {
    fn from(r: core_lyrics::LyricsResult) -> Self {
        match r {
            core_lyrics::LyricsResult::Synced { lines } => Self {
                status: FfiLyricsStatus::Synced,
                lines: lines
                    .into_iter()
                    .map(|l| FfiLyricLine {
                        time_ms: l.time_ms,
                        text: l.text,
                    })
                    .collect(),
                plain_text: None,
            },
            core_lyrics::LyricsResult::Plain { text } => Self {
                status: FfiLyricsStatus::Plain,
                lines: Vec::new(),
                plain_text: Some(text),
            },
            core_lyrics::LyricsResult::Instrumental => Self {
                status: FfiLyricsStatus::Instrumental,
                lines: Vec::new(),
                plain_text: None,
            },
            core_lyrics::LyricsResult::NotFound => Self {
                status: FfiLyricsStatus::NotFound,
                lines: Vec::new(),
                plain_text: None,
            },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiLibraryPlaylistSort {
    Default,
    TitleAz,
    TitleZa,
    TrackCountDesc,
    TrackCountAsc,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiQuickArtistItem {
    pub artist: String,
    pub play_count: u32,
    pub sample_track: FfiTrack,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiLocalTrackInput {
    pub uri: String,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub duration_ms: u64,
    pub artwork_uri: Option<String>,
    pub file_name: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiLocalSyncStats {
    pub added: u32,
    pub updated: u32,
    pub removed: u32,
    pub skipped_short: u32,
    pub total: u32,
}

impl From<LocalSyncStats> for FfiLocalSyncStats {
    fn from(s: LocalSyncStats) -> Self {
        Self {
            added: s.added,
            updated: s.updated,
            removed: s.removed,
            skipped_short: s.skipped_short,
            total: s.total,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiLocalSortOrder {
    ArtistAlbumTitle,
    Title,
    DurationDesc,
}

impl From<FfiLocalSortOrder> for LocalSortOrder {
    fn from(v: FfiLocalSortOrder) -> Self {
        match v {
            FfiLocalSortOrder::ArtistAlbumTitle => Self::ArtistAlbumTitle,
            FfiLocalSortOrder::Title => Self::Title,
            FfiLocalSortOrder::DurationDesc => Self::DurationDesc,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiAdvanceReason {
    TrackEnded,
    UserSkip,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiPreviousResult {
    pub should_restart_current: bool,
    pub moved_entry: Option<FfiQueueEntry>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiCapabilityStatus {
    pub is_available: bool,
    pub requires_auth: bool,
    pub unavailable_reason: Option<String>,
}

// Global Core instance
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDailyListeningPoint {
    pub day_key: String,
    pub day_epoch: i64,
    pub play_count: u32,
    pub completed_count: u32,
    pub skip_count: u32,
    pub listened_ms: u64,
}

impl From<DailyListeningPoint> for FfiDailyListeningPoint {
    fn from(v: DailyListeningPoint) -> Self {
        Self {
            day_key: v.day_key,
            day_epoch: v.day_epoch,
            play_count: v.play_count,
            completed_count: v.completed_count,
            skip_count: v.skip_count,
            listened_ms: v.listened_ms,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiHourlyListeningBucket {
    pub hour: u32,
    pub play_count: u32,
    pub listened_ms: u64,
}

impl From<HourlyListeningBucket> for FfiHourlyListeningBucket {
    fn from(v: HourlyListeningBucket) -> Self {
        Self {
            hour: v.hour,
            play_count: v.play_count,
            listened_ms: v.listened_ms,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiArtistStatSummary {
    pub artist: String,
    pub play_count: u32,
    pub total_listened_ms: u64,
    pub unique_tracks: u32,
    pub artwork_url: Option<String>,
}

impl From<ArtistStatSummary> for FfiArtistStatSummary {
    fn from(v: ArtistStatSummary) -> Self {
        Self {
            artist: v.artist,
            play_count: v.play_count,
            total_listened_ms: v.total_listened_ms,
            unique_tracks: v.unique_tracks,
            artwork_url: v.artwork_url,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAlbumStatSummary {
    pub album: String,
    pub artist: String,
    pub play_count: u32,
    pub total_listened_ms: u64,
    pub unique_tracks: u32,
    pub artwork_url: Option<String>,
}

impl From<AlbumStatSummary> for FfiAlbumStatSummary {
    fn from(v: AlbumStatSummary) -> Self {
        Self {
            album: v.album,
            artist: v.artist,
            play_count: v.play_count,
            total_listened_ms: v.total_listened_ms,
            unique_tracks: v.unique_tracks,
            artwork_url: v.artwork_url,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiListeningAnalytics {
    pub period_id: String,
    pub total_listened_ms: u64,
    pub total_plays: u32,
    pub unique_tracks: u32,
    pub unique_artists: u32,
    pub unique_albums: u32,
    pub current_streak_days: u32,
    pub longest_streak_days: u32,
    pub daily_average_ms: u64,
    pub peak_day_key: Option<String>,
    pub peak_day_ms: u64,
    pub completed_plays: u32,
    pub skipped_plays: u32,
    pub completion_rate_pct: u32,
    pub listening_persona: String,
    pub peak_hour: u32,
    pub top_track_title: Option<String>,
    pub top_track_artist: Option<String>,
    pub top_track_plays: u32,
    pub top_artist_name: Option<String>,
    pub top_artist_plays: u32,
    pub top_album_name: Option<String>,
    pub top_album_plays: u32,
    pub daily_points: Vec<FfiDailyListeningPoint>,
    pub hourly_buckets: Vec<FfiHourlyListeningBucket>,
    pub top_tracks: Vec<FfiTrackListeningStat>,
    pub top_artists: Vec<FfiArtistStatSummary>,
    pub top_albums: Vec<FfiAlbumStatSummary>,
    pub forgotten_favorites: Vec<FfiTrackListeningStat>,
}

impl From<ListeningAnalyticsSummary> for FfiListeningAnalytics {
    fn from(v: ListeningAnalyticsSummary) -> Self {
        Self {
            period_id: v.period_id,
            total_listened_ms: v.total_listened_ms,
            total_plays: v.total_plays,
            unique_tracks: v.unique_tracks,
            unique_artists: v.unique_artists,
            unique_albums: v.unique_albums,
            current_streak_days: v.current_streak_days,
            longest_streak_days: v.longest_streak_days,
            daily_average_ms: v.daily_average_ms,
            peak_day_key: v.peak_day_key,
            peak_day_ms: v.peak_day_ms,
            completed_plays: v.completed_plays,
            skipped_plays: v.skipped_plays,
            completion_rate_pct: v.completion_rate_pct,
            listening_persona: v.listening_persona,
            peak_hour: v.peak_hour,
            top_track_title: v.top_track_title,
            top_track_artist: v.top_track_artist,
            top_track_plays: v.top_track_plays,
            top_artist_name: v.top_artist_name,
            top_artist_plays: v.top_artist_plays,
            top_album_name: v.top_album_name,
            top_album_plays: v.top_album_plays,
            daily_points: v.daily_points.into_iter().map(Into::into).collect(),
            hourly_buckets: v.hourly_buckets.into_iter().map(Into::into).collect(),
            top_tracks: v.top_tracks.into_iter().map(Into::into).collect(),
            top_artists: v.top_artists.into_iter().map(Into::into).collect(),
            top_albums: v.top_albums.into_iter().map(Into::into).collect(),
            forgotten_favorites: v.forgotten_favorites.into_iter().map(Into::into).collect(),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiImportBackupReport {
    pub imported_tracks: u32,
    pub imported_history: u32,
    pub imported_days: u32,
    pub imported_themes: u32,
}

impl From<ImportBackupReport> for FfiImportBackupReport {
    fn from(v: ImportBackupReport) -> Self {
        Self {
            imported_tracks: v.imported_tracks,
            imported_history: v.imported_history,
            imported_days: v.imported_days,
            imported_themes: v.imported_themes,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiPrivateSessionStatus {
    pub active: bool,
    pub until_epoch_ms: u64, // 0 = indefinite while active
}

#[derive(uniffi::Object)]
pub struct PlasmaWaveCore {
    queue: Mutex<Queue>,
    database: Arc<Database>,
    library_service: LibraryService,
    settings_service: SettingsService,
    theme_registry: ThemeRegistry,
    mod_manager: ModManager,
    local_provider: LocalProvider,
    deezer_provider: DeezerProvider,
    dev_provider: DevProvider,
    download_manager: Arc<DownloadManager>,
    lyrics_client: core_lyrics::LyricsClient,
    artwork_client: provider_deezer::artwork::ArtworkClient,
    updater_client: core_updater::UpdaterClient,
    active_theme_id: RwLock<String>,
    tokio_runtime: tokio::runtime::Runtime,
    private_session_until_ms: Mutex<Option<u64>>,
    local_tz_offset_ms: Mutex<i64>,
    local_tracks_loaded: std::sync::Once,
}

impl PlasmaWaveCore {
    fn ensure_local_tracks_loaded(&self) {
        self.local_tracks_loaded.call_once(|| {
            if let Ok(tracks) = self.database.tracks_by_provider(&ProviderId::local()) {
                self.local_provider.load(tracks);
            }
        });
    }
}

#[uniffi::export]
impl PlasmaWaveCore {
    #[uniffi::constructor]
    pub fn new(storage_dir: String) -> FfiResult<Arc<Self>> {
        let path = PathBuf::from(&storage_dir);
        let _ = std::fs::create_dir_all(&path);
        let db_path = path.join("plasmawave.db");
        let dl_dir = path.join("downloads");

        let database = Arc::new(Database::open(&db_path)?);
        let library_service = LibraryService::new(database.clone());
        let settings_service = SettingsService::new(database.clone());
        let theme_registry = ThemeRegistry::new();
        if let Ok(custom_themes) = database.list_user_custom_themes() {
            for spec in custom_themes {
                if let Ok(manifest) = spec.to_manifest() {
                    let _ = theme_registry.register(manifest);
                }
            }
        }
        let mod_manager = ModManager::new();
        let local_provider = LocalProvider::new();
        let deezer_provider = DeezerProvider::new(DeezerAppConfig::default())?;
        let dev_provider = DevProvider::new();

        let dl_cfg = ManagerConfig::new(dl_dir);
        let download_manager = Arc::new(DownloadManager::new(database.clone(), dl_cfg)?);

        let initial_settings = settings_service.load()?;
        let active_theme_id = RwLock::new(initial_settings.theme_id);

        let tokio_runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| FfiError::OperationFailed {
                reason: format!("tokio init error: {e}"),
            })?;

        Ok(Arc::new(Self {
            queue: Mutex::new(Queue::default()),
            database,
            library_service,
            settings_service,
            theme_registry,
            mod_manager,
            local_provider,
            deezer_provider,
            dev_provider,
            download_manager,
            lyrics_client: core_lyrics::LyricsClient::new(),
            artwork_client: provider_deezer::artwork::ArtworkClient::new(),
            updater_client: core_updater::UpdaterClient::new(),
            active_theme_id,
            tokio_runtime,
            private_session_until_ms: Mutex::new(None),
            local_tz_offset_ms: Mutex::new(3 * 3_600_000),
            local_tracks_loaded: std::sync::Once::new(),
        }))
    }

    pub fn get_version(&self) -> String {
        "PlasmaWave 0.1.0-alpha (Rust Core + KDE Breeze)".into()
    }

    // ------------------------------------------------------------------------
    // Lyrics (LRCLIB + SQLite Cache)
    // ------------------------------------------------------------------------
    pub fn fetch_lyrics(
        &self,
        title: String,
        artist: String,
        album: Option<String>,
        duration_ms: u64,
        force_refresh: bool,
    ) -> FfiResult<FfiLyricsResult> {
        let cache_key = core_lyrics::LyricsClient::cache_key(&title, &artist);
        if !force_refresh {
            if let Ok(Some(cached_json)) = self.database.get_cached_lyrics(&cache_key) {
                if let Ok(parsed) = serde_json::from_str::<core_lyrics::LyricsResult>(&cached_json)
                {
                    return Ok(FfiLyricsResult::from(parsed));
                }
            }
        }

        let result = self
            .tokio_runtime
            .block_on(self.lyrics_client.fetch_lyrics(
                &title,
                &artist,
                album.as_deref(),
                duration_ms,
            ))
            .map_err(|e| FfiError::OperationFailed {
                reason: e.to_string(),
            })?;

        if let Ok(json) = serde_json::to_string(&result) {
            let _ = self
                .database
                .set_cached_lyrics(&cache_key, &json, now_epoch_ms());
        }

        Ok(FfiLyricsResult::from(result))
    }

    pub fn parse_lrc(&self, raw: String) -> Vec<FfiLyricLine> {
        core_lyrics::parse_lrc(&raw)
            .into_iter()
            .map(|l| FfiLyricLine {
                time_ms: l.time_ms,
                text: l.text,
            })
            .collect()
    }

    // ------------------------------------------------------------------------
    // Artwork Resolution (Deezer + iTunes + Bounded SQLite Cache)
    // ------------------------------------------------------------------------
    pub fn resolve_artwork(
        &self,
        title: String,
        artist: Option<String>,
    ) -> FfiResult<Option<String>> {
        let Some(key) = provider_deezer::artwork::cache_key(&title, artist.as_deref()) else {
            return Ok(None);
        };
        if let Ok(Some(cached)) = self.database.get_cached_artwork(&key) {
            return Ok(Some(cached));
        }

        let resolved = self
            .tokio_runtime
            .block_on(self.artwork_client.resolve(&title, artist.as_deref()));

        if let Some(ref url) = resolved {
            let _ = self
                .database
                .set_cached_artwork(&key, url, now_epoch_ms());
        }
        Ok(resolved)
    }

    pub fn list_cached_artwork(
        &self,
        limit: u32,
    ) -> FfiResult<std::collections::HashMap<String, String>> {
        let pairs = self.database.list_cached_artwork(limit)?;
        Ok(pairs.into_iter().collect())
    }

    // ------------------------------------------------------------------------
    // App Updater (GitHub Releases + Semver + Trusted URL Validation)
    // ------------------------------------------------------------------------
    pub fn check_app_update(
        &self,
        current_version: String,
    ) -> FfiResult<Option<FfiAppUpdateRelease>> {
        let release = self
            .tokio_runtime
            .block_on(self.updater_client.fetch_latest_release(&current_version))
            .map_err(|e| FfiError::OperationFailed {
                reason: e.to_string(),
            })?;
        Ok(release.map(FfiAppUpdateRelease::from))
    }

    pub fn is_trusted_update_url(&self, url: String) -> bool {
        core_updater::is_trusted_download_url(&url)
    }

    // ------------------------------------------------------------------------
    // App KV Preferences (Rust SQLite replacement for SharedPreferences)
    // ------------------------------------------------------------------------
    pub fn get_pref(&self, key: String) -> FfiResult<Option<String>> {
        Ok(self.database.get_kv(&key)?)
    }

    pub fn set_pref(&self, key: String, value: String) -> FfiResult<()> {
        Ok(self.database.set_kv(&key, &value)?)
    }

    pub fn remove_pref(&self, key: String) -> FfiResult<()> {
        Ok(self.database.remove_kv(&key)?)
    }

    pub fn get_prefs_by_prefix(
        &self,
        prefix: String,
    ) -> FfiResult<std::collections::HashMap<String, String>> {
        let pairs = self.database.get_kv_by_prefix(&prefix)?;
        Ok(pairs.into_iter().collect())
    }

    pub fn set_prefs_batch(
        &self,
        entries: std::collections::HashMap<String, String>,
    ) -> FfiResult<()> {
        let pairs: Vec<(String, String)> = entries.into_iter().collect();
        Ok(self.database.set_kv_batch(&pairs)?)
    }

    // ------------------------------------------------------------------------
    // Deezer Blowfish/MD5 Stripe Decryption
    // ------------------------------------------------------------------------
    pub fn derive_deezer_track_key(&self, track_id: String) -> Vec<u8> {
        provider_deezer::crypto::derive_track_key(&track_id).to_vec()
    }

    pub fn decrypt_deezer_chunk(&self, key: Vec<u8>, chunk_index: u64, data: Vec<u8>) -> Vec<u8> {
        provider_deezer::crypto::decrypt_stripe_bytes(&key, chunk_index, data)
    }

    // ------------------------------------------------------------------------
    // Full Downloads Queue (Rust SQLite)
    // ------------------------------------------------------------------------
    pub fn load_full_downloads(&self) -> FfiResult<Vec<FfiSavedDownload>> {
        let items = self.database.load_full_downloads()?;
        Ok(items.into_iter().map(FfiSavedDownload::from).collect())
    }

    pub fn insert_full_downloads(&self, items: Vec<FfiSavedDownload>) -> FfiResult<()> {
        let core_items: Vec<core_storage::StoredSavedDownload> =
            items.into_iter().map(Into::into).collect();
        Ok(self.database.insert_full_downloads(&core_items)?)
    }

    pub fn update_full_download(&self, item: FfiSavedDownload) -> FfiResult<()> {
        let core_item = core_storage::StoredSavedDownload::from(item);
        Ok(self.database.update_full_download(&core_item)?)
    }

    pub fn remove_full_download(&self, id: String) -> FfiResult<()> {
        Ok(self.database.remove_full_download(&id)?)
    }

    pub fn cancel_full_downloads(&self, ids: Vec<String>) -> FfiResult<()> {
        Ok(self.database.cancel_full_downloads(&ids)?)
    }

    // ------------------------------------------------------------------------
    // Jam / MQTT 3.1.1 Codec & Protocol Helpers
    // ------------------------------------------------------------------------
    pub fn jam_constant_time_eq(&self, expected: String, provided: String) -> bool {
        core_jam::constant_time_eq(&expected, &provided)
    }

    pub fn mqtt_encode_connect(&self, client_id: String, keep_alive_secs: u16) -> Vec<u8> {
        core_jam::encode_mqtt_connect(&client_id, keep_alive_secs)
    }

    pub fn mqtt_encode_subscribe(&self, packet_id: u16, topic_filter: String) -> Vec<u8> {
        core_jam::encode_mqtt_subscribe(packet_id, &topic_filter)
    }

    pub fn mqtt_encode_publish(&self, topic: String, payload: String, retain: bool) -> Vec<u8> {
        core_jam::encode_mqtt_publish(&topic, &payload, retain)
    }

    pub fn mqtt_encode_pingreq(&self) -> Vec<u8> {
        core_jam::encode_mqtt_pingreq()
    }

    pub fn mqtt_decode_publish(&self, header: u8, body: Vec<u8>) -> FfiResult<FfiMqttPublish> {
        let decoded = core_jam::decode_mqtt_publish_body(header, &body).map_err(|e| {
            FfiError::OperationFailed {
                reason: e.to_string(),
            }
        })?;
        Ok(FfiMqttPublish {
            topic: decoded.topic,
            payload: decoded.payload,
        })
    }

    // ------------------------------------------------------------------------
    // Provider & Capabilities
    // ------------------------------------------------------------------------
    pub fn get_providers(&self) -> Vec<FfiProviderInfo> {
        vec![
            self.provider_info(&self.local_provider),
            self.provider_info(&self.deezer_provider),
            self.provider_info(&self.dev_provider),
        ]
    }

    pub fn get_track_download_capability(&self, track_id_str: String) -> FfiCapabilityStatus {
        if let Ok(tid) = TrackId::parse(&track_id_str) {
            if let Ok(Some(path)) = self.download_manager.offline_path(&tid) {
                let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("saved");
                return FfiCapabilityStatus {
                    is_available: false,
                    requires_auth: false,
                    unavailable_reason: Some(format!("Downloaded: {name}")),
                };
            }
            if tid.provider().as_str() == ProviderId::LOCAL {
                return FfiCapabilityStatus {
                    is_available: false,
                    requires_auth: false,
                    unavailable_reason: Some("Already on this device".into()),
                };
            }
            FfiCapabilityStatus {
                is_available: true,
                requires_auth: false,
                unavailable_reason: None,
            }
        } else {
            FfiCapabilityStatus {
                is_available: false,
                requires_auth: false,
                unavailable_reason: Some("Invalid track ID".into()),
            }
        }
    }

    // ------------------------------------------------------------------------
    // Search & Catalog
    // ------------------------------------------------------------------------
    pub fn search_all(&self, query: String) -> FfiResult<Vec<FfiTrack>> {
        self.ensure_local_tracks_loaded();
        let q = query.trim();
        let mut results = Vec::new();

        // 1. Local tracks
        let local_matches = self.tokio_runtime.block_on(async {
            self.local_provider
                .search_tracks(q, PageRequest::first(25))
                .await
        })?;
        results.extend(local_matches.items.into_iter().map(FfiTrack::from));

        // 2. Dev fixtures (if enabled in settings)
        if let Ok(settings) = self.settings_service.load() {
            if settings.show_dev_fixtures {
                if let Ok(dev_matches) = self.tokio_runtime.block_on(async {
                    self.dev_provider
                        .search_tracks(q, PageRequest::first(25))
                        .await
                }) {
                    results.extend(dev_matches.items.into_iter().map(FfiTrack::from));
                }
            }
        }

        // 3. Deezer public search
        if !q.is_empty() {
            if let Ok(dz_matches) = self.tokio_runtime.block_on(async {
                self.deezer_provider
                    .search_tracks(q, PageRequest::first(25))
                    .await
            }) {
                results.extend(dz_matches.items.into_iter().map(FfiTrack::from));
            }
        }

        Ok(results)
    }

    pub fn get_deezer_chart(&self) -> FfiResult<Vec<FfiTrack>> {
        let page = self
            .tokio_runtime
            .block_on(async { self.deezer_provider.chart(PageRequest::first(30)).await })?;
        Ok(page.items.into_iter().map(FfiTrack::from).collect())
    }

    pub fn ingest_local_tracks(&self, inputs: Vec<FfiLocalTrackInput>) -> FfiResult<u32> {
        self.ensure_local_tracks_loaded();
        let core_inputs: Vec<LocalTrackInput> = inputs
            .into_iter()
            .map(|i| LocalTrackInput {
                uri: i.uri,
                title: i.title,
                artist: i.artist,
                album: i.album,
                duration_ms: i.duration_ms,
                artwork_uri: i.artwork_uri,
                file_name: i.file_name,
            })
            .collect();

        let (added, _) = self.local_provider.upsert(core_inputs);
        if !added.is_empty() {
            self.database.upsert_tracks(&added)?;
        }
        Ok(added.len() as u32)
    }

    pub fn get_local_tracks(&self) -> Vec<FfiTrack> {
        self.ensure_local_tracks_loaded();
        self.local_provider
            .all()
            .into_iter()
            .filter(|t| t.id.key().starts_with("content://"))
            .map(FfiTrack::from)
            .collect()
    }

    pub fn sync_local_tracks(
        &self,
        inputs: Vec<FfiLocalTrackInput>,
        min_duration_ms: u64,
    ) -> FfiResult<FfiLocalSyncStats> {
        self.ensure_local_tracks_loaded();
        let core_inputs: Vec<LocalTrackInput> = inputs
            .into_iter()
            .map(|i| LocalTrackInput {
                uri: i.uri,
                title: i.title,
                artist: i.artist,
                album: i.album,
                duration_ms: i.duration_ms,
                artwork_uri: i.artwork_uri,
                file_name: i.file_name,
            })
            .collect();

        let (changed, removed_ids, stats) = self.local_provider.sync(core_inputs, min_duration_ms);
        if !changed.is_empty() {
            self.database.upsert_tracks(&changed)?;
        }
        if !removed_ids.is_empty() {
            self.database.delete_tracks_batch(&removed_ids)?;
        }
        Ok(FfiLocalSyncStats::from(stats))
    }

    pub fn get_local_tracks_filtered(
        &self,
        sort: FfiLocalSortOrder,
        query: Option<String>,
    ) -> Vec<FfiTrack> {
        self.ensure_local_tracks_loaded();
        self.local_provider
            .query(sort.into(), query.as_deref())
            .into_iter()
            .filter(|t| t.id.key().starts_with("content://"))
            .map(FfiTrack::from)
            .collect()
    }

    // ------------------------------------------------------------------------
    // Queue Management
    // ------------------------------------------------------------------------
    pub fn queue_replace(
        &self,
        tracks: Vec<FfiTrack>,
        start_index: u32,
    ) -> FfiResult<Option<FfiQueueEntry>> {
        let core_tracks: Result<Vec<Track>, FfiError> =
            tracks.into_iter().map(Track::try_from).collect();
        let mut q = self.queue.lock().unwrap();
        let cur = q.replace(core_tracks?, start_index as usize);
        Ok(cur.map(|e| FfiQueueEntry {
            entry_id: e.entry_id,
            track: FfiTrack::from(e.track.clone()),
        }))
    }

    pub fn queue_play_next(&self, tracks: Vec<FfiTrack>) -> FfiResult<Vec<u64>> {
        let core_tracks: Result<Vec<Track>, FfiError> =
            tracks.into_iter().map(Track::try_from).collect();
        let mut q = self.queue.lock().unwrap();
        Ok(q.play_next(core_tracks?))
    }

    pub fn queue_add(&self, tracks: Vec<FfiTrack>) -> FfiResult<Vec<u64>> {
        let core_tracks: Result<Vec<Track>, FfiError> =
            tracks.into_iter().map(Track::try_from).collect();
        let mut q = self.queue.lock().unwrap();
        Ok(q.add_to_queue(core_tracks?))
    }

    pub fn queue_remove(&self, entry_id: u64) -> Option<bool> {
        let mut q = self.queue.lock().unwrap();
        q.remove(entry_id)
    }

    pub fn queue_move(&self, from_idx: u32, to_idx: u32) -> bool {
        let mut q = self.queue.lock().unwrap();
        q.move_entry(from_idx as usize, to_idx as usize)
    }

    pub fn queue_jump(&self, entry_id: u64) -> Option<FfiQueueEntry> {
        let mut q = self.queue.lock().unwrap();
        q.jump_to(entry_id).map(|e| FfiQueueEntry {
            entry_id: e.entry_id,
            track: FfiTrack::from(e.track.clone()),
        })
    }

    pub fn queue_advance(&self, reason: FfiAdvanceReason) -> Option<FfiQueueEntry> {
        let core_reason = match reason {
            FfiAdvanceReason::TrackEnded => AdvanceReason::TrackEnded,
            FfiAdvanceReason::UserSkip => AdvanceReason::UserSkip,
        };
        let mut q = self.queue.lock().unwrap();
        q.advance(core_reason).map(|e| FfiQueueEntry {
            entry_id: e.entry_id,
            track: FfiTrack::from(e.track.clone()),
        })
    }

    pub fn queue_previous(&self, position_ms: u64) -> FfiPreviousResult {
        let mut q = self.queue.lock().unwrap();
        match q.previous(position_ms) {
            PreviousOutcome::RestartCurrent => FfiPreviousResult {
                should_restart_current: true,
                moved_entry: None,
            },
            PreviousOutcome::Moved(e) => FfiPreviousResult {
                should_restart_current: false,
                moved_entry: Some(FfiQueueEntry {
                    entry_id: e.entry_id,
                    track: FfiTrack::from(e.track),
                }),
            },
            PreviousOutcome::Nothing => FfiPreviousResult {
                should_restart_current: false,
                moved_entry: None,
            },
        }
    }

    pub fn queue_set_repeat(&self, mode: FfiRepeatMode) {
        let mut q = self.queue.lock().unwrap();
        q.set_repeat(mode.into());
    }

    pub fn queue_set_shuffle(&self, enabled: bool) {
        let mut q = self.queue.lock().unwrap();
        q.set_shuffle(enabled);
    }

    pub fn get_queue_snapshot(&self) -> FfiQueueSnapshot {
        let q = self.queue.lock().unwrap();
        let snap = q.snapshot();
        FfiQueueSnapshot {
            entries: snap
                .entries
                .into_iter()
                .map(|e| FfiQueueEntry {
                    entry_id: e.entry_id,
                    track: FfiTrack::from(e.track),
                })
                .collect(),
            current_index: snap.current_index.map(|i| i as u32),
            repeat_mode: snap.repeat.into(),
            is_shuffled: snap.shuffle,
        }
    }

    // ------------------------------------------------------------------------
    // Favorites & Playlists
    // ------------------------------------------------------------------------
    pub fn toggle_favorite(&self, track: FfiTrack) -> FfiResult<bool> {
        let t = Track::try_from(track)?;
        Ok(self.library_service.toggle_favorite(&t)?)
    }

    pub fn is_favorite(&self, track_id_str: String) -> FfiResult<bool> {
        let tid = TrackId::parse(&track_id_str)?;
        Ok(self.library_service.is_favorite(&tid)?)
    }

    pub fn get_favorites(&self) -> FfiResult<Vec<FfiTrack>> {
        let tracks = self.library_service.favorites()?;
        Ok(tracks.into_iter().map(FfiTrack::from).collect())
    }

    pub fn create_playlist(&self, name: String) -> FfiResult<FfiPlaylist> {
        let pl = self.library_service.create_playlist(&name)?;
        Ok(FfiPlaylist::from(pl))
    }

    pub fn rename_playlist(&self, playlist_id: String, new_name: String) -> FfiResult<()> {
        let pid = PlaylistId::new(playlist_id)?;
        Ok(self.library_service.rename_playlist(&pid, &new_name)?)
    }

    pub fn delete_playlist(&self, playlist_id: String) -> FfiResult<()> {
        let pid = PlaylistId::new(playlist_id)?;
        Ok(self.library_service.delete_playlist(&pid)?)
    }

    pub fn get_playlists(&self) -> FfiResult<Vec<FfiPlaylist>> {
        let mut list: Vec<FfiPlaylist> = self
            .library_service
            .playlists()?
            .into_iter()
            .map(FfiPlaylist::from)
            .collect();

        // Include dev fixture playlists if enabled
        if let Ok(settings) = self.settings_service.load() {
            if settings.show_dev_fixtures {
                if let Ok(dev_pls) = self.tokio_runtime.block_on(async {
                    self.dev_provider
                        .user_playlists(PageRequest::first(10))
                        .await
                }) {
                    list.extend(dev_pls.items.into_iter().map(FfiPlaylist::from));
                }
            }
        }
        Ok(list)
    }

    pub fn get_playlist_tracks(&self, playlist_id: String) -> FfiResult<Vec<FfiTrack>> {
        let pid = PlaylistId::new(&playlist_id)?;
        let tracks = if playlist_id.starts_with("dev:") {
            let page = self.tokio_runtime.block_on(async {
                self.dev_provider
                    .playlist_tracks(&pid, PageRequest::first(50))
                    .await
            })?;
            page.items
        } else if playlist_id.starts_with("deezer:") {
            let page = self.tokio_runtime.block_on(async {
                self.deezer_provider
                    .playlist_tracks(&pid, PageRequest::first(50))
                    .await
            })?;
            page.items
        } else {
            self.library_service.playlist_tracks(&pid)?
        };
        Ok(tracks.into_iter().map(FfiTrack::from).collect())
    }

    pub fn add_tracks_to_playlist(
        &self,
        playlist_id: String,
        tracks: Vec<FfiTrack>,
    ) -> FfiResult<()> {
        let pid = PlaylistId::new(playlist_id)?;
        let core_tracks: Result<Vec<Track>, FfiError> =
            tracks.into_iter().map(Track::try_from).collect();
        Ok(self.library_service.add_to_playlist(&pid, &core_tracks?)?)
    }

    pub fn remove_track_from_playlist(&self, playlist_id: String, position: u32) -> FfiResult<()> {
        let pid = PlaylistId::new(playlist_id)?;
        Ok(self.library_service.remove_from_playlist(&pid, position)?)
    }

    pub fn remove_track_by_id_from_playlist(
        &self,
        playlist_id: String,
        track_id: String,
    ) -> FfiResult<bool> {
        let pid = PlaylistId::new(playlist_id)?;
        let tid = TrackId::parse(&track_id)?;
        Ok(self
            .library_service
            .remove_track_by_id_from_playlist(&pid, &tid)?)
    }

    pub fn update_playlist_membership_cache(
        &self,
        playlist_id: String,
        track_id: String,
        is_member: bool,
    ) -> FfiResult<()> {
        self.database
            .update_playlist_membership_cache(&playlist_id, &track_id, is_member)?;
        Ok(())
    }

    pub fn clear_playlist_membership_cache(&self, playlist_id: String) -> FfiResult<()> {
        self.database.clear_playlist_membership_cache(&playlist_id)?;
        Ok(())
    }

    pub fn replace_playlist_membership_cache(
        &self,
        playlist_id: String,
        track_ids: Vec<String>,
    ) -> FfiResult<()> {
        self.database
            .replace_playlist_membership_cache(&playlist_id, &track_ids)?;
        Ok(())
    }

    pub fn get_playlists_needing_membership_sync(
        &self,
        playlists: Vec<FfiPlaylist>,
    ) -> FfiResult<Vec<String>> {
        let pairs: Vec<(String, u32)> = playlists
            .into_iter()
            .map(|p| (p.id, p.track_count))
            .collect();
        Ok(self.database.get_playlists_needing_membership_sync(&pairs)?)
    }

    pub fn get_playlists_containing_track(&self, track_id: String) -> FfiResult<Vec<String>> {
        Ok(self.database.get_playlists_containing_track(&track_id)?)
    }

    pub fn get_uncached_playlist_ids(&self, playlist_ids: Vec<String>) -> FfiResult<Vec<String>> {
        Ok(self.database.get_uncached_playlist_ids(&playlist_ids)?)
    }

    pub fn get_playlist_picker_items(
        &self,
        playlists: Vec<FfiPlaylist>,
        track_id: String,
        track_provider: String,
        query: String,
    ) -> FfiResult<Vec<FfiPlaylistPickerItem>> {
        let member_ids: std::collections::HashSet<String> = self
            .database
            .get_playlists_containing_track(&track_id)?
            .into_iter()
            .collect();
        let trimmed_query = query.trim().to_lowercase();
        let mut seen = std::collections::HashSet::with_capacity(playlists.len());
        let mut items = Vec::with_capacity(playlists.len());

        for playlist in playlists {
            if !seen.insert(playlist.id.clone()) {
                continue;
            }
            if !trimmed_query.is_empty() && !playlist.name.to_lowercase().contains(&trimmed_query) {
                continue;
            }
            let is_member = member_ids.contains(&playlist.id);
            let is_compatible = playlist.is_local || track_provider == "deezer";
            items.push(FfiPlaylistPickerItem {
                playlist,
                is_member,
                is_compatible,
            });
        }

        items.sort_by(|a, b| b.is_compatible.cmp(&a.is_compatible));
        Ok(items)
    }

    // ------------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------------
    pub fn download_track(&self, track: FfiTrack) -> FfiResult<FfiDownloadTask> {
        let tid = TrackId::parse(&track.id)?;

        // If already downloaded and valid, return existing completed task
        if let Ok(Some(_)) = self.download_manager.offline_path(&tid) {
            if let Ok(Some(task)) = self.database.find_by_track(&tid) {
                return Ok(FfiDownloadTask::from(task));
            }
        }

        // Determine download URL (check playback_uri, or fetch preview URL from Deezer)
        let mut download_url = track.playback_uri.clone();
        if download_url.is_none() && tid.provider().as_str() == ProviderId::DEEZER {
            if let Ok(num_id) = tid.key().parse::<u64>() {
                if let Ok(dz_track) = self
                    .tokio_runtime
                    .block_on(async { self.deezer_provider.client().track(num_id).await })
                {
                    download_url = dz_track.playback_uri;
                }
            }
        }

        let url = download_url.ok_or_else(|| FfiError::OperationFailed {
            reason: "No stream available to download for this track".into(),
        })?;

        let safe_name = sanitize_file_name(&format!("{} - {}.mp3", track.artist, track.title))?;
        let auth = AuthorizedDownload {
            track_id: tid.clone(),
            url,
            file_name: safe_name,
            expected_size: None,
            expected_sha256: None,
            supports_resume: true,
        };

        let task = self.download_manager.enqueue(auth)?;
        let task_id = task.id;

        // Spawn background worker to download and register
        let dm = self.download_manager.clone();
        let db = self.database.clone();
        let lp = self.local_provider.clone();
        let tid_clone = tid.clone();
        let track_clone = track.clone();

        self.tokio_runtime.spawn(async move {
            if let Ok(finished) = dm.run(task_id).await {
                if finished.state == DownloadState::Completed {
                    let file_path = dm.completed_path(&finished);
                    let local_track = Track {
                        id: tid_clone,
                        title: track_clone.title,
                        artist: track_clone.artist,
                        album: track_clone.album,
                        duration_ms: track_clone.duration_ms,
                        artwork_url: track_clone.artwork_url,
                        playback_uri: Some(format!("file://{}", file_path.to_string_lossy())),
                        is_preview: track_clone.is_preview,
                        origin: DataOrigin::Real,
                    };
                    let _ = db.upsert_tracks(&[local_track.clone()]);
                    lp.load(vec![local_track]);
                }
            }
        });

        Ok(FfiDownloadTask::from(task))
    }

    pub fn enqueue_authorized_download(
        &self,
        url: String,
        sha256: Option<String>,
    ) -> FfiResult<FfiDownloadTask> {
        let auth = user_supplied_download(&url, sha256)?;
        let task = self.download_manager.enqueue(auth)?;
        let dm = self.download_manager.clone();
        let task_id = task.id;
        self.tokio_runtime.spawn(async move {
            let _ = dm.run(task_id).await;
        });
        Ok(FfiDownloadTask::from(task))
    }

    pub fn list_downloads(&self) -> FfiResult<Vec<FfiDownloadTask>> {
        let tasks = self.download_manager.list()?;
        Ok(tasks.into_iter().map(FfiDownloadTask::from).collect())
    }

    pub fn get_downloaded_tracks(&self) -> FfiResult<Vec<FfiTrack>> {
        let tasks = self.download_manager.list()?;
        let mut results = Vec::new();
        for t in tasks {
            if t.state == DownloadState::Completed {
                if let Ok(Some(path)) = self.download_manager.offline_path(&t.track_id) {
                    if let Ok(Some(track)) = self.database.track(&t.track_id) {
                        let mut ffi = FfiTrack::from(track);
                        ffi.playback_uri = Some(format!("file://{}", path.to_string_lossy()));
                        results.push(ffi);
                    }
                }
            }
        }
        Ok(results)
    }

    pub fn pause_download(&self, id: i64) -> FfiResult<FfiDownloadTask> {
        let t = self.download_manager.pause(id)?;
        Ok(FfiDownloadTask::from(t))
    }

    pub fn resume_download(&self, id: i64) -> FfiResult<FfiDownloadTask> {
        let t = self.download_manager.resume(id)?;
        let dm = self.download_manager.clone();
        let db = self.database.clone();
        let lp = self.local_provider.clone();
        self.tokio_runtime.spawn(async move {
            if let Ok(finished) = dm.run(id).await {
                if finished.state == DownloadState::Completed {
                    let file_path = dm.completed_path(&finished);
                    if let Ok(Some(existing_track)) = db.track(&finished.track_id) {
                        let mut local_track = existing_track;
                        local_track.playback_uri =
                            Some(format!("file://{}", file_path.to_string_lossy()));
                        let _ = db.upsert_tracks(&[local_track.clone()]);
                        lp.load(vec![local_track]);
                    }
                }
            }
        });
        Ok(FfiDownloadTask::from(t))
    }

    pub fn cancel_download(&self, id: i64) -> FfiResult<FfiDownloadTask> {
        let t = self.download_manager.cancel(id)?;
        Ok(FfiDownloadTask::from(t))
    }

    pub fn retry_download(&self, id: i64) -> FfiResult<FfiDownloadTask> {
        let t = self.download_manager.retry(id)?;
        let dm = self.download_manager.clone();
        let db = self.database.clone();
        let lp = self.local_provider.clone();
        self.tokio_runtime.spawn(async move {
            if let Ok(finished) = dm.run(id).await {
                if finished.state == DownloadState::Completed {
                    let file_path = dm.completed_path(&finished);
                    if let Ok(Some(existing_track)) = db.track(&finished.track_id) {
                        let mut local_track = existing_track;
                        local_track.playback_uri =
                            Some(format!("file://{}", file_path.to_string_lossy()));
                        let _ = db.upsert_tracks(&[local_track.clone()]);
                        lp.load(vec![local_track]);
                    }
                }
            }
        });
        Ok(FfiDownloadTask::from(t))
    }

    pub fn remove_download(&self, id: i64) -> FfiResult<()> {
        Ok(self.download_manager.remove(id)?)
    }

    pub fn get_offline_path(&self, track_id_str: String) -> FfiResult<Option<String>> {
        let tid = TrackId::parse(&track_id_str)?;
        let path = self.download_manager.offline_path(&tid)?;
        Ok(path.map(|p| p.to_string_lossy().into_owned()))
    }

    // ------------------------------------------------------------------------
    // Themes
    // ------------------------------------------------------------------------
    pub fn get_current_theme(&self) -> FfiTheme {
        let id = self.active_theme_id.read().unwrap();
        let mut theme = FfiTheme::from(self.theme_registry.resolve_or_default(&id));
        if let Ok(settings) = self.settings_service.load() {
            if let Some(custom_accent) = settings.custom_accent_color {
                theme.accent_color = custom_accent;
            }
        }
        theme
    }

    pub fn list_themes(&self) -> Vec<FfiTheme> {
        self.theme_registry
            .list()
            .into_iter()
            .map(FfiTheme::from)
            .collect()
    }

    pub fn set_theme(&self, theme_id: String) -> FfiResult<FfiTheme> {
        let theme = self.theme_registry.resolve_or_default(&theme_id);
        *self.active_theme_id.write().unwrap() = theme.id.clone();
        self.settings_service
            .update(|s| s.theme_id = theme.id.clone())?;
        Ok(self.get_current_theme())
    }

    pub fn set_custom_accent_color(&self, hex_color: Option<String>) -> FfiResult<FfiTheme> {
        let normalized = match hex_color {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() {
                    None
                } else {
                    let with_hash = if trimmed.starts_with('#') {
                        trimmed.to_uppercase()
                    } else {
                        format!("#{}", trimmed.to_uppercase())
                    };
                    if !core_settings::is_valid_hex_color(&with_hash) {
                        return Err(FfiError::OperationFailed {
                            reason: "Invalid hex color. Expected #RRGGBB (e.g. #FF4081)".into(),
                        });
                    }
                    Some(with_hash)
                }
            }
            None => None,
        };

        self.settings_service
            .update(|s| s.custom_accent_color = normalized.clone())?;
        Ok(self.get_current_theme())
    }

    pub fn import_theme_json(&self, json_str: String) -> FfiResult<FfiTheme> {
        let manifest = self.theme_registry.import_json(&json_str)?;
        Ok(FfiTheme::from(manifest))
    }

    // ------------------------------------------------------------------------
    // Mods (WebAssembly sandboxed)
    // ------------------------------------------------------------------------
    pub fn list_mods(&self) -> Vec<FfiModInfo> {
        self.mod_manager
            .list()
            .into_iter()
            .map(|m| FfiModInfo {
                id: m.manifest.id,
                name: m.manifest.name,
                version: m.manifest.version,
                author: m.manifest.author,
                description: m.manifest.description,
                is_enabled: m.state == ModState::Enabled,
                is_builtin: m.is_builtin,
                has_custom_track_menu: m
                    .manifest
                    .permissions
                    .contains(&ModPermission::CustomTrackMenu),
                last_error: m.last_error,
            })
            .collect()
    }

    pub fn set_mod_enabled(&self, mod_id: String, enabled: bool) -> FfiResult<()> {
        Ok(self.mod_manager.set_enabled(&mod_id, enabled)?)
    }

    pub fn get_custom_track_actions(&self) -> Vec<FfiModInfo> {
        self.mod_manager
            .get_track_menu_actions()
            .into_iter()
            .map(|m| FfiModInfo {
                id: m.manifest.id,
                name: m.manifest.name,
                version: m.manifest.version,
                author: m.manifest.author,
                description: m.manifest.description,
                is_enabled: m.state == ModState::Enabled,
                is_builtin: m.is_builtin,
                has_custom_track_menu: true,
                last_error: m.last_error,
            })
            .collect()
    }

    pub fn execute_track_mod_action(
        &self,
        mod_id: String,
        track_id_str: String,
    ) -> FfiResult<String> {
        let input = format!(r#"{{"trackId":"{track_id_str}"}}"#);
        let res =
            self.mod_manager
                .execute_action(&mod_id, ModPermission::CustomTrackMenu, &input)?;

        if res.success {
            Ok(res
                .output_message
                .unwrap_or_else(|| "Mod executed successfully".into()))
        } else {
            Err(FfiError::OperationFailed {
                reason: res.error.unwrap_or_else(|| "Mod trapped or failed".into()),
            })
        }
    }

    // ------------------------------------------------------------------------
    // Deezer Account & Extended Catalog (Refreezer-Style Features & Direct Auth)
    // ------------------------------------------------------------------------
    pub fn get_deezer_auth_url(&self, app_id: Option<String>) -> String {
        let aid = app_id.unwrap_or_else(|| "524482".into());
        format!(
            "https://connect.deezer.com/oauth/auth.php?app_id={aid}&redirect_uri=plasmawave://auth/deezer&response_type=token&perms=basic_access,email,offline_access,manage_library,listening_history"
        )
    }

    pub fn set_deezer_access_token(&self, token_input: String) -> FfiResult<FfiDeezerUser> {
        let mut token = token_input.trim();
        if let Some(idx) = token.find("access_token=") {
            let after = &token[idx + "access_token=".len()..];
            token = after.split('&').next().unwrap_or(after);
        }

        if token.is_empty() {
            return Err(FfiError::OperationFailed {
                reason: "Empty Deezer access token provided".into(),
            });
        }

        let user = self.tokio_runtime.block_on(async {
            self.deezer_provider.client().get_current_user(token).await
        })?;

        let is_prem = user.is_premium();
        let status_lbl = user.status_label();

        self.settings_service.update(|s| {
            s.deezer_access_token = Some(token.to_string());
            s.deezer_user_id = Some(user.id.to_string());
        })?;

        Ok(FfiDeezerUser {
            id: user.id.to_string(),
            name: user.name,
            email: user.email,
            status_label: status_lbl,
            is_premium: is_prem,
            link: user.link,
            picture_medium: user.picture_medium,
            nb_playlists: user.nb_playlists.unwrap_or(0),
            is_authenticated: true,
        })
    }

    pub fn set_deezer_user_id(&self, user_input: String) -> FfiResult<FfiDeezerUser> {
        let id_str = extract_numeric_id(&user_input).ok_or_else(|| FfiError::OperationFailed {
            reason: "Invalid Deezer User ID or URL. Please provide a numeric ID or deezer.com/profile URL.".into(),
        })?;

        let user = self.tokio_runtime.block_on(async {
            self.deezer_provider.client().get_user(&id_str).await
        })?;

        let is_prem = user.is_premium();
        let status_lbl = if is_prem { user.status_label() } else { "Public Profile".to_string() };

        self.settings_service.update(|s| {
            s.deezer_user_id = Some(id_str.clone());
            s.deezer_access_token = None;
        })?;

        Ok(FfiDeezerUser {
            id: user.id.to_string(),
            name: user.name,
            email: user.email,
            status_label: status_lbl,
            is_premium: is_prem,
            link: user.link,
            picture_medium: user.picture_medium,
            nb_playlists: user.nb_playlists.unwrap_or(0),
            is_authenticated: false,
        })
    }

    pub fn simulate_deezer_premium_login(&self) -> FfiResult<FfiDeezerUser> {
        self.settings_service.update(|s| {
            s.deezer_access_token = Some("demo_premium_token".into());
            s.deezer_user_id = Some("5".into());
        })?;

        Ok(FfiDeezerUser {
            id: "5".into(),
            name: "PlasmaWave Premium".into(),
            email: Some("premium@deezer.plasmawave".into()),
            status_label: "Deezer Premium (HiFi)".into(),
            is_premium: true,
            link: Some("https://www.deezer.com/profile/5".into()),
            picture_medium: Some("https://e-cdns-images.dzcdn.net/images/user/250x250-000000-80-0-0.jpg".into()),
            nb_playlists: 14,
            is_authenticated: true,
        })
    }

    pub fn get_deezer_user(&self) -> FfiResult<Option<FfiDeezerUser>> {
        let settings = self.settings_service.load()?;

        if let Some(token) = &settings.deezer_access_token {
            if token == "demo_premium_token" {
                return Ok(Some(FfiDeezerUser {
                    id: "5".into(),
                    name: "PlasmaWave Premium".into(),
                    email: Some("premium@deezer.plasmawave".into()),
                    status_label: "Deezer Premium (HiFi)".into(),
                    is_premium: true,
                    link: Some("https://www.deezer.com/profile/5".into()),
                    picture_medium: Some("https://e-cdns-images.dzcdn.net/images/user/250x250-000000-80-0-0.jpg".into()),
                    nb_playlists: 14,
                    is_authenticated: true,
                }));
            }
            if let Ok(user) = self.tokio_runtime.block_on(async {
                self.deezer_provider.client().get_current_user(token).await
            }) {
                let is_prem = user.is_premium();
                let status_lbl = user.status_label();
                return Ok(Some(FfiDeezerUser {
                    id: user.id.to_string(),
                    name: user.name,
                    email: user.email,
                    status_label: status_lbl,
                    is_premium: is_prem,
                    link: user.link,
                    picture_medium: user.picture_medium,
                    nb_playlists: user.nb_playlists.unwrap_or(0),
                    is_authenticated: true,
                }));
            }
        }

        let Some(uid) = settings.deezer_user_id else {
            return Ok(None);
        };

        let user = self.tokio_runtime.block_on(async {
            self.deezer_provider.client().get_user(&uid).await
        })?;

        let is_prem = user.is_premium();
        let status_lbl = if is_prem { user.status_label() } else { "Public Profile".to_string() };

        Ok(Some(FfiDeezerUser {
            id: user.id.to_string(),
            name: user.name,
            email: user.email,
            status_label: status_lbl,
            is_premium: is_prem,
            link: user.link,
            picture_medium: user.picture_medium,
            nb_playlists: user.nb_playlists.unwrap_or(0),
            is_authenticated: false,
        }))
    }

    pub fn clear_deezer_user(&self) -> FfiResult<()> {
        self.settings_service.update(|s| {
            s.deezer_user_id = None;
            s.deezer_access_token = None;
        })?;
        Ok(())
    }

    pub fn get_deezer_user_playlists(&self, page: u32) -> FfiResult<Vec<FfiPlaylist>> {
        let settings = self.settings_service.load()?;
        let req = PageRequest::new(page.saturating_mul(25), 25);

        if let Some(token) = &settings.deezer_access_token {
            if token == "demo_premium_token" {
                return Ok(vec![
                    FfiPlaylist {
                        id: "deezer:loved_tracks".into(),
                        name: "Sevdiğim Şarkılar (Loved Tracks)".into(),
                        description: Some("Tüm favori şarkılarınız".into()),
                        is_local: false,
                        track_count: 50,
                        artwork_url: Some("https://e-cdns-images.dzcdn.net/images/cover/d41d8cd98f00b204e9800998ecf8427e/250x250-000000-80-0-0.jpg".into()),
                        origin: "public_catalog".into(),
                    },
                    FfiPlaylist {
                        id: "deezer:flow".into(),
                        name: "Deezer Flow (Kişisel Akış)".into(),
                        description: Some("Zevkinize özel sonsuz müzik akışı".into()),
                        is_local: false,
                        track_count: 40,
                        artwork_url: Some("https://e-cdns-images.dzcdn.net/images/cover/250x250-000000-80-0-0.jpg".into()),
                        origin: "public_catalog".into(),
                    },
                ]);
            }
            if let Ok(p) = self.tokio_runtime.block_on(async {
                self.deezer_provider.client().get_my_playlists(token, req).await
            }) {
                return Ok(p.items.into_iter().map(FfiPlaylist::from).collect());
            }
        }

        let Some(uid) = settings.deezer_user_id else {
            return Ok(Vec::new());
        };
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .get_user_playlists(&uid, req)
                .await
        })?;
        Ok(p.items.into_iter().map(FfiPlaylist::from).collect())
    }

    pub fn get_deezer_user_favorites(&self, page: u32) -> FfiResult<Vec<FfiTrack>> {
        let settings = self.settings_service.load()?;
        let req = PageRequest::new(page.saturating_mul(25), 25);

        if let Some(token) = &settings.deezer_access_token {
            if token == "demo_premium_token" {
                let chart = self.tokio_runtime.block_on(async {
                    self.deezer_provider.client().chart_tracks(req).await
                })?;
                return Ok(chart.items.into_iter().map(FfiTrack::from).collect());
            }
            if let Ok(p) = self.tokio_runtime.block_on(async {
                self.deezer_provider.client().get_my_favorites(token, req).await
            }) {
                return Ok(p.items.into_iter().map(FfiTrack::from).collect());
            }
        }

        let Some(uid) = settings.deezer_user_id else {
            return Ok(Vec::new());
        };
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .get_user_favorites(&uid, req)
                .await
        })?;
        Ok(p.items.into_iter().map(FfiTrack::from).collect())
    }

    pub fn get_deezer_flow(&self) -> FfiResult<Vec<FfiTrack>> {
        let settings = self.settings_service.load()?;

        if let Some(token) = &settings.deezer_access_token {
            if token == "demo_premium_token" {
                let chart = self.tokio_runtime.block_on(async {
                    self.deezer_provider.client().chart_tracks(PageRequest::new(0, 30)).await
                })?;
                return Ok(chart.items.into_iter().map(FfiTrack::from).collect());
            }
            if let Ok(tracks) = self.tokio_runtime.block_on(async {
                self.deezer_provider.client().get_my_flow(token).await
            }) {
                return Ok(tracks.into_iter().map(FfiTrack::from).collect());
            }
        }

        Ok(Vec::new())
    }

    pub fn get_deezer_album(&self, album_id: String) -> FfiResult<FfiAlbum> {
        let clean_id = album_id.strip_prefix("deezer:").unwrap_or(&album_id);
        let num: u64 = clean_id.parse().map_err(|_| FfiError::OperationFailed {
            reason: format!("Invalid album id `{album_id}`"),
        })?;
        let a = self.tokio_runtime.block_on(async {
            self.deezer_provider.client().get_album(num).await
        })?;
        Ok(FfiAlbum {
            id: format!("deezer:{}", a.id),
            title: a.title,
            artist_name: a.artist.map(|ar| ar.name).unwrap_or_default(),
            cover_url: a.cover_medium,
            release_date: a.release_date,
            nb_tracks: a.nb_tracks.unwrap_or(0),
        })
    }

    pub fn get_deezer_album_tracks(&self, album_id: String) -> FfiResult<Vec<FfiTrack>> {
        let clean_id = album_id.strip_prefix("deezer:").unwrap_or(&album_id);
        let num: u64 = clean_id.parse().map_err(|_| FfiError::OperationFailed {
            reason: format!("Invalid album id `{album_id}`"),
        })?;
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .get_album_tracks(num, PageRequest::first(100))
                .await
        })?;
        Ok(p.items.into_iter().map(FfiTrack::from).collect())
    }

    pub fn get_deezer_artist(&self, artist_id: String) -> FfiResult<FfiArtist> {
        let clean_id = artist_id.strip_prefix("deezer:").unwrap_or(&artist_id);
        let num: u64 = clean_id.parse().map_err(|_| FfiError::OperationFailed {
            reason: format!("Invalid artist id `{artist_id}`"),
        })?;
        let a = self.tokio_runtime.block_on(async {
            self.deezer_provider.client().get_artist(num).await
        })?;
        Ok(FfiArtist {
            id: format!("deezer:{}", a.id),
            name: a.name,
            picture_url: a.picture_medium,
            nb_albums: a.nb_album.unwrap_or(0),
            nb_fans: a.nb_fan.unwrap_or(0),
        })
    }

    pub fn get_deezer_artist_top_tracks(&self, artist_id: String) -> FfiResult<Vec<FfiTrack>> {
        let clean_id = artist_id.strip_prefix("deezer:").unwrap_or(&artist_id);
        let num: u64 = clean_id.parse().map_err(|_| FfiError::OperationFailed {
            reason: format!("Invalid artist id `{artist_id}`"),
        })?;
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .get_artist_top_tracks(num, PageRequest::first(50))
                .await
        })?;
        Ok(p.items.into_iter().map(FfiTrack::from).collect())
    }

    pub fn get_deezer_artist_albums(&self, artist_id: String) -> FfiResult<Vec<FfiAlbum>> {
        let clean_id = artist_id.strip_prefix("deezer:").unwrap_or(&artist_id);
        let num: u64 = clean_id.parse().map_err(|_| FfiError::OperationFailed {
            reason: format!("Invalid artist id `{artist_id}`"),
        })?;
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .get_artist_albums(num, PageRequest::first(50))
                .await
        })?;
        Ok(p.items
            .into_iter()
            .map(|a| FfiAlbum {
                id: a.id,
                title: a.title,
                artist_name: a.artist_name,
                cover_url: a.cover_url,
                release_date: None,
                nb_tracks: 0,
            })
            .collect())
    }

    pub fn get_deezer_playlist_tracks(&self, playlist_id: String) -> FfiResult<Vec<FfiTrack>> {
        let clean_id = playlist_id.strip_prefix("deezer:").unwrap_or(&playlist_id);
        let num: u64 = clean_id.parse().map_err(|_| FfiError::OperationFailed {
            reason: format!("Invalid playlist id `{playlist_id}`"),
        })?;
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .public_playlist_tracks(num, PageRequest::first(100))
                .await
        })?;
        Ok(p.items.into_iter().map(FfiTrack::from).collect())
    }

    pub fn search_deezer_tracks(&self, query: String) -> FfiResult<Vec<FfiTrack>> {
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .search_tracks(&query, PageRequest::first(30))
                .await
        })?;
        Ok(p.items.into_iter().map(FfiTrack::from).collect())
    }

    pub fn search_deezer_albums(&self, query: String) -> FfiResult<Vec<FfiAlbum>> {
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .search_albums(&query, PageRequest::first(30))
                .await
        })?;
        Ok(p.items
            .into_iter()
            .map(|a| FfiAlbum {
                id: a.id,
                title: a.title,
                artist_name: a.artist_name,
                cover_url: a.cover_url,
                release_date: None,
                nb_tracks: 0,
            })
            .collect())
    }

    pub fn search_deezer_artists(&self, query: String) -> FfiResult<Vec<FfiArtist>> {
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .search_artists(&query, PageRequest::first(30))
                .await
        })?;
        Ok(p.items
            .into_iter()
            .map(|a| FfiArtist {
                id: a.id,
                name: a.name,
                picture_url: a.picture_url,
                nb_albums: 0,
                nb_fans: 0,
            })
            .collect())
    }

    pub fn search_deezer_playlists(&self, query: String) -> FfiResult<Vec<FfiPlaylist>> {
        let p = self.tokio_runtime.block_on(async {
            self.deezer_provider
                .client()
                .search_playlists(&query, PageRequest::first(30))
                .await
        })?;
        Ok(p.items.into_iter().map(FfiPlaylist::from).collect())
    }

    // ------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------
    pub fn get_settings(&self) -> FfiResult<FfiSettings> {
        let s = self.settings_service.load()?;
        Ok(FfiSettings::from(s))
    }

    pub fn update_settings(&self, settings: FfiSettings) -> FfiResult<FfiSettings> {
        let updated = self.settings_service.update(|s| {
            s.theme_id = settings.theme_id.clone();
            s.language = Language::from_key(&settings.language).unwrap_or(Language::System);
            s.show_dev_fixtures = settings.show_dev_fixtures;
            s.downloads_wifi_only = settings.downloads_wifi_only;
            s.download_storage_limit_mib = settings.download_storage_limit_mib;
            s.mods_enabled = settings.mods_enabled;
            s.deezer_user_id = settings.deezer_user_id.clone();
            s.deezer_access_token = settings.deezer_access_token.clone();
            s.custom_accent_color = settings.custom_accent_color.clone();
            s.high_refresh_rate = settings.high_refresh_rate;
            s.dynamic_color_enabled = settings.dynamic_color_enabled;
            s.swipe_to_skip = settings.swipe_to_skip;
            s.seekbar_style = settings.seekbar_style.clone();
            s.album_frame = settings.album_frame.clone();
            s.header_logo_style = settings.header_logo_style.clone();
            s.home_feed_type = settings.home_feed_type.clone();
            s.flow_hero_card_enabled = settings.flow_hero_card_enabled;
            s.flow_quick_access_pinned = settings.flow_quick_access_pinned;
            s.flow_library_pinned = settings.flow_library_pinned;
            s.downloads_quick_access_pinned = settings.downloads_quick_access_pinned;
            s.downloads_library_pinned = settings.downloads_library_pinned;
            s.resume_playback_enabled = settings.resume_playback_enabled;
            s.collect_listening_stats = settings.collect_listening_stats;
            s.collect_listening_history = settings.collect_listening_history;
            s.save_resume_position = settings.save_resume_position;
            s.enabled_home_widgets = settings.enabled_home_widgets.clone();
            s.home_edit_hold_seconds = settings.home_edit_hold_seconds.clamp(3, 10);
            s.local_filter_short_tracks = settings.local_filter_short_tracks;
        })?;
        *self.active_theme_id.write().unwrap() = updated.theme_id.clone();
        Ok(FfiSettings::from(updated))
    }

    // ------------------------------------------------------------------------
    // Home Screen Layout & Widget Customization (Rust Single Source of Truth)
    // ------------------------------------------------------------------------
    pub fn get_home_layout(&self) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self.settings_service.load_home_layout()?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn get_home_widget_catalog(&self) -> Vec<FfiHomeWidgetCatalogEntry> {
        home_widget_catalog()
            .into_iter()
            .map(FfiHomeWidgetCatalogEntry::from)
            .collect()
    }

    pub fn add_home_widget(
        &self,
        kind: String,
        insert_at: Option<u32>,
    ) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self.settings_service.add_home_widget(&kind, insert_at)?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn remove_home_widget(&self, instance_id: String) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self.settings_service.remove_home_widget(&instance_id)?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn move_home_widget(
        &self,
        instance_id: String,
        to_index: u32,
    ) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self.settings_service.move_home_widget(&instance_id, to_index)?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn reorder_home_widgets(
        &self,
        ordered_instance_ids: Vec<String>,
    ) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self
            .settings_service
            .reorder_home_widgets(ordered_instance_ids)?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn customize_home_widget(
        &self,
        instance_id: String,
        patch: FfiHomeWidgetPatch,
    ) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self
            .settings_service
            .customize_home_widget(&instance_id, patch.into())?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn set_home_widget_kind_enabled(
        &self,
        kind: String,
        enabled: bool,
    ) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self
            .settings_service
            .set_home_widget_kind_enabled(&kind, enabled)?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn restore_home_layout(
        &self,
        items: Vec<FfiHomeWidgetItem>,
    ) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let restored = self
            .settings_service
            .save_home_layout(items.into_iter().map(HomeWidgetItem::from).collect())?;
        Ok(restored.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    pub fn reset_home_layout(&self) -> FfiResult<Vec<FfiHomeWidgetItem>> {
        let items = self.settings_service.reset_home_layout()?;
        Ok(items.into_iter().map(FfiHomeWidgetItem::from).collect())
    }

    // ------------------------------------------------------------------------
    // User Custom Themes (Rust Color Luminance/Contrast & SQLite Persistence)
    // ------------------------------------------------------------------------
    pub fn list_user_custom_themes(&self) -> FfiResult<Vec<FfiCustomThemeSpec>> {
        let list = self.database.list_user_custom_themes()?;
        Ok(list.into_iter().map(FfiCustomThemeSpec::from).collect())
    }

    pub fn save_user_custom_theme(&self, spec: FfiCustomThemeSpec) -> FfiResult<FfiTheme> {
        let core_spec = CustomThemeSpec::from(spec).normalized();
        let manifest = core_spec.to_manifest()?;
        self.database
            .save_user_custom_theme(&core_spec, now_epoch_ms())?;
        self.theme_registry.register(manifest.clone())?;
        *self.active_theme_id.write().unwrap() = manifest.id.clone();
        self.settings_service.update(|s| {
            s.theme_id = manifest.id.clone();
            s.custom_accent_color = Some(core_spec.accent_hex.clone());
        })?;
        Ok(self.get_current_theme())
    }

    pub fn delete_user_custom_theme(&self, theme_id: String) -> FfiResult<FfiTheme> {
        self.database.delete_user_custom_theme(&theme_id)?;
        let _ = self.theme_registry.remove(&theme_id);
        let current_id = self.active_theme_id.read().unwrap().clone();
        if current_id == theme_id {
            let fallback_id = "org.kde.breeze-dark".to_string();
            *self.active_theme_id.write().unwrap() = fallback_id.clone();
            let _ = self.settings_service.update(|s| {
                s.theme_id = fallback_id.clone();
                s.custom_accent_color = None;
            });
        }
        Ok(self.get_current_theme())
    }

    // ------------------------------------------------------------------------
    // Listening Statistics, History, Privacy & User Data Export
    // ------------------------------------------------------------------------
    pub fn set_local_tz_offset_ms(&self, offset_ms: i64) {
        if let Ok(mut guard) = self.local_tz_offset_ms.lock() {
            *guard = offset_ms.clamp(-14 * 3_600_000, 14 * 3_600_000);
        }
    }

    pub fn set_private_session(&self, enabled: bool, duration_ms: u64, now_ms: u64) -> FfiPrivateSessionStatus {
        let ts = if now_ms > 0 { now_ms } else { now_epoch_ms() };
        let next = if !enabled {
            None
        } else if duration_ms == 0 {
            Some(0)
        } else {
            Some(ts.saturating_add(duration_ms))
        };
        if let Ok(mut guard) = self.private_session_until_ms.lock() {
            *guard = next;
        }
        FfiPrivateSessionStatus {
            active: next.is_some(),
            until_epoch_ms: next.unwrap_or(0),
        }
    }

    pub fn get_private_session_status(&self, now_ms: u64) -> FfiPrivateSessionStatus {
        let ts = if now_ms > 0 { now_ms } else { now_epoch_ms() };
        let mut guard = match self.private_session_until_ms.lock() {
            Ok(g) => g,
            Err(_) => return FfiPrivateSessionStatus { active: false, until_epoch_ms: 0 },
        };
        match *guard {
            None => FfiPrivateSessionStatus { active: false, until_epoch_ms: 0 },
            Some(0) => FfiPrivateSessionStatus { active: true, until_epoch_ms: 0 },
            Some(until) if until > ts => FfiPrivateSessionStatus { active: true, until_epoch_ms: until },
            Some(_) => {
                *guard = None;
                FfiPrivateSessionStatus { active: false, until_epoch_ms: 0 }
            }
        }
    }

    pub fn record_track_play(
        &self,
        track: FfiTrack,
        collect_stats: bool,
        collect_history: bool,
        timestamp_ms: u64,
    ) -> FfiResult<()> {
        let ts = if timestamp_ms > 0 {
            timestamp_ms
        } else {
            now_epoch_ms()
        };
        if self.get_private_session_status(ts).active {
            return Ok(());
        }
        let stored = StoredTrackRecord::from(track);
        let tz = self.local_tz_offset_ms.lock().map(|g| *g).unwrap_or(0);
        if collect_stats {
            self.database.record_track_play_stat(&stored, ts)?;
            let _ = self.database.record_analytics_play(&stored, ts, tz);
        }
        if collect_history {
            self.database.record_history_entry(&stored, ts)?;
        }
        Ok(())
    }

    pub fn record_listening_tick(
        &self,
        track: FfiTrack,
        delta_ms: u64,
        collect_stats: bool,
        timestamp_ms: u64,
    ) -> FfiResult<u64> {
        let ts = if timestamp_ms > 0 {
            timestamp_ms
        } else {
            now_epoch_ms()
        };
        if !collect_stats || self.get_private_session_status(ts).active {
            return Ok(self.database.get_total_listening_time_ms()?);
        }
        let stored = StoredTrackRecord::from(track);
        let tz = self.local_tz_offset_ms.lock().map(|g| *g).unwrap_or(0);
        let total = self.database.record_listening_tick(&stored, delta_ms, ts)?;
        let _ = self.database.record_analytics_tick(&stored, delta_ms, ts, tz);
        Ok(total)
    }

    pub fn record_track_outcome(
        &self,
        completed: bool,
        collect_stats: bool,
        timestamp_ms: u64,
    ) -> FfiResult<()> {
        let ts = if timestamp_ms > 0 {
            timestamp_ms
        } else {
            now_epoch_ms()
        };
        if !collect_stats || self.get_private_session_status(ts).active {
            return Ok(());
        }
        let tz = self.local_tz_offset_ms.lock().map(|g| *g).unwrap_or(0);
        self.database.record_track_outcome(completed, ts, tz)?;
        Ok(())
    }

    pub fn get_listening_analytics(
        &self,
        period_id: String,
        sort_by: String,
        now_ms: u64,
    ) -> FfiResult<FfiListeningAnalytics> {
        let ts = if now_ms > 0 { now_ms } else { now_epoch_ms() };
        let tz = self.local_tz_offset_ms.lock().map(|g| *g).unwrap_or(0);
        let summary = self
            .database
            .get_listening_analytics(&period_id, &sort_by, ts, tz)?;
        Ok(summary.into())
    }

    pub fn build_listening_stats_csv(&self) -> FfiResult<String> {
        Ok(self.database.build_listening_stats_csv()?)
    }

    pub fn import_user_data_json(
        &self,
        raw_json: String,
        merge: bool,
    ) -> FfiResult<FfiImportBackupReport> {
        let report = self.database.import_user_data_json(&raw_json, merge)?;
        // Refresh custom themes in ThemeRegistry if any were imported
        if let Ok(custom_themes) = self.database.list_user_custom_themes() {
            for spec in custom_themes {
                if let Ok(manifest) = spec.to_manifest() {
                    let _ = self.theme_registry.register(manifest);
                }
            }
        }
        Ok(report.into())
    }

    pub fn clear_listening_history_only(&self) -> FfiResult<()> {
        self.database.clear_listening_history_only()?;
        Ok(())
    }

    pub fn clear_listening_stats_only(&self) -> FfiResult<()> {
        self.database.clear_listening_stats_only()?;
        Ok(())
    }

    pub fn clear_last_24h_data(&self, now_ms: u64) -> FfiResult<()> {
        let ts = if now_ms > 0 { now_ms } else { now_epoch_ms() };
        let tz = self.local_tz_offset_ms.lock().map(|g| *g).unwrap_or(0);
        self.database.clear_last_24h_data(ts, tz)?;
        Ok(())
    }

    pub fn delete_single_track_stat(&self, track_id: String) -> FfiResult<()> {
        self.database.delete_single_track_stat(&track_id)?;
        Ok(())
    }

    pub fn get_listening_stats(&self, limit: u32) -> FfiResult<Vec<FfiTrackListeningStat>> {
        let stats = self.database.get_listening_stats(limit.max(1))?;
        Ok(stats
            .into_iter()
            .map(FfiTrackListeningStat::from)
            .collect())
    }

    pub fn upsert_listening_stats_batch(&self, stats: Vec<FfiTrackListeningStat>) -> FfiResult<()> {
        let core_stats: Vec<StoredTrackListeningStat> = stats
            .into_iter()
            .map(StoredTrackListeningStat::from)
            .collect();
        self.database.upsert_listening_stats_batch(&core_stats)?;
        Ok(())
    }

    pub fn get_recent_tracks(&self, limit: u32) -> FfiResult<Vec<FfiTrack>> {
        let tracks = self.database.get_recent_tracks(limit.max(1))?;
        Ok(tracks.into_iter().map(FfiTrack::from).collect())
    }

    pub fn set_recent_tracks(&self, tracks: Vec<FfiTrack>) -> FfiResult<()> {
        let base_ts = now_epoch_ms();
        let total = tracks.len() as u64;
        let entries: Vec<StoredHistoryEntry> = tracks
            .into_iter()
            .enumerate()
            .map(|(idx, t)| StoredHistoryEntry {
                track: StoredTrackRecord::from(t),
                play_count: 1,
                played_at_ms: base_ts.saturating_sub((total - idx as u64) * 1000),
            })
            .collect();
        self.database.upsert_history_batch(&entries)?;
        Ok(())
    }

    pub fn get_most_played_tracks(&self, limit: u32) -> FfiResult<Vec<FfiTrack>> {
        let tracks = self.database.get_most_played_tracks(limit.max(1))?;
        Ok(tracks.into_iter().map(FfiTrack::from).collect())
    }

    pub fn get_total_listening_time_ms(&self) -> FfiResult<u64> {
        Ok(self.database.get_total_listening_time_ms()?)
    }

    pub fn set_total_listening_time_ms(&self, total_ms: u64) -> FfiResult<()> {
        self.database.set_total_listening_time_ms(total_ms)?;
        Ok(())
    }

    pub fn format_duration_tr(&self, ms: u64) -> String {
        let total_seconds = ms / 1000;
        let hours = total_seconds / 3600;
        let minutes = (total_seconds % 3600) / 60;
        let seconds = total_seconds % 60;
        if hours > 0 {
            format!("{hours} sa {minutes} dk")
        } else if minutes > 0 {
            format!("{minutes} dk {seconds} sn")
        } else {
            format!("{seconds} sn")
        }
    }

    pub fn build_user_data_export_json(&self, exported_at_epoch_ms: u64) -> FfiResult<String> {
        let settings = self.settings_service.load().unwrap_or_default();
        let total_ms = self.database.get_total_listening_time_ms()?;
        let stats = self.database.get_listening_stats(500)?;
        let recent = self.database.get_recent_tracks(200)?;
        let resume = self.database.get_resume_state()?;
        let custom_themes = self.database.list_user_custom_themes()?;
        let daily_stats = self.database.load_daily_points(None).unwrap_or_default();
        let hourly_stats = self.database.load_hourly_buckets().unwrap_or_default();

        let stats_json: Vec<serde_json::Value> = stats
            .iter()
            .map(|s| {
                serde_json::json!({
                    "trackId": s.track.id,
                    "provider": s.track.provider,
                    "title": s.track.title,
                    "artist": s.track.artist,
                    "album": s.track.album,
                    "durationMs": s.track.duration_ms,
                    "artworkUrl": s.track.artwork_url,
                    "playCount": s.play_count,
                    "totalListenedMs": s.total_listened_ms,
                    "totalListenedFormatted": self.format_duration_tr(s.total_listened_ms),
                    "lastPlayedAtMs": s.last_played_at_ms,
                })
            })
            .collect();

        let recent_json: Vec<serde_json::Value> = recent
            .iter()
            .map(|t| {
                serde_json::json!({
                    "id": t.id,
                    "provider": t.provider,
                    "title": t.title,
                    "artist": t.artist,
                    "album": t.album,
                    "durationMs": t.duration_ms,
                    "artworkUrl": t.artwork_url,
                })
            })
            .collect();

        let resume_json = resume.map(|r| {
            serde_json::json!({
                "trackId": r.track.id,
                "title": r.track.title,
                "artist": r.track.artist,
                "positionMs": r.position_ms,
                "durationMs": r.duration_ms,
                "updatedAtMs": r.updated_at_ms,
            })
        });

        let root = serde_json::json!({
            "app": "projekt_b-d",
            "engine": "PlasmaWave Rust Core (SQLite v3)",
            "exportedAtEpochMs": exported_at_epoch_ms,
            "totalListeningTimeMs": total_ms,
            "totalListeningTimeFormatted": self.format_duration_tr(total_ms),
            "privacySettings": {
                "collectListeningStats": settings.collect_listening_stats,
                "collectListeningHistory": settings.collect_listening_history,
                "saveResumePosition": settings.save_resume_position,
                "resumePlaybackEnabled": settings.resume_playback_enabled,
            },
            "listeningStats": stats_json,
            "dailyStats": daily_stats,
            "hourlyStats": hourly_stats,
            "recentHistory": recent_json,
            "resumeState": resume_json,
            "customThemes": custom_themes,
            "customThemesCount": custom_themes.len(),
        });

        serde_json::to_string_pretty(&root).map_err(|e| FfiError::OperationFailed {
            reason: format!("JSON export serialization failed: {e}"),
        })
    }

    pub fn clear_all_collected_user_data(&self) -> FfiResult<()> {
        self.database.clear_all_collected_user_data()?;
        Ok(())
    }

    // ------------------------------------------------------------------------
    // Resume Playback State
    // ------------------------------------------------------------------------
    pub fn save_resume_playback_state(&self, state: FfiResumePlaybackState) -> FfiResult<()> {
        let mut core_state = StoredResumePlaybackState::from(state);
        if core_state.updated_at_ms == 0 {
            core_state.updated_at_ms = now_epoch_ms();
        }
        self.database.save_resume_state(&core_state)?;
        Ok(())
    }

    pub fn get_resume_playback_state(&self) -> FfiResult<Option<FfiResumePlaybackState>> {
        let state = self.database.get_resume_state()?;
        Ok(state.map(FfiResumePlaybackState::from))
    }

    pub fn clear_resume_playback_state(&self) -> FfiResult<()> {
        self.database.clear_resume_state()?;
        Ok(())
    }

    // ------------------------------------------------------------------------
    // Pinned Items & Fast Playlist Entries Cache
    // ------------------------------------------------------------------------
    pub fn get_pinned_items(&self) -> FfiResult<Vec<FfiPinnedItem>> {
        let items = self.database.get_pinned_items()?;
        Ok(items.into_iter().map(FfiPinnedItem::from).collect())
    }

    pub fn set_pinned_items(&self, items: Vec<FfiPinnedItem>) -> FfiResult<()> {
        let core_items: Vec<StoredPinnedItem> =
            items.into_iter().map(StoredPinnedItem::from).collect();
        self.database.set_pinned_items(&core_items)?;
        Ok(())
    }

    pub fn toggle_pinned_item(&self, item: FfiPinnedItem) -> FfiResult<Vec<FfiPinnedItem>> {
        let updated = self
            .database
            .toggle_pinned_item(StoredPinnedItem::from(item))?;
        Ok(updated.into_iter().map(FfiPinnedItem::from).collect())
    }

    pub fn remove_pinned_item(&self, pin_id: String) -> FfiResult<Vec<FfiPinnedItem>> {
        let updated = self.database.remove_pinned_item(&pin_id)?;
        Ok(updated.into_iter().map(FfiPinnedItem::from).collect())
    }

    pub fn get_cached_playlist_entries(
        &self,
        playlist_id: String,
    ) -> FfiResult<Option<Vec<FfiPlaylistTrackEntry>>> {
        let entries = self.database.get_cached_playlist_entries(&playlist_id)?;
        Ok(entries.map(|list| {
            list.into_iter()
                .map(FfiPlaylistTrackEntry::from)
                .collect()
        }))
    }

    pub fn set_cached_playlist_entries(
        &self,
        playlist_id: String,
        entries: Vec<FfiPlaylistTrackEntry>,
    ) -> FfiResult<()> {
        let core_entries: Vec<StoredPlaylistTrackEntry> = entries
            .into_iter()
            .map(StoredPlaylistTrackEntry::from)
            .collect();
        self.database
            .set_cached_playlist_entries(&playlist_id, &core_entries, now_epoch_ms())?;
        Ok(())
    }

    pub fn get_all_cached_playlist_entries(
        &self,
    ) -> FfiResult<std::collections::HashMap<String, Vec<FfiPlaylistTrackEntry>>> {
        let pairs = self.database.get_all_cached_playlist_entries()?;
        let mut out = std::collections::HashMap::with_capacity(pairs.len());
        for (pid, list) in pairs {
            out.insert(
                pid,
                list.into_iter()
                    .map(FfiPlaylistTrackEntry::from)
                    .collect(),
            );
        }
        Ok(out)
    }

    pub fn delete_cached_playlist_entries(&self, playlist_id: String) -> FfiResult<()> {
        self.database.delete_cached_playlist_entries(&playlist_id)?;
        Ok(())
    }

    // ------------------------------------------------------------------------
    // Rust Sorting & Home Widget Algorithms
    // ------------------------------------------------------------------------
    pub fn sort_playlist_tracks(
        &self,
        mut entries: Vec<FfiPlaylistTrackEntry>,
        sort: FfiPlaylistTrackSort,
    ) -> Vec<FfiPlaylistTrackEntry> {
        match sort {
            FfiPlaylistTrackSort::Default => {
                entries.sort_by(|a, b| a.original_index.cmp(&b.original_index));
                entries
            }
            FfiPlaylistTrackSort::NewestFirst => {
                entries.sort_by(|a, b| {
                    b.added_at
                        .is_some()
                        .cmp(&a.added_at.is_some())
                        .then_with(|| b.added_at.cmp(&a.added_at))
                        .then_with(|| a.original_index.cmp(&b.original_index))
                });
                entries
            }
            FfiPlaylistTrackSort::OldestFirst => {
                entries.sort_by(|a, b| {
                    b.added_at
                        .is_some()
                        .cmp(&a.added_at.is_some())
                        .then_with(|| a.added_at.cmp(&b.added_at))
                        .then_with(|| a.original_index.cmp(&b.original_index))
                });
                entries
            }
            FfiPlaylistTrackSort::TitleAz => {
                entries.sort_by(|a, b| {
                    a.track
                        .title
                        .to_lowercase()
                        .cmp(&b.track.title.to_lowercase())
                        .then_with(|| a.original_index.cmp(&b.original_index))
                });
                entries
            }
            FfiPlaylistTrackSort::ArtistAz => {
                entries.sort_by(|a, b| {
                    a.track
                        .artist
                        .to_lowercase()
                        .cmp(&b.track.artist.to_lowercase())
                        .then_with(|| {
                            a.track
                                .title
                                .to_lowercase()
                                .cmp(&b.track.title.to_lowercase())
                        })
                });
                entries
            }
            FfiPlaylistTrackSort::AlbumAz => {
                entries.sort_by(|a, b| {
                    a.track
                        .album
                        .to_lowercase()
                        .cmp(&b.track.album.to_lowercase())
                        .then_with(|| {
                            a.track
                                .title
                                .to_lowercase()
                                .cmp(&b.track.title.to_lowercase())
                        })
                });
                entries
            }
            FfiPlaylistTrackSort::DurationAsc => {
                entries.sort_by(|a, b| {
                    a.track
                        .duration_ms
                        .cmp(&b.track.duration_ms)
                        .then_with(|| a.original_index.cmp(&b.original_index))
                });
                entries
            }
            FfiPlaylistTrackSort::DurationDesc => {
                entries.sort_by(|a, b| {
                    b.track
                        .duration_ms
                        .cmp(&a.track.duration_ms)
                        .then_with(|| a.original_index.cmp(&b.original_index))
                });
                entries
            }
        }
    }

    pub fn sort_library_playlists(
        &self,
        mut playlists: Vec<FfiLibraryPlaylistEntry>,
        sort: FfiLibraryPlaylistSort,
    ) -> Vec<FfiPlaylist> {
        match sort {
            FfiLibraryPlaylistSort::Default => {
                playlists.sort_by(|a, b| b.is_pinned.cmp(&a.is_pinned));
            }
            FfiLibraryPlaylistSort::TitleAz => {
                playlists.sort_by(|a, b| {
                    b.is_pinned.cmp(&a.is_pinned).then_with(|| {
                        a.playlist
                            .name
                            .to_lowercase()
                            .cmp(&b.playlist.name.to_lowercase())
                    })
                });
            }
            FfiLibraryPlaylistSort::TitleZa => {
                playlists.sort_by(|a, b| {
                    b.is_pinned.cmp(&a.is_pinned).then_with(|| {
                        b.playlist
                            .name
                            .to_lowercase()
                            .cmp(&a.playlist.name.to_lowercase())
                    })
                });
            }
            FfiLibraryPlaylistSort::TrackCountDesc => {
                playlists.sort_by(|a, b| {
                    b.is_pinned
                        .cmp(&a.is_pinned)
                        .then_with(|| b.playlist.track_count.cmp(&a.playlist.track_count))
                        .then_with(|| {
                            a.playlist
                                .name
                                .to_lowercase()
                                .cmp(&b.playlist.name.to_lowercase())
                        })
                });
            }
            FfiLibraryPlaylistSort::TrackCountAsc => {
                playlists.sort_by(|a, b| {
                    b.is_pinned
                        .cmp(&a.is_pinned)
                        .then_with(|| a.playlist.track_count.cmp(&b.playlist.track_count))
                        .then_with(|| {
                            a.playlist
                                .name
                                .to_lowercase()
                                .cmp(&b.playlist.name.to_lowercase())
                        })
                });
            }
        }
        playlists.into_iter().map(|e| e.playlist).collect()
    }

    pub fn get_quick_artists(
        &self,
        limit: u32,
        fallback_tracks: Vec<FfiTrack>,
    ) -> FfiResult<Vec<FfiQuickArtistItem>> {
        let stats = self.database.get_listening_stats(200)?;
        let recent = self.database.get_recent_tracks(100)?;

        struct Acc {
            display_name: String,
            play_count: u32,
            sample_track: FfiTrack,
            order: usize,
        }

        let mut map: std::collections::HashMap<String, Acc> = std::collections::HashMap::new();
        let mut seq = 0usize;

        for s in stats {
            let trimmed = s.track.artist.trim().to_string();
            if trimmed.is_empty() {
                continue;
            }
            let key = trimmed.to_lowercase();
            let ffi_track = FfiTrack::from(s.track);
            match map.get_mut(&key) {
                Some(acc) => {
                    acc.play_count = acc.play_count.saturating_add(s.play_count);
                    if acc.sample_track.artwork_url.is_none() && ffi_track.artwork_url.is_some() {
                        acc.sample_track = ffi_track;
                    }
                }
                None => {
                    map.insert(
                        key,
                        Acc {
                            display_name: trimmed,
                            play_count: s.play_count,
                            sample_track: ffi_track,
                            order: seq,
                        },
                    );
                    seq += 1;
                }
            }
        }

        for r in recent.into_iter().map(FfiTrack::from).chain(fallback_tracks) {
            let trimmed = r.artist.trim().to_string();
            if trimmed.is_empty() {
                continue;
            }
            let key = trimmed.to_lowercase();
            match map.get_mut(&key) {
                Some(acc) => {
                    if acc.sample_track.artwork_url.is_none() && r.artwork_url.is_some() {
                        acc.sample_track = r;
                    }
                }
                None => {
                    map.insert(
                        key,
                        Acc {
                            display_name: trimmed,
                            play_count: 0,
                            sample_track: r,
                            order: seq,
                        },
                    );
                    seq += 1;
                }
            }
        }

        let mut items: Vec<Acc> = map.into_values().collect();
        items.sort_by(|a, b| {
            b.play_count
                .cmp(&a.play_count)
                .then_with(|| a.order.cmp(&b.order))
        });

        Ok(items
            .into_iter()
            .take(limit as usize)
            .map(|a| FfiQuickArtistItem {
                artist: a.display_name,
                play_count: a.play_count,
                sample_track: a.sample_track,
            })
            .collect())
    }

    pub fn generate_mood_mix(
        &self,
        mood: String,
        limit: u32,
        candidate_tracks: Vec<FfiTrack>,
    ) -> Vec<FfiTrack> {
        let mut seen = std::collections::HashSet::new();
        let mut pool = Vec::new();

        // Include recent & stats tracks alongside candidates
        let stats = self.database.get_listening_stats(200).unwrap_or_default();
        let mut play_counts: std::collections::HashMap<String, u32> =
            std::collections::HashMap::new();
        for s in &stats {
            play_counts.insert(s.track.id.clone(), s.play_count);
        }

        for t in candidate_tracks
            .into_iter()
            .chain(stats.into_iter().map(|s| FfiTrack::from(s.track)))
        {
            if seen.insert(t.id.clone()) {
                pool.push(t);
            }
        }

        if pool.is_empty() {
            return Vec::new();
        }

        let seed = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.subsec_nanos() as u64 ^ d.as_secs())
            .unwrap_or(0x9E37_79B9_7F4A_7C15);

        let mood_key = mood.trim().to_lowercase();

        let mut scored: Vec<(i64, u64, FfiTrack)> = pool
            .into_iter()
            .enumerate()
            .map(|(idx, track)| {
                let text = format!(
                    "{} {} {}",
                    track.title.to_lowercase(),
                    track.artist.to_lowercase(),
                    track.album.to_lowercase()
                );
                let mut score: i64 = 0;
                match mood_key.as_str() {
                    "enerjik" => {
                        if (110_000..=235_000).contains(&track.duration_ms) {
                            score += 35;
                        }
                        for kw in [
                            "remix", "dance", "club", "rock", "rap", "pop", "beat", "fast",
                            "fire", "party", "phonk", "hyper", "power", "action",
                        ] {
                            if text.contains(kw) {
                                score += 45;
                            }
                        }
                    }
                    "chill" => {
                        if track.duration_ms >= 200_000 {
                            score += 35;
                        }
                        for kw in [
                            "chill", "acoustic", "lofi", "ambient", "piano", "slow", "soft",
                            "dream", "instrumental", "rain", "calm", "jazz",
                        ] {
                            if text.contains(kw) {
                                score += 45;
                            }
                        }
                    }
                    "gece" => {
                        if track.duration_ms >= 185_000 {
                            score += 30;
                        }
                        for kw in [
                            "night", "dark", "moon", "gece", "noir", "ost", "soundtrack",
                            "synth", "wave", "after", "midnight", "shadow", "senfoni",
                        ] {
                            if text.contains(kw) {
                                score += 45;
                            }
                        }
                    }
                    "kesif" | "keşif" => {
                        let count = play_counts.get(&track.id).copied().unwrap_or(0);
                        if count == 0 {
                            score += 60;
                        } else if count <= 2 {
                            score += 30;
                        }
                    }
                    _ => {}
                }

                // Deterministic pseudo-random jitter mixed with time seed so repeated clicks vary
                let mut h = seed ^ ((idx as u64).wrapping_mul(0x517C_C1B7_2722_0A95));
                for b in track.id.bytes() {
                    h = h.wrapping_mul(1099511628211).wrapping_add(b as u64);
                }
                let jitter = (h % 50) as i64;
                (score + jitter, h, track)
            })
            .collect();

        scored.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
        scored
            .into_iter()
            .take(limit as usize)
            .map(|(_, _, t)| t)
            .collect()
    }
}

impl PlasmaWaveCore {
    fn provider_info(&self, p: &dyn MusicProvider) -> FfiProviderInfo {
        let caps = p.capabilities();
        let auth = p.auth_state();
        let auth_str = match auth {
            core_providers::AuthState::NotRequired => "not_required",
            core_providers::AuthState::SignedOut => "signed_out",
            core_providers::AuthState::SignedIn { .. } => "signed_in",
            core_providers::AuthState::Unsupported { .. } => "unsupported",
        };
        FfiProviderInfo {
            id: p.id().to_string(),
            display_name: p.display_name(),
            origin: match p.origin() {
                DataOrigin::Real => "real".into(),
                DataOrigin::PublicCatalog => "public_catalog".into(),
                DataOrigin::DevelopmentFixture => "development_fixture".into(),
            },
            auth_state: auth_str.into(),
            can_search: caps.is_available(Capability::CatalogSearch),
            can_full_playback: caps.is_available(Capability::FullPlayback),
            can_preview_playback: caps.is_available(Capability::PreviewPlayback),
            can_download: caps.is_available(Capability::AuthorizedOfflineDownloads),
            can_user_playlists: caps.is_available(Capability::UserPlaylists),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAmbientOrb {
    pub cx: f32,
    pub cy: f32,
    pub radius: f32,
    pub color_argb: u32,
    pub alpha: f32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAmbientRibbon {
    pub x0: f32,
    pub y0: f32,
    pub cx1: f32,
    pub cy1: f32,
    pub cx2: f32,
    pub cy2: f32,
    pub x1: f32,
    pub y1: f32,
    pub thickness: f32,
    pub color_argb: u32,
    pub alpha: f32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAmbientRing {
    pub cx: f32,
    pub cy: f32,
    pub radius: f32,
    pub stroke_width: f32,
    pub color_argb: u32,
    pub alpha: f32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAmbientBackdropFrame {
    pub orbs: Vec<FfiAmbientOrb>,
    pub ribbons: Vec<FfiAmbientRibbon>,
    pub rings: Vec<FfiAmbientRing>,
}

fn blend_argb(a: u32, b: u32, t: f32) -> u32 {
    let t = t.clamp(0.0, 1.0);
    let inv = 1.0 - t;
    let ar = ((a >> 16) & 0xFF) as f32;
    let ag = ((a >> 8) & 0xFF) as f32;
    let ab = (a & 0xFF) as f32;
    let br = ((b >> 16) & 0xFF) as f32;
    let bg = ((b >> 8) & 0xFF) as f32;
    let bb = (b & 0xFF) as f32;
    let r = (ar * inv + br * t).round().clamp(0.0, 255.0) as u32;
    let g = (ag * inv + bg * t).round().clamp(0.0, 255.0) as u32;
    let bl = (ab * inv + bb * t).round().clamp(0.0, 255.0) as u32;
    0xFF00_0000 | (r << 16) | (g << 8) | bl
}

/// Pure-Rust, zero-texture ambient aurora & silk-ribbon motion engine.
#[uniffi::export]
pub fn compute_ambient_backdrop_frame(
    phase: f32,
    primary_argb: u32,
    secondary_argb: u32,
    background_argb: u32,
) -> FfiAmbientBackdropFrame {
    compute_ambient_backdrop_styled(
        "silk_aurora".into(),
        phase,
        primary_argb,
        secondary_argb,
        background_argb,
    )
}

/// Pure-Rust multi-style ambient backdrop engine supporting 6 distinct visual modes:
/// - "silk_aurora" (İpek Aurora)
/// - "nebula_orbs" (Nebula Küreleri)
/// - "ocean_waves" (Okyanus Dalgaları)
/// - "cyber_pulse" (Siber Halkalar)
/// - "starlight_drift" (Yıldız Tozu)
/// - "prism_beams" (Prizma Hüzmeleri)
#[uniffi::export]
pub fn compute_ambient_backdrop_styled(
    style: String,
    phase: f32,
    primary_argb: u32,
    secondary_argb: u32,
    background_argb: u32,
) -> FfiAmbientBackdropFrame {
    let wrapped = phase.rem_euclid(1.0);
    let tau = wrapped * std::f32::consts::TAU;
    let s1 = tau.sin();
    let c1 = tau.cos();
    let s2 = (tau * 2.0 + 0.85).sin();
    let c2 = (tau * 2.0 + 0.85).cos();
    let s3 = (tau * 3.0 + 1.7).sin();

    // Harmonious theme-derived tints
    let soft_primary = blend_argb(primary_argb, 0xFFFF_FFFF, 0.16);
    let deep_primary = blend_argb(primary_argb, background_argb, 0.35);
    let mid_accent = blend_argb(primary_argb, secondary_argb, 0.48);
    let bright_accent = blend_argb(secondary_argb, 0xFFFF_FFFF, 0.22);

    match style.as_str() {
        "nebula_orbs" => {
            let orbs = vec![
                FfiAmbientOrb {
                    cx: 0.75 + 0.14 * c1,
                    cy: 0.12 + 0.07 * s1,
                    radius: 0.82 + 0.06 * s2,
                    color_argb: soft_primary,
                    alpha: 0.26 + 0.04 * s1,
                },
                FfiAmbientOrb {
                    cx: 0.22 - 0.12 * s1,
                    cy: 0.26 + 0.08 * c2,
                    radius: 0.70 + 0.05 * c1,
                    color_argb: mid_accent,
                    alpha: 0.20 + 0.03 * c1,
                },
                FfiAmbientOrb {
                    cx: 0.80 - 0.15 * c2,
                    cy: 0.62 + 0.09 * s1,
                    radius: 0.75 + 0.07 * s1,
                    color_argb: deep_primary,
                    alpha: 0.22 + 0.03 * s2,
                },
                FfiAmbientOrb {
                    cx: 0.24 + 0.14 * s2,
                    cy: 0.88 - 0.06 * c1,
                    radius: 0.78 + 0.05 * c2,
                    color_argb: bright_accent,
                    alpha: 0.18 + 0.03 * c2,
                },
            ];
            FfiAmbientBackdropFrame {
                orbs,
                ribbons: Vec::new(),
                rings: Vec::new(),
            }
        }
        "ocean_waves" => {
            let orbs = vec![
                FfiAmbientOrb {
                    cx: 0.50 + 0.10 * s1,
                    cy: 0.04,
                    radius: 0.88,
                    color_argb: soft_primary,
                    alpha: 0.18,
                },
                FfiAmbientOrb {
                    cx: 0.50 - 0.10 * c1,
                    cy: 0.96,
                    radius: 0.82,
                    color_argb: mid_accent,
                    alpha: 0.16,
                },
            ];
            let ribbons = vec![
                // Top rolling wave 1
                FfiAmbientRibbon {
                    x0: -0.08,
                    y0: 0.07 + 0.025 * s1,
                    cx1: 0.30 + 0.05 * c1,
                    cy1: 0.01 - 0.03 * c1,
                    cx2: 0.68 - 0.05 * s1,
                    cy2: 0.14 + 0.03 * s2,
                    x1: 1.08,
                    y1: 0.06 - 0.02 * c1,
                    thickness: 0.10 + 0.015 * s1,
                    color_argb: soft_primary,
                    alpha: 0.20 + 0.03 * c1,
                },
                // Top rolling wave 2
                FfiAmbientRibbon {
                    x0: -0.08,
                    y0: 0.13 - 0.02 * c1,
                    cx1: 0.35 - 0.04 * s2,
                    cy1: 0.20 + 0.025 * s1,
                    cx2: 0.72 + 0.04 * c1,
                    cy2: 0.07 - 0.025 * c2,
                    x1: 1.08,
                    y1: 0.15 + 0.02 * s1,
                    thickness: 0.085 + 0.01 * c2,
                    color_argb: mid_accent,
                    alpha: 0.15 + 0.025 * s1,
                },
                // Bottom gentle undertow wave
                FfiAmbientRibbon {
                    x0: -0.08,
                    y0: 0.86 + 0.02 * c2,
                    cx1: 0.34 + 0.05 * s1,
                    cy1: 0.80 - 0.025 * c1,
                    cx2: 0.70 - 0.05 * c1,
                    cy2: 0.92 + 0.025 * s1,
                    x1: 1.08,
                    y1: 0.85 - 0.02 * s2,
                    thickness: 0.095,
                    color_argb: deep_primary,
                    alpha: 0.16,
                },
            ];
            FfiAmbientBackdropFrame {
                orbs,
                ribbons,
                rings: Vec::new(),
            }
        }
        "cyber_pulse" => {
            let center_x = 0.50 + 0.03 * s1;
            let center_y = 0.13 + 0.02 * c1;
            let orbs = vec![
                FfiAmbientOrb {
                    cx: center_x,
                    cy: center_y,
                    radius: 0.68 + 0.06 * s1,
                    color_argb: soft_primary,
                    alpha: 0.22 + 0.03 * s1,
                },
                FfiAmbientOrb {
                    cx: 0.78 - 0.08 * c1,
                    cy: 0.90 + 0.03 * s1,
                    radius: 0.65,
                    color_argb: mid_accent,
                    alpha: 0.16,
                },
            ];
            let mut rings = Vec::with_capacity(4);
            for i in 0..4 {
                let p = (wrapped + (i as f32) * 0.25).rem_euclid(1.0);
                let radius = 0.12 + p * 0.58;
                let fade = (1.0 - p) * (0.20 + 0.80 * (p * std::f32::consts::PI).sin());
                rings.push(FfiAmbientRing {
                    cx: center_x,
                    cy: center_y,
                    radius,
                    stroke_width: 0.005 + (1.0 - p) * 0.004,
                    color_argb: if i % 2 == 0 { soft_primary } else { bright_accent },
                    alpha: (0.24 * fade).clamp(0.01, 0.26),
                });
            }
            FfiAmbientBackdropFrame {
                orbs,
                ribbons: Vec::new(),
                rings,
            }
        }
        "starlight_drift" => {
            let orbs = vec![
                FfiAmbientOrb {
                    cx: 0.68 + 0.08 * c1,
                    cy: 0.10 + 0.04 * s1,
                    radius: 0.74,
                    color_argb: soft_primary,
                    alpha: 0.19,
                },
                FfiAmbientOrb {
                    cx: 0.25 - 0.06 * s1,
                    cy: 0.82 + 0.05 * c1,
                    radius: 0.68,
                    color_argb: mid_accent,
                    alpha: 0.15,
                },
            ];
            let ribbons = vec![FfiAmbientRibbon {
                x0: 0.15 + 0.04 * s1,
                y0: -0.03,
                cx1: 0.42 - 0.05 * c1,
                cy1: 0.14 + 0.03 * s2,
                cx2: 0.74 + 0.04 * s1,
                cy2: 0.03 - 0.03 * c1,
                x1: 1.06,
                y1: 0.16 + 0.02 * c2,
                thickness: 0.09,
                color_argb: soft_primary,
                alpha: 0.15,
            }];
            let seeds: [(f32, f32, f32, f32); 10] = [
                (0.16, 0.08, 0.018, 0.0),
                (0.84, 0.07, 0.024, 0.7),
                (0.62, 0.16, 0.015, 1.4),
                (0.28, 0.22, 0.021, 2.1),
                (0.88, 0.27, 0.016, 2.8),
                (0.12, 0.44, 0.022, 3.5),
                (0.76, 0.54, 0.019, 4.2),
                (0.34, 0.68, 0.016, 4.9),
                (0.84, 0.80, 0.023, 5.5),
                (0.20, 0.86, 0.018, 6.0),
            ];
            let rings = seeds
                .iter()
                .enumerate()
                .map(|(idx, &(bx, by, rad, offset))| {
                    let twinkle = 0.5 + 0.5 * (tau * 2.0 + offset).sin();
                    let dx = 0.03 * (tau + offset).cos();
                    let dy = 0.025 * (tau + offset * 1.3).sin();
                    FfiAmbientRing {
                        cx: bx + dx,
                        cy: by + dy,
                        radius: rad * (0.85 + 0.30 * twinkle),
                        stroke_width: 0.0, // Filled glowing starlight bokeh particle
                        color_argb: if idx % 2 == 0 { bright_accent } else { soft_primary },
                        alpha: 0.12 + 0.22 * twinkle,
                    }
                })
                .collect();
            FfiAmbientBackdropFrame {
                orbs,
                ribbons,
                rings,
            }
        }
        "prism_beams" => {
            let orbs = vec![
                FfiAmbientOrb {
                    cx: 0.82 + 0.06 * c1,
                    cy: 0.04,
                    radius: 0.76,
                    color_argb: soft_primary,
                    alpha: 0.21,
                },
                FfiAmbientOrb {
                    cx: 0.18 - 0.06 * s1,
                    cy: 0.06,
                    radius: 0.68,
                    color_argb: mid_accent,
                    alpha: 0.17,
                },
            ];
            let ribbons = vec![
                // Diagonal prism shaft 1 from top-right
                FfiAmbientRibbon {
                    x0: 0.96,
                    y0: -0.05,
                    cx1: 0.72 + 0.05 * s1,
                    cy1: 0.12,
                    cx2: 0.45 + 0.06 * c1,
                    cy2: 0.24,
                    x1: 0.08 + 0.05 * s2,
                    y1: 0.38,
                    thickness: 0.12 + 0.02 * s1,
                    color_argb: soft_primary,
                    alpha: 0.17 + 0.03 * c1,
                },
                // Diagonal prism shaft 2 from top-center
                FfiAmbientRibbon {
                    x0: 0.64 + 0.04 * c1,
                    y0: -0.05,
                    cx1: 0.52 - 0.04 * s1,
                    cy1: 0.10,
                    cx2: 0.36 + 0.05 * c2,
                    cy2: 0.20,
                    x1: -0.04,
                    y1: 0.28 + 0.03 * s1,
                    thickness: 0.085 + 0.015 * c2,
                    color_argb: bright_accent,
                    alpha: 0.13 + 0.02 * s3,
                },
                // Counter beam from top-left
                FfiAmbientRibbon {
                    x0: 0.04,
                    y0: -0.04,
                    cx1: 0.32 + 0.04 * c1,
                    cy1: 0.09,
                    cx2: 0.64 - 0.04 * s1,
                    cy2: 0.18,
                    x1: 1.04,
                    y1: 0.27 + 0.02 * c1,
                    thickness: 0.075,
                    color_argb: mid_accent,
                    alpha: 0.12,
                },
            ];
            FfiAmbientBackdropFrame {
                orbs,
                ribbons,
                rings: Vec::new(),
            }
        }
        _ => {
            // Default: "silk_aurora" (İpek Aurora)
            let orbs = vec![
                FfiAmbientOrb {
                    cx: 0.72 + 0.08 * c1,
                    cy: 0.06 + 0.04 * s1,
                    radius: 0.78 + 0.05 * s2,
                    color_argb: soft_primary,
                    alpha: 0.22 + 0.03 * s1,
                },
                FfiAmbientOrb {
                    cx: 0.76 - 0.10 * s1,
                    cy: 0.94 + 0.04 * c1,
                    radius: 0.74 + 0.06 * c2,
                    color_argb: mid_accent,
                    alpha: 0.19 + 0.03 * c1,
                },
                FfiAmbientOrb {
                    cx: 0.10 + 0.07 * s2,
                    cy: 0.48 + 0.06 * c1,
                    radius: 0.62 + 0.04 * s1,
                    color_argb: deep_primary,
                    alpha: 0.13 + 0.02 * s2,
                },
            ];

            let ribbons = vec![
                FfiAmbientRibbon {
                    x0: 0.22 + 0.05 * c1,
                    y0: -0.04,
                    cx1: 0.44 + 0.06 * s1,
                    cy1: 0.15 + 0.03 * c1,
                    cx2: 0.68 - 0.05 * c2,
                    cy2: 0.02 + 0.04 * s1,
                    x1: 1.08,
                    y1: 0.13 + 0.03 * c1,
                    thickness: 0.11 + 0.015 * s1,
                    color_argb: soft_primary,
                    alpha: 0.18 + 0.03 * c1,
                },
                FfiAmbientRibbon {
                    x0: 0.34 - 0.04 * s1,
                    y0: 0.01,
                    cx1: 0.56 + 0.05 * c1,
                    cy1: 0.19 - 0.03 * s1,
                    cx2: 0.78 + 0.04 * s2,
                    cy2: 0.06 + 0.03 * c2,
                    x1: 1.12,
                    y1: 0.20 - 0.02 * s1,
                    thickness: 0.075 + 0.012 * c2,
                    color_argb: mid_accent,
                    alpha: 0.12 + 0.025 * s1,
                },
            ];

            FfiAmbientBackdropFrame {
                orbs,
                ribbons,
                rings: Vec::new(),
            }
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAdaptiveTextPalette {
    pub on_background_argb: u32,
    pub on_background_secondary_argb: u32,
    pub on_card_argb: u32,
    pub on_card_secondary_argb: u32,
    pub on_accent_argb: u32,
    pub is_dark_background: bool,
}

fn srgb_channel_to_linear(c: u32) -> f32 {
    let v = (c as f32) / 255.0;
    if v <= 0.04045 {
        v / 12.92
    } else {
        ((v + 0.055) / 1.055).powf(2.4)
    }
}

fn relative_luminance(argb: u32) -> f32 {
    let r = srgb_channel_to_linear((argb >> 16) & 0xFF);
    let g = srgb_channel_to_linear((argb >> 8) & 0xFF);
    let b = srgb_channel_to_linear(argb & 0xFF);
    0.2126 * r + 0.7152 * g + 0.0722 * b
}

fn contrast_ratio(a: u32, b: u32) -> f32 {
    let la = relative_luminance(a);
    let lb = relative_luminance(b);
    let (hi, lo) = if la >= lb { (la, lb) } else { (lb, la) };
    (hi + 0.05) / (lo + 0.05)
}

fn pick_foreground_pair(surface_argb: u32, primary_argb: u32) -> (u32, u32, bool) {
    let is_dark = relative_luminance(surface_argb) < 0.34;
    if is_dark {
        // Subtle 6% theme tint on pure crisp white so text pops against dark/ambient surfaces
        let tinted_white = blend_argb(0xFFFF_FFFF, primary_argb, 0.06);
        let primary_text = if contrast_ratio(tinted_white, surface_argb) >= 7.0 {
            tinted_white
        } else {
            0xFFF8_FAFC
        };
        let mut secondary = blend_argb(0xFFCB_D5E1, primary_argb, 0.14);
        if contrast_ratio(secondary, surface_argb) < 4.5 {
            secondary = 0xFFE2_E8F0;
        }
        (primary_text, secondary, true)
    } else {
        let tinted_ink = blend_argb(0xFF11_1827, primary_argb, 0.08);
        let primary_text = if contrast_ratio(tinted_ink, surface_argb) >= 7.0 {
            tinted_ink
        } else {
            0xFF0F_172A
        };
        let mut secondary = blend_argb(0xFF47_5569, primary_argb, 0.12);
        if contrast_ratio(secondary, surface_argb) < 4.5 {
            secondary = 0xFF33_4155;
        }
        (primary_text, secondary, false)
    }
}

/// Generates a harmonious, contrast-aware random custom theme in Rust.
#[uniffi::export]
pub fn generate_random_custom_theme(prefer_dark: Option<bool>) -> FfiCustomThemeSpec {
    core_themes::generate_random_custom_theme(prefer_dark).into()
}

/// Pure-Rust WCAG 2.1 contrast-aware foreground text palette engine.
/// Automatically adapts primary/secondary text and icon colors to the effective
/// background (including ambient aurora glow lift) and card surfaces.
#[uniffi::export]
pub fn compute_adaptive_text_palette(
    background_argb: u32,
    card_argb: u32,
    primary_argb: u32,
    ambient_enabled: bool,
) -> FfiAdaptiveTextPalette {
    let effective_bg = if ambient_enabled {
        blend_argb(background_argb, primary_argb, 0.14)
    } else {
        background_argb
    };
    let (on_bg, on_bg_sec, is_dark) = pick_foreground_pair(effective_bg, primary_argb);
    let (on_card, on_card_sec, _) = pick_foreground_pair(card_argb, primary_argb);
    let on_accent = if contrast_ratio(0xFFFF_FFFF, primary_argb) >= contrast_ratio(0xFF14_171A, primary_argb) {
        0xFFFF_FFFF
    } else {
        0xFF14_171A
    };

    FfiAdaptiveTextPalette {
        on_background_argb: on_bg,
        on_background_secondary_argb: on_bg_sec,
        on_card_argb: on_card,
        on_card_secondary_argb: on_card_sec,
        on_accent_argb: on_accent,
        is_dark_background: is_dark,
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiNowPlayingGlassPalette {
    pub base_backdrop_argb: u32,
    pub beam_primary_argb: u32,
    pub beam_secondary_argb: u32,
    pub beam_intensity: f32,
    pub veil_top_argb: u32,
    pub veil_mid_argb: u32,
    pub veil_bottom_argb: u32,
    pub glass_surface_argb: u32,
    pub glass_surface_active_argb: u32,
    pub glass_border_argb: u32,
    pub text_primary_argb: u32,
    pub text_secondary_argb: u32,
}

fn argb_to_hsl(argb: u32) -> (f32, f32, f32) {
    let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
    let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
    let b = (argb & 0xFF) as f32 / 255.0;

    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let l = (max + min) * 0.5;
    let d = max - min;

    if d <= 1e-5 {
        return (0.0, 0.0, l);
    }

    let s = if l > 0.5 {
        d / (2.0 - max - min)
    } else {
        d / (max + min)
    };

    let mut h = if (max - r).abs() <= 1e-5 {
        (g - b) / d + if g < b { 6.0 } else { 0.0 }
    } else if (max - g).abs() <= 1e-5 {
        (b - r) / d + 2.0
    } else {
        (r - g) / d + 4.0
    };
    h *= 60.0;
    (h.rem_euclid(360.0), s.clamp(0.0, 1.0), l.clamp(0.0, 1.0))
}

fn hue_to_rgb(p: f32, q: f32, mut t: f32) -> f32 {
    t = t.rem_euclid(1.0);
    if t < 1.0 / 6.0 {
        p + (q - p) * 6.0 * t
    } else if t < 0.5 {
        q
    } else if t < 2.0 / 3.0 {
        p + (q - p) * (2.0 / 3.0 - t) * 6.0
    } else {
        p
    }
}

fn hsl_to_argb(h: f32, s: f32, l: f32) -> u32 {
    let h_norm = h.rem_euclid(360.0) / 360.0;
    let s = s.clamp(0.0, 1.0);
    let l = l.clamp(0.0, 1.0);

    let (r, g, b) = if s <= 1e-5 {
        (l, l, l)
    } else {
        let q = if l < 0.5 {
            l * (1.0 + s)
        } else {
            l + s - l * s
        };
        let p = 2.0 * l - q;
        (
            hue_to_rgb(p, q, h_norm + 1.0 / 3.0),
            hue_to_rgb(p, q, h_norm),
            hue_to_rgb(p, q, h_norm - 1.0 / 3.0),
        )
    };

    let ru = (r * 255.0).round().clamp(0.0, 255.0) as u32;
    let gu = (g * 255.0).round().clamp(0.0, 255.0) as u32;
    let bu = (b * 255.0).round().clamp(0.0, 255.0) as u32;
    0xFF00_0000 | (ru << 16) | (gu << 8) | bu
}

fn with_alpha(rgb: u32, alpha: f32) -> u32 {
    let a = (alpha.clamp(0.0, 1.0) * 255.0).round().clamp(0.0, 255.0) as u32;
    (a << 24) | (rgb & 0x00FF_FFFF)
}

fn hue_distance(h1: f32, h2: f32) -> f32 {
    let diff = (h1 - h2).abs().rem_euclid(360.0);
    diff.min(360.0 - diff)
}

/// Pure-Rust translucent Now Playing glass & ambient light-beam palette engine.
/// Called once per track/theme change (zero per-frame FFI overhead).
#[uniffi::export]
pub fn compute_now_playing_glass_palette(
    top_argb: u32,
    bottom_argb: u32,
    accent_argb: u32,
    secondary_argb: u32,
    theme_bg_argb: u32,
    ambient_enabled: bool,
) -> FfiNowPlayingGlassPalette {
    let (top_h, top_s, top_l) = argb_to_hsl(top_argb);
    let (acc_h, acc_s, acc_l) = argb_to_hsl(accent_argb);
    let (sec_h, sec_s, sec_l) = argb_to_hsl(secondary_argb);

    let base_hue = if top_s >= 0.08 { top_h } else { acc_h };
    let base_sat = if top_s >= 0.08 || acc_s >= 0.08 {
        (top_s.max(acc_s * 0.65)).clamp(0.22, 0.46)
    } else {
        0.04
    };
    let base_light = if ambient_enabled {
        top_l.clamp(0.055, 0.09)
    } else {
        top_l.clamp(0.08, 0.14)
    };
    let tuned_base = hsl_to_argb(base_hue, base_sat, base_light);
    let base_backdrop_argb = blend_argb(tuned_base, theme_bg_argb, 0.22);

    let is_monochrome = acc_s < 0.09 && top_s < 0.09 && sec_s < 0.09;
    let (beam_primary_argb, beam_secondary_argb) = if is_monochrome {
        (0xFFD8_E4F0, 0xFF8E_A4BE)
    } else {
        let p_hue = if acc_s >= 0.09 { acc_h } else { top_h };
        let p_sat = acc_s.max(top_s).clamp(0.64, 0.88);
        let p_light = acc_l.clamp(0.56, 0.68);
        let primary_beam = hsl_to_argb(p_hue, p_sat, p_light);

        let (s_hue, s_sat, s_light) = if sec_s >= 0.14 && hue_distance(p_hue, sec_h) >= 20.0 {
            (sec_h, sec_s.clamp(0.58, 0.84), sec_l.clamp(0.52, 0.66))
        } else if top_s >= 0.14 && hue_distance(p_hue, top_h) >= 18.0 {
            (top_h, top_s.clamp(0.58, 0.84), 0.58)
        } else {
            (
                (p_hue + 38.0).rem_euclid(360.0),
                (p_sat * 0.94).clamp(0.56, 0.84),
                0.58,
            )
        };
        let secondary_beam = hsl_to_argb(s_hue, s_sat, s_light);
        (primary_beam, secondary_beam)
    };

    let beam_intensity = if ambient_enabled { 1.55 } else { 0.0 };

    let mid_tint = blend_argb(top_argb, bottom_argb, 0.45);
    let bottom_tint = blend_argb(bottom_argb, base_backdrop_argb, 0.35);
    let (veil_top_argb, veil_mid_argb, veil_bottom_argb) = if ambient_enabled {
        (
            with_alpha(top_argb, 0.26),
            with_alpha(mid_tint, 0.14),
            with_alpha(bottom_tint, 0.40),
        )
    } else {
        (
            with_alpha(top_argb, 0.92),
            with_alpha(mid_tint, 0.88),
            with_alpha(bottom_tint, 0.96),
        )
    };

    let glass_tint = blend_argb(0xFF0E_1318, top_argb, 0.24);
    let glass_surface_argb = with_alpha(glass_tint, 0.38);
    let active_tint = blend_argb(accent_argb, 0xFFFF_FFFF, 0.14);
    let glass_surface_active_argb = with_alpha(active_tint, 0.30);
    let rim_tint = blend_argb(0xFFFF_FFFF, beam_primary_argb, 0.32);
    let glass_border_argb = with_alpha(rim_tint, 0.20);

    let worst_case_bg = if ambient_enabled {
        blend_argb(base_backdrop_argb, beam_primary_argb, 0.28)
    } else {
        blend_argb(base_backdrop_argb, top_argb, 0.75)
    };
    let (mut text_primary_argb, mut text_secondary_argb, _) =
        pick_foreground_pair(worst_case_bg, beam_primary_argb);
    if contrast_ratio(text_primary_argb, worst_case_bg) < 7.0 {
        text_primary_argb = 0xFFFF_FFFF;
    }
    if contrast_ratio(text_secondary_argb, worst_case_bg) < 4.5 {
        text_secondary_argb = 0xFFE2_E8F0;
    }

    FfiNowPlayingGlassPalette {
        base_backdrop_argb,
        beam_primary_argb,
        beam_secondary_argb,
        beam_intensity,
        veil_top_argb,
        veil_mid_argb,
        veil_bottom_argb,
        glass_surface_argb,
        glass_surface_active_argb,
        glass_border_argb,
        text_primary_argb,
        text_secondary_argb,
    }
}

fn extract_numeric_id(input: &str) -> Option<String> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return None;
    }
    // If input is purely digits
    if trimmed.chars().all(|c| c.is_ascii_digit()) {
        return Some(trimmed.to_string());
    }
    // If it contains /profile/ or /user/
    for pattern in ["/profile/", "/user/"] {
        if let Some(pos) = trimmed.find(pattern) {
            let after = &trimmed[pos + pattern.len()..];
            let digits: String = after.chars().take_while(|c| c.is_ascii_digit()).collect();
            if !digits.is_empty() {
                return Some(digits);
            }
        }
    }
    // Fallback: take continuous digits if length >= 1
    let digits: String = trimmed.chars().filter(|c| c.is_ascii_digit()).collect();
    if !digits.is_empty() {
        Some(digits)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_initialization_and_basic_flows() {
        let dir = tempfile::tempdir().unwrap();
        let core = PlasmaWaveCore::new(dir.path().to_string_lossy().into()).unwrap();
        assert!(core.get_version().contains("PlasmaWave"));

        let providers = core.get_providers();
        assert_eq!(providers.len(), 3);

        let current_theme = core.get_current_theme();
        assert_eq!(current_theme.id, "org.kde.breeze-dark");

        let mods = core.list_mods();
        assert!(!mods.is_empty());

        let actions = core.get_custom_track_actions();
        assert_eq!(actions.len(), 1);

        let action_res = core
            .execute_track_mod_action(actions[0].id.clone(), "local:test_track".into())
            .unwrap();
        assert!(action_res.contains("Estimated Tempo"));

        assert_eq!(extract_numeric_id("5"), Some("5".into()));
        assert_eq!(
            extract_numeric_id("https://www.deezer.com/profile/123456"),
            Some("123456".into())
        );
        assert_eq!(
            extract_numeric_id("https://www.deezer.com/en/profile/789"),
            Some("789".into())
        );
        assert_eq!(extract_numeric_id("deezer.com/user/42"), Some("42".into()));

        let track = FfiTrack {
            id: "deezer:3135556".into(),
            provider: "deezer".into(),
            title: "Harder, Better, Faster, Stronger".into(),
            artist: "Daft Punk".into(),
            album: "Discovery".into(),
            duration_ms: 224_000,
            artwork_url: None,
            playback_uri: Some("https://example.invalid/audio.mp3".into()),
            is_preview: true,
            origin: "public_catalog".into(),
        };

        let cap = core.get_track_download_capability(track.id.clone());
        assert!(cap.is_available);
        assert!(cap.unavailable_reason.is_none());

        // Custom accent color roundtrip & reset
        let updated_theme = core.set_custom_accent_color(Some("ff4081".into())).unwrap();
        assert_eq!(updated_theme.accent_color, "#FF4081");
        assert_eq!(core.get_current_theme().accent_color, "#FF4081");
        assert!(core.set_custom_accent_color(Some("not-a-color".into())).is_err());
        let reset_theme = core.set_custom_accent_color(None).unwrap();
        assert_eq!(reset_theme.accent_color.to_uppercase(), "#3DAEE9");

        // Custom theme creation & persistence
        let saved_custom = core
            .save_user_custom_theme(FfiCustomThemeSpec {
                id: "org.custom.user.test1".into(),
                name: "Test Custom Theme".into(),
                background_hex: "#120924".into(),
                card_hex: "#231442".into(),
                accent_hex: "#E066FF".into(),
                text_hex: "#F8F2FF".into(),
            })
            .unwrap();
        assert_eq!(saved_custom.id, "org.custom.user.test1");
        assert_eq!(core.list_user_custom_themes().unwrap().len(), 1);

        // Stats, history, resume & export
        core.record_track_play(track.clone(), true, true, 1_700_000_000_000)
            .unwrap();
        let total = core
            .record_listening_tick(track.clone(), 65_000, true, 1_700_000_065_000)
            .unwrap();
        assert_eq!(total, 65_000);
        assert_eq!(core.format_duration_tr(total), "1 dk 5 sn");

        let quick_artists = core.get_quick_artists(5, vec![]).unwrap();
        assert_eq!(quick_artists.len(), 1);
        assert_eq!(quick_artists[0].artist, "Daft Punk");

        let mix = core.generate_mood_mix("enerjik".into(), 5, vec![track.clone()]);
        assert_eq!(mix.len(), 1);

        let exported = core.build_user_data_export_json(1_700_000_100_000).unwrap();
        assert!(exported.contains("Daft Punk"));

        // Home Layout (SQLite-backed Rust customization)
        let catalog = core.get_home_widget_catalog();
        assert!(catalog.iter().any(|c| c.kind == "widget_recent_tracks"));
        let layout = core.get_home_layout().unwrap();
        assert!(!layout.is_empty());
        let added = core
            .add_home_widget("widget_listening_summary".into(), Some(0))
            .unwrap();
        assert_eq!(added[0].kind, "widget_listening_summary");
        let added_id = added[0].instance_id.clone();
        let patched = core
            .customize_home_widget(
                added_id.clone(),
                FfiHomeWidgetPatch {
                    enabled: None,
                    size: Some(FfiHomeWidgetSize::Expanded),
                    style: Some(FfiHomeWidgetStyle::Minimal),
                    max_items: None,
                    show_subtitle: Some(false),
                    clear_custom_title: false,
                    custom_title: Some("Özetim".into()),
                    clear_accent_hex: false,
                    accent_hex: Some("#3DAEE9".into()),
                },
            )
            .unwrap();
        assert_eq!(patched[0].custom_title.as_deref(), Some("Özetim"));
        let reloaded = core.get_home_layout().unwrap();
        assert_eq!(reloaded[0].instance_id, added_id);
        let reset = core.reset_home_layout().unwrap();
        assert!(reset.iter().all(|i| i.instance_id != added_id));
    }

    #[test]
    fn test_now_playing_glass_palette_translucency_and_contrast() {
        let palette = compute_now_playing_glass_palette(
            0xFF16_2B26,
            0xFF0B_1614,
            0xFF7A_B3A8,
            0xFFD8_B48A,
            0xFF10_171D,
            true,
        );
        assert_eq!(palette.base_backdrop_argb >> 24, 0xFF);
        assert!((palette.veil_top_argb >> 24) < 0x60);
        assert!((palette.veil_mid_argb >> 24) < (palette.veil_top_argb >> 24));
        assert!((palette.veil_bottom_argb >> 24) < 0x80);
        assert!(palette.beam_intensity > 1.2);

        let effective_bg = blend_argb(palette.base_backdrop_argb, palette.beam_primary_argb, 0.28);
        assert!(contrast_ratio(palette.text_primary_argb, effective_bg) >= 7.0);
        assert!(contrast_ratio(palette.text_secondary_argb, effective_bg) >= 4.5);

        let disabled = compute_now_playing_glass_palette(
            0xFF18_1818,
            0xFF10_1010,
            0xFFE0_E0E0,
            0xFF80_8080,
            0xFF12_1212,
            false,
        );
        assert_eq!(disabled.beam_intensity, 0.0);
        assert!((disabled.veil_top_argb >> 24) > 0xDF);
    }
}
