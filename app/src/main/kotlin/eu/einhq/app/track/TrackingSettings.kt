package eu.einhq.app.track

import android.content.Context
import androidx.core.content.edit

/**
 * The position reports' settings: whether they are on, and whether to ask for the phone's precise
 * position (its GPS) rather than the balanced one, which spares the battery.
 */
data class TrackingSettings(val enabled: Boolean = false, val precise: Boolean = false)

/**
 * The settings and how the reports went: the last position taken and its accuracy, what the phone
 * was last doing, when positions last reached EiN, and why they did not when they did not. Kept in
 * the app's preferences.
 */
class TrackingStore(context: Context) {
    private val prefs = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)

    fun load(): TrackingSettings = TrackingSettings(prefs.getBoolean(ENABLED, false), prefs.getBoolean(PRECISE, false))

    fun save(settings: TrackingSettings) = prefs.edit {
        putBoolean(ENABLED, settings.enabled)
        putBoolean(PRECISE, settings.precise)
    }

    /** The last position taken: when, in milliseconds, and how sure, in metres. */
    fun lastFix(): Pair<Long, Double?>? =
        prefs.getLong(FIX_AT, 0).takeIf { it > 0 }?.let { it to prefs.getFloat(FIX_ACCURACY, -1f).takeIf { a -> a >= 0 }?.toDouble() }

    fun saveFix(fix: Fix) = prefs.edit {
        putLong(FIX_AT, fix.time)
        if (fix.accuracy != null) putFloat(FIX_ACCURACY, fix.accuracy.toFloat()) else remove(FIX_ACCURACY)
    }

    /** What the phone was last doing, by Traccar Client's names (`still`, `walking`, `in_vehicle`, …). */
    fun activity(): String? = prefs.getString(ACTIVITY, null)

    fun saveActivity(activity: String) = prefs.edit { putString(ACTIVITY, activity) }

    /** The phone no longer does what it was last seen doing, and does not yet say what instead. */
    fun clearActivity() = prefs.edit { remove(ACTIVITY) }

    fun lastSent(): Long? = prefs.getLong(SENT_AT, 0).takeIf { it > 0 }

    fun error(): String? = prefs.getString(ERROR, null)

    /** Positions reached EiN now. */
    fun saveSent(at: Long) = prefs.edit {
        putLong(SENT_AT, at)
        remove(ERROR)
    }

    fun saveError(error: String) = prefs.edit { putString(ERROR, error) }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val ENABLED = "enabled"
        const val PRECISE = "precise"
        const val FIX_AT = "fixAt"
        const val FIX_ACCURACY = "fixAccuracy"
        const val ACTIVITY = "activity"
        const val SENT_AT = "sentAt"
        const val ERROR = "error"
    }
}
