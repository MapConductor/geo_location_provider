package com.mapconductor.plugin.provider.geolocation.drive.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

internal object DriveHttp {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun auth(token: String) = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
        chain.proceed(req)
    }

    /** シンプル指数バックオフ（maxRetries 回まで） */
    inline fun <T> withBackoff(
        maxRetries: Int = 5,
        baseDelayMs: Long = 500,
        crossinline block: () -> T
    ): T {
        var attempt = 0
        var delay = baseDelayMs
        var last: Throwable? = null
        while (attempt <= maxRetries) {
            try {
                return block()
            } catch (t: Throwable) {
                last = t
                if (attempt == maxRetries) break
                try { Thread.sleep(delay) } catch (_: InterruptedException) { /* ignore */ }
                delay = min(8000, (baseDelayMs * 2.0.pow(attempt.toDouble())).toLong())
                attempt++
            }
        }
        when (last) {
            is IOException -> throw last
            else -> throw IOException(last?.message ?: "unknown error", last)
        }
    }

    /** 429 / 5xx はリトライ対象にするかの簡易判定 */
    fun shouldRetry(code: Int): Boolean = code == 429 || code in 500..599
}
