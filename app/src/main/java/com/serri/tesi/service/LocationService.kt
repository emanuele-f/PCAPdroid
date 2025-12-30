package serri.tesi.service

import android.annotation.SuppressLint // annotazione x sopprimere warning del compilatore (permessi runtime)
import android.content.Context // content necessario per inizializzare client di localizzazione
import android.os.Looper //looper x associare callback al thread principale
import com.google.android.gms.location.* //api google play services x geolocalizzazione

/**
 * Servizio di supporto per geolocalizzazione
 *
 * Fornisce ultima posizione GPS nota del dispositivo
 *
 * Il servizio è progettato per operare senza introdurre blocchi o dipendenze temporali nel tracciamento delle connessioni di rete
 */
object LocationService { //singleton globale

    private lateinit var fusedClient: FusedLocationProviderClient //client fornito da google play services x ottenere posizione
    private var lastLat: Double? = null // ultima lat nota (null se non disp)
    private var lastLon: Double? = null //ultima lon nota (null se non disp)

    //callback invocato automaticamente ad ogni aggiornamentoo gps
    private val callback = object : LocationCallback() {
        //chiama metodo quando arriva una nuova posizione
        override fun onLocationResult(result: LocationResult) {
            // loc ottiene ultima pos disponibile da result
            val loc = result.lastLocation ?: return //se posizione è null, allora esce
            // aggiorno i valori memorizzati
            lastLat = loc.latitude
            lastLon = loc.longitude

            //log di debug x ricezione gps
            android.util.Log.d(
                "LocationService",
                "GPS FIX lat=$lastLat lon=$lastLon"
            )
        }
    }

    /**
     * Inizializza servizio localizzazione.
     *
     * Viene creato client x accesso alla posizione utilizzando
     * API di Google Play Services.
     */
    @JvmStatic
    fun init(context: Context) {
        // ottiene client di localizzazione associato a contesto
        fusedClient = LocationServices.getFusedLocationProviderClient(context)
    }

    /**
     * Avvia richiesta di aggiornamenti posizione
     *
     * La posizione viene aggiornata periodicamente e memorizzata
     * come ultimo valore noto, senza persistenza o storicizzazione.
     */
    @JvmStatic
    @SuppressLint("MissingPermission")
    fun start() {
        //costruzione richiesta di localizzazione:
        // - alta accuratezza gps - aggiorna ogni 5 sec
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5_000
        ).build()

        //registra callback x ricevere aggiornamenti di pos
        fusedClient.requestLocationUpdates(
            request, //configurazione richiesta
            callback, //callback chiamato ad ogni update
            Looper.getMainLooper() // thread su cui eseguire callback
        )
    }

    /**
     * Restituisce ultima posizione GPS disponibile.
     *
     * @return coppia (latitudine, longitudine), oppure valori null
     *         se la posizione non è ancora disponibile.
     */
    @JvmStatic
    fun getLastLocation(): Pair<Double?, Double?> {
        return Pair(lastLat, lastLon) //restituisce valori in coppia
    }
}
