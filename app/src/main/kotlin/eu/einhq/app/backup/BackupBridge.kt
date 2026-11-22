package eu.einhq.app.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The photo backup's part of `window.EinApp`. Each method answers the backup as it is then (`state`):
 *
 * - `backup`: its settings, what the app may read, and how far it is;
 * - `backupSet` `{ enabled?, into?: { dir, label } | null, folders?: [path], wifiOnly?, chargingOnly? }`:
 *   change the settings, and when and how WorkManager runs it with them;
 * - `backupAllow`: ask the phone's owner for the permissions it needs (`askPermissions`);
 * - `backupNow`: run it now, under its conditions.
 *
 * Each blocks on MediaStore and the record, so it is called off the main thread.
 */
class BackupBridge(private val context: Context) {
    private val store = BackupSettingsStore(context)

    /** Do `method` with `params`; `askPermissions` asks for what the backup needs and returns once it has been answered. */
    suspend fun call(method: String, params: JSONObject, askPermissions: suspend () -> Unit): JSONObject {
        when (method) {
            "backupSet" -> {
                val settings = changeOf(params).applyTo(store.load())
                store.save(settings)
                Backup.schedule(context, settings)
            }
            "backupAllow" -> {
                askPermissions()
                Backup.schedule(context)
            }
            "backupNow" -> Backup.runNow(context)
        }
        return state()
    }

    /** The backup as the App page shows it. */
    fun state(): JSONObject {
        val settings = store.load()
        val access = Backup.mediaAccess(context)
        val outcome = store.outcome()
        val answer = JSONObject()
            .put("enabled", settings.active)
            .put("wifiOnly", settings.wifiOnly)
            .put("chargingOnly", settings.chargingOnly)
            .put("access", access.name.lowercase())
            .put("location", Backup.mediaLocation(context))
            .put("running", Backup.isRunning)
        settings.into?.let { answer.put("into", JSONObject().put("dir", it.dir).put("label", it.label)) }

        val folders = JSONArray()
        var waiting = 0
        var sent = 0
        if (access != MediaAccess.NONE) {
            val found = PhoneMedia.folders(context)
            for (folder in found) folders.put(folderJson(folder.path, folder.name, folder.count, folder.path in settings.folders))
            // A chosen folder that holds nothing now stays, so it can be left out again.
            for (path in settings.folders - found.map { it.path }.toSet()) {
                folders.put(folderJson(path, path.trimEnd('/').substringAfterLast('/'), 0, true))
            }
            SentRecord(context).use { record ->
                waiting = Backup.toSend(PhoneMedia.items(context, settings.folders), record.sent()).size
                sent = record.sentCount()
            }
        }
        answer.put("folders", folders).put("waiting", waiting).put("sent", sent)

        Backup.sending?.let { answer.put("current", JSONObject().put("name", it.name).put("sent", it.sent).put("size", it.size)) }
        if (settings.active && !Backup.isRunning) Backup.waitingFor(context, settings)?.let { answer.put("waitingFor", it) }
        outcome.endedAt?.let { answer.put("lastRun", Instant.ofEpochMilli(it).toString()) }
        outcome.error?.let { answer.put("lastError", it) }
        return answer
    }

    private fun folderJson(path: String, name: String, count: Int, chosen: Boolean): JSONObject =
        JSONObject().put("path", path).put("name", name).put("count", count).put("chosen", chosen)

    private fun changeOf(params: JSONObject): BackupChange {
        val into = params.optJSONObject("into")?.let { BackupTarget(it.getString("dir"), it.getString("label")) }
        return BackupChange(
            enabled = params.optBoolean("enabled").takeIf { params.has("enabled") },
            into = into,
            clearInto = params.has("into") && params.isNull("into"),
            folders = params.optJSONArray("folders")?.let { list -> (0 until list.length()).map { list.getString(it) }.toSet() },
            wifiOnly = params.optBoolean("wifiOnly").takeIf { params.has("wifiOnly") },
            chargingOnly = params.optBoolean("chargingOnly").takeIf { params.has("chargingOnly") },
        )
    }

    companion object {
        /** The bridge's methods this answers. */
        val METHODS = setOf("backup", "backupSet", "backupAllow", "backupNow")
    }
}
