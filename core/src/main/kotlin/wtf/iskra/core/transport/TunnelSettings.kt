package wtf.iskra.core.transport

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import wtf.iskra.tunnel.BridgeAddress
import wtf.iskra.tunnel.parseBridgeAddress
import java.net.URI

/**
 * Решение человека вести трафик через мост, и где оно записано. Порт `TunnelSettings.swift`.
 *
 * Читается моделью, а не экраном: [wtf.iskra.core.session.AuthenticationService] спрашивает его,
 * когда собирает клиент, — и при входе, и при восстановлении, и при расшифровке пуша, задолго до
 * любого экрана. Пуш на Android обрабатывается в том же процессе, поэтому место одно (на iOS
 * понадобилась App Group, чтобы расширение уведомлений не читало «моста нет»).
 *
 * `SharedPreferences`, а не DataStore: маршрут нужен синхронно, в момент сборки клиента, и
 * асинхронный DataStore здесь стал бы ожиданием на пути каждого входа. Так же хранит своё
 * [wtf.iskra.core.support.AppSettings].
 */
class TunnelSettings internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(ENABLED, false))
    private val mutableAddress = MutableStateFlow(preferences.getString(ADDRESS, null) ?: DEFAULT_ADDRESS)
    private val mutableRelays = MutableStateFlow(preferences.getString(RELAYS, null).orEmpty())

    /** Переключатель «Через мост». */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /**
     * Что в поле адреса: напечатанное или [DEFAULT_ADDRESS], если не печатали ничего. Пустое поле —
     * «моста нет»; поле с опечаткой — мост, которым хотели пользоваться и не могут (см. [asksForBridge]).
     */
    val address: StateFlow<String> = mutableAddress.asStateFlow()

    /** Поле relay'ев как есть: через запятую или по одному в строке. Пусто — [DEFAULT_RELAYS]. */
    val relaysText: StateFlow<String> = mutableRelays.asStateFlow()

    fun setEnabled(on: Boolean) {
        preferences.edit().putBoolean(ENABLED, on).apply()
        mutableEnabled.value = on
    }

    fun setAddress(text: String) {
        preferences.edit().putString(ADDRESS, text).apply()
        mutableAddress.value = text
    }

    fun setRelays(text: String) {
        preferences.edit().putString(RELAYS, text).apply()
        mutableRelays.value = text
    }

    val isEnabled: Boolean get() = enabled.value

    /** Есть ли в поле адреса хоть что-то. Не то же, что «ключ разобрался»: см. [asksForBridge]. */
    val hasAddress: Boolean get() = address.value.isNotBlank()

    /**
     * Просят ли настройки мост. Если да, соединение без моста — **отказ**, а не тихое прямое
     * (docs/PROMPT.md, 9.5): переключатель — решение о том, кто видит трафик.
     */
    val asksForBridge: Boolean get() = isEnabled && hasAddress

    /** Ключ моста, hex, если в поле разбирается ключ. Null — не «иди напрямую», пока [asksForBridge]. */
    val bridgeKey: String? get() = bridgeKeyOf(address.value)

    /** Relay'и, на которых искать мост. */
    val relays: List<String> get() = parseRelays(relaysText.value).ifEmpty { DEFAULT_RELAYS }

    /**
     * Из чего строится соединение — значения, которые прочтёт сборка клиента, а не текст полей:
     * пробел после ключа, `npub` поверх того же ключа в hex, список relay'ев, стёртый до умолчаний,
     * соединения не меняют и не должны пересобирать сеанс.
     */
    data class Snapshot(val enabled: Boolean, val bridgeKey: String?, val relays: List<String>)

    fun snapshot() = Snapshot(isEnabled, bridgeKey, relays)

    private val mutableChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Настройки соединения изменились так, что живой сеанс нужно пересобрать. Отвечает корень:
     * закрывает сеанс и восстанавливает его. Клиент Matrix строится *вокруг* соединения и
     * перенаправить его на лету нельзя, а «со следующего запуска» значило бы приложение, которое
     * перестаёт грузить сообщения, едва включили то, что их починит.
     */
    val changed: SharedFlow<Unit> = mutableChanged.asSharedFlow()

    fun announceChange() {
        mutableChanged.tryEmit(Unit)
    }

    /** Что не так с напечатанным в поле адреса; null — ключ или пустое поле. */
    enum class AddressProblem {
        /** `nsec1…` — чей-то *секретный* ключ: называется по имени, а не превращается молча в открытый. */
        LOOKS_LIKE_A_SECRET_KEY,
        NOT_A_KEY,
    }

    companion object {
        private const val FILE = "transport"
        private const val ENABLED = "transport.bridgeEnabled"
        private const val ADDRESS = "transport.bridgeAddress"
        private const val RELAYS = "transport.bridgeRelays"

        /** Мост, который держат разработчики Искры, открытый для любого публичного сервера Matrix. */
        const val DEFAULT_ADDRESS = "npub1dwztf356tpg2lvzv8z74qgx68efhk0aq7yv2xr4tfq4pnsr92musk5k06v"

        /**
         * Relay'и по умолчанию. Несколько, и не как запаска: событие публикуется на все сразу, ответ
         * приходит с самого быстрого, и relay, который лежит, ничего не стоит (docs/PROMPT.md, 9.5).
         * Выбраны по тому, *откуда* они доходят: из Москвы в октябре 2026 большинство relay'ев на
         * Hetzner, Contabo и DigitalOcean отвечают на ping и замирают на ~16 КБ, а туннель несёт через
         * relay весь sync. Тот же список, что у iOS и у `run-local.sh` моста.
         */
        val DEFAULT_RELAYS = listOf(
            "wss://relay.snort.social",
            "wss://nostr.wine",
            "wss://offchain.pub",
            "wss://nostr.bitcoiner.social",
            "wss://relay.primal.net",
        )

        fun bridgeKeyOf(text: String): String? = (parseBridgeAddress(text) as? BridgeAddress.Key)?.publicKeyHex

        fun addressProblem(text: String): AddressProblem? = when (parseBridgeAddress(text)) {
            is BridgeAddress.LooksLikeASecretKey -> AddressProblem.LOOKS_LIKE_A_SECRET_KEY
            is BridgeAddress.NotAKey -> AddressProblem.NOT_A_KEY
            is BridgeAddress.Key, is BridgeAddress.Empty -> null
        }

        /**
         * Через запятую и переводы строк — то, что действительно адрес relay'я. `https://` выбрасывается,
         * а не исправляется: relay — это `wss://`, и тихая замена схемы делает из опечатки relay,
         * который просто кажется лежащим. `ws://` — только к этой машине: где угодно ещё это оба
         * ключа, время и размеры каждого запроса открытым текстом.
         */
        fun parseRelays(text: String): List<String> = text.split(',', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { relay ->
                val uri = runCatching { URI(relay) }.getOrNull() ?: return@filter false
                if (uri.host.isNullOrEmpty()) return@filter false
                when (uri.scheme?.lowercase()) {
                    "wss" -> true
                    "ws" -> isLoopback(relay)
                    else -> false
                }
            }

        /**
         * Что напечатал человек — в адрес, к которому идти. Голое имя значит `https`, никогда не `http`:
         * на этом пути это была бы тихая потеря единственного участка, где TLS ещё настоящий.
         */
        fun upstreamUrl(address: String): String? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null
            return if ("://" in trimmed) trimmed else "https://$trimmed"
        }

        /** Эта ли машина. */
        fun isLoopback(url: String): Boolean {
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removeSurrounding("[", "]") ?: return false
            return host == "127.0.0.1" || host == "localhost" || host == "::1"
        }
    }
}
