package serri.tesi.config

import android.os.Build

object BackendConfig {

    fun getBaseUrl(): String {
        return if (isEmulator()) {
            "http://10.0.2.2:3000"
        } else {
            // IP del PC di sviluppo
            "http://192.168.1.218:3000"
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
    }
}
