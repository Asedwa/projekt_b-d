package org.kde.plasmawave

import android.app.Application
import android.util.Log
import uniffi.plasmawave_core.PlasmaWaveCore
import java.io.File

class PlasmaWaveApp : Application() {

    companion object {
        private const val TAG = "PlasmaWaveApp"
        lateinit var instance: PlasmaWaveApp
            private set
        lateinit var core: PlasmaWaveCore
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            // Load UniFFI native library
            System.loadLibrary("plasmawave_core")
            val storageDir = File(filesDir, "plasmawave_data").apply { mkdirs() }
            core = PlasmaWaveCore(storageDir.absolutePath)
            // Old versions saved a demo/profile or OAuth token in the database.
            // The current web session uses Keystore instead, so discard that obsolete state.
            try { core.clearDeezerUser() } catch (_: Exception) { }
            Log.i(TAG, "PlasmaWave core initialized: ${core.getVersion()}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize PlasmaWave core", e)
            throw e
        }
    }
}
