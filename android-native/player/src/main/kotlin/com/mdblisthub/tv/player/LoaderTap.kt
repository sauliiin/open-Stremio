package com.mdblisthub.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener

/**
 * Where the player's loader is reading, seen from the transfers passing through
 * its data source.
 *
 * Written on the loader's thread and read on [MediaPrefetcher]'s, which has to
 * know what the player already holds so it can work ahead of it rather than
 * download the same bytes a second time.
 */
@OptIn(UnstableApi::class)
internal class LoaderTap : TransferListener {

    /** The next byte of the file the loader will read. */
    @Volatile
    var readPosition = 0L
        private set

    /** Everything read so far, from the network or the disk. Only ever grows. */
    @Volatile
    var bytesRead = 0L
        private set

    /** Size of the whole file, once a response has said. */
    @Volatile
    var contentLength = C.LENGTH_UNSET.toLong()
        private set

    /** A new source starts with nothing known about it. */
    fun reset() {
        readPosition = 0L
        contentLength = C.LENGTH_UNSET.toLong()
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        readPosition = dataSpec.absoluteStreamPosition
        if (isNetwork) {
            val size = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()
                .let(HttpUtil::getDocumentSize)
            if (size != C.LENGTH_UNSET.toLong()) contentLength = size
        }
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        readPosition += bytesTransferred
        bytesRead += bytesTransferred
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
}
