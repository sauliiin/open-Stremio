package com.mdblisthub.tv.torrent

import android.content.Context
import android.os.StatFs
import java.io.File
import kotlin.concurrent.thread

/**
 * Plays BitTorrent links that have no debrid service in front of them, for viewers who
 * have none.
 *
 * The player never learns it is a torrent. [url] hands out an address on this device's
 * loopback interface, and [LocalTorrentServer] answers it like any web server would,
 * range requests included, while [TorrentEngine] fetches the pieces each read needs
 * first. So everything the player does for an HTTP source, the cascade, the stall
 * watch, seeking and resuming, works on a torrent unchanged.
 *
 * Nothing is kept. One torrent at a time, removed with its data when the player moves
 * to another source or closes, and this process's folder is the only one left at start.
 */
object TorrentStreaming {

    @Volatile
    private var folder: File? = null

    private val engine by lazy { TorrentEngine(checkNotNull(folder)) }
    private val server by lazy { LocalTorrentServer(engine) }

    /** Called once, at application start. Until then [url] answers null. */
    fun install(context: Context) {
        val root = File(context.cacheDir, "torrents")
        // A folder per process, so the cleanup below can never race this one's torrent.
        val current = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        folder = current
        // A crash or a kill skips every other cleanup.
        thread(name = "torrent-cleanup", isDaemon = true) {
            root.listFiles()?.filter { it != current }?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * Where the player reads this torrent from, or null when it cannot be played here.
     * [trackers] are the addon's `sources` with their `tracker:` prefix removed.
     */
    fun url(infoHash: String, fileIndex: Int?, trackers: List<String>, fileName: String?): String? {
        if (folder == null) return null
        val ref = TorrentRef.of(infoHash, fileIndex, trackers, fileName) ?: return null
        return server.urlFor(ref)
    }

    /**
     * Drops every torrent except [infoHash]'s, data included. The player calls this with
     * each source it opens, and with null when it closes.
     */
    fun keepOnly(infoHash: String?) {
        if (folder == null) return
        engine.keepOnly(infoHash?.lowercase())
    }

    /**
     * Bytes a torrent may fill on this device. A torrent keeps what it downloads until it
     * is removed, so a film bigger than this would stop where the disk ran out.
     */
    fun roomBytes(): Long? {
        val current = folder ?: return null
        return runCatching { StatFs(current.path).availableBytes - RESERVED_BYTES }.getOrNull()
            ?.coerceAtLeast(0L)
    }

    /** Left free for the system and for the player's own disk cache. */
    private const val RESERVED_BYTES = 1024L * 1024 * 1024
}
