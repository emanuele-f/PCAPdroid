package serri.tesi.repo

import android.content.ContentValues
import android.content.Context
import serri.tesi.db.TesiDbHelper
import serri.tesi.model.ConnectionRecord
import serri.tesi.model.HttpRequestRecord

class TrackerRepository(context: Context) {

    private val dbHelper = TesiDbHelper(context)

    fun insertConnection(record: ConnectionRecord): Long {
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            put("user_uuid", record.userUuid)
            put("ip", record.ip)
            put("port", record.port)
            put("bytes", record.bytes)
            put("timestamp", record.timestamp)
            put("latitude", record.latitude)
            put("longitude", record.longitude)
            put("domain", record.domain)
            put("path", record.path)
        }

        return db.insert("connections", null, values)
    }

    fun insertHttpRequest(record: HttpRequestRecord): Long {
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            //put("connection_id", record.connectionId)
            put("method", record.method)
            put("host", record.host)
            put("path", record.path)
            put("timestamp", record.timestamp)
        }

        return db.insert("http_requests", null, values)
    }

    //funzione per verificare db
    fun debugDumpConnections(limit: Int = 10) {
        val db = dbHelper.readableDatabase

        val cursor = db.rawQuery(
            """
        SELECT id, ip, port, bytes, latitude, longitude, domain, path, timestamp
        FROM connections
        ORDER BY timestamp DESC
        LIMIT ?
        """,
            arrayOf(limit.toString())
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val ip = cursor.getString(1)
            val port = cursor.getInt(2)
            val bytes = cursor.getLong(3)
            val lat = if (cursor.isNull(4)) null else cursor.getDouble(4)
            val lon = if (cursor.isNull(5)) null else cursor.getDouble(5)
            val domain = cursor.getString(6)
            val path = cursor.getString(7)
            val ts = cursor.getLong(8)

            android.util.Log.d(
                "TESI_DB",
                "ID=$id ip=$ip:$port bytes=$bytes lat=$lat lon=$lon domain=$domain path=$path ts=$ts"
            )
        }

        cursor.close()
    }

}
