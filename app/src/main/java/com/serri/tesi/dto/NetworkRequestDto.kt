package serri.tesi.dto

data class NetworkRequestDto(
    val user_uuid: String,

    val app_name: String?,
    val app_uid: Int,

    val protocol: String,
    val domain: String?,

    val dst_ip: String?,
    val dst_port: Int?,

    val bytes_tx: Long,
    val bytes_rx: Long,
    val packets_tx: Int,
    val packets_rx: Int,

    val start_ts: Long,
    val end_ts: Long,
    val duration_ms: Long,

    val latitude: Double?,
    val longitude: Double?
)
