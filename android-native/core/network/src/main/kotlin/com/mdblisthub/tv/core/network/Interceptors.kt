package com.mdblisthub.tv.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", ApiConfig.USER_AGENT)
            // Deliberately no `Accept-Encoding`.
            //
            // OkHttp adds `Accept-Encoding: gzip` itself and gunzips the answer
            // transparently — but *only* when it added the header. Setting it
            // here is read as "the caller will handle decompression", so the
            // body reaches the JSON parser still compressed and every request
            // in the app fails with `Expected start of the object '{'`.
            .build()
        return chain.proceed(request)
    }
}

/** Carries the OpenSubtitles.com credentials so the Retrofit interface doesn't have to. */
internal object OpenSubtitlesHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Api-Key", ApiConfig.OPENSUBTITLES_API_KEY)
            .header("User-Agent", ApiConfig.OPENSUBTITLES_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }
}



/**
 * Gives cacheable answers a lifetime they did not ask for.
 *
 * A film's cast does not change; a list's contents change daily at most. Both
 * arrive with `no-cache`, so without this the disk cache never holds anything
 * and every background refresh pays full price. `stale-if-error` is the other
 * half: when the box wakes up with no network yet, the last answer is served
 * instead of an exception.
 */
internal object CacheControlInterceptor : Interceptor {

    private val ONE_DAY = TimeUnit.DAYS.toSeconds(1)
    private val ONE_WEEK = TimeUnit.DAYS.toSeconds(7)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // A write must never be cached, and neither must anything the caller
        // explicitly marked no-store.
        if (request.method != "GET") return response
        if (request.cacheControl.noStore) return response

        val maxAge = when {
            // Immutable once published.
            request.url.encodedPath.contains("/credits") -> ONE_WEEK
            request.url.host == "api.themoviedb.org" -> ONE_DAY
            request.url.host == "www.omdbapi.com" -> ONE_WEEK
            // Playback sessions and library membership must stay live.
            request.url.encodedPath.contains("/sync/") -> return response
            request.url.host == "api.mdblist.com" -> TimeUnit.HOURS.toSeconds(6)
            else -> return response
        }

        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .header("Cache-Control", "public, max-age=$maxAge, stale-if-error=${ONE_WEEK}")
            .build()
    }
}
