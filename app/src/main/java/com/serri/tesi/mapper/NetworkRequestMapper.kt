package serri.tesi.mapper

import serri.tesi.dto.NetworkRequestDto
import serri.tesi.model.NetworkRequestRecord

object NetworkRequestMapper {

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
