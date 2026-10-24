package wtf.iskra.core.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.matrix.rustcomponents.sdk.Client
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/**
 * Один запрос приложения с токеном аккаунта — то, чего нет в биндингах SDK (`AccountDevices`, присутствие). Токен читается из
 * сессии SDK в момент отправки и нигде не хранится: токен, который SDK успел обновить, не должен заменяться тем, что был при
 * открытии экрана. Ни запрос, ни ответ не логируются.
 *
 * Идёт **тем же путём, что запросы SDK** (docs/PROMPT.md, 9.6): с включённым мостом — через его листенер `CONNECT`
 * ([AuthenticationService.homeserverHttp]), а не напрямую; мост просили, а его нет — отказ, как у самого SDK.
 */
internal class AuthorisedRequests(private val client: Client, private val authentication: AuthenticationService) {
    suspend fun send(request: HomeserverRequest): HomeserverResponse = withTimeLimit(LIMIT) {
        val homeserver = client.homeserver()
        val http = authentication.homeserverHttp(homeserver)
        // Читаем в момент отправки, не раньше.
        val token = client.session().accessToken
        val body = request.body?.toRequestBody(JSON)
            ?: if (request.method == "GET" || request.method == "DELETE") null else ByteArray(0).toRequestBody(null)
        val call = http.newCall(
            Request.Builder()
                .url(homeserver.trimEnd('/') + request.target)
                .header("Authorization", "Bearer $token")
                .method(request.method, body)
                .build(),
        )
        withContext(Dispatchers.IO) {
            call.execute().use { response -> HomeserverResponse(response.code, response.body?.string().orEmpty()) }
        }
    }

    private companion object {
        val LIMIT = 30.seconds
        val JSON = "application/json".toMediaType()
    }
}
