package serri.tesi.privacy

import java.security.MessageDigest
// Classe per l'utilizzo di funzioni hash crittografiche

/**
 * Utility per l'anonimizzazione dei dati sensibili.
 *
 * Fornisce una funzione di hashing basata su SHA-256,
 * utilizzata per anonimizzare informazioni potenzialmente riconducibili
 * all'utente (es. identificativo utente, dominio, indirizzi IP) prima
 * della trasmissione al backend remoto.
 *
 * L'uso di un salt statico consente di ridurre il rischio di attacchi
 * basati su rainbow table, mantenendo comunque la possibilità di
 * confrontare valori hashati in modo consistente.
 */
object HashUtils {
    //singleton: non mantiene stato e fornisce solo metodi di utilità

    //Salt statico utilizzato nella funzione di hashing.
    //inn sistema reale dovrebbe essere gestito in modo più sicuro

    //Ora i dati sono completi lcoalmente e anonimizzati in rete
    //SHA-256+salt statico, hashing unidirezionale e applicato prima di invio al backend (db locl non anonim)

    //(input + SALT)
    //salt= stringa aggiuntiva che viene combinata al dato prima di applicare la funzione hash --> rafforza anonimizzazione
    private const val SALT = "TESI_SALT_2025"

    /**
     * Calcola hash SHA-256 di una stringa in input combinata con un salt.
     *
     * @param input stringa da anonimizzare
     * @return rappresentazione esadecimale dell'hash SHA-256
     */
    fun sha256(input: String): String {

        // Ottiene un'istanza dell'algoritmo di hashing SHA-256
        val md = MessageDigest.getInstance("SHA-256")

        // Combina input con il salt e calcola il digest (array di byte)
        val bytes = md.digest(
            (input + SALT).toByteArray(Charsets.UTF_8)
        )

        // Converte l'array di byte in una stringa esadecimale leggibile
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
