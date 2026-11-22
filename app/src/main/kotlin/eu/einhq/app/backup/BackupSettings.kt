package eu.einhq.app.backup

import android.content.Context
import androidx.core.content.edit

/** Where the backup sends: a folder of the paired account's on EiN, and what it is called there. */
data class BackupTarget(val dir: String, val label: String)

/**
 * The photo backup's settings: whether it is on, where it sends, the phone's folders it sends (by
 * their `RELATIVE_PATH`, `DCIM/Camera/`), and the conditions it sends under.
 */
data class BackupSettings(
    val enabled: Boolean = false,
    val into: BackupTarget? = null,
    val folders: Set<String> = setOf(CAMERA),
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
) {
    /** Whether a backup has what it needs to run: it is on, and has somewhere to send to. */
    val active: Boolean get() = enabled && into != null

    companion object {
        /** The camera's folder, the one chosen until the phone's owner chooses others. */
        const val CAMERA = "DCIM/Camera/"
    }
}

/** What a change to the settings names; what it leaves null stays as it is, and `clearInto` takes the target away. */
data class BackupChange(
    val enabled: Boolean? = null,
    val into: BackupTarget? = null,
    val clearInto: Boolean = false,
    val folders: Set<String>? = null,
    val wifiOnly: Boolean? = null,
    val chargingOnly: Boolean? = null,
) {
    /** `settings` with this change made. A backup with nowhere to send to is off. */
    fun applyTo(settings: BackupSettings): BackupSettings {
        val into = if (clearInto) null else into ?: settings.into
        return settings.copy(
            enabled = (enabled ?: settings.enabled) && into != null,
            into = into,
            folders = folders ?: settings.folders,
            wifiOnly = wifiOnly ?: settings.wifiOnly,
            chargingOnly = chargingOnly ?: settings.chargingOnly,
        )
    }
}

/** How the last backup went: when it ended, and why it stopped when it did not finish. */
data class BackupOutcome(val endedAt: Long?, val error: String?)

/** The settings and the last outcome, kept in the app's preferences. */
class BackupSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun load(): BackupSettings {
        val dir = prefs.getString(INTO_DIR, null)
        val label = prefs.getString(INTO_LABEL, null)
        return BackupSettings(
            enabled = prefs.getBoolean(ENABLED, false),
            into = if (dir != null && label != null) BackupTarget(dir, label) else null,
            folders = prefs.getStringSet(FOLDERS, null)?.toSet() ?: setOf(BackupSettings.CAMERA),
            wifiOnly = prefs.getBoolean(WIFI_ONLY, true),
            chargingOnly = prefs.getBoolean(CHARGING_ONLY, false),
        )
    }

    fun save(settings: BackupSettings) = prefs.edit {
        putBoolean(ENABLED, settings.enabled)
        if (settings.into != null) {
            putString(INTO_DIR, settings.into.dir)
            putString(INTO_LABEL, settings.into.label)
        } else {
            remove(INTO_DIR)
            remove(INTO_LABEL)
        }
        putStringSet(FOLDERS, settings.folders)
        putBoolean(WIFI_ONLY, settings.wifiOnly)
        putBoolean(CHARGING_ONLY, settings.chargingOnly)
    }

    fun outcome(): BackupOutcome =
        BackupOutcome(prefs.getLong(ENDED_AT, 0).takeIf { it > 0 }, prefs.getString(ERROR, null))

    fun saveOutcome(outcome: BackupOutcome) = prefs.edit {
        putLong(ENDED_AT, outcome.endedAt ?: 0)
        if (outcome.error != null) putString(ERROR, outcome.error) else remove(ERROR)
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val ENABLED = "enabled"
        const val INTO_DIR = "into.dir"
        const val INTO_LABEL = "into.label"
        const val FOLDERS = "folders"
        const val WIFI_ONLY = "wifiOnly"
        const val CHARGING_ONLY = "chargingOnly"
        const val ENDED_AT = "endedAt"
        const val ERROR = "error"
    }
}
