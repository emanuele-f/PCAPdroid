package serri.tesi.dto

/**
 * DTO utilizzato per l'invio batch delle connessioni di rete al backend.
 *
 * Contenitore x una collezione di NetworkRequestDto,
 * consentendo l'invio di più record in un'unica richiesta HTTP.
 *
 * L'uso di un wrapper dedicato migliora la chiarezza del contratto API e ne facilita l'estendibilità futura.
 */
data class BatchDto(
    val requests: List<NetworkRequestDto> //elenco connessioni sincronizzate
)
