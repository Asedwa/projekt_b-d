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
use core_storage::Database;
use core_themes::{ThemeManifest, ThemeRegistry};
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
        }
    }
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
            s.custom_accent_color = settings.custom_accent_color.clone();
        })?;
        *self.active_theme_id.write().unwrap() = updated.theme_id.clone();
        Ok(FfiSettings::from(updated))
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
    }
}
