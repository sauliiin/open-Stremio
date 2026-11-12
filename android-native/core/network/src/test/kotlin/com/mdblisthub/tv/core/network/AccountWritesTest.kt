package com.mdblisthub.tv.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home skips its account refresh on a quick return unless [AccountWrites] has
 * moved, so this is what decides whether "added to the watchlist, came back"
 * shows the change. Answered by a stub at the end of the chain: nothing here
 * touches the network.
 */
class AccountWritesTest {

    private val stub = Interceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(AccountWriteInterceptor)
        .addInterceptor(stub)
        .build()

    private fun call(method: String, url: String) {
        val body = if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url).method(method, body).build()).execute().close()
    }

    private fun moved(block: () -> Unit): Long {
        val before = AccountWrites.revision
        block()
        return AccountWrites.revision - before
    }

    @Test
    fun `writes to the library providers are counted`() {
        assertEquals(1, moved { call("POST", "https://api.trakt.tv/sync/watchlist") })
        assertEquals(1, moved { call("DELETE", "https://api.trakt.tv/sync/playback/1") })
        assertEquals(1, moved { call("POST", "https://api.mdblist.com/scrobble/stop") })
        assertEquals(1, moved { call("POST", "https://api.simkl.com/sync/history") })
    }

    @Test
    fun `reads are not counted`() {
        assertEquals(0, moved { call("GET", "https://api.trakt.tv/sync/playback") })
        assertEquals(0, moved { call("GET", "https://api.mdblist.com/lists/user") })
    }

    @Test
    fun `posts that only read or sign in elsewhere are not counted`() {
        assertEquals(0, moved { call("POST", "https://graphql.prod.api.imdb.a2z.com/") })
        assertEquals(0, moved { call("POST", "https://api.opensubtitles.com/api/v1/download") })
        assertEquals(0, moved { call("POST", "https://auth.trakt.tv/oauth/token") })
        assertEquals(0, moved { call("POST", "https://api.strem.io/api/login") })
    }
}
