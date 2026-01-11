package serri.tesi.mapper

import serri.tesi.dto.NetworkRequestDto
import serri.tesi.model.NetworkRequestRecord
import serri.tesi.privacy.HashUtils

/**
 * Mapper responsabile di conversione tra modello persistenza
 * e modello comunicazione.
 *
 * trasforma un NetworkRequestRecord (utilizzato per la
 * cache locale) in un NetworkRequestDto (destinato all'invio verso
 * il backend remoto)
 *
 * Il mapper rappresenta il punto ideale per applicare politiche di
 * normalizzazione e anonimizzazione dei dati.
 */

object NetworkRequestMapper {

    /**
     * Converte un record persistente in un Data Transfer Object. Pronto per invio a backend remoto
     *
     * @param record = modello di persistenza che rappresenta connessione conclusa
     * @return DTO pronto per la serializzazione e l'invio al backend.
     */
    fun toDto(record: NetworkRequestRecord): NetworkRequestDto {
        return NetworkRequestDto(
            appName = record.appName,
            appUid = record.appUid,

            protocol = record.protocol,

            //domain = record.domain,
            //dst_ip = record.dstIp,

            // Anonimizzazione dati sensibili prima di invio
            domainHash = record.domain?.let { HashUtils.sha256(it) },
            dstIpHash = record.dstIp?.let { HashUtils.sha256(it) },
            dstPort = record.dstPort,

            bytesTx = record.bytesTx,
            bytesRx = record.bytesRx,
            packetsTx = record.packetsTx,
            packetsRx = record.packetsRx,

            startTs = record.startTs,
            endTs = record.endTs,
            durationMs = record.durationMs,

            // Geolocalizzazione approssimata, coordinate precise = dato altamente sensibile
            latitude = record.latitude?.let { round(it, 2) },
            longitude = record.longitude?.let { round(it, 2) }
        )
    }

    //metodo per approssimare dati localizzazione (precisi=sensibili)
    // in questo modo e geolocalizzazione approssimata, posizione precisa anonima
    private fun round(value: Double, decimals: Int): Double {   //prende in input valore posizione, e restituisce approssimata
        val factor = Math.pow(10.0, decimals.toDouble()) //calocla fattore per spostare virgola, per arrotondamento
        return Math.round(value * factor) / factor // arrotonda a seconda del fattore calcolato, il valore della posizione (meno preciso)
    }

}
