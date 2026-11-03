package com.mdblisthub.tv.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * An addon that answers its resolve link with a 302 to a CDN on another port, as Torrentio
 * sends a Real-Debrid link to Real-Debrid. Each test uses its own path, because
 * [ResolvedLinks] is one memory for the whole process.
 */
class ResolvedLinksTest {

    private val resolves = AtomicInteger()
    private val cdnRequests = AtomicInteger()

    /** The CDN answers 403 while this is above zero, counting down. */
    private val cdnFailures = AtomicInteger()

    @Volatile
    private var cdnFileBytes = 64L * 1024 * 1024

    private val cdn = TinyServer { _ ->
        cdnRequests.incrementAndGet()
        if (cdnFailures.getAndDecrement() > 0) "403" to emptyMap() else partial(cdnFileBytes)
    }

    private val addon = TinyServer { path ->
        when {
            path.startsWith("/resolve/") -> {
                resolves.incrementAndGet()
                "302" to mapOf("Location" to "http://127.0.0.1:${cdn.port}/d/${path.substringAfterLast('/')}")
            }
            path.startsWith("/notice/") -> {
                resolves.incrementAndGet()
                "302" to mapOf("Location" to "/videos/failed_unavailable_v1.mp4")
            }
            else -> partial(143_452)
        }
    }

    private val client = OkHttpClient.Builder().addInterceptor(ResolvedLinks).build()

    @After
    fun stop() {
        addon.close()
        cdn.close()
    }

    @Test
    fun `a resolved link is read from the cdn after the first request`() {
        repeat(3) { assertEquals(206, get("/resolve/kept.mkv")) }
        assertEquals(1, resolves.get())
        assertEquals(3, cdnRequests.get())
    }

    @Test
    fun `a kept link that fails is resolved again, without an error`() {
        assertEquals(206, get("/resolve/expiring.mkv"))
        cdnFailures.set(1)
        assertEquals(206, get("/resolve/expiring.mkv"))
        assertEquals(2, resolves.get())
        assertEquals(206, get("/resolve/expiring.mkv"))
        assertEquals(2, resolves.get())
    }

    @Test
    fun `a notice on the addon's own host is never kept`() {
        repeat(2) { assertEquals(206, get("/notice/film.mkv")) }
        assertEquals(2, resolves.get())
    }

    @Test
    fun `a file far smaller than a film is never kept`() {
        cdnFileBytes = 143_452
        repeat(2) { assertEquals(206, get("/resolve/tiny.mkv")) }
        assertEquals(2, resolves.get())
    }

    @Test
    fun `an error from the cdn is not kept`() {
        cdnFailures.set(1)
        assertEquals(403, get("/resolve/refused.mkv"))
        assertEquals(206, get("/resolve/refused.mkv"))
        assertEquals(2, resolves.get())
    }

    private fun get(path: String): Int {
        val request = Request.Builder()
            .url("http://127.0.0.1:${addon.port}$path")
            .header("Range", "bytes=0-1")
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun partial(totalBytes: Long) = "206" to mapOf("Content-Range" to "bytes 0-1/$totalBytes")

    /**
     * Just enough HTTP/1.1 for these tests: one request per connection, and a body of two
     * bytes on a 206. `com.sun.net.httpserver` is not on an Android unit test's classpath.
     */
    private class TinyServer(private val answer: (path: String) -> Pair<String, Map<String, String>>) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    serve(client)
                }
            }
        }

        private fun serve(client: Socket) = client.use {
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
            while (reader.readLine().orEmpty().isNotEmpty()) Unit
            val (status, headers) = answer(path)
            val body = if (status == "206") "\u0000\u0000" else ""
            val head = buildString {
                append("HTTP/1.1 $status X\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${body.length}\r\nConnection: close\r\n\r\n")
            }
            client.getOutputStream().apply {
                write((head + body).toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        }

        fun close() = socket.close()
    }
}
