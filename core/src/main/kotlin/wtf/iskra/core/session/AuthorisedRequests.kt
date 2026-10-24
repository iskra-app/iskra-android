package wtf.iskra.core.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import wtf.iskra.core.support.withTimeLimit
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

/**
 * Один запрос приложения с токеном аккаунта — то, чего нет в биндингах SDK (`AccountDevices`). Токен читается из сессии SDK
 * в момент отправки и нигде не хранится: токен, который SDK успел обновить, не должен заменяться тем, что был при открытии
 * экрана. Ни запрос, ни ответ не логируются.
 *
 * Идёт напрямую на домашний сервер, как и сам SDK, пока моста нет; с мостом (этап 9) этот вызов станет одним из тех, что идут
 * через него, — шов здесь один.
 */
internal class AuthorisedRequests(private val client: Client) {
    suspend fun send(request: HomeserverRequest): HomeserverResponse = withTimeLimit(LIMIT) {
        // Читаем в момент отправки, не раньше.
        val token = client.session().accessToken
        withContext(Dispatchers.IO) {
            val connection = URL(client.homeserver().trimEnd('/') + request.target).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = CONNECT_MS
                connection.readTimeout = READ_MS
                connection.setRequestProperty("Authorization", "Bearer $token")
                if (request.body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(request.body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                HomeserverResponse(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        val LIMIT = 30.seconds
        const val CONNECT_MS = 15_000
        const val READ_MS = 20_000
    }
}
