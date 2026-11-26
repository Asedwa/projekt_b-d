package org.kde.plasmawave.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kde.plasmawave.PlasmaWaveApp
import uniffi.plasmawave_core.FfiLyricsStatus
import java.util.concurrent.ConcurrentHashMap

data class LyricLine(
    val timeMs: Long,
    val text: String
)

sealed class LyricsState {
    data object Idle : LyricsState()
    data object Loading : LyricsState()
    data class Synced(val lines: List<LyricLine>) : LyricsState()
    data class Plain(val text: String) : LyricsState()
    data object Instrumental : LyricsState()
    data object NotFound : LyricsState()
    data class Error(val message: String) : LyricsState()
}

/** Thin Kotlin facade over the Rust `core-lyrics` engine and SQLite lyrics cache. */
object LyricsRepository {
    private val cache = ConcurrentHashMap<String, LyricsState>()

    private fun cacheKey(title: String, artist: String): String =
        "${artist.trim().lowercase()}|${title.trim().lowercase()}"

    fun getCached(title: String, artist: String): LyricsState? =
        cache[cacheKey(title, artist)]

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        forceRefresh: Boolean = false
    ): LyricsState {
        val key = cacheKey(title, artist)
        if (!forceRefresh) {
            cache[key]?.let { if (it !is LyricsState.Error) return it }
        }

        val result = withContext(Dispatchers.IO) {
            try {
                val ffi = PlasmaWaveApp.core.fetchLyrics(
                    title = title,
                    artist = artist,
                    album = album?.takeIf { it.isNotBlank() },
                    durationMs = durationMs.coerceAtLeast(0L).toULong(),
                    forceRefresh = forceRefresh
                )
                when (ffi.status) {
                    FfiLyricsStatus.SYNCED -> LyricsState.Synced(
                        ffi.lines.map { LyricLine(it.timeMs.toLong(), it.text) }
                    )
                    FfiLyricsStatus.PLAIN -> LyricsState.Plain(ffi.plainText.orEmpty())
                    FfiLyricsStatus.INSTRUMENTAL -> LyricsState.Instrumental
                    FfiLyricsStatus.NOT_FOUND -> LyricsState.NotFound
                }
            } catch (e: Exception) {
                LyricsState.Error(e.message ?: "Şarkı sözleri alınamadı")
            }
        }

        if (result !is LyricsState.Error) {
            cache[key] = result
        }
        return result
    }

    fun parseLrc(raw: String): List<LyricLine> =
        PlasmaWaveApp.core.parseLrc(raw).map { LyricLine(it.timeMs.toLong(), it.text) }
}
