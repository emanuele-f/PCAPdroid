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
 * filtraggio, normalizzazione e anonimizzazione dei dati.
 */

object NetworkRequestMapper {

    /**
     * Converte un record persistente in un Data Transfer Object.
     *
     * @param record = modello di persistenza che rappresenta connessione conclusa
     * @return DTO pronto per la serializzazione e l'invio al backend.
     */
    fun toDto(record: NetworkRequestRecord): NetworkRequestDto {
        return NetworkRequestDto(
            //user_uuid = record.userUuid,
            user_uuid = HashUtils.sha256(record.userUuid), //user anonimizzato
            app_name = record.appName,
            app_uid = record.appUid,
            protocol = record.protocol,
            //domain = record.domain,
            domain = record.domain?.let { HashUtils.sha256(it) }, //dominio anonim.
            //dst_ip = record.dstIp,
            dst_ip = record.dstIp?.let { HashUtils.sha256(it) },//destination ip anon. se presente
            dst_port = record.dstPort,
            bytes_tx = record.bytesTx,
            bytes_rx = record.bytesRx,
            packets_tx = record.packetsTx,
            packets_rx = record.packetsRx,
            start_ts = record.startTs,
            end_ts = record.endTs,
            duration_ms = record.durationMs,
            //latitude = record.latitude,
            //longitude = record.longitude
            latitude = record.latitude?.let { round(it, 2) }, //approssimo dati localizzazione, coordinate precise = dato altamente sensibile
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
