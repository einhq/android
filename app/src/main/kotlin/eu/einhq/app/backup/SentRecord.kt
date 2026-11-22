package eu.einhq.app.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/** A version of a photo or video on the phone: its size and when it was last written, in seconds. */
data class Version(val size: Long, val modified: Long)

/** A file on its way: its checksum and length as read, and the upload it is being sent by, once begun. */
data class Pending(val sha256: String, val length: Long, val upload: String?)

/**
 * What the backup has sent, by MediaStore id: each photo or video EiN has, sent by the app or found
 * there already, with the version it had and its sha256, so neither is read again until it changes.
 * Beside it, the files on their way, so a backup that stopped goes on with the upload it began.
 */
class SentRecord(context: Context) : SQLiteOpenHelper(context, "backup.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sent (media_id INTEGER PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, " +
                "sha256 TEXT NOT NULL, stored TEXT, at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE pending (media_id INTEGER PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, " +
                "sha256 TEXT NOT NULL, length INTEGER NOT NULL, upload TEXT)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The version each photo or video was sent at, by its MediaStore id. */
    fun sent(): Map<Long, Version> = readableDatabase.rawQuery("SELECT media_id, size, modified FROM sent", null).use { rows ->
        buildMap { while (rows.moveToNext()) put(rows.getLong(0), Version(rows.getLong(1), rows.getLong(2))) }
    }

    fun sentCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM sent", null).use { rows -> if (rows.moveToFirst()) rows.getInt(0) else 0 }

    /** The file on its way for `item`, while it is the version it was read at. */
    fun pending(item: MediaItem): Pending? = readableDatabase.rawQuery(
        "SELECT sha256, length, upload FROM pending WHERE media_id = ? AND size = ? AND modified = ?",
        arrayOf(item.id.toString(), item.size.toString(), item.modified.toString()),
    ).use { rows -> if (rows.moveToFirst()) Pending(rows.getString(0), rows.getLong(1), rows.getString(2)) else null }

    /** Keep what `item` was read as, and the upload it is sent by once there is one. */
    fun keepPending(item: MediaItem, pending: Pending) {
        writableDatabase.insertWithOnConflict(
            "pending",
            null,
            ContentValues().apply {
                put("media_id", item.id)
                put("size", item.size)
                put("modified", item.modified)
                put("sha256", pending.sha256)
                put("length", pending.length)
                put("upload", pending.upload)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** `item` is on EiN: sent as `stored`, a path there, or found there already (null). */
    fun markSent(item: MediaItem, sha256: String, stored: String?) {
        writableDatabase.transaction {
            insertWithOnConflict(
                "sent",
                null,
                ContentValues().apply {
                    put("media_id", item.id)
                    put("size", item.size)
                    put("modified", item.modified)
                    put("sha256", sha256)
                    put("stored", stored)
                    put("at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            delete("pending", "media_id = ?", arrayOf(item.id.toString()))
        }
    }

    fun clear() {
        writableDatabase.delete("sent", null, null)
        writableDatabase.delete("pending", null, null)
    }
}
