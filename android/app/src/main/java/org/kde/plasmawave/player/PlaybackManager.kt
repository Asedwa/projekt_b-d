package org.kde.plasmawave.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kde.plasmawave.PlasmaWaveApp
import org.kde.plasmawave.deezer.DeezerSession
import org.kde.plasmawave.deezer.DeezerException
import uniffi.plasmawave_core.FfiAdvanceReason
import uniffi.plasmawave_core.FfiQueueEntry
import uniffi.plasmawave_core.FfiQueueSnapshot
import uniffi.plasmawave_core.FfiRepeatMode
import uniffi.plasmawave_core.FfiTrack

data class PlayerUiState(
    val currentEntry: FfiQueueEntry? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueSnapshot: FfiQueueSnapshot? = null,
    val isShuffle: Boolean = false,
    val repeatMode: FfiRepeatMode = FfiRepeatMode.OFF
)

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

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var clearWhenConnected = false
    private var pendingEntry: FfiQueueEntry? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null

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
                        loadAndPlay(entry)
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
                    _uiState.value = _uiState.value.copy(
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
        _uiState.value = _uiState.value.copy(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L)
        )
    }

    fun refreshQueueSnapshot() {
        try {
            val snapshot = PlasmaWaveApp.core.getQueueSnapshot()
            val cur = snapshot.currentIndex?.let { idx ->
                snapshot.entries.getOrNull(idx.toInt())
            }
            _uiState.value = _uiState.value.copy(
                queueSnapshot = snapshot,
                currentEntry = cur,
                isShuffle = snapshot.isShuffled,
                repeatMode = snapshot.repeatMode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing queue snapshot", e)
        }
    }

    fun playTrackList(tracks: List<FfiTrack>, startIndex: Int = 0) {
        try {
            val entry = PlasmaWaveApp.core.queueReplace(tracks, startIndex.toUInt())
            refreshQueueSnapshot()
            entry?.let { loadAndPlay(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error replacing queue", e)
        }
    }

    fun playNext(tracks: List<FfiTrack>) {
        try {
            PlasmaWaveApp.core.queuePlayNext(tracks)
            refreshQueueSnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error on playNext", e)
        }
    }

    fun addToQueue(tracks: List<FfiTrack>) {
        try {
            PlasmaWaveApp.core.queueAdd(tracks)
            refreshQueueSnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error on addToQueue", e)
        }
    }

    fun jumpToQueueIndex(entryId: ULong) {
        try {
            val entry = PlasmaWaveApp.core.queueJump(entryId)
            refreshQueueSnapshot()
            entry?.let { loadAndPlay(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error jumping to entry", e)
        }
    }

    fun removeQueueEntry(entryId: ULong) {
        try {
            val currentRemoved = PlasmaWaveApp.core.queueRemove(entryId)
            refreshQueueSnapshot()
            if (currentRemoved == true) {
                _uiState.value.currentEntry?.let { loadAndPlay(it) } ?: run {
                    pendingEntry = null
                    mediaController?.stop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing queue entry", e)
        }
    }

    fun clearQueue() {
        try {
            PlasmaWaveApp.core.queueReplace(emptyList(), 0u)
            pendingEntry = null
            val player = mediaController
            if (player == null) {
                clearWhenConnected = true
            } else {
                player.stop()
                player.clearMediaItems()
            }
            stopProgressTicker()
            refreshQueueSnapshot()
            _uiState.value = _uiState.value.copy(currentEntry = null, isPlaying = false, positionMs = 0L, durationMs = 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing queue", e)
            Toast.makeText(context, "Kuyruk temizlenemedi", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopPlayback() { mediaController?.stop() }

    fun togglePlayPause() {
        val player = mediaController ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE && _uiState.value.currentEntry != null) {
                _uiState.value.currentEntry?.let { loadAndPlay(it) }
            } else {
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _uiState.value = _uiState.value.copy(positionMs = positionMs)
    }

    fun skipNext() {
        try {
            val next = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.USER_SKIP)
            refreshQueueSnapshot()
            if (next != null) {
                loadAndPlay(next)
            } else {
                mediaController?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping to next", e)
        }
    }

    fun skipPrevious() {
        try {
            val pos = mediaController?.currentPosition?.toULong() ?: 0uL
            val res = PlasmaWaveApp.core.queuePrevious(pos)
            refreshQueueSnapshot()
            if (res.shouldRestartCurrent) {
                mediaController?.seekTo(0L)
            } else {
                res.movedEntry?.let { loadAndPlay(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping previous", e)
        }
    }

    private fun onTrackEnded() {
        try {
            val next = PlasmaWaveApp.core.queueAdvance(FfiAdvanceReason.TRACK_ENDED)
            refreshQueueSnapshot()
            if (next != null) {
                loadAndPlay(next)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error advancing on track end", e)
        }
    }

    fun toggleRepeat() {
        try {
            val current = _uiState.value.repeatMode
            val next = when (current) {
                FfiRepeatMode.OFF -> FfiRepeatMode.ALL
                FfiRepeatMode.ALL -> FfiRepeatMode.ONE
                FfiRepeatMode.ONE -> FfiRepeatMode.OFF
            }
            PlasmaWaveApp.core.queueSetRepeat(next)
            refreshQueueSnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling repeat", e)
        }
    }

    fun toggleShuffle() {
        try {
            val next = !_uiState.value.isShuffle
            PlasmaWaveApp.core.queueSetShuffle(next)
            refreshQueueSnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling shuffle", e)
        }
    }

    private fun loadAndPlay(entry: FfiQueueEntry) {
        val player = mediaController ?: run {
            pendingEntry = entry
            return
        }
        val deezerSession = DeezerSession.get(context)
        val useFullDeezer = entry.track.provider == "deezer" && deezerSession.hasSavedSession
        // Old downloads may be preview clips: do not prefer them over an account's full stream.
        val offlinePath = if (useFullDeezer) null else
            try { PlasmaWaveApp.core.getOfflinePath(entry.track.id) } catch (_: Exception) { null }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error loading media item", e)
        }
    }
}
