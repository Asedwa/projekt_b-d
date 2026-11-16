package org.kde.plasmawave.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.kde.plasmawave.deezer.DeezerDataSource
import org.kde.plasmawave.deezer.DeezerSession
import java.io.File

@UnstableApi
internal object PlayerMediaCache {
    private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache? {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: runCatching {
                val appCtx = context.applicationContext
                val dir = File(appCtx.cacheDir, "media_cache").apply { mkdirs() }
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                    StandaloneDatabaseProvider(appCtx)
                ).also { cache = it }
            }.getOrNull()
        }
    }
}

@UnstableApi
class PlasmaPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var playbackManager: PlaybackManager? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val deezerFactory = DataSource.Factory { DeezerDataSource(DeezerSession.get(this)) }
        val simpleCache = PlayerMediaCache.get(this)
        val networkOrCachedFactory = if (simpleCache != null) {
            CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(deezerFactory)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(simpleCache))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            deezerFactory
        }
        val sources = DefaultDataSource.Factory(this, networkOrCachedFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(sources)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
            )
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true) // Handles audio focus automatically
            .setHandleAudioBecomingNoisy(true) // Pauses automatically when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val manager = PlaybackManager.getInstance(this)
        playbackManager = manager
        val sessionPlayer = QueueSessionPlayer(player!!, manager::skipNext, manager::skipPrevious)
        mediaSession = MediaSession.Builder(this, sessionPlayer).build()
        manager.attachPlayer(player!!, sessionPlayer::updateQueue)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        player?.let { playbackManager?.detachPlayer(it) }
        playbackManager = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
