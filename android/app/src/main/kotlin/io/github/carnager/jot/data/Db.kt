package io.github.carnager.jot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.carnager.jot.model.Item

/**
 * Local store: the item JSON plus a few indexed columns, a dirty flag and the local
 * edit sequence that tells sync whether an item changed while a request was in flight.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "jot.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items (
                id TEXT PRIMARY KEY,
                json TEXT NOT NULL,
                due TEXT NOT NULL DEFAULT '',
                done INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                trashed_at INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                rev INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 0,
                seq INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX items_dirty ON items(dirty)")
        db.execSQL("CREATE INDEX items_due ON items(due, done)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    data class Row(val item: Item, val dirtySeq: Long)

    fun loadAll(): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery("SELECT json, dirty, seq FROM items", null).use { c ->
            while (c.moveToNext()) {
                val item = runCatching { Item.parse(c.getString(0)) }.getOrNull() ?: continue
                out += Row(item, if (c.getInt(1) != 0) c.getLong(2) else 0)
            }
        }
        return out
    }

    /** Writes items; [dirtySeq] 0 = clean, otherwise the local edit sequence. */
    fun write(rows: List<Pair<Item, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((item, seq) in rows) {
                val v = ContentValues().apply {
                    put("id", item.id)
                    put("json", item.toJson().toString())
                    put("due", item.due)
                    put("done", if (item.done) 1 else 0)
                    put("archived", if (item.archived) 1 else 0)
                    put("trashed_at", item.trashedAt)
                    put("deleted", if (item.deleted) 1 else 0)
                    put("updated_at", item.updatedAt)
                    put("rev", item.rev)
                    put("dirty", if (seq > 0) 1 else 0)
                    put("seq", seq)
                }
                db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.delete("items", null, null)
    }
}
