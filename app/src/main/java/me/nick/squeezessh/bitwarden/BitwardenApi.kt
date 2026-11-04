package me.nick.squeezessh.bitwarden

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BitwardenApiException(message: String, val status: Int? = null) : IOException(message)

/** Where an account lives. Bitwarden's cloud splits identity and API across hosts; self-hosted puts both under one. */
data class BitwardenServer(val identityUrl: String, val apiUrl: String) {
    companion object {
        const val US = "https://vault.bitwarden.com"
        const val EU = "https://vault.bitwarden.eu"

        fun of(baseUrl: String): BitwardenServer = when (val base = baseUrl.trim().trimEnd('/')) {
            US -> BitwardenServer("https://identity.bitwarden.com", "https://api.bitwarden.com")
            EU -> BitwardenServer("https://identity.bitwarden.eu", "https://api.bitwarden.eu")
            else -> BitwardenServer("$base/identity", "$base/api")
        }
    }
}

/** What logging in with the personal API key returns: a bearer token plus what is needed to unlock the vault. */
class TokenResult(
    val accessToken: String,
    /** When the access token stops working, in epoch millis. */
    val expiresAt: Long,
    val kdf: KdfParams,
    val encryptedUserKey: String,
    val salt: String?,
)

/**
 * A small client for the handful of Bitwarden server endpoints sync needs. Works against both Bitwarden's cloud
 * and Vaultwarden. Responses are handled as loose JSON, looked up case-insensitively, because the identity
 * server answers in PascalCase and the API server in camelCase.
 */
class BitwardenApi(private val server: BitwardenServer, private val deviceId: String) {

    suspend fun loginWithApiKey(clientId: String, clientSecret: String): TokenResult {
        val json = request(
            "POST",
            server.identityUrl + "/connect/token",
            form = mapOf(
                "grant_type" to "client_credentials",
                "scope" to "api",
                "client_id" to clientId.trim(),
                "client_secret" to clientSecret.trim(),
                "deviceType" to DEVICE_TYPE_ANDROID,
                "deviceIdentifier" to deviceId,
                "deviceName" to "SqueezeSSH",
            ),
        ) as JsonObject
        val unlock = json.obj("UserDecryptionOptions")?.obj("MasterPasswordUnlock")
        return TokenResult(
            accessToken = json.str("access_token") ?: throw BitwardenApiException("Login returned no access token"),
            expiresAt = System.currentTimeMillis() + (json.int("expires_in") ?: 3600) * 1000L,
            kdf = KdfParams(
                type = json.int("Kdf") ?: KdfParams.PBKDF2,
                iterations = json.int("KdfIterations") ?: 600_000,
                memoryMb = json.int("KdfMemory"),
                parallelism = json.int("KdfParallelism"),
            ),
            encryptedUserKey = json.str("Key") ?: unlock?.str("MasterKeyEncryptedUserKey")
                ?: throw BitwardenApiException("This account has no master password key (SSO-only accounts are not supported)"),
            salt = unlock?.str("Salt"),
        )
    }

    suspend fun profile(token: String) = request("GET", server.apiUrl + "/accounts/profile", token = token) as JsonObject

    suspend fun sync(token: String) = request("GET", server.apiUrl + "/sync?excludeDomains=true", token = token) as JsonObject

    suspend fun createFolder(token: String, encryptedName: String) =
        request("POST", server.apiUrl + "/folders", token = token, json = JsonObject(mapOf("name" to JsonPrimitive(encryptedName)))) as JsonObject

    suspend fun createCipher(token: String, cipher: JsonObject) =
        request("POST", server.apiUrl + "/ciphers", token = token, json = cipher) as JsonObject

    suspend fun updateCipher(token: String, id: String, cipher: JsonObject) =
        request("PUT", server.apiUrl + "/ciphers/$id", token = token, json = cipher) as JsonObject

    /** Moves the item to the vault's trash, where it can still be restored. */
    suspend fun trashCipher(token: String, id: String) {
        request("PUT", server.apiUrl + "/ciphers/$id/delete", token = token)
    }

    private suspend fun request(
        method: String,
        url: String,
        token: String? = null,
        form: Map<String, String>? = null,
        json: JsonObject? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            // The server hides item types newer than the client claims to understand; SSH keys need 2024.12+.
            conn.setRequestProperty("Bitwarden-Client-Name", CLIENT_NAME)
            conn.setRequestProperty("Bitwarden-Client-Version", CLIENT_VERSION)
            conn.setRequestProperty("Device-Type", DEVICE_TYPE_ANDROID)
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val body = when {
                form != null -> {
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
                }
                json != null -> {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    json.toString()
                }
                else -> null
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            } else if (method != "GET") {
                conn.setFixedLengthStreamingMode(0)
                conn.doOutput = true
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) throw BitwardenApiException(errorMessage(status, text), status)
            if (text.isBlank()) JsonNull else Json.parseToJsonElement(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(status: Int, body: String): String {
        val parsed = runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrNull()
        val detail = parsed?.let {
            listOf(it.str("error_description"), it.str("message"), it.obj("ErrorModel")?.str("Message"))
                .firstOrNull { d -> !d.isNullOrBlank() }
        }
        return when {
            status == 429 -> "The server is rate limiting logins; try again in a minute"
            status == 400 && detail?.contains("invalid_client", true) == true -> "The API key client_id or client_secret is wrong"
            detail != null -> "$detail (HTTP $status)"
            else -> "The server answered HTTP $status"
        }
    }

    companion object {
        const val CLIENT_NAME = "mobile"
        const val CLIENT_VERSION = "2026.10.0"
        const val DEVICE_TYPE_ANDROID = "0"
    }
}

// ---- case-insensitive JSON helpers ----

internal fun JsonObject.field(name: String): JsonElement? =
    get(name) ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

internal fun JsonObject.str(name: String): String? = (field(name) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(name: String): Int? = (field(name) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.obj(name: String): JsonObject? = field(name) as? JsonObject
internal fun JsonObject.arr(name: String): List<JsonObject> = (field(name) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
