package eu.einhq.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A page's file input: the phone's files, with its camera offered beside them when the input takes
 * photos or videos, or the camera alone when the input asks to capture. Made while the activity is
 * made, as an activity result has to be registered then.
 */
class FileChooser(private val activity: ComponentActivity) {
    /** A camera offered, and the file it was given to write to. */
    private class Capture(val action: String, val file: File, val uri: Uri)

    private var waiting: ValueCallback<Array<Uri>>? = null
    /** The cameras offered; the one whose file has a photo or video in it was used. */
    private var captures: List<Capture> = emptyList()
    private val chooser = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::chosen)
    private val folder get() = File(activity.cacheDir, "captures")

    /** Open the chooser for `params`; false, with nothing chosen, when the phone has nothing to choose with. */
    fun open(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        waiting?.onReceiveValue(null)
        waiting = callback
        forgetOldCaptures()
        val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val any = types.isEmpty() || "*/*" in types
        captures = listOfNotNull(
            if (any || types.any { it.startsWith("image/") }) newCapture(MediaStore.ACTION_IMAGE_CAPTURE, "IMG", "jpg") else null,
            if (any || types.any { it.startsWith("video/") }) newCapture(MediaStore.ACTION_VIDEO_CAPTURE, "VID", "mp4") else null,
        )
        val cameras = captures.map(::camera)
        val intent = if (params.isCaptureEnabled && cameras.isNotEmpty()) {
            cameras.first()
        } else {
            val files = params.createIntent()
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) files.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            Intent.createChooser(files, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, cameras.toTypedArray())
        }
        return try {
            chooser.launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            waiting = null
            false
        }
    }

    private fun chosen(result: ActivityResult) {
        val callback = waiting ?: return
        waiting = null
        val data = result.data
        val picked = when {
            result.resultCode != Activity.RESULT_OK -> null
            data?.clipData != null -> data.clipData!!.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
            data?.data != null -> arrayOf(data.data!!)
            else -> captures.filter { it.file.length() > 0 }.map { it.uri }.toTypedArray().ifEmpty { null }
        }
        captures.filter { it.file.length() == 0L }.forEach { it.file.delete() }
        captures = emptyList()
        callback.onReceiveValue(picked)
    }

    /** A file for a camera to write to, named as a phone's camera names its own (`IMG_20260704_181500.jpg`), which the upload keeps. */
    private fun newCapture(action: String, prefix: String, extension: String): Capture {
        folder.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = generateSequence(0) { it + 1 }
            .map { File(folder, if (it == 0) "${prefix}_$stamp.$extension" else "${prefix}_${stamp}_$it.$extension") }
            .first { it.createNewFile() }
        return Capture(action, file, FileProvider.getUriForFile(activity, "${activity.packageName}.captures", file))
    }

    /** The camera's intent, its file writable by each camera app on the phone: a chooser does not pass a grant on. */
    private fun camera(capture: Capture): Intent {
        val grant = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        val intent = Intent(capture.action).putExtra(MediaStore.EXTRA_OUTPUT, capture.uri).addFlags(grant)
        activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach {
            activity.grantUriPermission(it.activityInfo.packageName, capture.uri, grant)
        }
        return intent
    }

    /** A capture is uploaded from its file after the chooser closes, so it is kept a day. */
    private fun forgetOldCaptures() {
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        folder.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
    }
}
