package com.mdblisthub.tv.torrent

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The web server the player reads a torrent from, on the loopback interface only.
 *
 * It answers like any file host: `Range` requests, `206`, `Content-Range`. A read past
 * what has arrived waits for the piece instead of failing, and that wait is what steers
 * the torrent, see [TorrentEngine.awaitPiece]. Everything the player does with a slow
 * host therefore applies unchanged: a reconnect is a new request here, a seek is a range
 * further on.
 *
 * One request per connection. It is a local socket, so a new one costs nothing, and
 * HTTP/1.1 without keep-alive is all the parsing there is.
 */
internal class LocalTorrentServer(private val engine: TorrentEngine) {

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "torrent-http").apply { isDaemon = true }
    }

    val port: Int get() = socket.localPort

    init {
        thread(name = "torrent-http-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { serve(client) }
            }
        }
    }

    fun urlFor(ref: TorrentRef): String = "http://$LOOPBACK:$port${ref.path()}"

    private fun serve(client: Socket) = client.use {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream(), COPY_BYTES)
            val request = readLine(input)?.split(' ') ?: return@use
            val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val method = request.getOrNull(0)
            val ref = request.getOrNull(1)?.let(TorrentRef::parse)
            if (ref == null || (method != "GET" && method != "HEAD")) return@use output.status(404)

            val torrent = engine.open(ref) ?: return@use output.status(503)
            val file = engine.awaitFile(torrent, METADATA_WAIT_MS) ?: return@use output.status(503)
            val range = ByteRange.of(headers["range"], file.size)
                ?: return@use output.status(416, "Content-Range: bytes */${file.size}")
            if (engine.isStranded(torrent) && !engine.hasPiece(torrent, file.pieceAt(range.start))) {
                return@use output.status(503)
            }

            val partial = headers.containsKey("range")
            output.head(
                code = if (partial) 206 else 200,
                "Content-Type: ${contentType(file.path.name)}",
                "Accept-Ranges: bytes",
                "Content-Length: ${range.length}",
                if (partial) "Content-Range: bytes ${range.start}-${range.end}/${file.size}" else null,
            )
            if (method == "GET") send(torrent, file, range, output)
        } catch (_: IOException) {
            // The player hung up: a seek, a reconnect, or the film closing.
        } catch (e: RuntimeException) {
            // Never past this thread: an exception here would end the app, not the request.
            Log.w(TorrentEngine.TAG, "torrent request failed: $e")
        }
    }

    /** Copies [range] piece by piece, each one only once it has arrived. */
    private fun send(
        torrent: TorrentEngine.Torrent,
        file: TorrentEngine.TorrentFile,
        range: ByteRange,
        output: OutputStream,
    ) {
        var position = range.start
        var disk: RandomAccessFile? = null
        val buffer = ByteArray(COPY_BYTES)
        try {
            while (position <= range.end) {
                val piece = file.pieceAt(position)
                if (!engine.awaitPiece(torrent, piece, PIECE_WAIT_MS)) {
                    Log.i(TorrentEngine.TAG, "torrent read gave up waiting for piece $piece")
                    return
                }
                // Opened late: libtorrent creates the file with its first piece.
                val reader = disk ?: RandomAccessFile(file.path, "r").also { disk = it }
                val until = minOf(range.end + 1, file.pieceEnd(piece))
                reader.seek(position)
                while (position < until) {
                    val read = reader.read(buffer, 0, minOf(buffer.size.toLong(), until - position).toInt())
                    if (read < 0) return
                    output.write(buffer, 0, read)
                    position += read
                }
                output.flush()
            }
        } finally {
            disk?.close()
        }
    }

    private fun OutputStream.status(code: Int, vararg extra: String?) {
        head(code, *extra, "Content-Length: 0")
    }

    private fun OutputStream.head(code: Int, vararg lines: String?) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(REASONS[code] ?: "Status").append("\r\n")
            lines.filterNotNull().forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        write(text.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    /** One header line, without its CRLF. Null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_LINE) return null
            line.append(byte.toChar())
        }
    }

    private fun contentType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 16
        const val COPY_BYTES = 64 * 1024
        const val MAX_LINE = 16 * 1024

        /**
         * Just under the player's 15s read timeout, so a torrent nobody is seeding answers
         * a clean 503 rather than a socket timeout. The torrent keeps looking meanwhile,
         * and each retry from the player waits again.
         */
        const val METADATA_WAIT_MS = 12_000L

        /** Past the player's read timeout: it gives up first, then asks again. */
        const val PIECE_WAIT_MS = 20_000L

        val REASONS = mapOf(
            200 to "OK",
            206 to "Partial Content",
            404 to "Not Found",
            416 to "Range Not Satisfiable",
            503 to "Service Unavailable",
        )
    }
}
