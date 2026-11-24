package org.kde.plasmawave.deezer

import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client pool so Deezer gateway RPCs and CDN audio streams reuse warm
 * HTTP/2 and TLS connections across track skips instead of repeating handshakes.
 */
internal object DeezerHttp {
    private const val MAX_STREAM_REDIRECTS = 5
    // Keep-alive under 45s + HTTP/2 ping prevents NAT-dropped half-open sockets during 3-4 min songs.
    private val pool = ConnectionPool(6, 45, TimeUnit.SECONDS)

    fun isTrustedMediaHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return h == "dzcdn.net" || h.endsWith(".dzcdn.net") ||
            h == "deezer.com" || h.endsWith(".deezer.com")
    }

    fun isTrustedMediaUrl(url: HttpUrl): Boolean =
        url.isHttps && isTrustedMediaHost(url.host)

    private val verifiedRedirectInterceptor = Interceptor { chain ->
        var request = chain.request()
        if (!isTrustedMediaUrl(request.url)) {
            throw DeezerException("Geçersiz Deezer ses kaynağı")
        }
        repeat(MAX_STREAM_REDIRECTS + 1) { attempt ->
            val response = chain.proceed(request)
            if (response.code !in intArrayOf(301, 302, 303, 307, 308)) {
                return@Interceptor response
            }
            val location = response.header("Location")
            val nextUrl = location?.let { request.url.resolve(it) }
            response.close()
            if (nextUrl == null) {
                throw IOException("Geçersiz yönlendirme yanıtı")
            }
            if (!isTrustedMediaUrl(nextUrl)) {
                throw DeezerException("Güvenilmeyen yönlendirme hedefi engellendi")
            }
            if (attempt >= MAX_STREAM_REDIRECTS) {
                throw IOException("Çok fazla yönlendirme")
            }
            request = request.newBuilder().url(nextUrl).build()
        }
        throw IOException("Çok fazla yönlendirme")
    }

    val gatewayClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(pool)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    val streamClient: OkHttpClient by lazy {
        gatewayClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(verifiedRedirectInterceptor)
            .build()
    }
}
