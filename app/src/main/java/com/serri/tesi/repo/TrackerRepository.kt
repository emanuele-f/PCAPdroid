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
}
