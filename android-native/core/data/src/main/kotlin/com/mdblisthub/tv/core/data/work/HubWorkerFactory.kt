package com.mdblisthub.tv.core.data.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.mdblisthub.tv.core.data.DataGraph

/**
 * Hands each worker its dependencies.
 *
 * WorkManager instantiates workers reflectively, which is exactly the case a
 * DI framework exists to paper over. With a graph this shallow, a `when` over
 * four class names is less machinery than an annotation processor and fails at
 * a readable place when a worker is added and forgotten.
 */
class HubWorkerFactory(private val graphProvider: () -> DataGraph) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        // Resolved per call rather than held: WorkManager may ask for a worker
        // at any point after process start, including before the Application
        // has finished building the graph.
        val graph = graphProvider()

        return when (workerClassName) {
            ListSyncWorker::class.java.name ->
                ListSyncWorker(appContext, workerParameters, graph.lists)

            MetadataWorker::class.java.name ->
                MetadataWorker(appContext, workerParameters, graph.media, graph.database)

            ArtworkWorker::class.java.name ->
                ArtworkWorker(appContext, workerParameters, graph.database, graph.imageWarmer)

            ResumeSyncWorker::class.java.name ->
                ResumeSyncWorker(appContext, workerParameters, graph.playback)

            CachePruneWorker::class.java.name ->
                CachePruneWorker(appContext, workerParameters, graph.database)

            // Null lets WorkManager fall back to its default factory, which
            // is what any worker added by a library would need.
            else -> null
        }
    }
}
