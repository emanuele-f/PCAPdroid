package serri.tesi.service

import android.content.Context
import serri.tesi.model.ConnectionRecord
import serri.tesi.model.HttpRequestRecord
import serri.tesi.repo.TrackerRepository
import java.util.UUID

object TrackerService {

    private lateinit var repository: TrackerRepository
    private lateinit var userUuid: String

    @JvmStatic
    fun init(context: Context) {
        repository = TrackerRepository(context.applicationContext)
        userUuid = UUID.randomUUID().toString()
    }

    @JvmStatic
    fun onNewConnection(
        ip: String?,
        port: Int?,
        bytes: Long,
        domain: String?,
        path: String?,
        lat: Double?,
        lon: Double?
    ): Long {
        val record = ConnectionRecord(
            userUuid = userUuid,
            ip = ip,
            port = port,
            bytes = bytes,
            timestamp = System.currentTimeMillis(),
            latitude = lat,
            longitude = lon,
            domain = domain,
            path = path
        )
        return repository.insertConnection(record)
    }

    @JvmStatic
    fun onHttpRequest(
        //connectionId: Long,
        method: String,
        host: String?,
        path: String
    ) {
        val record = HttpRequestRecord(
            //connectionId = connectionId,
            method = method,
            host = host,
            path = path,
            timestamp = System.currentTimeMillis()
        )
        repository.insertHttpRequest(record)
    }
}

