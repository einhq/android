package eu.einhq.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.IntentCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * Pictures, videos and recordings another app shared to EiN through the phone's Share menu, from
 * the moment the activity takes them until the Web UI's `/share` page has read them through the
 * bridge, which opens them in its Import media dialog as it does what Chrome shares to the web app
 * (ein/core#1509). They are read with the grant the share gave the activity, and kept in place of
 * any shared before and not taken.
 *
 * - `shared`: `{ files: [{ name, type, size, modified? }] }`, `modified` in milliseconds.
 * - `sharedRead`: `{ index, offset }` → `{ data, end }`, up to `CHUNK_BYTES` of the file at `index`
 *   from `offset`, in base64, and whether the file ends there.
 * - `sharedDone`: forgets them.
 */
class SharedFiles(private val resolver: ContentResolver) {
    private class Shared(val uri: Uri, val name: String, val type: String, val size: Long, val modified: Long?)

    /** The file being read, and how far: the next chunk goes on from it rather than reading it again. */
    private class Reading(val index: Int, val stream: InputStream, var position: Long)

    private var files: List<Shared> = emptyList()
    private var reading: Reading? = null

    val isEmpty: Boolean @Synchronized get() = files.isEmpty()

    /** Keep the files `intent` shares, in place of those before; whether it shares any. */
    @Synchronized
    fun take(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return false
        val uris = buildList {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
        }.distinct()
        forget()
        files = uris.mapNotNull { uri -> runCatching { describe(uri, intent.type) }.getOrNull() }.filter { isMedia(it.type) }
        return files.isNotEmpty()
    }

    @Synchronized
    fun call(method: String, params: JSONObject): JSONObject = when (method) {
        "shared" -> JSONObject().put(
            "files",
            JSONArray(
                files.map { file ->
                    JSONObject().put("name", file.name).put("type", file.type).put("size", file.size).apply {
                        if (file.modified != null) put("modified", file.modified)
                    }
                },
            ),
        )
        "sharedRead" -> read(params.getInt("index"), params.optLong("offset"))
        "sharedDone" -> {
            forget()
            JSONObject()
        }
        else -> error("the app does not know $method")
    }

    private fun read(index: Int, offset: Long): JSONObject {
        val file = files.getOrNull(index) ?: error("no shared file $index")
        val at = reading?.takeIf { it.index == index && it.position == offset } ?: open(index, file, offset)
        val chunk = at.stream.readNBytesCompat(CHUNK_BYTES)
        at.position += chunk.size
        val end = chunk.size < CHUNK_BYTES
        if (end) close()
        return JSONObject().put("data", Base64.encodeToString(chunk, Base64.NO_WRAP)).put("end", end)
    }

    private fun open(index: Int, file: Shared, offset: Long): Reading {
        close()
        val stream = resolver.openInputStream(file.uri) ?: error("${file.name} cannot be read")
        var skipped = 0L
        while (skipped < offset) {
            val step = stream.skip(offset - skipped)
            if (step <= 0) break
            skipped += step
        }
        if (skipped < offset) error("${file.name} ends before $offset")
        return Reading(index, stream, skipped).also { reading = it }
    }

    private fun close() {
        reading?.stream?.close()
        reading = null
    }

    private fun forget() {
        close()
        files = emptyList()
    }

    /** A shared file's name, type, size and time, as the app that shared it tells them. */
    private fun describe(uri: Uri, sharedType: String?): Shared {
        var name: String? = null
        var size = -1L
        var modified: Long? = null
        resolver.query(uri, null, null, null, null)?.use { row ->
            if (row.moveToFirst()) {
                fun column(name: String) = row.getColumnIndex(name).takeIf { it >= 0 && !row.isNull(it) }
                column(OpenableColumns.DISPLAY_NAME)?.let { name = row.getString(it) }
                column(OpenableColumns.SIZE)?.let { size = row.getLong(it) }
                // A document provider's time is in milliseconds, MediaStore's in seconds.
                modified = column(LAST_MODIFIED)?.let { row.getLong(it) }
                    ?: column(MediaStore.MediaColumns.DATE_MODIFIED)?.let { row.getLong(it) * 1000 }
            }
        }
        val type = resolver.getType(uri) ?: sharedType?.takeUnless { it.endsWith("/*") } ?: "application/octet-stream"
        return Shared(uri, name ?: uri.lastPathSegment ?: "shared", type, size, modified?.takeIf { it > 0 })
    }

    companion object {
        val METHODS = setOf("shared", "sharedRead", "sharedDone")

        /** What one `sharedRead` answers at most, 3 MB, 4 MB in base64. */
        const val CHUNK_BYTES = 3 * 1024 * 1024

        /** `DocumentsContract.Document.COLUMN_LAST_MODIFIED`. */
        private const val LAST_MODIFIED = "last_modified"

        /** What the Share menu offers EiN, as the Web UI's web manifest does: pictures, videos and recordings. */
        fun isMedia(type: String): Boolean = type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")
    }
}

/** Up to `count` bytes, fewer only at the end of the stream. */
private fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return if (filled == count) buffer else buffer.copyOf(filled)
}
