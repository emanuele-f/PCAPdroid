package serri.tesi.repo

import android.content.ContentValues
import android.content.Context
import serri.tesi.db.TesiDbHelper
import serri.tesi.model.ConnectionRecord
import serri.tesi.model.HttpRequestRecord
import serri.tesi.model.NetworkRequestRecord

class TrackerRepository(context: Context) {

    private val dbHelper = TesiDbHelper(context)

    // vecchio insert
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

    // Nuovo metodo
    fun insertNetworkRequest(record: NetworkRequestRecord): Long {
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            put("user_uuid", record.userUuid)

            put("app_name", record.appName)
            put("app_uid", record.appUid)

            put("protocol", record.protocol)
            put("domain", record.domain)

            put("src_ip", record.srcIp)
            put("src_port", record.srcPort)
            put("dst_ip", record.dstIp)
            put("dst_port", record.dstPort)

            put("bytes_tx", record.bytesTx)
            put("bytes_rx", record.bytesRx)
            put("packets_tx", record.packetsTx)
            put("packets_rx", record.packetsRx)

            put("start_ts", record.startTs)
            put("end_ts", record.endTs)
            put("duration_ms", record.durationMs)

            put("latitude", record.latitude)
            put("longitude", record.longitude)
        }

        return db.insert("network_requests", null, values)
    }

    //DEBUG
    //funzione per verificare db connections
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

    //funzione per verificare db http request
    fun debugDumpHttpRequests(limit: Int = 10) {
        val db = dbHelper.readableDatabase

        val cursor = db.rawQuery(
            """
    SELECT id, method, path, host, timestamp
    FROM http_requests
    ORDER BY timestamp DESC
    LIMIT ?
    """,
            arrayOf(limit.toString())
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val method = cursor.getString(1)
            val path = cursor.getString(2)
            val host = cursor.getString(3)
            val ts = cursor.getLong(4)

            android.util.Log.d(
                "TESI_DB_HTTP",
                "ID=$id method=$method host=$host path=$path ts=$ts"
            )
        }

        cursor.close()
    }
}
