package com.mdblisthub.tv.torrent

import android.os.SystemClock
import android.util.Log
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.ErrorCode
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * One libtorrent session, holding at most one torrent: the one being watched.
 *
 * Everything here is driven by what the player reads. [awaitPiece] is called for each
 * piece a request needs, and it is what tells libtorrent where the playhead is: the next
 * pieces get deadlines, so they are fetched first and from the fastest peers, and the
 * rest of the file follows in order. A jump, a seek or a resume elsewhere, drops
 * everything behind the new position except the two ends of the file, where the
 * container keeps its header and its index.
 *
 * The session starts on the first torrent played and then stays up for the life of the
 * process. Stopping it blocks for seconds, and the only cost of keeping it is the DHT
 * idling.
 */
internal class TorrentEngine(private val root: File) {

    private val lock = ReentrantLock()

    /** Signalled on every finished piece and every failure, so waiters look again. */
    private val changed = lock.newCondition()

    private var session: SessionManager? = null
    private var active: Torrent? = null

    /** Torrents the disk refused this process: asking again would fail the same way. */
    private val refused = mutableSetOf<String>()

    /**
     * A torrent and its libtorrent handle.
     *
     * The handle is only ever used through [use]. Once the torrent is removed every call
     * on it throws, and a reader may still be in the middle of a piece when that happens:
     * the film closing, the player moving to another source. Measured in the lab, that
     * exception killed the app.
     */
    inner class Torrent(val ref: TorrentRef, private val handle: TorrentHandle, val dir: File) {
        val addedAtMs = SystemClock.elapsedRealtime()

        @Volatile
        var file: TorrentFile? = null

        @Volatile
        var failure: String? = null

        /** The piece the reader asked for last; see [focus]. */
        var focusPiece = -1

        /** See [askForPeers]. */
        @Volatile
        var askedForPeersAtMs = addedAtMs

        /** When the last peer went, or zero while there is one. See [isStranded]. */
        @Volatile
        var peerlessSinceMs = addedAtMs

        /**
         * [block] on the handle, or null once the torrent is gone or failed. [evenIfFailed]
         * is for removing it, which a failed torrent needs as much as any.
         */
        fun <T> use(evenIfFailed: Boolean = false, block: (TorrentHandle) -> T): T? {
            if (failure != null && !evenIfFailed) return null
            return try {
                block(handle)
            } catch (e: RuntimeException) {
                // libtorrent's "invalid torrent handle used", from a torrent removed under us.
                failure = failure ?: "handle: ${e.message}"
                null
            }
        }
    }

    /** The one file of a torrent being played, in libtorrent's coordinates. */
    class TorrentFile(
        val index: Int,
        val size: Long,
        val path: File,
        private val offsetInTorrent: Long,
        private val pieceLength: Int,
        val numPieces: Int,
    ) {
        fun pieceAt(position: Long): Int = ((offsetInTorrent + position) / pieceLength).toInt()

        /** How many pieces at each end hold the header and the index; see [focus]. */
        val edgePieces: Int = (EDGE_BYTES / pieceLength).toInt().coerceAtLeast(1)

        /** Where [piece] ends in this file, exclusive. */
        fun pieceEnd(piece: Int): Long = (piece + 1L) * pieceLength - offsetInTorrent

        val firstPiece: Int get() = pieceAt(0)
        val lastPiece: Int get() = pieceAt(size - 1)
    }

    /**
     * The torrent [ref] names, added if it is not the one already running. Replacing
     * deletes the previous one and its data. Null when it cannot be added at all.
     */
    fun open(ref: TorrentRef): Torrent? = lock.withLock {
        active?.takeIf { it.ref.infoHash == ref.infoHash && it.failure == null }
            ?.let { return it.also(::askForPeers) }
        if (ref.infoHash in refused) return null

        val session = session ?: startSession().also { session = it }
        active?.let { discard(it) }

        val dir = File(root, ref.infoHash).apply { mkdirs() }
        val params = AddTorrentParams.parseMagnetUri(ref.magnet())
        params.setSavePath(dir.absolutePath)
        // Not auto-managed, so the session's queue can never pause the film, and not paused,
        // which libtorrent adds by default for that queue to undo. Sequential, so whatever
        // the deadlines leave over still arrives in the order it is watched.
        params.setFlags(
            params.getFlags()
                .and_(TorrentFlags.AUTO_MANAGED.inv())
                .and_(TorrentFlags.PAUSED.inv())
                .or_(TorrentFlags.SEQUENTIAL_DOWNLOAD),
        )
        val error = ErrorCode(error_code())
        val handle = SessionHandle(session.swig()).addTorrent(params, error)
        if (error.isError || !handle.isValid) {
            Log.w(TAG, "torrent ${ref.short()} could not be added: ${error.message}")
            return null
        }
        Log.i(TAG, "torrent ${ref.short()} added, ${ref.trackers.size} trackers from the addon")
        Torrent(ref, handle, dir).also { active = it }
    }

    /**
     * The file to play, once the metadata has come in from the swarm. Null when it does
     * not arrive within [timeoutMs] or the torrent has no file worth playing.
     */
    fun awaitFile(torrent: Torrent, timeoutMs: Long): TorrentFile? {
        torrent.file?.let { return it }
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (torrent.failure != null || !isActive(torrent)) return null
            if (torrent.use { it.status().hasMetadata() } == true) break
            askForPeers(torrent)
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            lock.withLock { changed.await(minOf(left, POLL_MS), TimeUnit.MILLISECONDS) }
        }
        return lock.withLock { torrent.file ?: chooseFile(torrent) }
    }

    private fun chooseFile(torrent: Torrent): TorrentFile? {
        val info = torrent.use { it.torrentFile() } ?: return null
        val files = info.files()
        val index = pickFile(files, torrent.ref) ?: return null
        torrent.use { handle ->
            handle.prioritizeFiles(Array(files.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE })
        } ?: return null
        val file = TorrentFile(
            index = index,
            size = files.fileSize(index),
            path = File(torrent.dir, files.filePath(index)),
            offsetInTorrent = files.fileOffset(index),
            pieceLength = info.pieceLength(),
            numPieces = info.numPieces(),
        )
        Log.i(
            TAG,
            "torrent ${torrent.ref.short()} metadata after ${SystemClock.elapsedRealtime() - torrent.addedAtMs}ms: " +
                "file ${index + 1} of ${files.numFiles()}, ${file.size / MB}MB, " +
                "${info.numPieces()} pieces of ${info.pieceLength() / 1024}KB",
        )
        torrent.file = file
        return file
    }

    /**
     * Waits until [piece] is on disk, making it the most urgent thing the torrent
     * fetches. False on a failure, on a newer torrent replacing this one, or after
     * [timeoutMs], which the caller answers by closing the connection: the player
     * reconnects, and this is asked again.
     */
    fun awaitPiece(torrent: Torrent, piece: Int, timeoutMs: Long): Boolean {
        lock.withLock { focus(torrent, piece) }
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (torrent.use { it.havePiece(piece) } != true) {
            if (torrent.failure != null || !isActive(torrent)) return false
            askForPeers(torrent)
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) return false
            lock.withLock { changed.await(minOf(left, POLL_MS), TimeUnit.MILLISECONDS) }
        }
        return true
    }

    /**
     * Moves the torrent's attention to [piece]: deadlines on it and the next few, and,
     * after a jump, nothing behind it but the file's two ends.
     *
     * A read of either end is not a jump. The player reads the header and the index there
     * while it opens, and again on every reopen, and the film is somewhere else. Treating
     * the index at the end as the playhead marked the whole film unwanted, and a torrent
     * with nothing left to want counts as finished, which drops its seeds. libtorrent then
     * waits a minute to call them again: measured in the lab, the film stopped for a
     * minute right after it started.
     */
    private fun focus(torrent: Torrent, piece: Int) {
        val file = torrent.file ?: return
        if (piece == torrent.focusPiece) return
        val edge = piece < file.firstPiece + file.edgePieces || piece > file.lastPiece - file.edgePieces
        if (edge && torrent.focusPiece >= 0) {
            torrent.use { handle -> hurry(handle, file, piece) }
            return
        }
        val jumped = torrent.focusPiece < 0 || piece !in torrent.focusPiece..torrent.focusPiece + 1
        torrent.focusPiece = piece
        torrent.use { handle -> steer(handle, file, piece, jumped) }
    }

    private fun steer(handle: TorrentHandle, file: TorrentFile, piece: Int, jumped: Boolean) {
        if (jumped) {
            handle.clearPieceDeadlines()
            handle.prioritizePieces(
                Array(file.numPieces) { p ->
                    when {
                        p < file.firstPiece || p > file.lastPiece -> Priority.IGNORE
                        p >= piece -> Priority.DEFAULT
                        p < file.firstPiece + file.edgePieces || p > file.lastPiece - file.edgePieces -> Priority.DEFAULT
                        else -> Priority.IGNORE
                    }
                },
            )
        }
        hurry(handle, file, piece)
    }

    /** Deadlines on [piece] and the next few, nearest first. */
    private fun hurry(handle: TorrentHandle, file: TorrentFile, piece: Int) {
        for (step in 0 until URGENT_PIECES) {
            val next = piece + step
            if (next > file.lastPiece) break
            if (!handle.havePiece(next)) handle.setPieceDeadline(next, step * DEADLINE_STEP_MS)
        }
    }

    /**
     * Asks the trackers and the DHT again, while the torrent has no peer at all.
     *
     * libtorrent asks a tracker on the interval the tracker sets, and never sooner than
     * five minutes by its own default. A swarm that was empty when the film opened, or
     * one whose only peers left, was then not looked for again for five minutes, while
     * the player retried every few seconds. Measured in the lab: no first frame in 90s
     * from a seeder that arrived at 30s.
     */
    private fun askForPeers(torrent: Torrent) {
        val now = SystemClock.elapsedRealtime()
        if (now - torrent.askedForPeersAtMs < ASK_FOR_PEERS_EVERY_MS) return
        torrent.askedForPeersAtMs = now
        torrent.use { handle ->
            if (handle.status().numPeers() > 0) return@use
            handle.forceReannounce()
            handle.forceDHTAnnounce()
            Log.i(TAG, "torrent ${torrent.ref.short()} has no peers, asking the trackers and the DHT again")
        }
    }

    /**
     * True when no peer has been connected for [STRANDED_MS]: the
     * swarm is gone, for now at least. The server answers that with a 503, which is what
     * the player reads as a host refusing, and with another source queued it moves there
     * at once instead of reopening this one for minutes. Measured in the lab: a swarm that
     * left 40s in froze the film for 93s with a working source waiting.
     */
    fun isStranded(torrent: Torrent): Boolean {
        val since = torrent.peerlessSinceMs
        return since != 0L && SystemClock.elapsedRealtime() - since >= STRANDED_MS
    }

    fun hasPiece(torrent: Torrent, piece: Int): Boolean = torrent.use { it.havePiece(piece) } == true

    /** Removes every torrent but [infoHash]'s, with its data. Null removes all of them. */
    fun keepOnly(infoHash: String?) = lock.withLock {
        val current = active ?: return@withLock
        if (current.ref.infoHash != infoHash) discard(current)
    }

    private fun discard(torrent: Torrent) {
        if (active === torrent) active = null
        changed.signalAll()
        Log.i(TAG, "torrent ${torrent.ref.short()} removed with its data")
        torrent.use(evenIfFailed = true) { handle -> session?.remove(handle, SessionHandle.DELETE_FILES) }
        torrent.failure = "closed"
        // libtorrent deletes what it wrote, but not a part file or an emptied folder.
        thread(name = "torrent-cleanup", isDaemon = true) {
            Thread.sleep(CLEANUP_DELAY_MS)
            torrent.dir.deleteRecursively()
        }
    }

    private fun isActive(torrent: Torrent) = lock.withLock { active === torrent }

    private fun startSession(): SessionManager {
        val settings = SettingsPack()
            // Upload stays on: peers serve first whoever serves them, so a torrent that
            // never uploads is the last to get pieces. Capped well below a home uplink, so
            // it never slows the download that carries the film, and nothing is seeded once
            // the film closes, because the torrent goes with it.
            .uploadRateLimit(UPLOAD_BYTES_PER_SECOND)
            .connectionsLimit(MAX_CONNECTIONS)
            .activeDownloads(1)
            .activeSeeds(1)
            .apply {
                // A torrent with every wanted piece counts as finished and, by default, drops
                // its seeds. Here that is only a pause: a seek back makes pieces wanted again,
                // and reconnecting would cost a minute.
                setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
            }
        val params = SessionParams(settings).apply {
            // libtorrent 2's default maps the files into memory, and a 20GB film does not
            // fit in the address space of a 32-bit TV. POSIX reads and writes in order on
            // one thread, so a piece is on disk by the time it counts as had.
            setPosixDiskIO()
        }
        return SessionManager(false).apply {
            addListener(alerts)
            start(params)
            Log.i(TAG, "torrent session started")
            thread(name = "torrent-status", isDaemon = true) { logStatus() }
        }
    }

    /**
     * One line every [STATUS_EVERY_MS] while a torrent runs: what a swarm can give is the
     * whole story of a torrent that stalls, and nothing else in the log shows it.
     */
    private fun logStatus() {
        while (true) {
            Thread.sleep(STATUS_EVERY_MS)
            val torrent = lock.withLock { active } ?: continue
            val status = torrent.use { it.status() } ?: continue
            // Peers, not the download rate: libtorrent's rate is an average that takes most
            // of a minute to reach zero after the last peer has gone.
            torrent.peerlessSinceMs = when {
                status.numPeers() > 0 -> 0L
                torrent.peerlessSinceMs == 0L -> SystemClock.elapsedRealtime()
                else -> torrent.peerlessSinceMs
            }
            Log.i(
                TAG,
                "torrent ${torrent.ref.short()}: ${status.downloadPayloadRate() / 1024}KB/s down, " +
                    "${status.uploadPayloadRate() / 1024}KB/s up, ${status.numPeers()} peers " +
                    "(${status.numSeeds()} seeds), ${(status.progress() * 100).toInt()}% of the file, " +
                    "reading piece ${torrent.focusPiece}",
            )
        }
    }

    private val alerts = object : AlertListener {
        override fun types() = intArrayOf(
            AlertType.PIECE_FINISHED.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.TORRENT_ERROR.swig(),
        )

        override fun alert(alert: Alert<*>) {
            val failure = when (alert) {
                is FileErrorAlert -> "file: ${alert.error().message}"
                is TorrentErrorAlert -> "torrent: ${alert.error().message}"
                else -> null
            }
            lock.withLock {
                if (failure != null) {
                    val hash = runCatching { (alert as TorrentAlert<*>).handle().infoHash().toHex() }.getOrNull()
                    active?.takeIf { it.ref.infoHash == hash }?.let { torrent ->
                        Log.w(TAG, "torrent ${torrent.ref.short()} failed, $failure")
                        torrent.failure = failure
                        refused += torrent.ref.infoHash
                    }
                }
                changed.signalAll()
            }
        }
    }

    private fun TorrentRef.short() = infoHash.take(8)

    companion object {
        /**
         * The file the addon named, or the largest one. `fileIdx` is the addon's count,
         * which may or may not include padding files, so a file name that disagrees with it
         * wins.
         */
        internal fun pickFile(files: FileStorage, ref: TorrentRef): Int? {
            val usable = (0 until files.numFiles()).filter { !files.padFileAt(it) && files.fileSize(it) > 0 }
            val named = ref.fileName?.let { name -> usable.firstOrNull { files.fileName(it) == name } }
            return named
                ?: ref.fileIndex?.takeIf { it in usable }
                ?: usable.maxByOrNull { files.fileSize(it) }
        }

        const val TAG = "Playback"
        private const val MB = 1024 * 1024
        private const val POLL_MS = 250L

        /** Pieces ahead of the reader that get a deadline, the nearest first. */
        private const val URGENT_PIECES = 8
        private const val DEADLINE_STEP_MS = 400

        /**
         * Kept wanted at each end of the file, where containers put header and index. In
         * bytes, because an MP4's index can run to megabytes and pieces can be 256KB.
         */
        private const val EDGE_BYTES = 8L * 1024 * 1024

        /** 2 Mbps. */
        private const val UPLOAD_BYTES_PER_SECOND = 250_000
        private const val MAX_CONNECTIONS = 120
        private const val CLEANUP_DELAY_MS = 5_000L
        private const val STATUS_EVERY_MS = 10_000L
        private const val ASK_FOR_PEERS_EVERY_MS = 15_000L
        private const val STRANDED_MS = 20_000L
    }
}
