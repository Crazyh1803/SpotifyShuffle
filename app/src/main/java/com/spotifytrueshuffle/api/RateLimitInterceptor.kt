package com.spotifytrueshuffle.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "RateLimit"

/** Don't wait longer than this for a single Retry-After; beyond it we surface the 429. */
private const val MAX_RETRY_AFTER_SECONDS = 10L

/** How many times to wait-and-retry a 429 before giving up on the request. */
private const val MAX_RETRIES = 3

/**
 * Honors Spotify's `Retry-After` header on HTTP 429 (Too Many Requests).
 *
 * Spotify returns 429 with a `Retry-After: <seconds>` header when the app exceeds its rolling
 * rate-limit window. Without this, the app treats a 429 as a hard failure and (for gap-fill)
 * aborts scanning, so discovery never recovers while throttled. Here we instead wait the
 * server-specified time and retry — but only for **short** waits ([MAX_RETRY_AFTER_SECONDS]),
 * so a build never hangs for minutes. Longer penalties fall through as a 429 for the caller to
 * degrade gracefully (e.g. the gap-fill circuit-breaker rolls the work to a later build).
 *
 * The wait is a blocking `Thread.sleep`, which is fine: OkHttp calls run on Retrofit's
 * background dispatcher / a coroutine IO thread, never the main thread.
 */
class RateLimitInterceptor : Interceptor {

    /**
     * Process-lifetime 429 tally. The caller flushes this into [com.spotifytrueshuffle.cache.AppSettings]
     * after each build so a diagnostics export taken later still reports it — a stalled discovery
     * scan is otherwise impossible to attribute from an export alone.
     */
    companion object Stats {
        private val hits = AtomicInteger(0)
        private val lastAtMs = AtomicLong(0L)

        fun record() {
            hits.incrementAndGet()
            lastAtMs.set(System.currentTimeMillis())
        }

        /** Hits seen since process start, and when the most recent one landed (0 = never). */
        fun snapshot(): Pair<Int, Long> = hits.get() to lastAtMs.get()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempts = 0

        while (response.code == 429 && attempts < MAX_RETRIES) {
            Stats.record()
            val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 1L
            if (retryAfter > MAX_RETRY_AFTER_SECONDS) {
                Log.w(TAG, "429 Retry-After=${retryAfter}s exceeds cap — surfacing to caller")
                return response
            }
            Log.w(TAG, "429 — waiting ${retryAfter}s then retrying (attempt ${attempts + 1}/$MAX_RETRIES)")
            response.close()  // release the body before retrying
            try {
                Thread.sleep(retryAfter * 1000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return chain.proceed(chain.request())
            }
            response = chain.proceed(chain.request())
            attempts++
        }
        return response
    }
}
