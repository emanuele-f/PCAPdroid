package serri.tesi.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import serri.tesi.model.ConnectionRecord
import serri.tesi.model.HttpRequestRecord
import serri.tesi.repo.TrackerRepository
import java.util.UUID
import serri.tesi.model.NetworkRequestRecord


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
     * NOTA FIX: registra connessione parziale e veniva chiamato nel punto sbagliato
     * in ConnectionsRegister, nel punto di creazione e non chiusura: dati ancora non tutti accessibili
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

    /**
     * DEBUG – stampa su Logcat le ultime richieste HTTP salvate su SQLite
     */
    @JvmStatic
    fun debugDumpHttp() {
        repository.debugDumpHttpRequests(20)
    }

    //Metodo per log connessione versione completa
    @JvmStatic
    fun logFinalConnection(
        appName: String?,
        appUid: Int,
        protocol: String,
        domain: String?,
        srcIp: String?,
        srcPort: Int?,
        dstIp: String?,
        dstPort: Int,
        bytesTx: Long,
        bytesRx: Long,
        packetsTx: Int,
        packetsRx: Int,
        startTs: Long,
        endTs: Long,
        durationMs: Long
    ) {
        val (lat, lon) = LocationService.getLastLocation()

        val record = NetworkRequestRecord(
            userUuid = userUuid,
            appName = appName,
            appUid = appUid,
            protocol = protocol,
            domain = domain,
            srcIp = srcIp,
            srcPort = srcPort,
            dstIp = dstIp,
            dstPort = dstPort,
            bytesTx = bytesTx,
            bytesRx = bytesRx,
            packetsTx = packetsTx,
            packetsRx = packetsRx,
            startTs = startTs,
            endTs = endTs,
            durationMs = durationMs,
            latitude = lat,
            longitude = lon
        )

        repository.insertNetworkRequest(record)
    }


}
