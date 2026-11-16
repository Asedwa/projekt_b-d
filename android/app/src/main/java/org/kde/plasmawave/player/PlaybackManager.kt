package org.kde.plasmawave.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    private fun acquireTransitionWakeLock() {
        synchronized(wakeLockSync) {
            runCatching { transitionWakeLock.acquire(30_000L) }
        }
    }

    private fun releaseTransitionWakeLock() {
        synchronized(wakeLockSync) {
            runCatching {
                if (transitionWakeLock.isHeld) transitionWakeLock.release()
            }
        }
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()
    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()
    // Position ticks live in `progress`, so `overview` / `currentTrackId` never churn during playback.
    val currentTrackId = uiState.map { it.currentEntry?.track?.id }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)
    val overview: StateFlow<PlayerUiState> = uiState

    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var currentAudioFormat: Format? = null
    private var lastKnownTrackId: String? = null
    private var lastKnownPositionMs: Long = 0L
    private var errorRetryTrackId: String? = null
    private var errorRetryCount: Int = 0
    private var errorRetryJob: Job? = null

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

    private val playerListener = object : Player.Listener {
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
                if (errorRetryCount < 2) {
                    errorRetryCount++
                    val attempt = errorRetryCount
                    val resumePos = if (lastKnownTrackId == trackId) lastKnownPositionMs else 0L
                    DeezerSession.get(context).invalidateResolvedMedia(trackId)
                    errorRetryJob?.cancel()
                    errorRetryJob = scope.launch {
                        delay(700L * attempt)
                        launchQueueOperation {
                            if (_uiState.value.currentEntry?.track?.id == trackId) {
                                prepareAndPlay(currentEntry, resumePos, automatic = false, resetRetry = false)
                            }
                        }
                    }
                    return
                }
            }
            val message = deezerMsg
                ?: if (isRecoverableIo) "Bağlantı kesildi; şarkı yüklenemedi. İnternetini kontrol edip tekrar dene."
                else "Şarkı oynatılamadı. Bağlantını ve Deezer hesabını kontrol et."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            if (isPlaying) {
                errorRetryCount = 0
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
        launchQueueOperation {
            try {
                val (entry, snapshot) = withContext(queueDispatcher) {
                    val replaced = PlasmaWaveApp.core.queueReplace(tracks, startIndex.toUInt())
                    replaced to readSnapshot()
                }
                applySnapshot(snapshot)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error on addToQueue", e)
            }
        }
    }

    fun jumpToQueueIndex(entryId: ULong) {
        launchQueueOperation {
            try {
                val (entry, snapshot) = withContext(queueDispatcher) {
                    val jumped = PlasmaWaveApp.core.queueJump(entryId)
                    jumped to readSnapshot()
                }
                applySnapshot(snapshot)
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing queue entry", e)
            }
        }
    }

    fun clearQueue() {
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

    fun skipNext(): Job = launchQueueOperation {
        try {
            val (next, snapshot) = withContext(queueDispatcher) {
                val advanced = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.USER_SKIP)
                advanced to readSnapshot()
            }
            applySnapshot(snapshot)
            if (next != null) {
                prepareAndPlay(next)
            } else {
                playbackPlayer?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping to next", e)
        }
    }

    fun skipPrevious(): Job = launchQueueOperation {
        try {
            val pos = playbackPlayer?.currentPosition?.toULong() ?: 0uL
            val (res, snapshot) = withContext(queueDispatcher) {
                val prev = PlasmaWaveApp.core.queuePrevious(pos)
                prev to readSnapshot()
            }
            applySnapshot(snapshot)
            if (res.shouldRestartCurrent) {
                playbackPlayer?.seekTo(0L)
            } else {
                res.movedEntry?.let { prepareAndPlay(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping previous", e)
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
        Toast.makeText(context, "Uyku zamanı modu: Bu şarkı bitince duracak", Toast.LENGTH_SHORT).show()
    }

    fun cancelSleepTimer(showToast: Boolean = true) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.Off
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
        val generation = playbackGeneration
        // ExoPlayer releases its own wake lock at STATE_ENDED. Bridge the short queue/URI
        // lookup until prepare() enables its buffering wake lock for the next stream.
        acquireTransitionWakeLock()
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
            releaseTransitionWakeLock()
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
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling shuffle", e)
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
        }
        val player = playbackPlayer ?: run {
            pendingPlayback = entry to startPositionMs
            initMediaController()
            return
        }
        val generation = ++playbackGeneration
        val deezerSession = DeezerSession.get(context)
        val useFullDeezer = entry.track.provider == "deezer" && deezerSession.hasSavedSession
        // Old downloads may be preview clips: do not prefer them over an account's full stream.
        val offlinePath = if (useFullDeezer) null else withContext(queueDispatcher) {
            try { PlasmaWaveApp.core.getOfflinePath(entry.track.id) } catch (_: Exception) { null }
        }
        if (playbackPlayer !== player || playbackGeneration != generation) return
        val uriStr = when {
            offlinePath != null -> "file://$offlinePath"
            useFullDeezer -> "deezer://track/${entry.track.id.removePrefix("deezer:")}"
            else -> entry.track.playbackUri ?: run {
                Toast.makeText(context, "Bu şarkıyı dinlemek için Deezer'a giriş yap", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            val uri = Uri.parse(uriStr)
            val metadata = MediaMetadata.Builder()
                .setTitle(entry.track.title)
                .setArtist(entry.track.artist)
                .setAlbumTitle(entry.track.album)
                .apply {
                    entry.track.artworkUrl?.let { setArtworkUri(Uri.parse(it)) }
                }
                .build()

            val mediaItemBuilder = MediaItem.Builder()
                .setMediaId(entry.track.id)
                .setUri(uri)
                .setMediaMetadata(metadata)
            if (useFullDeezer) {
                val cleanId = entry.track.id.removePrefix("deezer:")
                val qualityKey = deezerSession.getResolvedTrackFormat(cleanId)
                    ?: deezerSession.currentStreamingQuality().name
                mediaItemBuilder.setCustomCacheKey("deezer:$cleanId:$qualityKey")
            }
            val mediaItem = mediaItemBuilder.build()

            // A pause or sleep timer during the URI lookup must not restart playback.
            val playWhenPrepared = !automatic || player.playWhenReady
            val safeStartPos = startPositionMs.coerceAtLeast(0L)
            lastKnownTrackId = entry.track.id
            lastKnownPositionMs = safeStartPos
            if (safeStartPos > 0L) {
                player.setMediaItem(mediaItem, safeStartPos)
            } else {
                player.setMediaItem(mediaItem)
            }
            player.prepare()
            if (playWhenPrepared) player.play()
            currentAudioFormat = null
            val initialQualityLabel = computeInitialQualityLabel(entry, offlinePath)
            _uiState.value = _uiState.value.copy(currentEntry = entry, audioQualityLabel = initialQualityLabel)
            _progress.value = PlaybackProgress(safeStartPos, entry.track.durationMs.toLong())
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

    private fun computeInitialQualityLabel(entry: FfiQueueEntry, offlinePath: String? = null): String {
        if (entry.track.isPreview) return "MP3 • 128 kb/s • 44,1 kHz"

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
            return when (deezerSession.currentStreamingQuality()) {
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
