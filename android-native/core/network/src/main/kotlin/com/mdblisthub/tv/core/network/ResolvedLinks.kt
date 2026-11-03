package com.mdblisthub.tv.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Where an addon's resolve link led, so a film is resolved once instead of on every request.
 *
 * A debrid addon does not hand out the file. Torrentio's link is
 * `/resolve/realdebrid/<key>/<hash>/...`, and asking it makes the addon call the debrid
 * service and answer a 302 to the debrid CDN. OkHttp follows that inside each call and
 * Media3 always asks the URL it was given, so every request for the film went through the
 * addon again: the opening's reads of the header and the index, every seek, every reconnect
 * and every 16MB chunk of read-ahead. Measured on Torrentio, one of those takes about a
 * second, and two seconds or more through AIOStreams, which redirects twice.
 *
 * It also costs a limit that is not ours. Torrentio's resolve route sits behind an nginx
 * `limit_req`: measured from this app, sixteen at once went through and most of the next
 * twenty, a second later, were answered 429. That budget is per public IP, so it is shared
 * with every other device in the house. Torrentio sends the redirect with
 * `Cache-Control: max-age=21600`, so keeping it is what the addon asks for.
 *
 * Only a successful answer from another origin is kept. Torrentio's notices
 * (`failed_unavailable`, `failed_infringement`, "downloading") are short videos on its own
 * host, and a file a fraction of the size of a film is not kept either. A kept link that fails in any way is forgotten
 * and the request goes through the addon again, once, so an expired link costs one extra
 * request and never an error.
 *
 * Installed on the playback client and on the probe's, so a link the probe resolved is
 * opened by the player straight from the CDN.
 */
object ResolvedLinks : Interceptor {

    /** Torrentio's own `max-age` for the redirect. */
    private val TTL_NANOS = TimeUnit.HOURS.toNanos(6)

    /** Bounds the memory over a long session. One title resolves a few dozen links at most. */
    private const val MAX_LINKS = 512

    /**
     * Below this, the answer is a notice rather than the film. Torrentio's are about 140KB,
     * and a feature or an episode is hundreds of megabytes.
     */
    private const val MIN_FILE_BYTES = 8L * 1024 * 1024

    private class Link(val url: HttpUrl, val keptAtNanos: Long)

    private val links = object : LinkedHashMap<String, Link>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Link>) = size > MAX_LINKS
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = request.url.toString()

        recall(key)?.let { resolved ->
            val direct = request.newBuilder()
                .url(resolved)
                // What OkHttp's own redirect does: a credential meant for the addon does not
                // travel to another host.
                .apply { if (resolved.host != request.url.host) removeHeader("Authorization") }
                .build()
            try {
                val response = chain.proceed(direct)
                if (response.isSuccessful) return response
                response.close()
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
            }
            forget(key)
        }

        val response = chain.proceed(request)
        val landed = response.request.url
        if (response.isSuccessful && !sameOrigin(landed, request.url) && !isNotice(response)) {
            synchronized(links) { links[key] = Link(landed, System.nanoTime()) }
        }
        return response
    }

    private fun recall(key: String): HttpUrl? = synchronized(links) {
        val link = links[key] ?: return null
        if (System.nanoTime() - link.keptAtNanos > TTL_NANOS) {
            links.remove(key)
            return null
        }
        link.url
    }

    private fun forget(key: String) {
        synchronized(links) { links.remove(key) }
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun isNotice(response: Response): Boolean {
        val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: response.body.contentLength().takeIf { response.code == 200 }
        return total != null && total in 0 until MIN_FILE_BYTES
    }
}
