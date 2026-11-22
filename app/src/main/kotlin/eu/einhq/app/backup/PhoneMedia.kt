package eu.einhq.app.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream
import java.security.MessageDigest

/** A photo or video on the phone: its MediaStore id, name, folder, and version. */
data class MediaItem(val id: Long, val video: Boolean, val name: String, val folder: String, val size: Long, val modified: Long) {
    val version: Version get() = Version(size, modified)

    /** Its address in MediaStore. */
    val uri: Uri get() = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

/** One of the phone's folders of photos and videos: where it is (`DCIM/Camera/`), its name, and how many it holds. */
data class MediaFolder(val path: String, val name: String, val count: Int)

/** A file as read: its sha256 in hex, and how many bytes it had. */
data class Digest(val sha256: String, val length: Long)

/**
 * The phone's photos and videos, as MediaStore gives them to the app: all of them, or only those
 * picked when the phone's owner allowed only some. Files still being written are left out.
 */
object PhoneMedia {
    private val FILES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )

    private const val MEDIA =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" +
            " AND ${MediaStore.MediaColumns.IS_PENDING} = 0"

    /** The folders that hold photos or videos, by name. */
    fun folders(context: Context): List<MediaFolder> {
        val found = linkedMapOf<String, Pair<String, Int>>()
        query(context, MEDIA, emptyArray()) { path, name, _ ->
            val (_, count) = found[path] ?: (name to 0)
            found[path] = name to count + 1
        }
        return found.map { (path, value) -> MediaFolder(path, value.first, value.second) }.sortedBy { it.name.lowercase() }
    }

    /** The photos and videos in `folders`, newest first. */
    fun items(context: Context, folders: Set<String>): List<MediaItem> {
        if (folders.isEmpty()) return emptyList()
        val items = mutableListOf<MediaItem>()
        val selection = "$MEDIA AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (${folders.joinToString { "?" }})"
        query(context, selection, folders.toTypedArray()) { _, _, item -> items += item }
        return items
    }

    /**
     * Read `item` through, for its sha256 and length; `original` asks for its bytes as written,
     * where it was taken too, which needs `ACCESS_MEDIA_LOCATION`.
     */
    fun digest(context: Context, item: MediaItem, original: Boolean): Digest {
        val sha256 = MessageDigest.getInstance("SHA-256")
        var length = 0L
        open(context, item, original).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha256.update(buffer, 0, n)
                length += n
            }
        }
        return Digest(sha256.digest().joinToString("") { "%02x".format(it) }, length)
    }

    /** `item`'s bytes; as written, where it was taken too, when `original`. */
    fun open(context: Context, item: MediaItem, original: Boolean): InputStream {
        val uri = if (original) MediaStore.setRequireOriginal(item.uri) else item.uri
        return context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("${item.name} is gone")
    }

    private fun query(context: Context, selection: String, args: Array<String>, each: (path: String, name: String, item: MediaItem) -> Unit) {
        context.contentResolver.query(FILES, PROJECTION, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getLong(0)
                val path = rows.getString(2) ?: continue
                val video = rows.getInt(6) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val item = MediaItem(id, video, rows.getString(1) ?: "$id", path, rows.getLong(4), rows.getLong(5))
                each(path, rows.getString(3) ?: path.trimEnd('/').substringAfterLast('/'), item)
            }
        }
    }
}
