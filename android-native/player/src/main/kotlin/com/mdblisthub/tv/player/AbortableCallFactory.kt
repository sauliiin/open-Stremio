package com.mdblisthub.tv.player

import okhttp3.Call
import okhttp3.Request

/**
 * A [Call.Factory] whose latest call can be ended from another thread.
 *
 * This is the only way out of a read on a socket that has gone quiet. Media3's
 * `Loader` runs on a single thread and cancels a load by interrupting it, which
 * a thread blocked in a socket read does not notice — so a seek on a dead
 * connection does not reach the network at all. The new request waits in the
 * queue behind the old read until the read timeout frees the thread.
 *
 * Cancelling the call closes the socket and fails that read at once. The loader
 * treats it as any other load error and resumes from the byte it had reached,
 * so the buffer, the decoders and the picture are all left alone.
 */
internal class AbortableCallFactory(private val delegate: Call.Factory) : Call.Factory {

    @Volatile
    private var latest: Call? = null

    override fun newCall(request: Request): Call = delegate.newCall(request).also { latest = it }

    /** Safe from any thread, and a no-op once the call has finished. */
    fun abortLatest() {
        latest?.cancel()
    }
}
