package org.kde.plasmawave.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeezerWebCookiesTest {
    @Test fun extractsValidArlFromCookieList() {
        val token = "a".repeat(30) + "=="
        assertEquals(token, DeezerWebCookies.extractArl(listOf("lang=tr; arl=$token; sid=ignored")))
    }
    @Test fun ignoresAbsentExpiredAndSimilarCookieNames() {
        assertNull(DeezerWebCookies.extractArl(listOf("notarl=${"a".repeat(30)}; arl=deleted")))
        assertNull(DeezerWebCookies.extractArl(emptyList()))
    }
}
