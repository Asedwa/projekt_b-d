package org.kde.plasmawave.player

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.deezer.DeezerSession
import org.kde.plasmawave.deezer.DeezerException
import org.kde.plasmawave.deezer.StreamingQuality
import org.kde.plasmawave.download.Downloads
import java.io.IOException
import uniffi.plasmawave_core.FfiAdvanceReason
import uniffi.plasmawave_core.FfiQueueEntry
import uniffi.plasmawave_core.FfiQueueSnapshot
import uniffi.plasmawave_core.FfiRepeatMode
import uniffi.plasmawave_core.FfiTrack

data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

data class PlayerUiState(
    val currentEntry: FfiQueueEntry? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueSnapshot: FfiQueueSnapshot? = null,
    val isShuffle: Boolean = false,
    val repeatMode: FfiRepeatMode = FfiRepeatMode.OFF,
    val audioQualityLabel: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PlaybackManager"

        @Volatile
        private var instance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    // Serialize Rust FFI + SQLite queue operations off the UI thread so rapid taps stay ordered without jank.
    private val queueDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playbackPlayer: Player? = null
    private var onQueueChanged: ((FfiQueueSnapshot) -> Unit)? = null
    private var clearWhenConnected = false
    private var pendingPlayback: Pair<FfiQueueEntry, Long>? = null
    private val queueMutex = Mutex()
    private var playbackGeneration = 0L
    private val wakeLockSync = Any()
    private val transitionWakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlasmaWave:TrackTransition").apply {
                setReferenceCounted(false)
            }
    }
    @Suppress("DEPRECATION")
    private val transitionWifiLock by lazy {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(mode, "PlasmaWave:TransitionWifi").apply {
                setReferenceCounted(false)
            }
    }

    private fun acquireTransitionLocks(timeoutMs: Long = 30_000L) {
        synchronized(wakeLockSync) {
            runCatching { transitionWakeLock.acquire(timeoutMs) }
            runCatching { if (!transitionWifiLock.isHeld) transitionWifiLock.acquire() }
        }
    }

    private fun releaseTransitionLocks() {
        synchronized(wakeLockSync) {
            runCatching {
                if (transitionWakeLock.isHeld) transitionWakeLock.release()
            }
            runCatching {
                if (transitionWifiLock.isHeld) transitionWifiLock.release()
            }
        }
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()
    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()
    private val audioFxPrefs = context.getSharedPreferences("audio_fx", Context.MODE_PRIVATE)
    private val _soundBoostEnabled = MutableStateFlow(audioFxPrefs.getBoolean("sound_boost_enabled", false))
    val soundBoostEnabled: StateFlow<Boolean> = _soundBoostEnabled.asStateFlow()
    private val _soundBoostPercent = MutableStateFlow(audioFxPrefs.getInt("sound_boost_percent", 0).coerceIn(0, 100))
    val soundBoostPercent: StateFlow<Int> = _soundBoostPercent.asStateFlow()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    fun setSoundBoostEnabled(enabled: Boolean) {
        _soundBoostEnabled.value = enabled
        audioFxPrefs.edit().putBoolean("sound_boost_enabled", enabled).apply()
        scope.launch(Dispatchers.Main.immediate) {
            applySoundBoostForPlayer(playbackPlayer)
        }
    }

    fun setSoundBoostPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _soundBoostPercent.value = clamped
        audioFxPrefs.edit().putInt("sound_boost_percent", clamped).apply()
        scope.launch(Dispatchers.Main.immediate) {
            applySoundBoostForPlayer(playbackPlayer)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applySoundBoostForPlayer(player: Player?, explicitSessionId: Int? = null) {
        val sessionId = explicitSessionId
            ?: (player as? ExoPlayer)?.audioSessionId
            ?: C.AUDIO_SESSION_ID_UNSET
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return

        val enabled = _soundBoostEnabled.value
        val percent = if (enabled) _soundBoostPercent.value.coerceIn(0, 100) else 0
        if (percent == 0 && loudnessEnhancer == null) return

        runCatching {
            if (loudnessEnhancer == null || loudnessSessionId != sessionId) {
                runCatching { loudnessEnhancer?.release() }
                loudnessEnhancer = LoudnessEnhancer(sessionId)
                loudnessSessionId = sessionId
            }
            val gainMb = (percent * 15).coerceIn(0, 1500) // 0 dB .. +15 dB
            loudnessEnhancer?.setTargetGain(gainMb)
            loudnessEnhancer?.enabled = enabled && percent > 0
        }.onFailure { err ->
            Log.w(TAG, "LoudnessEnhancer unavailable for session $sessionId", err)
            releaseSoundBoost()
        }
    }

    private fun releaseSoundBoost() {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        loudnessSessionId = C.AUDIO_SESSION_ID_UNSET
    }
    // Position ticks live in `progress`, so `overview` / `currentTrackId` never churn during playback.
    val currentTrackId = uiState.map { it.currentEntry?.track?.id }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)
    val overview: StateFlow<PlayerUiState> = uiState

    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var prefetchJob: Job? = null
    @Volatile private var activePrefetchWriter: CacheWriter? = null
    @Volatile private var intentGeneration = 0L
    private var currentAudioFormat: Format? = null
    private var lastKnownTrackId: String? = null
    private var lastKnownPositionMs: Long = 0L
    private var errorRetryTrackId: String? = null
    private var errorRetryCount: Int = 0
    private var errorRetryJob: Job? = null
    private var recoveryNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var nextSyncJob: Job? = null

    init {
        initMediaController()
        refreshQueueSnapshot()
        observeQualityChanges()
    }

    private fun initMediaController() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlasmaPlaybackService::class.java)
        )
        // Keep the service connection, but handle playback on its player directly. Track-end
        // processing must not depend on a UI controller receiving a remote state update.
        val future = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    controllerFuture = null
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            scope.launch {
                try {
                    future.get()
                } catch (e: Exception) {
                    if (controllerFuture === future) controllerFuture = null
                    Log.e(TAG, "Failed to connect to MediaController", e)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    internal fun attachPlayer(player: Player, onQueueChanged: (FfiQueueSnapshot) -> Unit) {
        playbackPlayer?.removeListener(playerListener)
        playbackPlayer = player
        this.onQueueChanged = onQueueChanged
        player.addListener(playerListener)
        applySoundBoostForPlayer(player)
        player.repeatMode = if (_uiState.value.repeatMode == FfiRepeatMode.ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        _uiState.value.queueSnapshot?.let(onQueueChanged)
        if (clearWhenConnected) {
            player.stop()
            player.clearMediaItems()
            clearWhenConnected = false
        }
        val pending = pendingPlayback
        pendingPlayback = null
        if (pending != null) {
            launchQueueOperation {
                if (playbackPlayer === player) prepareAndPlay(pending.first, pending.second)
            }
        }
        syncPlayerState()
    }

    internal fun detachPlayer(player: Player) {
        if (playbackPlayer !== player) return
        cancelPrefetch()
        nextSyncJob?.cancel()
        nextSyncJob = null
        clearErrorRecoveryNetworkCallback()
        releaseTransitionLocks()
        releaseSoundBoost()
        player.removeListener(playerListener)
        playbackPlayer = null
        onQueueChanged = null
        playbackGeneration++
        stopProgressTicker()
        _uiState.value = _uiState.value.copy(isPlaying = false)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
    }

    // Keep the entire queue change + stream preparation ordered, including suspending lookups.
    private fun launchQueueOperation(block: suspend () -> Unit): Job = scope.launch {
        queueMutex.withLock { block() }
    }

    private fun clearErrorRecoveryNetworkCallback() {
        recoveryNetworkCallback?.let { cb ->
            runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        recoveryNetworkCallback = null
    }

    private fun registerErrorRecoveryNetworkCallback(entry: FfiQueueEntry, resumePos: Long) {
        clearErrorRecoveryNetworkCallback()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val expectedTrackId = entry.track.id
        val expectedIntent = intentGeneration
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    if (intentGeneration != expectedIntent) {
                        clearErrorRecoveryNetworkCallback()
                        return@launch
                    }
                    val player = playbackPlayer ?: return@launch
                    if (!player.playWhenReady || _uiState.value.currentEntry?.track?.id != expectedTrackId) {
                        clearErrorRecoveryNetworkCallback()
                        return@launch
                    }
                    clearErrorRecoveryNetworkCallback()
                    acquireTransitionLocks(30_000L)
                    try {
                        DeezerSession.get(context).invalidateResolvedMedia(expectedTrackId)
                        launchQueueOperation {
                            if (_uiState.value.currentEntry?.track?.id == expectedTrackId &&
                                intentGeneration == expectedIntent &&
                                playbackPlayer?.playWhenReady == true
                            ) {
                                prepareAndPlay(entry, resumePos, automatic = false, resetRetry = true)
                            }
                        }.join()
                    } finally {
                        releaseTransitionLocks()
                    }
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            recoveryNetworkCallback = cb
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            applySoundBoostForPlayer(playbackPlayer, explicitSessionId = audioSessionId)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                lastKnownPositionMs = 0L
                _uiState.value.currentEntry?.let { entry ->
                    _progress.value = PlaybackProgress(0L, entry.track.durationMs.toLong())
                }
                return
            }
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val player = playbackPlayer ?: return
            if (_sleepTimerState.value is SleepTimerState.EndOfTrack) {
                _sleepTimerState.value = SleepTimerState.Off
                player.pause()
                Toast.makeText(context, "Şarkı bitti, uyku zamanı modu oynatmayı duraklattı", Toast.LENGTH_SHORT).show()
                return
            }
            cancelPrefetch()
            while (player.currentMediaItemIndex > 0) {
                player.removeMediaItem(0)
            }
            val generation = playbackGeneration
            val expectedIntent = intentGeneration
            acquireTransitionLocks(30_000L)
            val job = launchQueueOperation {
                try {
                    if (playbackPlayer !== player || playbackGeneration != generation ||
                        intentGeneration != expectedIntent
                    ) return@launchQueueOperation
                    val (next, snapshot) = withContext(queueDispatcher) {
                        val advanced = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.TRACK_ENDED)
                        advanced to readSnapshot()
                    }
                    applySnapshot(snapshot)
                    if (playbackPlayer !== player || playbackGeneration != generation ||
                        intentGeneration != expectedIntent
                    ) return@launchQueueOperation
                    if (next != null && next.track.id == mediaItem?.mediaId) {
                        val deezerSession = DeezerSession.get(context)
                        val streamingQuality = deezerSession.currentStreamingQuality()
                        lastKnownTrackId = next.track.id
                        lastKnownPositionMs = 0L
                        errorRetryTrackId = next.track.id
                        errorRetryCount = 0
                        currentAudioFormat = null
                        val label = computeInitialQualityLabel(
                            entry = next,
                            offlinePath = null,
                            downloadedFormat = null,
                            streamingQuality = streamingQuality
                        )
                        _uiState.value = _uiState.value.copy(currentEntry = next, audioQualityLabel = label)
                        _progress.value = PlaybackProgress(0L, next.track.durationMs.toLong())
                        syncNextExoPlayerItem(snapshot, generation)
                        scheduleLookaheadPrefetch(snapshot, intentGeneration)
                    } else if (next != null && player.playWhenReady) {
                        prepareAndPlay(next, automatic = true)
                    } else {
                        player.pause()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling auto media transition", e)
                }
            }
            job.invokeOnCompletion {
                releaseTransitionLocks()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val currentEntry = _uiState.value.currentEntry
            val deezerError = generateSequence(error.cause) { it.cause }
                .filterIsInstance<DeezerException>()
                .firstOrNull()
            val deezerMsg = deezerError?.message
            val isPermanentAuthOrRights = deezerMsg != null && (
                deezerMsg.contains("oturum", ignoreCase = true) ||
                deezerMsg.contains("giriş", ignoreCase = true) ||
                deezerMsg.contains("kullanılamıyor", ignoreCase = true) ||
                deezerMsg.contains("kimliği", ignoreCase = true)
            )
            val isRecoverableIo = !isPermanentAuthOrRights && (
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                generateSequence(error.cause) { it.cause }.any { it is IOException }
            )
            if (currentEntry != null && isRecoverableIo && currentEntry.track.provider == "deezer") {
                val trackId = currentEntry.track.id
                if (errorRetryTrackId != trackId) {
                    errorRetryTrackId = trackId
                    errorRetryCount = 0
                }
                val backoffs = longArrayOf(700L, 1500L, 3000L, 5000L)
                val resumePos = if (lastKnownTrackId == trackId) lastKnownPositionMs else 0L
                if (errorRetryCount < backoffs.size) {
                    val waitMs = backoffs[errorRetryCount]
                    errorRetryCount++
                    val expectedIntent = intentGeneration
                    DeezerSession.get(context).invalidateResolvedMedia(trackId)
                    errorRetryJob?.cancel()
                    // Keep CPU + Wi-Fi awake across the retry delay so Doze doesn't freeze it until screen unlock.
                    acquireTransitionLocks(waitMs + 20_000L)
                    errorRetryJob = scope.launch {
                        try {
                            delay(waitMs)
                            if (intentGeneration != expectedIntent) return@launch
                            if (playbackPlayer?.playWhenReady == false) return@launch
                            launchQueueOperation {
                                if (_uiState.value.currentEntry?.track?.id == trackId &&
                                    intentGeneration == expectedIntent &&
                                    playbackPlayer?.playWhenReady != false
                                ) {
                                    prepareAndPlay(currentEntry, resumePos, automatic = false, resetRetry = false)
                                }
                            }.join()
                        } finally {
                            releaseTransitionLocks()
                        }
                    }
                    return
                }
                if (playbackPlayer?.playWhenReady != false) {
                    registerErrorRecoveryNetworkCallback(currentEntry, resumePos)
                }
            }
            val message = deezerMsg
                ?: if (isRecoverableIo) "Bağlantı kesildi; internet gelince otomatik devam edecek."
                else "Şarkı oynatılamadı. Bağlantını ve Deezer hesabını kontrol et."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            if (isPlaying) {
                errorRetryCount = 0
                clearErrorRecoveryNetworkCallback()
                startProgressTicker()
            } else {
                stopProgressTicker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onTrackEnded()
            }
            syncPlayerState()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateQualityFromTracks(tracks)
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                playbackPlayer?.let { player ->
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.coerceAtLeast(0L)
                    _uiState.value.currentEntry?.track?.id?.let { id ->
                        if (player.playbackState != Player.STATE_IDLE) {
                            lastKnownTrackId = id
                            lastKnownPositionMs = pos
                        }
                    }
                    _progress.value = PlaybackProgress(
                        positionMs = pos,
                        durationMs = dur
                    )
                }
                delay(300)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun syncPlayerState() {
        val player = playbackPlayer ?: return
        _uiState.value = _uiState.value.copy(isPlaying = player.isPlaying)
        if (player.playbackState != Player.STATE_IDLE) {
            val pos = player.currentPosition.coerceAtLeast(0L)
            _uiState.value.currentEntry?.track?.id?.let { id ->
                lastKnownTrackId = id
                lastKnownPositionMs = pos
            }
            _progress.value = PlaybackProgress(
                positionMs = pos,
                durationMs = player.duration.coerceAtLeast(0L)
            )
        }
    }

    private fun readSnapshot(): FfiQueueSnapshot = PlasmaWaveApp.core.getQueueSnapshot()

    private fun applySnapshot(snapshot: FfiQueueSnapshot) {
        val cur = snapshot.currentIndex?.let { idx ->
            snapshot.entries.getOrNull(idx.toInt())
        }
        _uiState.value = _uiState.value.copy(
            queueSnapshot = snapshot,
            currentEntry = cur,
            isShuffle = snapshot.isShuffled,
            repeatMode = snapshot.repeatMode
        )
        onQueueChanged?.invoke(snapshot)
    }

    fun refreshQueueSnapshot() {
        launchQueueOperation {
            try {
                val snapshot = withContext(queueDispatcher) { readSnapshot() }
                applySnapshot(snapshot)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing queue snapshot", e)
            }
        }
    }

    fun playTrackList(tracks: List<FfiTrack>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        val myIntent = ++intentGeneration
        cancelPrefetch()
        clearErrorRecoveryNetworkCallback()
        launchQueueOperation {
            try {
                val (entry, snapshot) = withContext(queueDispatcher) {
                    val replaced = PlasmaWaveApp.core.queueReplace(tracks, startIndex.toUInt())
                    replaced to readSnapshot()
                }
                applySnapshot(snapshot)
                if (intentGeneration != myIntent) return@launchQueueOperation
                if (entry != null) {
                    prepareAndPlay(entry, startPositionMs)
                } else {
                    pendingPlayback = null
                    playbackGeneration++
                    playbackPlayer?.stop()
                    playbackPlayer?.clearMediaItems()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error replacing queue", e)
            }
        }
    }

    fun playNext(tracks: List<FfiTrack>) {
        launchQueueOperation {
            try {
                val snapshot = withContext(queueDispatcher) {
                    PlasmaWaveApp.core.queuePlayNext(tracks)
                    readSnapshot()
                }
                applySnapshot(snapshot)
                syncNextExoPlayerItem(snapshot, playbackGeneration)
                scheduleLookaheadPrefetch(snapshot, intentGeneration)
            } catch (e: Exception) {
                Log.e(TAG, "Error on playNext", e)
            }
        }
    }

    fun addToQueue(tracks: List<FfiTrack>) {
        launchQueueOperation {
            try {
                val snapshot = withContext(queueDispatcher) {
                    PlasmaWaveApp.core.queueAdd(tracks)
                    readSnapshot()
                }
                applySnapshot(snapshot)
                syncNextExoPlayerItem(snapshot, playbackGeneration)
                scheduleLookaheadPrefetch(snapshot, intentGeneration)
            } catch (e: Exception) {
                Log.e(TAG, "Error on addToQueue", e)
            }
        }
    }

    fun jumpToQueueIndex(entryId: ULong) {
        val myIntent = ++intentGeneration
        cancelPrefetch()
        clearErrorRecoveryNetworkCallback()
        launchQueueOperation {
            try {
                val (entry, snapshot) = withContext(queueDispatcher) {
                    val jumped = PlasmaWaveApp.core.queueJump(entryId)
                    jumped to readSnapshot()
                }
                applySnapshot(snapshot)
                if (intentGeneration != myIntent) return@launchQueueOperation
                entry?.let { prepareAndPlay(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error jumping to entry", e)
            }
        }
    }

    fun removeQueueEntry(entryId: ULong) {
        launchQueueOperation {
            try {
                val (currentRemoved, snapshot) = withContext(queueDispatcher) {
                    val removed = PlasmaWaveApp.core.queueRemove(entryId)
                    removed to readSnapshot()
                }
                applySnapshot(snapshot)
                if (currentRemoved == true) {
                    _uiState.value.currentEntry?.let { prepareAndPlay(it) } ?: run {
                        pendingPlayback = null
                        playbackGeneration++
                        playbackPlayer?.stop()
                        playbackPlayer?.clearMediaItems()
                    }
                } else {
                    syncNextExoPlayerItem(snapshot, playbackGeneration)
                    scheduleLookaheadPrefetch(snapshot, intentGeneration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing queue entry", e)
            }
        }
    }

    fun clearQueue() {
        intentGeneration++
        cancelPrefetch()
        clearErrorRecoveryNetworkCallback()
        launchQueueOperation {
            try {
                val snapshot = withContext(queueDispatcher) {
                    PlasmaWaveApp.core.queueReplace(emptyList(), 0u)
                    readSnapshot()
                }
                pendingPlayback = null
                errorRetryJob?.cancel()
                errorRetryJob = null
                lastKnownTrackId = null
                lastKnownPositionMs = 0L
                errorRetryCount = 0
                playbackGeneration++
                val player = playbackPlayer
                if (player == null) {
                    clearWhenConnected = true
                } else {
                    player.stop()
                    player.clearMediaItems()
                }
                stopProgressTicker()
                applySnapshot(snapshot)
                _uiState.value = _uiState.value.copy(currentEntry = null, isPlaying = false)
                _progress.value = PlaybackProgress()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing queue", e)
                Toast.makeText(context, "Kuyruk temizlenemedi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopPlayback() {
        intentGeneration++
        cancelPrefetch()
        clearErrorRecoveryNetworkCallback()
        errorRetryJob?.cancel()
        errorRetryJob = null
        lastKnownTrackId = null
        lastKnownPositionMs = 0L
        errorRetryCount = 0
        playbackGeneration++
        pendingPlayback = null
        playbackPlayer?.stop()
    }

    fun togglePlayPause() {
        val player = playbackPlayer ?: run {
            _uiState.value.currentEntry?.let { entry ->
                val resumePos = if (lastKnownTrackId == entry.track.id) lastKnownPositionMs else 0L
                launchQueueOperation { prepareAndPlay(entry, resumePos) }
            }
            return
        }
        if (player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        ) {
            clearErrorRecoveryNetworkCallback()
            errorRetryJob?.cancel()
            errorRetryJob = null
            player.pause()
        } else {
            if ((player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) &&
                _uiState.value.currentEntry != null
            ) {
                _uiState.value.currentEntry?.let { entry ->
                    val resumePos = if (player.playbackState == Player.STATE_IDLE && lastKnownTrackId == entry.track.id) {
                        lastKnownPositionMs
                    } else {
                        0L
                    }
                    launchQueueOperation { prepareAndPlay(entry, resumePos) }
                }
            } else {
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        playbackPlayer?.seekTo(safePos)
        _uiState.value.currentEntry?.track?.id?.let { id ->
            lastKnownTrackId = id
            lastKnownPositionMs = safePos
        }
        _progress.value = _progress.value.copy(positionMs = safePos)
    }

    fun skipNext(): Job {
        val myIntent = ++intentGeneration
        cancelPrefetch()
        clearErrorRecoveryNetworkCallback()
        return launchQueueOperation {
            try {
                val (next, snapshot) = withContext(queueDispatcher) {
                    val advanced = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.USER_SKIP)
                    advanced to readSnapshot()
                }
                applySnapshot(snapshot)
                if (intentGeneration != myIntent) return@launchQueueOperation
                if (next != null) {
                    prepareAndPlay(next)
                } else {
                    playbackPlayer?.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error skipping to next", e)
            }
        }
    }

    fun skipPrevious(): Job {
        val myIntent = ++intentGeneration
        cancelPrefetch()
        clearErrorRecoveryNetworkCallback()
        return launchQueueOperation {
            try {
                val pos = playbackPlayer?.currentPosition?.toULong() ?: 0uL
                val (res, snapshot) = withContext(queueDispatcher) {
                    val prev = PlasmaWaveApp.core.queuePrevious(pos)
                    prev to readSnapshot()
                }
                applySnapshot(snapshot)
                if (intentGeneration != myIntent) return@launchQueueOperation
                if (res.shouldRestartCurrent) {
                    playbackPlayer?.seekTo(0L)
                } else {
                    res.movedEntry?.let { prepareAndPlay(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error skipping previous", e)
            }
        }
    }

    fun startSleepTimerMinutes(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (minutes <= 0) {
            _sleepTimerState.value = SleepTimerState.Off
            return
        }
        val durationMs = minutes * 60_000L
        val endElapsedMs = SystemClock.elapsedRealtime() + durationMs
        _sleepTimerState.value = SleepTimerState.Countdown(endElapsedMs, durationMs)
        Toast.makeText(context, "Uyku zamanı modu: $minutes dakika", Toast.LENGTH_SHORT).show()
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = endElapsedMs - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    onSleepTimerExpired()
                    break
                }
                _sleepTimerState.value = SleepTimerState.Countdown(endElapsedMs, remaining)
                delay(minOf(1000L, remaining))
            }
        }
    }

    fun startSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.EndOfTrack
        syncNextExoPlayerItem(_uiState.value.queueSnapshot, playbackGeneration)
        Toast.makeText(context, "Uyku zamanı modu: Bu şarkı bitince duracak", Toast.LENGTH_SHORT).show()
    }

    fun cancelSleepTimer(showToast: Boolean = true) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.Off
        syncNextExoPlayerItem(_uiState.value.queueSnapshot, playbackGeneration)
        if (showToast) {
            Toast.makeText(context, "Uyku zamanı modu kapatıldı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onSleepTimerExpired() {
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.Off
        playbackPlayer?.pause()
        Toast.makeText(context, "Uyku zamanı modu süresi doldu, oynatma duraklatıldı", Toast.LENGTH_SHORT).show()
    }

    private fun onTrackEnded() {
        val player = playbackPlayer ?: return
        if (!player.playWhenReady) return
        if (_sleepTimerState.value is SleepTimerState.EndOfTrack) {
            _sleepTimerState.value = SleepTimerState.Off
            player.pause()
            Toast.makeText(context, "Şarkı bitti, uyku zamanı modu oynatmayı duraklattı", Toast.LENGTH_SHORT).show()
            return
        }
        cancelPrefetch()
        val generation = playbackGeneration
        // ExoPlayer releases its own wake lock at STATE_ENDED. Bridge the short queue/URI
        // lookup until prepare() enables its buffering wake lock for the next stream.
        acquireTransitionLocks(30_000L)
        val job = launchQueueOperation {
            try {
                if (playbackPlayer !== player || playbackGeneration != generation ||
                    player.playbackState != Player.STATE_ENDED || !player.playWhenReady
                ) return@launchQueueOperation
                val (next, snapshot) = withContext(queueDispatcher) {
                    val advanced = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.TRACK_ENDED)
                    advanced to readSnapshot()
                }
                applySnapshot(snapshot)
                if (playbackPlayer === player && playbackGeneration == generation && player.playWhenReady) {
                    if (next != null) prepareAndPlay(next, automatic = true) else player.pause()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error advancing on track end", e)
            }
        }
        job.invokeOnCompletion {
            releaseTransitionLocks()
        }
    }

    fun toggleRepeat() {
        launchQueueOperation {
            try {
                val current = _uiState.value.repeatMode
                val next = when (current) {
                    FfiRepeatMode.OFF -> FfiRepeatMode.ALL
                    FfiRepeatMode.ALL -> FfiRepeatMode.ONE
                    FfiRepeatMode.ONE -> FfiRepeatMode.OFF
                }
                val snapshot = withContext(queueDispatcher) {
                    PlasmaWaveApp.core.queueSetRepeat(next)
                    readSnapshot()
                }
                applySnapshot(snapshot)
                playbackPlayer?.repeatMode = if (next == FfiRepeatMode.ONE) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                syncNextExoPlayerItem(snapshot, playbackGeneration)
                scheduleLookaheadPrefetch(snapshot, intentGeneration)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling repeat", e)
            }
        }
    }

    fun toggleShuffle() {
        launchQueueOperation {
            try {
                val next = !_uiState.value.isShuffle
                val snapshot = withContext(queueDispatcher) {
                    PlasmaWaveApp.core.queueSetShuffle(next)
                    readSnapshot()
                }
                applySnapshot(snapshot)
                syncNextExoPlayerItem(snapshot, playbackGeneration)
                scheduleLookaheadPrefetch(snapshot, intentGeneration)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling shuffle", e)
            }
        }
    }

    private fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        runCatching { activePrefetchWriter?.cancel() }
        activePrefetchWriter = null
    }

    private fun scheduleLookaheadPrefetch(snapshot: FfiQueueSnapshot?, expectedIntent: Long) {
        cancelPrefetch()
        if (snapshot == null) return
        val upcoming = selectLookaheadTracks(snapshot, maxCount = 2)
            .filter { it.provider == "deezer" }
        if (upcoming.isEmpty()) return
        val deezerSession = DeezerSession.get(context)
        if (!deezerSession.hasSavedSession) return

        prefetchJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            if (!isActive || intentGeneration != expectedIntent) return@launch
            val downloads = Downloads.get(context)
            val streamCandidates = upcoming.filter { downloads.findCompletedTrack(it.id) == null }
            if (streamCandidates.isEmpty()) return@launch

            // 1. Pre-resolve URLs for the next 2 tracks in parallel
            coroutineScope {
                streamCandidates.map { track ->
                    async {
                        if (isActive && intentGeneration == expectedIntent) {
                            deezerSession.preResolve(track.id)
                        }
                    }
                }.forEach { it.await() }
            }

            if (!isActive || intentGeneration != expectedIntent) return@launch
            // 2. Pre-buffer the head of the immediate next track into SimpleCache
            val nextTrack = streamCandidates.first()
            val cleanId = nextTrack.id.removePrefix("deezer:")
            val quality = deezerSession.currentStreamingQuality()
            val cacheKey = deezerSession.cacheKeyFor(cleanId, quality)
            val maxBytes = if (deezerSession.isOnUnmeteredWifi()) 256L * 1024L else 128L * 1024L
            val writer = PlayerMediaCache.createPrefetchWriter(context, cleanId, cacheKey, maxBytes) ?: return@launch
            activePrefetchWriter = writer
            try {
                writer.cache()
            } catch (_: Exception) {
                // Best-effort prefetch; ignore cancellations or transient network blips
            } finally {
                if (activePrefetchWriter === writer) activePrefetchWriter = null
            }
        }
    }

    private data class PreparedMediaItem(
        val mediaItem: MediaItem,
        val offlinePath: String?,
        val downloadedFormat: String?,
        val streamingQuality: StreamingQuality
    )

    private fun buildPreparedMediaItem(track: FfiTrack): PreparedMediaItem? {
        val deezerSession = DeezerSession.get(context)
        val completedDownload = if (track.provider == "deezer") {
            Downloads.get(context).findCompletedTrack(track.id)
        } else {
            null
        }
        val useFullDeezer = track.provider == "deezer" && deezerSession.hasSavedSession && completedDownload == null
        // Old Rust preview downloads are ignored when streaming or full Downloads exist.
        val offlinePath = if (useFullDeezer || completedDownload != null) null else {
            try { PlasmaWaveApp.core.getOfflinePath(track.id) } catch (_: Exception) { null }
        }
        val uriStr = when {
            completedDownload?.uri != null -> completedDownload.uri
            offlinePath != null -> "file://$offlinePath"
            useFullDeezer -> "deezer://track/${track.id.removePrefix("deezer:")}"
            else -> track.playbackUri ?: return null
        }
        val uri = Uri.parse(uriStr)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply {
                track.artworkUrl?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()

        val streamingQuality = if (useFullDeezer) deezerSession.currentStreamingQuality() else StreamingQuality.AUTO
        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
        if (useFullDeezer) {
            mediaItemBuilder.setCustomCacheKey(deezerSession.cacheKeyFor(track.id, streamingQuality))
        }
        return PreparedMediaItem(
            mediaItem = mediaItemBuilder.build(),
            offlinePath = offlinePath,
            downloadedFormat = completedDownload?.quality?.format,
            streamingQuality = streamingQuality
        )
    }

    private fun syncNextExoPlayerItem(snapshot: FfiQueueSnapshot?, expectedGeneration: Long) {
        nextSyncJob?.cancel()
        val player = playbackPlayer ?: return
        val shouldQueueNext = snapshot != null &&
            snapshot.repeatMode != FfiRepeatMode.ONE &&
            _sleepTimerState.value !is SleepTimerState.EndOfTrack
        if (!shouldQueueNext) {
            val curIdx = player.currentMediaItemIndex.coerceAtLeast(0)
            while (player.mediaItemCount > curIdx + 1) {
                player.removeMediaItem(player.mediaItemCount - 1)
            }
            return
        }
        val nextTrack = selectLookaheadTracks(snapshot!!, maxCount = 1).firstOrNull()
        if (nextTrack == null) {
            val curIdx = player.currentMediaItemIndex.coerceAtLeast(0)
            while (player.mediaItemCount > curIdx + 1) {
                player.removeMediaItem(player.mediaItemCount - 1)
            }
            return
        }
        nextSyncJob = scope.launch {
            val prepared = withContext(queueDispatcher) {
                buildPreparedMediaItem(nextTrack)
            } ?: return@launch
            if (playbackPlayer !== player || playbackGeneration != expectedGeneration) return@launch
            if (_uiState.value.repeatMode == FfiRepeatMode.ONE || _sleepTimerState.value is SleepTimerState.EndOfTrack) return@launch
            val curIdx = player.currentMediaItemIndex.coerceAtLeast(0)
            val nextIdx = curIdx + 1
            if (nextIdx < player.mediaItemCount) {
                val existing = player.getMediaItemAt(nextIdx)
                if (existing.mediaId != prepared.mediaItem.mediaId ||
                    existing.localConfiguration?.uri != prepared.mediaItem.localConfiguration?.uri
                ) {
                    player.replaceMediaItem(nextIdx, prepared.mediaItem)
                }
                while (player.mediaItemCount > nextIdx + 1) {
                    player.removeMediaItem(player.mediaItemCount - 1)
                }
            } else {
                player.addMediaItem(prepared.mediaItem)
            }
        }
    }

    private suspend fun prepareAndPlay(
        entry: FfiQueueEntry,
        startPositionMs: Long = 0L,
        automatic: Boolean = false,
        resetRetry: Boolean = true
    ) {
        if (resetRetry) {
            errorRetryJob?.cancel()
            errorRetryJob = null
            errorRetryTrackId = entry.track.id
            errorRetryCount = 0
            clearErrorRecoveryNetworkCallback()
        }
        cancelPrefetch()
        val player = playbackPlayer ?: run {
            pendingPlayback = entry to startPositionMs
            initMediaController()
            return
        }
        val generation = ++playbackGeneration
        val prepared = withContext(queueDispatcher) {
            buildPreparedMediaItem(entry.track)
        }
        if (playbackPlayer !== player || playbackGeneration != generation) return
        if (prepared == null) {
            Toast.makeText(context, "Bu şarkıyı dinlemek için Deezer'a giriş yap", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // A pause or sleep timer during the URI lookup must not restart playback.
            val playWhenPrepared = !automatic || player.playWhenReady
            val safeStartPos = startPositionMs.coerceAtLeast(0L)
            lastKnownTrackId = entry.track.id
            lastKnownPositionMs = safeStartPos
            player.repeatMode = if (_uiState.value.repeatMode == FfiRepeatMode.ONE) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            if (safeStartPos > 0L) {
                player.setMediaItem(prepared.mediaItem, safeStartPos)
            } else {
                player.setMediaItem(prepared.mediaItem)
            }
            player.prepare()
            if (playWhenPrepared) player.play()
            currentAudioFormat = null
            val initialQualityLabel = computeInitialQualityLabel(
                entry = entry,
                offlinePath = prepared.offlinePath,
                downloadedFormat = prepared.downloadedFormat,
                streamingQuality = prepared.streamingQuality
            )
            _uiState.value = _uiState.value.copy(currentEntry = entry, audioQualityLabel = initialQualityLabel)
            _progress.value = PlaybackProgress(safeStartPos, entry.track.durationMs.toLong())
            syncNextExoPlayerItem(_uiState.value.queueSnapshot, generation)
            scheduleLookaheadPrefetch(_uiState.value.queueSnapshot, intentGeneration)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading media item", e)
        }
    }

    private fun observeQualityChanges() {
        val deezerSession = DeezerSession.get(context)
        scope.launch {
            deezerSession.lastResolvedMedia.collect { pair ->
                if (pair != null) {
                    val (trackId, format) = pair
                    val curTrack = _uiState.value.currentEntry?.track
                    if (curTrack != null && curTrack.id.removePrefix("deezer:") == trackId) {
                        val currentDecoded = currentAudioFormat
                        val label = formatAudioQuality(
                            mimeType = currentDecoded?.sampleMimeType,
                            sampleRate = currentDecoded?.sampleRate ?: 44100,
                            bitrate = currentDecoded?.bitrate ?: 0,
                            deezerFormat = format,
                            isPreview = curTrack.isPreview
                        )
                        _uiState.value = _uiState.value.copy(audioQualityLabel = label)
                    }
                }
            }
        }
        scope.launch {
            combine(deezerSession.wifiQuality, deezerSession.mobileQuality) { _, _ ->
                deezerSession.currentStreamingQuality()
            }.collect { quality ->
                val curEntry = _uiState.value.currentEntry
                if (curEntry != null && curEntry.track.provider == "deezer" && !curEntry.track.isPreview) {
                    val cleanId = curEntry.track.id.removePrefix("deezer:")
                    val resolved = deezerSession.getResolvedTrackFormat(cleanId)
                    if (resolved == null) {
                        val label = when (quality) {
                            StreamingQuality.FLAC -> "FLAC • 44,1 kHz • Kayıpsız"
                            StreamingQuality.MP3_320 -> "MP3 • 320 kb/s • 44,1 kHz"
                            StreamingQuality.MP3_128 -> "MP3 • 128 kb/s • 44,1 kHz"
                            StreamingQuality.AUTO -> "MP3 • 320 kb/s • 44,1 kHz"
                        }
                        _uiState.value = _uiState.value.copy(audioQualityLabel = label)
                    }
                }
            }
        }
    }

    private fun updateQualityFromTracks(tracks: Tracks) {
        var foundFormat: Format? = null
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        foundFormat = group.getTrackFormat(i)
                        break
                    }
                }
            }
            if (foundFormat != null) break
        }
        if (foundFormat != null) {
            currentAudioFormat = foundFormat
            val curTrack = _uiState.value.currentEntry?.track ?: return
            val cleanId = curTrack.id.removePrefix("deezer:")
            val deezerFormat = DeezerSession.get(context).getResolvedTrackFormat(cleanId)
            val label = formatAudioQuality(
                mimeType = foundFormat.sampleMimeType,
                sampleRate = foundFormat.sampleRate,
                bitrate = foundFormat.bitrate,
                deezerFormat = deezerFormat,
                isPreview = curTrack.isPreview
            )
            _uiState.value = _uiState.value.copy(audioQualityLabel = label)
        }
    }

    private fun computeInitialQualityLabel(
        entry: FfiQueueEntry,
        offlinePath: String? = null,
        downloadedFormat: String? = null,
        streamingQuality: StreamingQuality? = null
    ): String {
        if (entry.track.isPreview) return "MP3 • 128 kb/s • 44,1 kHz"

        if (downloadedFormat != null) {
            return formatAudioQuality(null, 44100, 0, deezerFormat = downloadedFormat, isPreview = false)
        }

        if (offlinePath != null) {
            val lower = offlinePath.lowercase()
            return when {
                lower.endsWith(".flac") -> "FLAC • 44,1 kHz • Kayıpsız"
                lower.endsWith(".wav") -> "WAV • 44,1 kHz • Kayıpsız"
                lower.endsWith(".m4a") || lower.endsWith(".aac") -> "AAC • 256 kb/s • 44,1 kHz"
                lower.endsWith(".ogg") || lower.endsWith(".opus") -> "OGG • 320 kb/s • 44,1 kHz"
                else -> "MP3 • 320 kb/s • 44,1 kHz"
            }
        }

        val deezerSession = DeezerSession.get(context)
        val cleanId = entry.track.id.removePrefix("deezer:")
        val resolved = deezerSession.getResolvedTrackFormat(cleanId)
        if (resolved != null) {
            return formatAudioQuality(null, 44100, 0, deezerFormat = resolved, isPreview = false)
        }

        if (entry.track.provider == "deezer" && deezerSession.hasSavedSession) {
            return when (streamingQuality ?: deezerSession.currentStreamingQuality()) {
                StreamingQuality.FLAC -> "FLAC • 44,1 kHz • Kayıpsız"
                StreamingQuality.MP3_128 -> "MP3 • 128 kb/s • 44,1 kHz"
                StreamingQuality.MP3_320 -> "MP3 • 320 kb/s • 44,1 kHz"
                StreamingQuality.AUTO -> "MP3 • 320 kb/s • 44,1 kHz"
            }
        }

        val uriStr = entry.track.playbackUri.orEmpty().lowercase()
        return when {
            uriStr.contains(".flac") || uriStr.contains("flac") -> "FLAC • 44,1 kHz • Kayıpsız"
            uriStr.contains(".wav") || uriStr.contains("wav") -> "WAV • 44,1 kHz • Kayıpsız"
            uriStr.contains("128") -> "MP3 • 128 kb/s • 44,1 kHz"
            uriStr.contains(".m4a") || uriStr.contains(".aac") -> "AAC • 256 kb/s • 44,1 kHz"
            uriStr.contains(".ogg") || uriStr.contains(".opus") -> "OGG • 320 kb/s • 44,1 kHz"
            else -> "MP3 • 320 kb/s • 44,1 kHz"
        }
    }
}

internal fun selectLookaheadTracks(snapshot: FfiQueueSnapshot, maxCount: Int = 2): List<FfiTrack> {
    val entries = snapshot.entries
    val cur = snapshot.currentIndex?.toInt() ?: return emptyList()
    if (entries.isEmpty() || cur !in entries.indices) return emptyList()
    if (snapshot.repeatMode == FfiRepeatMode.ONE) return emptyList()
    val currentId = entries[cur].track.id
    val out = ArrayList<FfiTrack>(maxCount)
    for (offset in 1..maxCount) {
        val nextIdx = cur + offset
        val track = when {
            nextIdx < entries.size -> entries[nextIdx].track
            snapshot.repeatMode == FfiRepeatMode.ALL && entries.size > 1 -> entries[nextIdx % entries.size].track
            else -> null
        } ?: break
        if (track.id != currentId && out.none { it.id == track.id }) {
            out.add(track)
        }
    }
    return out
}

internal fun formatAudioQuality(
    mimeType: String?,
    sampleRate: Int,
    bitrate: Int,
    deezerFormat: String? = null,
    isPreview: Boolean = false
): String {
    if (isPreview) return "MP3 • 128 kb/s • 44,1 kHz"

    val rate = if (sampleRate > 0) sampleRate else 44100
    val rateText = when (rate) {
        44100 -> "44,1 kHz"
        48000 -> "48 kHz"
        96000 -> "96 kHz"
        88200 -> "88,2 kHz"
        192000 -> "192 kHz"
        else -> "${rate / 1000} kHz"
    }

    val isFlac = deezerFormat.equals("FLAC", ignoreCase = true) ||
        mimeType?.contains("flac", ignoreCase = true) == true
    if (isFlac) {
        return "FLAC • $rateText • Kayıpsız"
    }

    val isWav = mimeType?.contains("wav", ignoreCase = true) == true ||
        mimeType?.contains("pcm", ignoreCase = true) == true
    if (isWav) {
        return "WAV • $rateText • Kayıpsız"
    }

    val kbps = when {
        deezerFormat.equals("MP3_320", ignoreCase = true) -> 320
        deezerFormat.equals("MP3_128", ignoreCase = true) -> 128
        deezerFormat.equals("MP3_64", ignoreCase = true) -> 64
        bitrate > 0 -> {
            val rounded = (bitrate + 500) / 1000
            when {
                rounded in 310..330 -> 320
                rounded in 240..265 -> 256
                rounded in 180..200 -> 192
                rounded in 120..135 -> 128
                rounded in 60..70 -> 64
                else -> rounded
            }
        }
        else -> 320
    }

    val codec = when {
        mimeType?.contains("aac", ignoreCase = true) == true ||
            mimeType?.contains("mp4a", ignoreCase = true) == true -> "AAC"
        mimeType?.contains("ogg", ignoreCase = true) == true ||
            mimeType?.contains("vorbis", ignoreCase = true) == true ||
            mimeType?.contains("opus", ignoreCase = true) == true -> "OGG"
        else -> "MP3"
    }

    return "$codec • $kbps kb/s • $rateText"
}
