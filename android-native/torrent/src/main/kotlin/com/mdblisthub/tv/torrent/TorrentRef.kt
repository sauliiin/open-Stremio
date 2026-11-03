package com.mdblisthub.tv.torrent

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Which torrent, and which file in it, a local URL stands for.
 *
 * The whole description travels in the URL, so the player can hand it back on every
 * request, a reconnect or a seek, without this module keeping a table of what it gave out.
 */
internal data class TorrentRef(
    /** 40 lowercase hex characters, the v1 info hash every Stremio addon sends. */
    val infoHash: String,
    /** The addon's `fileIdx`, or null to take the largest video. */
    val fileIndex: Int?,
    val trackers: List<String>,
    /** `behaviorHints.filename`, to find the file when `fileIdx` counts differently. */
    val fileName: String?,
) {

    fun path(): String = buildString {
        append('/').append(infoHash).append('/').append(fileIndex ?: -1)
        val query = buildList {
            fileName?.let { add("name=" + encode(it)) }
            trackers.forEach { add("tr=" + encode(it)) }
        }
        if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
    }

    /**
     * The magnet libtorrent is given. A few public trackers are always added: an addon
     * that sends none leaves the torrent to the DHT alone, which takes far longer to
     * find the first peers.
     */
    fun magnet(): String = buildString {
        append("magnet:?xt=urn:btih:").append(infoHash)
        fileName?.let { append("&dn=").append(encode(it)) }
        (trackers + FALLBACK_TRACKERS).distinct().forEach { append("&tr=").append(encode(it)) }
    }

    companion object {
        private val INFO_HASH = Regex("[0-9a-f]{40}")

        /** The same three the source list's magnet links carry. */
        private val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce",
        )

        fun of(infoHash: String, fileIndex: Int?, trackers: List<String>, fileName: String?): TorrentRef? {
            val hash = infoHash.lowercase()
            if (!INFO_HASH.matches(hash)) return null
            return TorrentRef(hash, fileIndex?.takeIf { it >= 0 }, trackers, fileName?.takeIf { it.isNotBlank() })
        }

        /** The inverse of [path], from the request line's target. */
        fun parse(target: String): TorrentRef? {
            val path = target.substringBefore('?')
            val parts = path.trim('/').split('/')
            if (parts.size != 2) return null
            val query = target.substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .map { it.substringBefore('=') to decode(it.substringAfter('=', "")) }
            return of(
                infoHash = parts[0],
                fileIndex = parts[1].toIntOrNull(),
                trackers = query.filter { it.first == "tr" }.map { it.second },
                fileName = query.firstOrNull { it.first == "name" }?.second,
            )
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}

/** An inclusive byte range of a file, as a `Range` header asks for one. */
internal data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1

    companion object {
        /**
         * The range [header] asks of a file of [size] bytes: the whole file when there is
         * no header, null when the range cannot be served. Only the first of several ranges
         * is answered, which is all a media player ever asks for.
         */
        fun of(header: String?, size: Long): ByteRange? {
            if (size <= 0) return null
            if (header == null) return ByteRange(0, size - 1)
            val spec = header.trim().takeIf { it.startsWith("bytes=", ignoreCase = true) }
                ?.substring(6)?.substringBefore(',')?.trim()
                ?: return ByteRange(0, size - 1)
            val first = spec.substringBefore('-').trim()
            val last = spec.substringAfter('-', "").trim()
            return when {
                // A suffix: the last N bytes.
                first.isEmpty() -> last.toLongOrNull()?.takeIf { it > 0 }
                    ?.let { ByteRange((size - it).coerceAtLeast(0), size - 1) }
                else -> {
                    val start = first.toLongOrNull() ?: return null
                    val end = if (last.isEmpty()) size - 1 else last.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
                    if (start >= size || end < start) null else ByteRange(start, end)
                }
            }
        }
    }
}
