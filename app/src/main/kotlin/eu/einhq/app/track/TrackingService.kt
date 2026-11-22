package eu.einhq.app.track

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import eu.einhq.app.MainActivity
import eu.einhq.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service of the `location` type that takes the phone's position while the reports
 * are on, with a notification of its own for as long as it runs.
 *
 * While the phone moves it asks the fused provider for a position every `MOVING_INTERVAL_MS` at
 * most, and only once it has gone `MOVING_DISTANCE_M` further, so how often follows how fast it
 * goes; the provider may hold positions for up to `BATCH_MS` and hand them over together. While it
 * lies still it asks for one now and then, at the cost of the cell and Wi-Fi around it, in case it
 * moves without activity recognition telling.
 */
class TrackingService : Service() {
    private val scope = MainScope()
    private lateinit var provider: FusedLocationProviderClient
    private var watching = false
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations
            scope.launch(Dispatchers.IO) { Tracking.keep(applicationContext, locations) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        provider = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = TrackingStore(this).load()
        if (!settings.enabled || Tracking.access(this) == LocationAccess.NONE) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, ID, notification(Tracking.motion.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (error: RuntimeException) {
            // Android refuses a location service started without the position allowed now (a SecurityException) or from the background.
            TrackingStore(this).saveError("Android did not let the app take the phone's position: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (!watching) {
            watching = true
            Tracking.watchMotion(this)
            scope.launch { Tracking.motion.collect { moving -> follow(moving) } }
        } else {
            follow(Tracking.motion.value)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        provider.removeLocationUpdates(callback)
        if (watching) Tracking.stopWatchingMotion(this)
        scope.cancel()
        running = false
        super.onDestroy()
    }

    /** Ask the provider for positions as the phone moves or lies still, under the settings now. */
    @SuppressLint("MissingPermission") // onStartCommand runs the service only with the position allowed.
    private fun follow(moving: Boolean) {
        val precise = TrackingStore(this).load().precise
        val request = if (moving) {
            LocationRequest.Builder(if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, MOVING_INTERVAL_MS)
                .setMinUpdateIntervalMillis(MOVING_INTERVAL_MS / 2)
                .setMinUpdateDistanceMeters(MOVING_DISTANCE_M)
                .setMaxUpdateDelayMillis(BATCH_MS)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, STILL_INTERVAL_MS)
                .setMinUpdateDistanceMeters(STILL_DISTANCE_M)
                .setMaxUpdateDelayMillis(STILL_INTERVAL_MS)
                .build()
        }
        try {
            provider.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (error: SecurityException) {
            TrackingStore(this).saveError("The app may no longer know the phone's position.")
            stopSelf()
            return
        }
        getSystemService<NotificationManager>()?.notify(ID, notification(moving))
    }

    private fun notification(moving: Boolean) = NotificationCompat.Builder(this, channel())
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(getString(R.string.tracking_on))
        .setContentText(getString(if (moving) R.string.tracking_moving else R.string.tracking_still))
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                ID,
                Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_PATH, "/app"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun channel(): String {
        val manager = getSystemService<NotificationManager>()
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.tracking_channel_description) },
            )
        }
        return CHANNEL
    }

    companion object {
        /** Whether the service runs now. */
        @Volatile
        var running = false
            private set

        private const val CHANNEL = "tracking"
        private const val ID = 1521
        private const val MOVING_INTERVAL_MS = 15_000L
        private const val MOVING_DISTANCE_M = 20f
        private const val BATCH_MS = 2 * 60_000L
        private const val STILL_INTERVAL_MS = 15 * 60_000L
        private const val STILL_DISTANCE_M = 100f
    }
}
