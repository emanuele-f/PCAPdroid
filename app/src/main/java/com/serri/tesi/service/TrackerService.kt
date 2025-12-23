package serri.tesi.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import serri.tesi.model.ConnectionRecord
import serri.tesi.model.HttpRequestRecord
import serri.tesi.repo.TrackerRepository
import java.util.UUID

object TrackerService {

    private lateinit var repository: TrackerRepository
    private lateinit var userUuid: String

    /**
     * Inizializza il repository e il servizio GPS.
     */
    @JvmStatic
    fun init(context: Context) {
        repository = TrackerRepository(context.applicationContext)
        userUuid = UUID.randomUUID().toString()
        LocationService.init(context) // inizializza GPS
    }

    /**
     * Registra una nuova connessione con GPS opzionale.
     */
    @JvmStatic
    fun logConnection(
        ip: String?,
        port: Int?,
        bytes: Long,
        domain: String?,
        path: String?
    ) {
        val (lat, lon) = LocationService.getLastLocation()

        onNewConnection(
            ip = ip,
            port = port,
            bytes = bytes,
            domain = domain,
            path = path,
            lat = lat,
            lon = lon
        )
    }


    /**
     * Inserisce una nuova connessione nel database SQLite.
     */
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

    /**
     * Inserisce una nuova richiesta HTTP nel database SQLite.
     */
    @JvmStatic
    fun onHttpRequest(
        method: String,
        host: String?,
        path: String
    ) {
        val record = HttpRequestRecord(
            method = method,
            host = host,
            path = path,
            timestamp = System.currentTimeMillis()
        )
        repository.insertHttpRequest(record)
    }

    /**
     * DEBUG – stampa su Logcat le ultime connessioni salvate su SQLite
     */
    @JvmStatic
    fun debugDump() {
        repository.debugDumpConnections(20)
    }
}
