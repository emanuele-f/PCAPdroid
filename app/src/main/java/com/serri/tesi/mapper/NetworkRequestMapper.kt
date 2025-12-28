package serri.tesi.mapper

import serri.tesi.dto.NetworkRequestDto
import serri.tesi.model.NetworkRequestRecord
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
            user_uuid = record.userUuid,
            app_name = record.appName,
            app_uid = record.appUid,
            protocol = record.protocol,
            domain = record.domain,
            dst_ip = record.dstIp,
            dst_port = record.dstPort,
            bytes_tx = record.bytesTx,
            bytes_rx = record.bytesRx,
            packets_tx = record.packetsTx,
            packets_rx = record.packetsRx,
            start_ts = record.startTs,
            end_ts = record.endTs,
            duration_ms = record.durationMs,
            latitude = record.latitude,
            longitude = record.longitude
        )
    }
}
