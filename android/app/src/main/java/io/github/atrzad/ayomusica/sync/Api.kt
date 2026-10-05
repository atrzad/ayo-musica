package io.github.atrzad.ayomusica.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The server said no: the message is the server's own (in Portuguese), shown as is. */
class ApiError(val status: Int, message: String) : IOException(message)

/** Plain HTTP + JSON to the sync server, with the session token. */
object Api {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun open(path: String, method: String, timeoutMs: Int = 30_000, token: String? = null): HttpURLConnection {
        val connection = URL(Account.server + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "AyoMusica-Android")
        val session = token ?: Account.token
        if (session.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $session")
        return connection
    }

    private fun answer(connection: HttpURLConnection): JsonObject {
        try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            if (code == 401) Account.signOut().also { throw ApiError(code, body.message() ?: "Entre de novo.") }
            if (code !in 200..299) throw ApiError(code, body.message() ?: "O servidor respondeu $code.")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.message() = (this["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    fun get(path: String, timeoutMs: Int = 30_000, token: String? = null): JsonObject = answer(open(path, "GET", timeoutMs, token))

    fun post(path: String, body: JsonElement): JsonObject {
        val connection = open(path, "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return answer(connection)
    }

    fun delete(path: String): JsonObject = answer(open(path, "DELETE"))

    /** Sends a file's bytes (picture or song) without loading it all in memory. */
    fun upload(path: String, type: String, size: Long, headers: Map<String, String> = emptyMap(), method: String = "POST",
               progress: ((Long) -> Unit)? = null, body: () -> InputStream): JsonObject {
        val connection = open(path, method, 10 * 60_000)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", type)
        headers.forEach(connection::setRequestProperty)
        if (size > 0) connection.setFixedLengthStreamingMode(size) else connection.setChunkedStreamingMode(64 * 1024)
        body().use { input ->
            connection.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    progress?.invoke(sent)
                }
            }
        }
        return answer(connection)
    }

    /** Saves a server file (song, picture) into [target]; false when the server has no such file. */
    fun download(path: String, target: File, progress: ((Long, Long) -> Unit)? = null): Boolean {
        val connection = open(path, "GET", 120_000)
        try {
            if (connection.responseCode == 404) return false
            if (connection.responseCode == 401) { Account.signOut(); throw ApiError(401, "Entre de novo.") }
            if (connection.responseCode !in 200..299) throw ApiError(connection.responseCode, "O servidor respondeu ${connection.responseCode}.")
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.part")
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        got += read
                        progress?.invoke(got, total)
                    }
                }
            }
            if (!temp.renameTo(target)) throw IOException("Não deu para salvar ${target.name}.")
            return true
        } finally {
            connection.disconnect()
        }
    }

    /** "Entrar com o Google": the server checks Google's token and gives back its own session. */
    fun login(idToken: String): JsonObject = post("/api/login", JsonObject(mapOf(
        "idToken" to kotlinx.serialization.json.JsonPrimitive(idToken),
        "device" to kotlinx.serialization.json.JsonPrimitive(Account.deviceName),
    )))

    fun config(): JsonObject = get("/api/config")

    fun string(obj: JsonObject, key: String): String = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    fun objectOf(obj: JsonObject, key: String): JsonObject? = obj[key]?.let { runCatching { it.jsonObject }.getOrNull() }

    @Suppress("unused") private fun JsonElement.text() = jsonPrimitive.content
}
