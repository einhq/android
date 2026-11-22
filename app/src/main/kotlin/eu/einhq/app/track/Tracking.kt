package eu.einhq.app.track

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import eu.einhq.app.PairingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/** Where the app may know the phone's position: never, while it is in use, or all the time. */
enum class LocationAccess { NONE, IN_USE, ALWAYS }

/**
 * The position reports: while they are on, `TrackingService` takes the phone's position from
 * Google's fused location provider as it moves, and the positions go to the phone's `position`
 * track on EiN in batches, as the account the app was paired as.
 *
 * A position is kept in `FixQueue` first, so none is lost while the phone is offline. A send job
 * (`SEND`) runs a minute after a new one, gathering what arrives meanwhile, when the phone has a
 * connection; WorkManager runs it again later while EiN cannot be reached.
 *
 * Activity recognition tells when the phone lies still: the service then asks for no more than a
 * position now and then, until it moves again.
 */
object Tracking {
    private const val SEND = "track-send"
    /** How long a send waits after a position, for those that follow it. */
    private const val SEND_DELAY_S = 60L

    private val moving = MutableStateFlow(true)

    /** Whether the phone moves, as activity recognition last told; true until it tells otherwise. */
    val motion: StateFlow<Boolean> = moving

    // --- Permissions ---

    fun access(context: Context): LocationAccess = when {
        !granted(context, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.NONE
        granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> LocationAccess.ALWAYS
        else -> LocationAccess.IN_USE
    }

    /** Whether the phone's owner let the app know only its approximate position. */
    fun approximate(context: Context): Boolean =
        access(context) != LocationAccess.NONE && !granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Whether the app may tell when the phone moves, to pause while it lies still. */
    fun motionAllowed(context: Context): Boolean = granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /**
     * The first step of asking: the position while the app is in use, how the phone moves, and its
     * notification. Being let know the position all the time is asked for on its own after it, as
     * Android requires.
     */
    fun foregroundPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // --- Running ---

    /**
     * Start or stop the service as the settings say. Started from the app, it runs with the position
     * only while in use too; started from the background (after a restart of the phone) it needs it
     * all the time, as Android gives a service started so no position otherwise.
     */
    fun apply(context: Context, fromBackground: Boolean = false) {
        val settings = TrackingStore(context).load()
        val access = access(context)
        val run = settings.enabled && PairingStore(context).load() != null &&
            access != LocationAccess.NONE && (!fromBackground || access == LocationAccess.ALWAYS)
        val service = Intent(context, TrackingService::class.java)
        if (!run) {
            context.stopService(service)
            return
        }
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (error: IllegalStateException) {
            TrackingStore(context).saveError("Android did not let the app start reporting: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Stop reporting and forget everything about it: the phone is unpaired. */
    fun forget(context: Context) {
        context.stopService(Intent(context, TrackingService::class.java))
        WorkManager.getInstance(context).cancelUniqueWork(SEND)
        FixQueue(context).use { it.clear() }
        TrackingStore(context).clear()
        moving.value = true
    }

    // --- Motion ---

    /** Have activity recognition tell `TrackingReceiver` when the phone starts or stops moving, when the app may. */
    @SuppressLint("MissingPermission") // motionAllowed checks it.
    fun watchMotion(context: Context) {
        if (!motionAllowed(context)) return
        val transitions = MOTIONS.keys.map { ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build() } +
            ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
        ActivityRecognition.getClient(context).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), motionIntent(context))
            .addOnFailureListener { moving.value = true }
    }

    @SuppressLint("MissingPermission") // motionAllowed checks it.
    fun stopWatchingMotion(context: Context) {
        if (motionAllowed(context)) ActivityRecognition.getClient(context).removeActivityTransitionUpdates(motionIntent(context))
        moving.value = true
    }

    /** The phone entered `activity` (`DetectedActivity`'s), or left lying still. */
    fun moved(context: Context, activity: Int, entered: Boolean) {
        val still = activity == DetectedActivity.STILL && entered
        val store = TrackingStore(context)
        if (entered) MOTIONS[activity]?.let { store.saveActivity(it) } else if (activity == DetectedActivity.STILL) store.clearActivity()
        moving.value = !still
    }

    private fun motionIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TrackingReceiver::class.java).setAction(TrackingReceiver.MOTION),
        // Activity recognition adds what it saw to the intent.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** What the phone does, by `DetectedActivity`'s types, in Traccar Client's names, which EiN keeps. */
    private val MOTIONS = mapOf(
        DetectedActivity.STILL to "still",
        DetectedActivity.WALKING to "walking",
        DetectedActivity.RUNNING to "running",
        DetectedActivity.ON_BICYCLE to "on_bicycle",
        DetectedActivity.IN_VEHICLE to "in_vehicle",
    )

    // --- Positions ---

    /** Keep the positions the provider gave that are worth keeping, and have them sent. */
    fun keep(context: Context, locations: List<Location>) {
        val store = TrackingStore(context)
        val battery = context.getSystemService<BatteryManager>()
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val charging = battery?.isCharging
        // What the phone was last seen doing holds only while activity recognition goes on telling.
        val activity = if (motionAllowed(context)) store.activity() else null
        val fixes = locations.map { fixOf(it, level, charging, activity) }.filter { it.usable }
        if (fixes.isEmpty()) return
        FixQueue(context).use { it.add(fixes) }
        store.saveFix(fixes.maxBy { it.time })
        send(context, ExistingWorkPolicy.KEEP, SEND_DELAY_S)
    }

    private fun fixOf(location: Location, battery: Int?, charging: Boolean?, activity: String?) = Fix(
        lat = location.latitude,
        lng = location.longitude,
        time = location.time,
        alt = if (location.hasAltitude()) location.altitude else null,
        speed = if (location.hasSpeed()) location.speed.toDouble() else null,
        course = if (location.hasBearing()) location.bearing.toDouble() else null,
        accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        battery = battery,
        charging = charging,
        activity = activity,
    )

    /** Send what is waiting, after `delaySeconds`, once the phone has a connection; `policy` says whether a send already waiting stays. */
    fun send(context: Context, policy: ExistingWorkPolicy, delaySeconds: Long = 0) {
        val work = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SEND, policy, work)
    }

    /** Whether the phone has a connection to send over. */
    fun online(context: Context): Boolean {
        val connectivity = context.getSystemService<ConnectivityManager>()
        return connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}

/** The send job: everything waiting, in batches, until none is left or EiN cannot be reached. */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (FixSender.sendAll(applicationContext)) {
        FixSender.Sent.DONE -> Result.success()
        FixSender.Sent.AGAIN -> Result.retry()
        FixSender.Sent.REFUSED -> Result.failure()
    }
}
