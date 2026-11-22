package eu.einhq.app.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.einhq.app.Pairing
import eu.einhq.app.PairingStore
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Which of the phone's photos and videos the app may read: all, only those picked, or none. */
enum class MediaAccess { ALL, PARTIAL, NONE }

/** The file a backup is sending now, with how much of it is sent. */
data class Sending(val name: String, val sent: Long, val size: Long)

/**
 * The photo backup: it sends the photos and videos of the chosen folders that EiN does not have
 * into the chosen folder there, under their own names and times, and never deletes anything on
 * either side.
 *
 * A run reads the chosen folders from MediaStore and leaves out what the record says was sent at the
 * version the phone has now. It reads each of the rest through for its sha256, asks EiN which of
 * those it has (`POST /checksums`), records those as sent, and sends the others in pieces of
 * `PIECE` bytes (tus); an upload that broke off goes on where EiN says it stopped, in this run or a
 * later one. A run looks again once it is done, for what arrived while it sent.
 *
 * Runs are one WorkManager job (`RUN`) under the settings' conditions, which WorkManager brings back
 * when they are met again after it stopped one. A change to MediaStore's photos or videos starts it,
 * and so does a job every `PERIOD_HOURS` hours, catching what that missed, and asking.
 */
object Backup {
    /** The bytes one request sends of a file. */
    private const val PIECE = 8L * 1024 * 1024

    /** Photos and videos read through before EiN is asked about them. */
    private const val CHECK_BATCH = 100

    private const val PERIOD_HOURS = 1L
    /** Runs that look again at once, at most, for what arrived while they sent. */
    private const val PASSES = 3
    private const val PERIODIC = "backup-periodic"
    private const val NEW_MEDIA = "backup-new-media"
    private const val RUN = "backup-run"

    /** What a `BackupWorker` is: a run (`RUN_JOB`), or what starts one (`WATCH_JOB`, `PERIODIC_JOB`). */
    const val KEY_JOB = "job"
    const val RUN_JOB = "run"
    const val WATCH_JOB = "watch"
    const val PERIODIC_JOB = "periodic"

    private val running = AtomicBoolean(false)

    /** The file being sent now, if any. */
    @Volatile
    var sending: Sending? = null
        private set

    val isRunning: Boolean get() = running.get()

    // --- Permissions ---

    /** The permissions the backup asks for: the photos and videos (picked ones too), where they were taken, and a notification. */
    fun wantedPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    /** The permission whose refusal means the app may see none of the photos, for Android's "don't ask again". */
    fun photosPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun mediaAccess(context: Context): MediaAccess {
        val granted = { permission: String -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.ALL
            Build.VERSION.SDK_INT >= 33 && (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.ALL
            else -> MediaAccess.NONE
        }
    }

    /** Whether the app may read where photos were taken; without it, Android takes that out of every photo it reads. */
    fun mediaLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    // --- Scheduling ---

    /**
     * Have WorkManager run the backup under `settings`' conditions, or no longer, when it is off.
     * With `restart`, a run waiting or under way starts again under them, and one starts now.
     */
    fun schedule(context: Context, settings: BackupSettings = BackupSettingsStore(context).load(), restart: Boolean = true) {
        val work = WorkManager.getInstance(context)
        if (!settings.active) {
            for (name in listOf(PERIODIC, NEW_MEDIA, RUN)) work.cancelUniqueWork(name)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(PERIOD_HOURS, TimeUnit.HOURS).setInputData(workDataOf(KEY_JOB to PERIODIC_JOB)).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        watchForNew(context, ExistingWorkPolicy.KEEP)
        enqueueRun(context, settings, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /** Back up now, under the settings' conditions, unless a run is waiting or under way. */
    fun runNow(context: Context) {
        val settings = BackupSettingsStore(context).load()
        if (settings.active) enqueueRun(context, settings, ExistingWorkPolicy.KEEP)
    }

    /** Stop backing up and forget everything about it: the phone is unpaired. */
    fun forget(context: Context) {
        BackupSettingsStore(context).clear()
        SentRecord(context).use { it.clear() }
        schedule(context, BackupSettings())
    }

    private fun enqueueRun(context: Context, settings: BackupSettings, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.chargingOnly)
            .build()
        val run = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints).setInputData(workDataOf(KEY_JOB to RUN_JOB)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, policy, run)
    }

    /**
     * A job once MediaStore's photos or videos change, which starts a run. Such a trigger fires
     * once, so the job asks for the next (`policy` APPEND_OR_REPLACE: after itself).
     */
    fun watchForNew(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
            .build()
        val watch = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints).setInputData(workDataOf(KEY_JOB to WATCH_JOB)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NEW_MEDIA, policy, watch)
    }

    /** What holds a backup back now: "wifi", "charging" or "network", or null when nothing does. */
    fun waitingFor(context: Context, settings: BackupSettings): String? {
        val connectivity = context.getSystemService<ConnectivityManager>()
        val network = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val charging = context.getSystemService<BatteryManager>()?.isCharging ?: false
        return when {
            network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true -> "network"
            settings.wifiOnly && !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "wifi"
            settings.chargingOnly && !charging -> "charging"
            else -> null
        }
    }

    // --- A run ---

    /**
     * How a run ended: done, stopped by something a later run may get past, or by something it will
     * not; or, for one pass, done with something sent, so another pass looks again.
     */
    enum class Ended { DONE, MORE, AGAIN, STOPPED }

    /** One run of the backup; `notice` shows how far it is while it sends, and `stopped` asks whether to stop. */
    suspend fun run(context: Context, notice: suspend (ForegroundInfo) -> Unit, stopped: () -> Boolean): Ended {
        val store = BackupSettingsStore(context)
        val settings = store.load()
        val pairing = PairingStore(context).load()
        if (pairing == null || !settings.active) return Ended.DONE
        if (!running.compareAndSet(false, true)) return Ended.DONE
        val ended = try {
            SentRecord(context).use { record ->
                var pass = 0
                var ended: Ended
                do ended = send(context, pairing, settings, record, notice, stopped) while (ended == Ended.MORE && ++pass < PASSES)
                if (ended == Ended.MORE) Ended.DONE else ended
            }
        } catch (refused: EinRefused) {
            store.saveOutcome(BackupOutcome(System.currentTimeMillis(), refusal(refused, settings)))
            if (refused.status == 413) Ended.AGAIN else Ended.STOPPED
        } catch (error: IOException) {
            store.saveOutcome(BackupOutcome(System.currentTimeMillis(), "EiN could not be reached: ${error.message ?: error.javaClass.simpleName}"))
            Ended.AGAIN
        } catch (error: SecurityException) {
            store.saveOutcome(BackupOutcome(System.currentTimeMillis(), "The app may no longer read the phone's photos and videos."))
            Ended.STOPPED
        } finally {
            sending = null
            running.set(false)
        }
        return ended
    }

    private fun refusal(refused: EinRefused, settings: BackupSettings): String = when (refused.status) {
        401 -> "EiN refused this phone: pair it again."
        413 -> "EiN has no room for more unfinished uploads now: ${refused.reason}"
        else -> "EiN would not take files into ${settings.into?.label}: ${refused.reason}"
    }

    private suspend fun send(
        context: Context,
        pairing: Pairing,
        settings: BackupSettings,
        record: SentRecord,
        notice: suspend (ForegroundInfo) -> Unit,
        stopped: () -> Boolean,
    ): Ended {
        val store = BackupSettingsStore(context)
        if (mediaAccess(context) == MediaAccess.NONE) {
            store.saveOutcome(BackupOutcome(System.currentTimeMillis(), "The app may not read the phone's photos and videos."))
            return Ended.STOPPED
        }
        val original = mediaLocation(context)
        val sent = record.sent()
        val todo = toSend(PhoneMedia.items(context, settings.folders), sent)
        val into = settings.into!!
        val client = EinUploads(pairing)
        val shown = Notice(context, notice)
        // Copies on the phone of a file sent in this run are not sent again.
        val sentNow = mutableMapOf<String, String?>()
        var done = 0
        for (batch in todo.chunked(CHECK_BATCH)) {
            shown.show(batch.first().name, done, todo.size, 0, 0)
            val read = batch.mapNotNull { item ->
                if (stopped()) return Ended.AGAIN
                val pending = record.pending(item) ?: try {
                    PhoneMedia.digest(context, item, original).let { Pending(it.sha256, it.length, null) }.also { record.keepPending(item, it) }
                } catch (_: FileNotFoundException) {
                    null
                }
                pending?.let { item to it }
            }
            val known = if (read.isEmpty()) emptySet() else client.known(read.map { it.second.sha256 }.distinct())
            for ((item, pending) in read) {
                if (stopped()) return Ended.AGAIN
                when {
                    pending.sha256 in known -> record.markSent(item, pending.sha256, null)
                    pending.sha256 in sentNow -> record.markSent(item, pending.sha256, sentNow[pending.sha256])
                    else -> {
                        shown.show(item.name, done, todo.size, 0, pending.length)
                        val stored = upload(context, client, into, item, pending, original, record) { bytes ->
                            sending = Sending(item.name, bytes, pending.length)
                            shown.show(item.name, done, todo.size, bytes, pending.length)
                        } ?: continue
                        sentNow[pending.sha256] = stored
                        record.markSent(item, pending.sha256, stored)
                    }
                }
                done++
            }
        }
        store.saveOutcome(BackupOutcome(System.currentTimeMillis(), null))
        return if (todo.isEmpty()) Ended.DONE else Ended.MORE
    }

    /**
     * Send `item` into `into`, going on with the upload begun for it while EiN still has it, and
     * beginning anew when it has not; answers where EiN stored it, or null when the file went away
     * while it was sent.
     */
    private suspend fun upload(
        context: Context,
        client: EinUploads,
        into: BackupTarget,
        item: MediaItem,
        pending: Pending,
        original: Boolean,
        record: SentRecord,
        progress: suspend (Long) -> Unit,
    ): String? {
        var url = pending.upload
        var state = url?.let { client.state(it) }
        while (state?.stored == null) {
            if (url == null || state == null) {
                val (begun, stored) = client.begin(into.dir, item.name, pending.length, item.modified)
                url = begun
                record.keepPending(item, pending.copy(upload = url))
                state = UploadState(0, stored)
                continue
            }
            val offset = state.offset
            progress(offset)
            state = try {
                PhoneMedia.open(context, item, original).use { input ->
                    input.skipFully(offset)
                    var reported = 0L
                    client.send(url, offset, input, minOf(PIECE, pending.length - offset)) { sent ->
                        if (sent - reported >= REPORT_BYTES) {
                            reported = sent
                            sending = Sending(item.name, offset + sent, pending.length)
                        }
                    }
                }
            } catch (_: FileNotFoundException) {
                return null
            } catch (refused: EinRefused) {
                // EiN has another offset (409): ask it which; it no longer has the upload (404): begin anew.
                when (refused.status) {
                    409 -> client.state(url)
                    404 -> null
                    else -> throw refused
                }
            }
        }
        progress(pending.length)
        return state.stored
    }

    /** Bytes sent between two reports of how far a file is. */
    private const val REPORT_BYTES = 256L * 1024

    private fun java.io.InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw IOException("the file is shorter than what was sent of it")
                left--
            } else {
                left -= skipped
            }
        }
    }

    /** What of `items` is not on EiN at the version the phone has: not sent, or changed since. */
    fun toSend(items: List<MediaItem>, sent: Map<Long, Version>): List<MediaItem> = items.filter { sent[it.id] != it.version }
}

/** The backup's WorkManager job: a run, or a change to MediaStore or the hourly job, which starts one. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        when (inputData.getString(Backup.KEY_JOB)) {
            Backup.WATCH_JOB -> {
                Backup.watchForNew(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
                Backup.runNow(applicationContext)
            }
            Backup.PERIODIC_JOB -> Backup.runNow(applicationContext)
            else -> return when (Backup.run(applicationContext, notice = { setForeground(it) }, stopped = { isStopped })) {
                Backup.Ended.AGAIN -> Result.retry()
                else -> Result.success()
            }
        }
        return Result.success()
    }
}
