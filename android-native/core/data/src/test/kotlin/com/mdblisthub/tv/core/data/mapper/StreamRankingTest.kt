package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.Addon
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SourceReadiness
import com.mdblisthub.tv.core.model.StreamKind
import com.mdblisthub.tv.core.network.dto.StremioStreamDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamRankingTest {

    private val addon = Addon(base = "https://addon.example", id = "addon", name = "Addon")

    @Test
    fun `reads each addon's way of marking a cached link`() {
        listOf(
            "[RD+] Torrentio\n4k",
            "[RD⚡] Comet 2160p",
            "[RD⚡] AIOStreams",
            "AIOStreams (Instant RD) 1080p",
        ).forEach { name ->
            assertEquals(name, SourceReadiness.CACHED_REAL_DEBRID, readiness(name))
        }
        listOf("[TB+] Torrentio\n4k", "[AD⚡] Comet 1080p", "MediaFusion 🧲 PM ⚡️").forEach { name ->
            assertEquals(name, SourceReadiness.CACHED_DEBRID, readiness(name))
        }
    }

    @Test
    fun `reads each addon's way of marking an uncached link`() {
        listOf(
            "[RD download] Torrentio\n4k",
            "[RD⏳] AIOStreams",
            "[RD⬇️] Comet 2160p",
            "MediaFusion 🧲 RD ⏳",
        ).forEach { name ->
            assertEquals(name, SourceReadiness.NOT_CACHED, readiness(name))
        }
        assertEquals(SourceReadiness.NOT_CACHED, readiness("Comet", description = "⬇️ Not Cached"))
        assertEquals(SourceReadiness.NOT_CACHED, readiness("AIO", description = "❌ Not Ready (RD)"))
    }

    @Test
    fun `a film's title is not read as a cache mark`() {
        assertEquals(SourceReadiness.UNKNOWN, readiness("HTTP", title = "Ready.Player.One.2018.1080p"))
        assertEquals(SourceReadiness.UNKNOWN, readiness("HTTP", title = "Instant.Family.2018.1080p"))
        assertEquals(SourceReadiness.UNKNOWN, readiness("HTTP", title = "Film 2160p [HDR10+] [DV+]"))
    }

    @Test
    fun `a link already on the Real-Debrid CDN is cached whatever its label says`() {
        assertEquals(
            SourceReadiness.CACHED_REAL_DEBRID,
            readiness("Mirror", url = "https://12.download.real-debrid.com/d/ABC/film.mkv"),
        )
    }

    @Test
    fun `a link with no mark is unknown, not uncached`() {
        assertEquals(SourceReadiness.UNKNOWN, readiness("Some Host 1080p"))
    }

    @Test
    fun `ready links beat sharper ones that are still being fetched`() {
        val ranked = listOf(
            stream("uncached-4k", SourceReadiness.NOT_CACHED, "2160p"),
            stream("unknown-4k", SourceReadiness.UNKNOWN, "2160p"),
            stream("other-debrid-1080", SourceReadiness.CACHED_DEBRID, "1080p"),
            stream("rd-720", SourceReadiness.CACHED_REAL_DEBRID, "720p"),
        ).rankForPlayback()

        assertEquals(
            listOf("rd-720", "other-debrid-1080", "unknown-4k", "uncached-4k"),
            ranked.map { it.key },
        )
    }

    @Test
    fun `resolution still decides among equally ready links`() {
        val ranked = listOf(
            stream("rd-1080", SourceReadiness.CACHED_REAL_DEBRID, "1080p"),
            stream("rd-4k", SourceReadiness.CACHED_REAL_DEBRID, "2160p"),
        ).rankForPlayback()

        assertEquals(listOf("rd-4k", "rd-1080"), ranked.map { it.key })
    }

    @Test
    fun `the biggest file wins until its bitrate outruns the connection`() {
        val gb = 1024L * 1024 * 1024
        val ranked = listOf(
            stream("remux-70gb", SourceReadiness.CACHED_REAL_DEBRID, "2160p", 70 * gb),
            stream("web-12gb", SourceReadiness.CACHED_REAL_DEBRID, "2160p", 12 * gb),
            stream("web-20gb", SourceReadiness.CACHED_REAL_DEBRID, "2160p", 20 * gb),
        ).rankForPlayback(runtimeMinutes = 120)

        assertEquals(listOf("web-20gb", "web-12gb", "remux-70gb"), ranked.map { it.key })
    }

    @Test
    fun `an over-bitrate file never loses to a lower resolution`() {
        val gb = 1024L * 1024 * 1024
        val ranked = listOf(
            stream("web-1080", SourceReadiness.CACHED_REAL_DEBRID, "1080p", 10 * gb),
            stream("remux-4k", SourceReadiness.CACHED_REAL_DEBRID, "2160p", 70 * gb),
        ).rankForPlayback(runtimeMinutes = 120)

        assertEquals(listOf("remux-4k", "web-1080"), ranked.map { it.key })
    }

    @Test
    fun `without a runtime the biggest file simply wins`() {
        val gb = 1024L * 1024 * 1024
        val ranked = listOf(
            stream("web-12gb", SourceReadiness.CACHED_REAL_DEBRID, "2160p", 12 * gb),
            stream("remux-70gb", SourceReadiness.CACHED_REAL_DEBRID, "2160p", 70 * gb),
        ).rankForPlayback()

        assertEquals(listOf("remux-70gb", "web-12gb"), ranked.map { it.key })
    }

    @Test
    fun `a torrent without debrid keeps what the engine needs`() {
        val stream = StremioStreamDto(
            name = "Torrentio\n1080p",
            title = "Sintel.2010.1080p.mkv\n👤 412 💾 1.2 GB ⚙️ ThePirateBay",
            infoHash = "08ADA5A7A6183AAE1E09D831DF6748D566095A10",
            fileIdx = 2,
            sources = listOf("tracker:udp://tracker.example:1337/announce", "dht:08ada5a7a6183aae1e09d831df6748d566095a10"),
        ).toPlayable(addon, 0)
        assertEquals(StreamKind.TORRENT, stream.kind)
        assertEquals(SourceReadiness.PEER_TO_PEER, stream.readiness)
        assertEquals("08ada5a7a6183aae1e09d831df6748d566095a10", stream.infoHash)
        assertEquals(2, stream.fileIndex)
        assertEquals(listOf("udp://tracker.example:1337/announce"), stream.trackers)
        assertEquals(412, stream.seeders)
        // Playable only once the torrent engine has given it a link.
        assertFalse(stream.playable)
    }

    @Test
    fun `a swarm goes after every link a server holds, and before an uncached one`() {
        val ranked = listOf(
            stream("uncached", SourceReadiness.NOT_CACHED, "2160p"),
            torrent("swarm", "2160p", seeders = 900),
            stream("direct", SourceReadiness.UNKNOWN, "720p"),
            stream("cached", SourceReadiness.CACHED_DEBRID, "1080p"),
        ).rankForPlayback()
        assertEquals(listOf("cached", "direct", "swarm", "uncached"), ranked.map { it.key })
    }

    @Test
    fun `among torrents, what fits on disk and has a swarm comes first`() {
        val gb = 1024L * 1024 * 1024
        val ranked = listOf(
            torrent("too-big", "1080p", seeders = 2000, bytes = 30 * gb),
            torrent("thin", "2160p", seeders = 3, bytes = 2 * gb),
            torrent("healthy-720", "720p", seeders = 50, bytes = 1 * gb),
            torrent("healthy-1080-few", "1080p", seeders = 40, bytes = 2 * gb),
            torrent("healthy-1080-many", "1080p", seeders = 800, bytes = 2 * gb),
        ).rankForPlayback(runtimeMinutes = 120, torrentRoomBytes = 10 * gb)
        assertEquals(
            listOf("healthy-1080-many", "healthy-1080-few", "healthy-720", "thin", "too-big"),
            ranked.map { it.key },
        )
    }

    private fun readiness(
        name: String,
        title: String? = null,
        description: String? = null,
        url: String = "https://addon.example/resolve/film.mkv",
    ): SourceReadiness = StremioStreamDto(
        name = name,
        title = title,
        description = description,
        url = url,
    ).toPlayable(addon, 0).readiness

    private fun torrent(key: String, quality: String, seeders: Int, bytes: Long? = null) = PlayableStream(
        key = key,
        addon = "Addon",
        title = key,
        quality = quality,
        sizeBytesHint = bytes,
        url = "http://127.0.0.1:1/$key",
        kind = StreamKind.TORRENT,
        readiness = SourceReadiness.PEER_TO_PEER,
        seeders = seeders,
    )

    private fun stream(
        key: String,
        readiness: SourceReadiness,
        quality: String,
        bytes: Long? = null,
    ) = PlayableStream(
        key = key,
        addon = "Addon",
        title = key,
        quality = quality,
        sizeBytesHint = bytes,
        url = "https://host.example/$key.mkv",
        readiness = readiness,
    )
}
