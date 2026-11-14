package org.kde.plasmawave.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
    private var currentAudioFormat: Format? = null

    init {
        initMediaController()
        refreshQueueSnapshot()
        observeQualityChanges()
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

        override fun onTracksChanged(tracks: Tracks) {
            updateQualityFromTracks(tracks)
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

    fun playTrackList(tracks: List<FfiTrack>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        scope.launch {
            try {
                val (entry, snapshot) = withContext(queueDispatcher) {
                    val replaced = PlasmaWaveApp.core.queueReplace(tracks, startIndex.toUInt())
                    replaced to readSnapshot()
                }
                applySnapshot(snapshot)
                entry?.let { prepareAndPlay(it, startPositionMs) }
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

    private suspend fun prepareAndPlay(entry: FfiQueueEntry, startPositionMs: Long = 0L) {
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

            val safeStartPos = startPositionMs.coerceAtLeast(0L)
            if (safeStartPos > 0L) {
                player.setMediaItem(mediaItem, safeStartPos)
            } else {
                player.setMediaItem(mediaItem)
            }
            player.prepare()
            player.play()
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
