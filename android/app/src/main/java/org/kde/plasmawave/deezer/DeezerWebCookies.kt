package org.kde.plasmawave.deezer

import android.webkit.CookieManager

/** Read only the Deezer login cookie; neither cookie contents nor values are logged. */
internal object DeezerWebCookies {
    fun readArl(): String? {
        val manager = CookieManager.getInstance()
        return extractArl(listOfNotNull(
            manager.getCookie("https://www.deezer.com/"),
            manager.getCookie("https://deezer.com/"),
            manager.getCookie("https://connect.deezer.com/"),
            manager.getCookie("https://auth.deezer.com/"),
            manager.getCookie(".deezer.com")
        ))
    }

    fun extractArl(cookies: List<String>): String? = cookies.asSequence()
        .flatMap { it.split(';').asSequence() }
        .map(String::trim)
        .filter { it.startsWith("arl=") }
        .map { it.substringAfter('=') }
        .firstOrNull { it.length > 20 && it.none { char -> char.isWhitespace() } }
}
