package serri.tesi.model

data class NetworkRequestRecord(
    val userUuid: String,

    // App
    val appName: String?,
    val appUid: Int,

    // Network
    val protocol: String,        // TCP / HTTPS / HTTP
    val domain: String?,         // SNI o Host
    val srcIp: String?,
    val srcPort: Int?,
    val dstIp: String?,
    val dstPort: Int?,

    // Stats
    val bytesTx: Long,
    val bytesRx: Long,
    val packetsTx: Int,
    val packetsRx: Int,

    // Time
    val startTs: Long,
    val endTs: Long,
    val durationMs: Long,

    // GPS
    val latitude: Double?,
    val longitude: Double?
)
