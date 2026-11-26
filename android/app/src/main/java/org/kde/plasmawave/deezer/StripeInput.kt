package org.kde.plasmawave.deezer

import org.kde.plasmawave.PlasmaWaveApp
import java.io.InputStream

/** Reads arbitrary request sizes while keeping the 2048-byte Deezer stripes aligned; decryption runs in Rust. */
internal class StripeInput(
    private val input: InputStream,
    trackId: String,
    alignedPosition: Long,
    initialSkip: Int,
    private var remaining: Long
) {
    companion object {
        const val CHUNK_SIZE = 2048
    }

    private val key: ByteArray = PlasmaWaveApp.core.deriveDeezerTrackKey(trackId)
    private var chunkIndex = alignedPosition / CHUNK_SIZE
    private var skip = initialSkip
    private val rawBuffer = ByteArray(CHUNK_SIZE)
    private var activeBuffer = rawBuffer
    private var dataSize = 0
    private var offset = 0
    private var eof = false

    fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        while (offset >= dataSize) {
            if (eof) return -1
            fill()
        }
        val count = minOf(length.toLong(), (dataSize - offset).toLong(), if (remaining < 0) Long.MAX_VALUE else remaining).toInt()
        System.arraycopy(activeBuffer, offset, target, targetOffset, count)
        offset += count
        if (remaining >= 0) remaining -= count
        return count
    }

    private fun fill() {
        var count = 0
        while (count < CHUNK_SIZE) {
            val n = input.read(rawBuffer, count, CHUNK_SIZE - count)
            if (n < 0) { eof = true; break }
            if (n == 0) continue
            count += n
        }
        if (count == CHUNK_SIZE && chunkIndex % 3L == 0L) {
            activeBuffer = PlasmaWaveApp.core.decryptDeezerChunk(key, chunkIndex.toULong(), rawBuffer)
            dataSize = CHUNK_SIZE
        } else {
            activeBuffer = rawBuffer
            dataSize = count
        }
        chunkIndex++
        offset = minOf(skip, dataSize)
        skip -= offset
    }
}
