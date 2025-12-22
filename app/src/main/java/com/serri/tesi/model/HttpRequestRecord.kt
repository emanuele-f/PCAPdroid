package serri.tesi.model

data class HttpRequestRecord(
    val method: String,
    val host: String?,
    val path: String,
    val timestamp: Long
)
