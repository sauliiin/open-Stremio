package com.mdblisthub.tv.ui.player

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mdblisthub.tv.MainActivity
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.model.MediaType
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Posts one system notification when one concrete playback reaches its end. */
class PlaybackCompletionNotifier(
    context: Context,
    private val type: MediaType,
    private val tmdbId: Int,
    private val season: Int?,
    private val episode: Int?,
) {
    private val appContext = context.applicationContext
    private val delivered = AtomicBoolean(false)

    @Volatile
    private var title: String = ""

    @Volatile
    private var episodeLabel: String? = null

    fun update(title: String, episodeLabel: String?) {
        if (title.isNotBlank()) this.title = title
        if (!episodeLabel.isNullOrBlank()) this.episodeLabel = episodeLabel
    }

    fun show(languageTag: String) {
        // Keep the permission check in the same control-flow scope as notify:
        // besides making the runtime contract explicit, this lets Android
        // lint prove that the protected call below is safe on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!delivered.compareAndSet(false, true)) return

        val localizedContext = appContext.createConfigurationContext(
            Configuration(appContext.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            },
        )
        val contentTitle = localizedContext.getString(
            if (type == MediaType.SHOW) {
                R.string.playback_notification_episode_title
            } else {
                R.string.playback_notification_movie_title
            },
        )
        val mediaTitle = title.ifBlank { localizedContext.getString(R.string.app_name) }
        val contentText = if (type == MediaType.SHOW && !episodeLabel.isNullOrBlank()) {
            localizedContext.getString(
                R.string.playback_notification_episode_content,
                mediaTitle,
                episodeLabel,
            )
        } else {
            mediaTitle
        }

        val openApp = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_REQUEST_CODE,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_omnistream_infinity)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            // Permission can still be revoked between the check above and
            // this call. Treat that race as a skipped notification rather
            // than allowing playback completion to crash the app.
            NotificationManagerCompat.from(appContext).notify(notificationId(), notification)
        } catch (_: SecurityException) {
            delivered.set(false)
        }
    }

    private fun notificationId(): Int = listOf(type, tmdbId, season, episode).hashCode()

    companion object {
        private const val CHANNEL_ID = "playback_completed"
        private const val NOTIFICATION_REQUEST_CODE = 4107

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.playback_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.playback_notification_channel_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
