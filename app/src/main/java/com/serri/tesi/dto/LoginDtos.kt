package serri.tesi.dto

//dto login x serializzare e deserializzare json
data class LoginRequestDto(
    val email: String,
    val password: String
)

data class LoginResponseDto(
    val access_token: String
)
