package serri.tesi.network

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import serri.tesi.dto.LoginRequestDto
import serri.tesi.dto.LoginResponseDto

class AuthClient(private val baseUrl: String) {

    private val client = OkHttpClient()
    private val gson = Gson()

    fun login(email: String, password: String): String? {
        return try {
            val json = gson.toJson(LoginRequestDto(email, password))
            val body = json.toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )

            val request = Request.Builder()
                .url("$baseUrl/auth/login")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("TESI_LOGIN", "HTTP ${response.code}")
                if (!response.isSuccessful) return null

                val resp = response.body?.string() ?: return null
                gson.fromJson(resp, LoginResponseDto::class.java).access_token
            }
        } catch (e: Exception) {
            null
        }
    }
}
