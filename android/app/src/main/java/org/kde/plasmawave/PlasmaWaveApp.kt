package org.kde.plasmawave

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.plasmawave_core.PlasmaWaveCore
import java.io.File

class PlasmaWaveApp : Application(), ImageLoaderFactory {

    companion object {
        private const val TAG = "PlasmaWaveApp"
        lateinit var instance: PlasmaWaveApp
            private set
        lateinit var core: PlasmaWaveCore
            private set
    }

    private val appInitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            // Load UniFFI native library
            System.loadLibrary("plasmawave_core")
            val storageDir = File(filesDir, "plasmawave_data").apply { mkdirs() }
            core = PlasmaWaveCore(storageDir.absolutePath)
            Log.i(TAG, "PlasmaWave core initialized: ${core.getVersion()}")
            appInitScope.launch {
                runCatching {
                    org.kde.plasmawave.ui.components.ArtworkResolver.warmup(this@PlasmaWaveApp)
                }
                runCatching {
                    org.kde.plasmawave.ui.components.LauncherIconManager.syncLauncherIcon(this@PlasmaWaveApp)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize PlasmaWave core", e)
            throw e
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(org.kde.plasmawave.ui.components.AudioArtFetcher.Factory(this@PlasmaWaveApp))
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "image_cache"))
                .maxSizeBytes(128L * 1024L * 1024L)
                .build()
        }
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
