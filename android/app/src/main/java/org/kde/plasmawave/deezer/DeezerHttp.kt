package org.kde.plasmawave.deezer

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client pool so Deezer gateway RPCs and CDN audio streams reuse warm
 * HTTP/2 and TLS connections across track skips instead of repeating handshakes.
 */
internal object DeezerHttp {
    // Keep-alive under 45s + HTTP/2 ping prevents NAT-dropped half-open sockets during 3-4 min songs.
    private val pool = ConnectionPool(6, 45, TimeUnit.SECONDS)

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
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
