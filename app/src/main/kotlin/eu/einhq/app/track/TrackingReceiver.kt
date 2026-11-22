package eu.einhq.app.track

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult

/**
 * What the position reports hear from outside the app: activity recognition telling the phone
 * started or stopped moving (`MOTION`), and the phone having started or the app been updated, after
 * which the reports go on when they are on.
 */
class TrackingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MOTION -> {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
                Tracking.moved(context, last.activityType, last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Tracking.apply(context, fromBackground = true)
        }
    }

    companion object {
        const val MOTION = "eu.einhq.app.MOTION"
    }
}
