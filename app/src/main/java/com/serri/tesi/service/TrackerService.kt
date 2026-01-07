package serri.tesi.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import serri.tesi.model.ConnectionRecord
import serri.tesi.model.HttpRequestRecord
import serri.tesi.repo.TrackerRepository
import java.util.UUID // classe java x generazione id univoci
import serri.tesi.model.NetworkRequestRecord

/**
 * Servizio centrale di tracciamento delle connessioni di rete.
 *
 * punto di integrazione tra il sistema di intercettazione del traffico (PCAPdroid) e  livello di persistenza
 * introdotto dalla tesi.
 *
 * Si occupa di raccogliere i dati finali delle connessioni,
 * arricchirli con informazioni di contesto (GPS, applicazione di origine...)
 * e delegare la persistenza al repository.
 */
object TrackerService {
    //object kotlin = singleton globale, una sola istanza in tutta l'app
    private lateinit var repository: TrackerRepository //repo x salvare e leggere dati da db locale
    private lateinit var userUuid: String //uuid anonim. associato a user corrente

    // cache temporanea aggiunta per url di http request
    private var lastHttpRequest: HttpRequestRecord? = null


    /**
     * - inizializza repository x accesso a db locale
     * - genera UUID anonimo per l'utente
     * - inizializza il servizio di localizzazione.
     */
    @JvmStatic
    fun init(context: Context) {
        repository = TrackerRepository(context.applicationContext) // crea repo usando context dell'app
        userUuid = UUID.randomUUID().toString() // genera uuid casuale
        LocationService.init(context) // inizializza GPS
    }

    //*metodo vecchio*
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

    //*metodo vecchio*
    //Inserisce una nuova connessione nel database SQLite.
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

    //metodo vecchio* --> riutilizzato per aggiungere url
    //Inserisce una nuova richiesta HTTP nel database SQLite.
    @JvmStatic
    fun onHttpRequest(
        method: String,
        host: String?,
        path: String
    ) {
        lastHttpRequest = HttpRequestRecord(
            method = method,
            host = host,
            path = path,
            timestamp = System.currentTimeMillis()
        )
    }


    // DEBUG, stampa su Logcat le ultime connessioni salvate su SQLite
    @JvmStatic
    fun debugDump() {
        repository.debugDumpConnections(20)
    }

    //DEBUG, stampa su Logcat le ultime richieste HTTP salvate su SQLite
    @JvmStatic
    fun debugDumpHttp() {
        repository.debugDumpHttpRequests(20)
    }

    //Metodo FINALE per log connessione versione completa
    /**
     * Registra una connessione di rete conclusa
     *
     * Viene invocato esclusivamente alla chiusura della
     * connessione, quando tutte le metriche  risultano disponibili.
     *
     * I dati vengono incapsulati in NetworkRequestRecord e
     * salvati nel db locale come parte della cache persistente.
     */
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
        val (lat, lon) = LocationService.getLastLocation() //recupera ultima posizione disponibile

        // per url
        val isHttp = protocol.equals("HTTP", ignoreCase = true)

        val httpMethod = if (isHttp) lastHttpRequest?.method else null
        val httpPath   = if (isHttp) lastHttpRequest?.path else null
        val httpHost   = if (isHttp) lastHttpRequest?.host else null

        //costruzione record completo connessione
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

            // url per http request
            httpMethod = httpMethod,
            httpPath = httpPath,
            httpHost = httpHost,

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

        //HTTP → campi valorizzati
        //HTTPS → sempre null
        lastHttpRequest = null

        //salvataggio record in db tramite repository
        repository.insertNetworkRequest(record)
    }
}
