package com.mdblisthub.tv.core.data.work

/**
 * Pulls artwork into the image cache ahead of it being drawn.
 *
 * Declared here and implemented in the app, where the Coil loader lives: the
 * worker's job is knowing *which* posters matter, and that is a data question.
 * How they get cached is not.
 */
fun interface ImageWarmer {
    suspend fun warm(urls: List<String>)
}
