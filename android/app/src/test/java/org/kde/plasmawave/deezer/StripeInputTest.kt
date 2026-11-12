package org.kde.plasmawave.deezer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class StripeInputTest {
    private val track = "3135556"

    @Test fun knownTrackKey() {
        assertArrayEquals(byteArrayOf(108, 108, 102, 107, 57, 102, 44, 55, 101, 37, 117, 96, 60, 100, 52, 57), StripeInput.key(track))
    }

    @Test fun streamsAndSeeksAcrossEncryptedAndPartialStripes() {
        val original = ByteArray(2048 * 7 + 317) { ((it * 17 + it / 13) % 256).toByte() }
        val encrypted = original.clone()
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        val key = SecretKeySpec(StripeInput.key(track), "Blowfish")
        for (index in 0 until 7 step 3) {
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)))
            val start = index * 2048
            cipher.doFinal(original, start, 2048).copyInto(encrypted, start)
        }
        for ((start, length) in listOf(0 to original.size, 1 to 200, 1999 to 5000, 6144 to 2048, 8701 to 4870, original.size - 99 to 99)) {
            val aligned = start - start % 2048
            val reader = StripeInput(ByteArrayInputStream(encrypted, aligned, encrypted.size - aligned), track, aligned.toLong(), start - aligned, length.toLong())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(173)
            while (true) {
                val read = reader.read(buffer, 0, buffer.size)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
            assertArrayEquals("range $start + $length", original.copyOfRange(start, start + length), output.toByteArray())
        }
    }

    @Test fun partialFinalStripeIsNotDecrypted() {
        val bytes = ByteArray(700) { it.toByte() }
        val input = StripeInput(ByteArrayInputStream(bytes), track, 0, 0, -1)
        val out = ByteArray(700)
        assertEquals(700, input.read(out, 0, out.size))
        assertArrayEquals(bytes, out)
        assertEquals(-1, input.read(out, 0, 1))
    }
}
