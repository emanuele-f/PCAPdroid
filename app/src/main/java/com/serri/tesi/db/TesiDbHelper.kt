package serri.tesi.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TesiDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL("""
            CREATE TABLE connections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_uuid TEXT NOT NULL,
                ip TEXT,
                port INTEGER,
                bytes INTEGER,
                timestamp INTEGER,
                latitude REAL,
                longitude REAL,
                domain TEXT,
                path TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE http_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                connection_id INTEGER,
                method TEXT,
                path TEXT,
                host TEXT,
                timestamp INTEGER
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS connections")
        db.execSQL("DROP TABLE IF EXISTS http_requests")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "tesi.db"
        const val DB_VERSION = 1
    }
}