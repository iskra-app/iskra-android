package wtf.iskra.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.TimeoutError
import wtf.iskra.core.support.withTimeLimit
import wtf.iskra.core.transport.nostr.BridgeRefusal
import wtf.iskra.core.transport.nostr.Heard
import wtf.iskra.core.transport.nostr.NostrStreamTunnel
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Один запрос через мост — ответ на единственный вопрос, который экран настроек оставляет открытым:
 * есть ли там на самом деле мост. Порт `BridgeCheck.swift` (docs/PROMPT.md, 9.8).
 *
 * Существует, потому что всё в этой функции иначе ломается молча. Опечатка в ключе, мост, который не
 * запущен, мост, который не возит к этому серверу (`ALLOWED_UPSTREAMS`), закрытый мост, не знающий
 * этого устройства, relay'и, которые все лежат, — изнутри приложения все пять выглядят как вход, который
 * крутится. Здесь спрашивается `/_matrix/client/versions`, самое маленькое, на что отвечает сервер
 * Matrix, и говорится, что из пяти это было.
 *
 * Идёт ровно так, как SDK: через такой же листенер `CONNECT`, потоками nostr, с TLS до сервера.
 * Мост, прошедший проверку, проходит и для SDK, а причина отказа — слово самого моста.
 *
 * Кнопка «Проверить мост» и проба `--es probe bridge` зовут **одну и ту же** функцию, чтобы терминал и
 * экран не могли разойтись в том, что оба диагностируют.
 */
object BridgeCheck {
    sealed interface Outcome {
        /** Мост ответил, и сервер за ним ответил тоже. */
        data class Reachable(val milliseconds: Long) : Outcome

        /**
         * Мост на месте и кончил поток, со своей причиной: чаще всего — не повезёт это устройство к
         * этому серверу (не в списке, не Matrix, не в публичном интернете, не 443), иначе — занят или
         * поток порвался.
         */
        data class Refused(val refusal: BridgeRefusal) : Outcome

        /** Ни один relay не взял запрос. Ничего не опубликовано — ничего и не услышано. */
        data object NoRelay : Outcome

        /** Опубликовано, и никто не ответил: не тот ключ, мост не запущен или закрытый мост не знает устройства. */
        data object Silent : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * @param limit короче, чем у SDK, нарочно: это человек стоит перед экраном и ждёт ответа, а не
     *   запрос, от которого что-то зависит.
     */
    suspend fun run(
        bridgeKeyHex: String,
        relays: List<String>,
        homeserver: String,
        secretKey: ByteArray,
        limit: Duration = 20.seconds,
    ): Outcome {
        val tunnel = try {
            NostrStreamTunnel(bridgeKeyHex, relays, secretKey)
        } catch (error: Exception) {
            return Outcome.Failed(Log.describe(error))
        }
        tunnel.start()
        val proxy = LocalConnectProxy(tunnel)
        return try {
            proxy.start()
            check(proxy, homeserver, limit, heard = tunnel.heard, relays = { tunnel.connectedRelays })
        } catch (error: IOException) {
            Outcome.Failed(Log.describe(error))
        } finally {
            proxy.stop()
            tunnel.stop()
        }
    }

    /**
     * Сам запрос, через уже поднятый листенер, — то, что тесты гоняют с [DirectPipe] вместо моста и
     * клиентом [base], который доверяет их серверу.
     */
    suspend fun check(
        through: LocalConnectProxy,
        homeserver: String,
        limit: Duration,
        base: OkHttpClient = OkHttpClient(),
        heard: Heard? = null,
        relays: () -> Int = { 1 },
    ): Outcome {
        val url = homeserver.trimEnd('/') + "/_matrix/client/versions"
        val request = runCatching { Request.Builder().url(url).build() }.getOrElse { return Outcome.Failed("bad homeserver address") }
        val client = through.httpClient(base.newBuilder().callTimeout(limit.inWholeMilliseconds, TimeUnit.MILLISECONDS).build())
        val started = System.nanoTime()
        return try {
            val status = withTimeLimit(limit + 1.seconds) {
                withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (status == 200) Outcome.Reachable(elapsed) else Outcome.Failed("HTTP $status")
        } catch (error: Exception) {
            if (error !is IOException && error !is TimeoutError) throw error
            heard?.lastReset?.let { return Outcome.Refused(BridgeRefusal.of(it)) }
            if (relays() == 0) return Outcome.NoRelay
            if (heard?.anything == false) return Outcome.Silent
            Outcome.Failed(Log.describe(error))
        }
    }
}
