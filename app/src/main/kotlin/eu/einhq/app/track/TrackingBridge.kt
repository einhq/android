package eu.einhq.app.track

import android.content.Context
import androidx.work.ExistingWorkPolicy
import org.json.JSONObject
import java.time.Instant

/**
 * The position reports' part of `window.EinApp`. Each method answers the reports as they are then (`state`):
 *
 * - `tracking`: the settings, what the app may know, and how the reports go;
 * - `trackingSet` `{ enabled?, precise? }`: change the settings, and start or stop the service with them;
 * - `trackingAllow`: ask the phone's owner for the permissions the reports need (`askPermissions`);
 * - `trackingSend`: send the waiting positions now.
 *
 * Each reads the app's preferences and database, so it is called off the main thread.
 */
class TrackingBridge(private val context: Context) {
    private val store = TrackingStore(context)

    /** Do `method` with `params`; `askPermissions` asks for what the reports need and returns once it has been answered. */
    suspend fun call(method: String, params: JSONObject, askPermissions: suspend () -> Unit): JSONObject {
        when (method) {
            "trackingSet" -> {
                val settings = store.load()
                store.save(
                    settings.copy(
                        enabled = if (params.has("enabled")) params.getBoolean("enabled") else settings.enabled,
                        precise = if (params.has("precise")) params.getBoolean("precise") else settings.precise,
                    ),
                )
                Tracking.apply(context)
            }
            "trackingAllow" -> {
                askPermissions()
                Tracking.apply(context)
            }
            "trackingSend" -> Tracking.send(context, ExistingWorkPolicy.REPLACE)
        }
        return state()
    }

    /** The reports as the App page shows them. */
    fun state(): JSONObject {
        val settings = store.load()
        val queued = FixQueue(context).use { it.count() }
        val answer = JSONObject()
            .put("enabled", settings.enabled)
            .put("precise", settings.precise)
            .put("access", ACCESS.getValue(Tracking.access(context)))
            .put("approximate", Tracking.approximate(context))
            .put("motion", Tracking.motionAllowed(context))
            .put("notifications", Tracking.notificationsAllowed(context))
            .put("running", TrackingService.running)
            .put("still", TrackingService.running && !Tracking.motion.value)
            .put("queued", queued)
        if (Tracking.motionAllowed(context)) store.activity()?.let { answer.put("activity", it) }
        store.lastFix()?.let { (at, accuracy) ->
            answer.put("lastFix", Instant.ofEpochMilli(at).toString())
            accuracy?.let { answer.put("accuracy", it) }
        }
        store.lastSent()?.let { answer.put("lastSent", Instant.ofEpochMilli(it).toString()) }
        store.error()?.let { answer.put("lastError", it) }
        if (queued > 0 && !Tracking.online(context)) answer.put("waitingFor", "network")
        return answer
    }

    companion object {
        /** The bridge's methods this answers. */
        val METHODS = setOf("tracking", "trackingSet", "trackingAllow", "trackingSend")

        private val ACCESS = mapOf(LocationAccess.NONE to "none", LocationAccess.IN_USE to "in-use", LocationAccess.ALWAYS to "always")
    }
}
