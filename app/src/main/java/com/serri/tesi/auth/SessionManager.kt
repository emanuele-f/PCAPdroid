package serri.tesi.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Classe responsabile della gestione della sessione utente.
 *
 * Salva e recupera il token JWT utilizzato per
 * autenticare le richieste verso il backend remoto.
 */
class SessionManager(context: Context) {

    // crea/apre spazio persistente (tesi_session) visibile solo a app
    // shared preferences = android api per salvare dati
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tesi_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_JWT = "jwt_token" // chiave const per salvare/leggere token
    }

    //Salva il token JWT ottenuto da login
    fun saveToken(token: String) {
        prefs.edit() //scrive token in ShardPreferences
            .putString(KEY_JWT, token)
            .apply()
    }

    //Restituisce token jwt salvato, se presente
    fun getToken(): String? {
        return prefs.getString(KEY_JWT, null)
    }


    //Cancella il token (logout o token non valido).
    fun clearToken() {
        prefs.edit()
            .remove(KEY_JWT)
            .apply()
    }

    // true se esiste un token salvato
    fun isLoggedIn(): Boolean {
        return getToken() != null
    }

    // logout = cancella token
    fun logout() {
        clearToken()
    }

}
