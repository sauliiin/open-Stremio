package com.mdblisthub.tv.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentRefTest {

    private val hash = "08ada5a7a6183aae1e09d831df6748d566095a10"

    @Test
    fun `a url carries everything back`() {
        val ref = TorrentRef.of(
            infoHash = hash.uppercase(),
            fileIndex = 2,
            trackers = listOf("udp://tracker.example:1337/announce", "https://t.example/a?x=1&y=2"),
            fileName = "Sintel (2010) [1080p].mkv",
        )!!
        assertEquals(ref, TorrentRef.parse(ref.path()))
        assertEquals(hash, ref.infoHash)
    }

    @Test
    fun `no file index and no name still round trip`() {
        val ref = TorrentRef.of(hash, null, emptyList(), null)!!
        assertEquals("/$hash/-1", ref.path())
        assertEquals(ref, TorrentRef.parse(ref.path()))
    }

    @Test
    fun `only a v1 info hash is accepted`() {
        assertNull(TorrentRef.of("not-a-hash", 0, emptyList(), null))
        assertNull(TorrentRef.of(hash.dropLast(1), 0, emptyList(), null))
        assertNull(TorrentRef.parse("/favicon.ico"))
    }

    @Test
    fun `the magnet always has trackers to start from`() {
        val magnet = TorrentRef.of(hash, 0, emptyList(), null)!!.magnet()
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:$hash"))
        assertTrue(magnet.contains("&tr=udp%3A%2F%2Ftracker.opentrackr.org"))
    }

    @Test
    fun `ranges as a player asks for them`() {
        assertEquals(ByteRange(0, 999), ByteRange.of(null, 1000))
        assertEquals(ByteRange(0, 999), ByteRange.of("bytes=0-", 1000))
        assertEquals(ByteRange(500, 999), ByteRange.of("bytes=500-", 1000))
        assertEquals(ByteRange(0, 1), ByteRange.of("bytes=0-1", 1000))
        assertEquals(ByteRange(900, 999), ByteRange.of("bytes=-100", 1000))
        assertEquals(ByteRange(990, 999), ByteRange.of("bytes=990-5000", 1000))
        assertEquals(ByteRange(10, 19), ByteRange.of("bytes=10-19, 30-39", 1000))
    }

    @Test
    fun `a range past the end cannot be served`() {
        assertNull(ByteRange.of("bytes=1000-", 1000))
        assertNull(ByteRange.of("bytes=20-10", 1000))
        assertNull(ByteRange.of("bytes=abc-", 1000))
    }
}
