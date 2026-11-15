package org.kde.plasmawave.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Job
import uniffi.plasmawave_core.FfiQueueSnapshot
import uniffi.plasmawave_core.FfiRepeatMode

/** Exposes the Rust queue's transport controls while ExoPlayer loads one stream at a time. */
@UnstableApi
internal class QueueSessionPlayer(
    player: Player,
    private val skipNext: () -> Job,
    private val skipPrevious: () -> Job
) : ForwardingSimpleBasePlayer(player) {
    private var canSkipNext = false
    private var canSkipPrevious = false

    fun updateQueue(snapshot: FfiQueueSnapshot) {
        val index = snapshot.currentIndex?.toInt()
        val hasCurrent = index != null && index in snapshot.entries.indices
        canSkipPrevious = hasCurrent
        canSkipNext = hasCurrent &&
            (index!! < snapshot.entries.lastIndex || snapshot.repeatMode != FfiRepeatMode.OFF)
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        val hasMedia = !state.timeline.isEmpty
        val commands = state.availableCommands.buildUpon()
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasMedia && canSkipNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasMedia && canSkipNext)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasMedia && canSkipPrevious)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasMedia && canSkipPrevious)
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val job = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> skipNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> skipPrevious()
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return SettableFuture.create<Void>().also { future ->
            job.invokeOnCompletion { error ->
                if (error == null) future.set(null) else future.setException(error)
            }
        }
    }
}
