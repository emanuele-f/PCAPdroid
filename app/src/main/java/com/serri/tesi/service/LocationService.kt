package serri.tesi.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*

/**
 * Servizio di supporto per geolocalizzazione
 *
 * Fornisce ultima posizione GPS nota del dispositivo
 *
 * Il servizio è progettato per operare
 * senza introdurre blocchi o dipendenze temporali nel tracciamento
 * delle connessioni di rete.
 */
object LocationService { //singleton

    private lateinit var fusedClient: FusedLocationProviderClient
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLat = loc.latitude
            lastLon = loc.longitude

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
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5_000
        ).build()

        fusedClient.requestLocationUpdates(
            request,
            callback,
            Looper.getMainLooper()
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
        return Pair(lastLat, lastLon)
    }
}
