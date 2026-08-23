package com.mdblisthub.tv.core.network

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
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
 * Carries the Trakt credentials, so no method on [TraktApi] has to take one.
 *
 * Unlike mdblist — where the key is a query parameter on every call — Trakt
 * wants three headers: the application's own id, the API version, and the
 * user's bearer token. The first two are required even on the unauthenticated
 * OAuth calls, which is why this is attached to both Trakt clients.
 *
 * [tokens] is read per request rather than captured once: the graph installs
 * the real implementation after the network module is built, and the token
 * behind it changes on every link, refresh and unlink.
 */
internal class TraktHeadersInterceptor(private val tokens: () -> TraktTokens) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("trakt-api-key", ApiConfig.TRAKT_CLIENT_ID)
            .header("trakt-api-version", ApiConfig.TRAKT_API_VERSION)
            .apply {
                // The OAuth host has no bearer to send — the whole point of
                // those calls is to obtain one — and an unlinked account has
                // none either.
                tokens().accessToken()
                    .takeIf { it.isNotBlank() }
                    ?.let { header("Authorization", "Bearer $it") }
            }
            .build()
        return chain.proceed(request)
    }
}

/** Adds Simkl's application identity and the linked user's token per request. */
internal class SimklHeadersInterceptor(private val token: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.newBuilder()
            .addQueryParameter("client_id", ApiConfig.SIMKL_CLIENT_ID)
            .addQueryParameter("app-name", ApiConfig.SIMKL_APP_NAME)
            .addQueryParameter("app-version", ApiConfig.SIMKL_APP_VERSION)
            .build()
        return chain.proceed(chain.request().newBuilder().url(url).apply {
            header("User-Agent", "${ApiConfig.SIMKL_APP_NAME}/${ApiConfig.SIMKL_APP_VERSION}")
            token().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build())
    }
}

/**
 * Renews the token once when Trakt rejects it, then replays the request.
 *
 * A Trakt access token lives seven days, so an app opened weekly is always
 * one request away from a `401`. Handling it here rather than in each
 * repository means a refresh is invisible: the call that triggered it
 * completes normally and nothing above the HTTP layer learns it happened.
 *
 * Exactly one retry. Returning a request that fails the same way again would
 * have OkHttp call this once more, and a refresh token is single use — a loop
 * would spend the whole chain of them and end with the account unlinked.
 */
internal class TraktAuthenticator(private val tokens: () -> TraktTokens) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null

        val rejected = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            .orEmpty()
        val fresh = tokens().refreshed(rejected) ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
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
