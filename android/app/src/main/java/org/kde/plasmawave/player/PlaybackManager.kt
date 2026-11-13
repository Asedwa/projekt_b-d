package org.kde.plasmawave.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val repeatMode: FfiRepeatMode = FfiRepeatMode.OFF
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
    private var mediaController: MediaController? = null
    private var clearWhenConnected = false
    private var pendingEntry: FfiQueueEntry? = null

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

    init {
        initMediaController()
        refreshQueueSnapshot()
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlasmaPlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            scope.launch {
                try {
                    mediaController = controllerFuture?.get()?.apply {
                        addListener(playerListener)
                    }
                    if (clearWhenConnected) {
                        mediaController?.stop()
                        mediaController?.clearMediaItems()
                        clearWhenConnected = false
                    }
                    pendingEntry?.let { entry ->
                        pendingEntry = null
                        prepareAndPlay(entry)
                    }
                    syncPlayerState()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to MediaController", e)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val message = generateSequence(error.cause) { it.cause }
                .filterIsInstance<DeezerException>()
                .firstOrNull()?.message ?: "Şarkı oynatılamadı. Bağlantını ve Deezer hesabını kontrol et."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            if (isPlaying) startProgressTicker() else stopProgressTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onTrackEnded()
            }
            syncPlayerState()
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                mediaController?.let { player ->
                    _progress.value = PlaybackProgress(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.coerceAtLeast(0L)
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
        val player = mediaController ?: return
        _uiState.value = _uiState.value.copy(isPlaying = player.isPlaying)
        _progress.value = PlaybackProgress(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L)
        )
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
    }

    fun refreshQueueSnapshot() {
        scope.launch {
            try {
                val snapshot = withContext(queueDispatcher) { readSnapshot() }
                applySnapshot(snapshot)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing queue snapshot", e)
            }
        }
    }

    fun playTrackList(tracks: List<FfiTrack>, startIndex: Int = 0) {
        scope.launch {
            try {
                val (entry, snapshot) = withContext(queueDispatcher) {
                    val replaced = PlasmaWaveApp.core.queueReplace(tracks, startIndex.toUInt())
                    replaced to readSnapshot()
                }
                applySnapshot(snapshot)
                entry?.let { prepareAndPlay(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error replacing queue", e)
            }
        }
    }

    fun playNext(tracks: List<FfiTrack>) {
        scope.launch {
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
        scope.launch {
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
        scope.launch {
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
        scope.launch {
            try {
                val (currentRemoved, snapshot) = withContext(queueDispatcher) {
                    val removed = PlasmaWaveApp.core.queueRemove(entryId)
                    removed to readSnapshot()
                }
                applySnapshot(snapshot)
                if (currentRemoved == true) {
                    _uiState.value.currentEntry?.let { prepareAndPlay(it) } ?: run {
                        pendingEntry = null
                        mediaController?.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing queue entry", e)
            }
        }
    }

    fun clearQueue() {
        scope.launch {
            try {
                val snapshot = withContext(queueDispatcher) {
                    PlasmaWaveApp.core.queueReplace(emptyList(), 0u)
                    readSnapshot()
                }
                pendingEntry = null
                val player = mediaController
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

    fun stopPlayback() { mediaController?.stop() }

    fun togglePlayPause() {
        val player = mediaController ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE && _uiState.value.currentEntry != null) {
                _uiState.value.currentEntry?.let { scope.launch { prepareAndPlay(it) } }
            } else {
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _progress.value = _progress.value.copy(positionMs = positionMs)
    }

    fun skipNext() {
        scope.launch {
            try {
                val (next, snapshot) = withContext(queueDispatcher) {
                    val advanced = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.USER_SKIP)
                    advanced to readSnapshot()
                }
                applySnapshot(snapshot)
                if (next != null) {
                    prepareAndPlay(next)
                } else {
                    mediaController?.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error skipping to next", e)
            }
        }
    }

    fun skipPrevious() {
        scope.launch {
            try {
                val pos = mediaController?.currentPosition?.toULong() ?: 0uL
                val (res, snapshot) = withContext(queueDispatcher) {
                    val prev = PlasmaWaveApp.core.queuePrevious(pos)
                    prev to readSnapshot()
                }
                applySnapshot(snapshot)
                if (res.shouldRestartCurrent) {
                    mediaController?.seekTo(0L)
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
        mediaController?.pause()
        Toast.makeText(context, "Uyku zamanı modu süresi doldu, oynatma duraklatıldı", Toast.LENGTH_SHORT).show()
    }

    private fun onTrackEnded() {
        if (_sleepTimerState.value is SleepTimerState.EndOfTrack) {
            _sleepTimerState.value = SleepTimerState.Off
            mediaController?.pause()
            Toast.makeText(context, "Şarkı bitti, uyku zamanı modu oynatmayı duraklattı", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val (next, snapshot) = withContext(queueDispatcher) {
                    val advanced = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.TRACK_ENDED)
                    advanced to readSnapshot()
                }
                applySnapshot(snapshot)
                if (next != null) {
                    prepareAndPlay(next)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error advancing on track end", e)
            }
        }
    }

    fun toggleRepeat() {
        scope.launch {
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
        scope.launch {
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

    private suspend fun prepareAndPlay(entry: FfiQueueEntry) {
        val player = mediaController ?: run {
            pendingEntry = entry
            return
        }
        val deezerSession = DeezerSession.get(context)
        val useFullDeezer = entry.track.provider == "deezer" && deezerSession.hasSavedSession
        // Old downloads may be preview clips: do not prefer them over an account's full stream.
        val offlinePath = if (useFullDeezer) null else withContext(queueDispatcher) {
            try { PlasmaWaveApp.core.getOfflinePath(entry.track.id) } catch (_: Exception) { null }
        }
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

            val mediaItem = MediaItem.Builder()
                .setMediaId(entry.track.id)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            _uiState.value = _uiState.value.copy(currentEntry = entry)
            _progress.value = PlaybackProgress(0L, entry.track.durationMs.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error loading media item", e)
        }
    }
}
