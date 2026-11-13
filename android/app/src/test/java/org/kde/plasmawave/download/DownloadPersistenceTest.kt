package org.kde.plasmawave.download

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.plasmawave.ui.trackJson
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.plasmawave_core.FfiTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DownloadPersistenceTest {
    private fun track(id: String) = FfiTrack("deezer:$id", "deezer", "Şarkı $id", "Sanatçı", "Albüm", 120_000uL, null, null, false, "real")

    @Test fun legacyDownloadsMigrateAndQueueCancellationsPersist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("downloads.db")
        val prefs = context.getSharedPreferences("full_downloads", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("quality", DownloadQuality.FLAC.name).putString("items", JSONArray().put(
            JSONObject().put("id", "old-id").put("track", trackJson(track("1"))).put("quality", "FLAC")
                .put("state", "completed").put("progress", 100).put("uri", "content://media/audio/1")
        ).toString()).commit()

        val initial = DownloadDatabase(context)
        initial.migrateLegacy(context)
        initial.migrateLegacy(context)
        assertEquals(listOf("old-id"), initial.load().map { it.id })
        assertFalse(prefs.contains("items"))
        initial.close()

        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val downloads = Downloads.get(context)
        assertEquals(DownloadQuality.FLAC, downloads.quality.value)
        assertFalse(downloads.mobileAllowed.value)
        val result = downloads.enqueueAll(listOf(track("1"), track("2"), track("3"), track("2")), DownloadQuality.FLAC)
        assertEquals(2, result.added)
        assertEquals(2, result.skipped)
        assertEquals(listOf("deezer:1", "deezer:2", "deezer:3"), downloads.items.value.map { it.track.id })
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("plasmawave-download-queue").get().isNotEmpty())

        val second = downloads.items.value[1].id
        downloads.cancel(second)
        downloads.cancelAll()
        assertEquals(listOf("completed", "cancelled", "cancelled"), downloads.items.value.map { it.state })
        downloads.forget(second)
        val reloaded = DownloadDatabase(context)
        assertEquals(listOf("deezer:1", "deezer:3"), reloaded.load().map { it.track.id })
        assertEquals(listOf("completed", "cancelled"), reloaded.load().map { it.state })
        assertEquals("content://media/audio/1", reloaded.load().first().uri)
        reloaded.close()
    }
}
