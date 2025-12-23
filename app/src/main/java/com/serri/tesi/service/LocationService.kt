package serri.tesi.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*

object LocationService {

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
    @JvmStatic
    fun init(context: Context) {
        fusedClient = LocationServices.getFusedLocationProviderClient(context)
    }

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
    @JvmStatic
    fun getLastLocation(): Pair<Double?, Double?> {
        return Pair(lastLat, lastLon)
    }
}
