package app.qrow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Scan(val id: Long, val ts: Long, val content: String, val format: String)

class Db(context: Context) : SQLiteOpenHelper(context, "qr.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scans (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, content TEXT NOT NULL, format TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(content: String, format: String): Scan {
        val ts = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("ts", ts)
            put("content", content)
            put("format", format)
        }
        val id = writableDatabase.insert("scans", null, values)
        return Scan(id, ts, content, format)
    }

    fun all(): List<Scan> {
        val result = mutableListOf<Scan>()
        readableDatabase.rawQuery(
            "SELECT id, ts, content, format FROM scans ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                result += Scan(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))
            }
        }
        return result
    }

    fun delete(id: Long) {
        writableDatabase.delete("scans", "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("scans", null, null)
    }
}
