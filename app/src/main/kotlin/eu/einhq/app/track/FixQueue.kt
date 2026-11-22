package eu.einhq.app.track

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/**
 * The positions taken and not yet sent, oldest first, each as its JSON (`Fix.json`). It keeps at
 * most `MAX` of them: a phone offline for weeks drops its oldest rather than fill up.
 */
class FixQueue(context: Context) : SQLiteOpenHelper(context, "positions.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fixes (id INTEGER PRIMARY KEY AUTOINCREMENT, fix TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(fixes: List<Fix>) {
        if (fixes.isEmpty()) return
        writableDatabase.transaction {
            for (fix in fixes) insert("fixes", null, ContentValues().apply { put("fix", fix.json()) })
            execSQL("DELETE FROM fixes WHERE id <= (SELECT max(id) FROM fixes) - $MAX")
        }
    }

    /** The oldest `limit` positions, with their ids. */
    fun oldest(limit: Int): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT id, fix FROM fixes ORDER BY id LIMIT $limit", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getLong(0) to rows.getString(1)) }
        }

    /** Forget the positions up to and including `id`, once EiN has them. */
    fun removeUpTo(id: Long) {
        writableDatabase.delete("fixes", "id <= ?", arrayOf(id.toString()))
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT count(*) FROM fixes", null).use { rows -> if (rows.moveToFirst()) rows.getInt(0) else 0 }

    fun clear() {
        writableDatabase.delete("fixes", null, null)
    }

    private companion object {
        /** About a month of moving every day, at a few hundred bytes each. */
        const val MAX = 100_000
    }
}
