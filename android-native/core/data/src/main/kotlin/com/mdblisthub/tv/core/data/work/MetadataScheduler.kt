package com.mdblisthub.tv.core.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * When each metadata worker runs.
 *
 * The shape of the schedule is the design: the lists refresh on the timescale
 * mdblist rebuilds them, the expensive hydration runs unattended in the
 * background, and neither is ever something the user waits on. Opening the app
 * enqueues a catch-up sync and then reads the database regardless of how it
 * goes.
 */
class MetadataScheduler(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Resolved on first use, never in the constructor.
     *
     * `WorkManager.getInstance` triggers on-demand initialisation, which calls
     * back into `Application.workManagerConfiguration` to get the worker
     * factory — and that factory needs the very graph this scheduler is being
     * constructed as part of. Touching it eagerly closes the loop and throws
     * `lateinit property graph has not been initialized` before the first
     * screen ever draws.
     */
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    private val online = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Bulk work also waits on battery. A TV box is mains-powered, but the same
     * APK on a tablet should not hydrate a thousand titles on 8% charge.
     */
    private val background = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresDeviceIdle(true)
        .build()

    /** A fresh sign-in has nothing cached, so it starts everything at once. */
    fun onSignedIn() {
        syncNow(force = true)
        schedulePeriodic()
    }

    fun onSignedOut() {
        workManager.cancelAllWork()
    }

    /**
     * The catch-up run on app open. Artwork warming is chained behind the list
     * sync rather than run beside it — there is no point pre-fetching posters
     * for rows that are about to be replaced.
     */
    fun syncNow(force: Boolean = false) {
        val sync = OneTimeWorkRequestBuilder<ListSyncWorker>()
            .setConstraints(online)
            .setInputData(Data.Builder().putBoolean(ListSyncWorker.KEY_FORCE, force).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val artwork = OneTimeWorkRequestBuilder<ArtworkWorker>()
            .setConstraints(online)
            .build()

        workManager
            .beginUniqueWork(ListSyncWorker.NAME, ExistingWorkPolicy.REPLACE, sync)
            .then(artwork)
            .enqueue()

        workManager.enqueueUniqueWork(
            ResumeSyncWorker.NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ResumeSyncWorker>().setConstraints(online).build(),
        )
    }

    /** Nudges the hydration queue — after a sync brought in new titles. */
    fun hydrateSoon() {
        workManager.enqueueUniqueWork(
            "${MetadataWorker.NAME}-now",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MetadataWorker>().setConstraints(background).build(),
        )
    }

    private fun schedulePeriodic() {
        periodic<ListSyncWorker>("${ListSyncWorker.NAME}-periodic", 6, online)
        periodic<MetadataWorker>(MetadataWorker.NAME, 4, background)
        periodic<ResumeSyncWorker>("${ResumeSyncWorker.NAME}-periodic", 3, online)
        periodic<CachePruneWorker>(CachePruneWorker.NAME, 24, background)
    }

    private inline fun <reified T : androidx.work.ListenableWorker> periodic(
        name: String,
        hours: Long,
        constraints: Constraints,
    ) {
        workManager.enqueueUniquePeriodicWork(
            name,
            // KEEP, so an app update does not reset the clock on work that is
            // already scheduled and has been running fine.
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<T>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }
}
