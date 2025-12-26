package serri.tesi.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TesiDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE network_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,

                user_uuid TEXT NOT NULL,

                app_name TEXT,
                app_uid INTEGER,

                protocol TEXT,
                domain TEXT,

                src_ip TEXT,
                src_port INTEGER,
                dst_ip TEXT,
                dst_port INTEGER,

                bytes_tx INTEGER,
                bytes_rx INTEGER,
                packets_tx INTEGER,
                packets_rx INTEGER,

                start_ts INTEGER,
                end_ts INTEGER,
                duration_ms INTEGER,

                latitude REAL,
                longitude REAL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS network_requests")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "tesi.db"
        const val DB_VERSION = 2
    }
}
