package org.kde.plasmawave.deezer

import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Reads arbitrary request sizes while keeping the 2048-byte Deezer stripes aligned. */
internal class StripeInput(
    private val input: InputStream,
    trackId: String,
    alignedPosition: Long,
    initialSkip: Int,
    private var remaining: Long
) {
    companion object {
        const val CHUNK_SIZE = 2048
        fun key(trackId: String): ByteArray {
            val hash = MessageDigest.getInstance("MD5").digest(trackId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val salt = "g4el58wc0zvf9na1"
            return ByteArray(16) { (hash[it].code xor hash[it + 16].code xor salt[it].code).toByte() }
        }
    }
    private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
    private val keySpec = SecretKeySpec(key(trackId), "Blowfish")
    private val iv = IvParameterSpec(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
    private var chunkIndex = alignedPosition / CHUNK_SIZE
    private var skip = initialSkip
    private val rawBuffer = ByteArray(CHUNK_SIZE)
    private val decryptedBuffer = ByteArray(CHUNK_SIZE)
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
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
            cipher.doFinal(rawBuffer, 0, count, decryptedBuffer, 0)
            activeBuffer = decryptedBuffer
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
