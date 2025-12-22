package serri.tesi.model

data class ConnectionRecord(
    val userUuid: String,
    val ip: String?,
    val port: Int?,
    val bytes: Long,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val domain: String?,
    val path: String?
)
