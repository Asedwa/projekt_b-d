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
use core_settings::{Language, Settings, SettingsService};
use core_storage::{
    now_epoch_ms, Database, StoredHistoryEntry, StoredPinnedItem, StoredPlaylistRecord,
    StoredPlaylistTrackEntry, StoredResumePlaybackState, StoredTrackListeningStat,
    StoredTrackRecord,
};
use core_themes::{CustomThemeSpec, ThemeManifest, ThemeRegistry};
use provider_deezer::{DeezerAppConfig, DeezerProvider};
use provider_dev::DevProvider;
use provider_local::{LocalProvider, LocalTrackInput};

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
    active_theme_id: RwLock<String>,
    tokio_runtime: tokio::runtime::Runtime,
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

        // Load existing local tracks from DB into memory
        if let Ok(tracks) = database.tracks_by_provider(&ProviderId::local()) {
            local_provider.load(tracks);
        }

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
            active_theme_id,
            tokio_runtime,
        }))
    }

    pub fn get_version(&self) -> String {
        "PlasmaWave 0.1.0-alpha (Rust Core + KDE Breeze)".into()
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
        self.local_provider
            .all()
            .into_iter()
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
        })?;
        *self.active_theme_id.write().unwrap() = updated.theme_id.clone();
        Ok(FfiSettings::from(updated))
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
    pub fn record_track_play(
        &self,
        track: FfiTrack,
        collect_stats: bool,
        collect_history: bool,
        timestamp_ms: u64,
    ) -> FfiResult<()> {
        let stored = StoredTrackRecord::from(track);
        let ts = if timestamp_ms > 0 {
            timestamp_ms
        } else {
            now_epoch_ms()
        };
        if collect_stats {
            self.database.record_track_play_stat(&stored, ts)?;
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
        if !collect_stats {
            return Ok(self.database.get_total_listening_time_ms()?);
        }
        let stored = StoredTrackRecord::from(track);
        let ts = if timestamp_ms > 0 {
            timestamp_ms
        } else {
            now_epoch_ms()
        };
        let total = self.database.record_listening_tick(&stored, delta_ms, ts)?;
        Ok(total)
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

        let stats_json: Vec<serde_json::Value> = stats
            .iter()
            .map(|s| {
                serde_json::json!({
                    "trackId": s.track.id,
                    "title": s.track.title,
                    "artist": s.track.artist,
                    "album": s.track.album,
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
                    "title": t.title,
                    "artist": t.artist,
                    "album": t.album,
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
            "engine": "PlasmaWave Rust Core (SQLite v2)",
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
            "recentHistory": recent_json,
            "resumeState": resume_json,
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
    }
}
