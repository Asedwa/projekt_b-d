package org.kde.plasmawave.player

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.plasmawave_core.FfiQueueEntry
import uniffi.plasmawave_core.FfiQueueSnapshot
import uniffi.plasmawave_core.FfiRepeatMode
import uniffi.plasmawave_core.FfiTrack

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class QueueSessionPlayerTest {
    @Test fun nextAndPreviousReachTheQueueWithOnlyOneLoadedStream() {
        val delegate = SingleStreamPlayer()
        var nextCount = 0
        var previousCount = 0
        val player = QueueSessionPlayer(
            delegate,
            skipNext = { nextCount++; completedJob() },
            skipPrevious = { previousCount++; completedJob() }
        )
        player.updateQueue(queue(size = 3, currentIndex = 1))

        assertEquals(1, delegate.mediaItemCount)
        assertFalse(delegate.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()

        assertEquals(2, nextCount)
        assertEquals(2, previousCount)
        assertTrue(delegate.seeks.isEmpty())
    }

    @Test fun queueChangesNotifyControllersWithoutReloadingTheStream() {
        val player = QueueSessionPlayer(SingleStreamPlayer(), ::completedJob, ::completedJob)
        player.updateQueue(queue(size = 1))
        val nextAvailability = mutableListOf<Boolean>()
        player.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                nextAvailability += availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)
            }
        })

        player.updateQueue(queue(size = 2))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        player.updateQueue(queue(size = 2, currentIndex = 1))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        player.updateQueue(queue(size = 2, currentIndex = 1, repeat = FfiRepeatMode.ALL))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        player.updateQueue(queue(size = 2, currentIndex = 1, repeat = FfiRepeatMode.ONE))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        player.updateQueue(queue(size = 0))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))

        assertEquals(listOf(true, false, true, false), nextAvailability)
    }

    @Test fun disabledNextDoesNotAdvancePastTheQueueEnd() {
        var nextCount = 0
        val player = QueueSessionPlayer(
            SingleStreamPlayer(),
            skipNext = { nextCount++; completedJob() },
            skipPrevious = ::completedJob
        )
        player.updateQueue(queue(size = 1))

        player.seekToNext()
        player.seekToNextMediaItem()

        assertEquals(0, nextCount)
    }

    @Test fun seekingWithinTheSongStillReachesTheStreamPlayer() {
        val delegate = SingleStreamPlayer()
        val player = QueueSessionPlayer(delegate, ::completedJob, ::completedJob)
        player.updateQueue(queue(size = 3))

        player.seekTo(45_000L)

        assertEquals(listOf(45_000L), delegate.seeks)
        assertEquals(45_000L, player.currentPosition)
    }

    @Test fun asynchronousSkipPublishesTheCompletedQueueState() {
        val pendingSkip = Job()
        val player = QueueSessionPlayer(SingleStreamPlayer(), { pendingSkip }, ::completedJob)
        player.updateQueue(queue(size = 2))

        player.seekToNext()
        player.updateQueue(queue(size = 2, currentIndex = 1))
        pendingSkip.complete()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test fun mediaSessionControllerCanSkipWithoutAnAppPlaybackController() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var nextCount = 0
        val player = QueueSessionPlayer(
            SingleStreamPlayer(),
            skipNext = { nextCount++; completedJob() },
            skipPrevious = ::completedJob
        )
        player.updateQueue(queue(size = 2))
        val session = MediaSession.Builder(context, player).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("The system controller should connect", future.isDone)
            val controller = future.get()
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

            controller.seekToNext()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, nextCount)
        } finally {
            MediaController.releaseFuture(future)
            session.release()
            player.release()
        }
    }

    private fun completedJob() = Job().apply { complete() }

    private fun queue(size: Int, currentIndex: Int = 0, repeat: FfiRepeatMode = FfiRepeatMode.OFF) =
        FfiQueueSnapshot(
            entries = (0 until size).map { index ->
                FfiQueueEntry(
                    index.toULong(),
                    FfiTrack("deezer:$index", "deezer", "Song $index", "Artist", "Album",
                        120_000uL, null, null, false, "real")
                )
            },
            currentIndex = currentIndex.toUInt().takeIf { size > 0 },
            repeatMode = repeat,
            isShuffled = false
        )

    private class SingleStreamPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        private var state = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_RELEASE
            ).build())
            .setPlaylist(listOf(MediaItemData.Builder("stream")
                .setMediaItem(MediaItem.fromUri("https://example.com/stream.mp3"))
                .setIsSeekable(true)
                .setDurationUs(120_000_000L)
                .build()))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(0L)
            .build()

        override fun getState(): State = state

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks += positionMs
            state = state.buildUpon().setContentPositionMs(positionMs).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }
}
