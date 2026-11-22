package eu.einhq.app.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.ForegroundInfo
import eu.einhq.app.MainActivity
import eu.einhq.app.R

/**
 * The notification a backup shows while it sends, which also keeps it running in a foreground
 * service: which file, how many are left and how far the file is. It opens the app's App page.
 * Android may refuse a foreground service to an app in the background; the backup then goes on
 * without one, as far as its time allows, and the next run goes on from there.
 */
class Notice(private val context: Context, private val foreground: suspend (ForegroundInfo) -> Unit) {
    private var refused = false
    private var shownAt = 0L

    /** Show `name`, the `done`th of `total`, with `sent` of its `size` bytes sent; a size of 0 while it is being read. */
    suspend fun show(name: String, done: Int, total: Int, sent: Long, size: Long) {
        if (refused) return
        val now = SystemClock.elapsedRealtime()
        if (sent in 1 until size && now - shownAt < MIN_INTERVAL_MS) return
        shownAt = now
        channel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_PATH, "/app"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.backup_sending, done + 1, total))
            .setContentText(name)
            .setProgress(1000, if (size > 0) (1000 * sent / size).toInt() else 0, size <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        try {
            foreground(ForegroundInfo(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (_: IllegalStateException) {
            refused = true
        }
    }

    private companion object {
        const val CHANNEL = "backup"
        const val ID = 1520
        const val MIN_INTERVAL_MS = 1_000L

        fun channel(context: Context) {
            val manager = context.getSystemService<NotificationManager>() ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.backup_channel_description) },
            )
        }
    }
}
