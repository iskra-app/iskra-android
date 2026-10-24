package wtf.iskra.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Одна сессия аккаунта — строка на экране «Устройства». */
data class AccountDevice(
    val id: String,
    /** Как сессия назвала себя серверу; null — не назвала. */
    val name: String?,
    val lastSeenIp: String?,
    /** Миллисекунды Unix; null — сервер не знает. */
    val lastSeenAt: Long?,
    /** Это устройство — сессия, в которой работает приложение. */
    val isCurrent: Boolean,
) {
    private val parsed = DeviceName(name)

    /** Устройство так, как его назвал бы человек: «iPhone 16 Pro», «Firefox on Linux». Железо первым, приложение под ним. */
    val title: String get() = parsed.title ?: name ?: id

    /** Приложение и система под названием, где имя их несёт: «Iskra 1.0 · iOS 26.0». */
    val detail: String? get() = parsed.detail

    val platform: DevicePlatform get() = DevicePlatform.of(name)
}

/** На чём работает сессия — для значка рядом. */
enum class DevicePlatform {
    IPHONE, IPAD, MAC, ANDROID, WEB, DESKTOP, UNKNOWN;

    companion object {
        // По порядку: срабатывает первое, поэтому «ios» стоит после всего, внутри чего он бывает.
        private val byWords = listOf(
            IPHONE to listOf("iphone"),
            IPAD to listOf("ipad"),
            ANDROID to listOf("android"),
            WEB to listOf("firefox", "chrome", "safari", "edge", "browser", " web"),
            MAC to listOf("macbook", "imac", "mac mini", "mac studio", "macos", "mac pro"),
            DESKTOP to listOf("windows", "linux"),
            IPHONE to listOf("ios"),
        )

        fun of(name: String?): DevicePlatform {
            val lower = name.orEmpty().lowercase()
            return byWords.firstOrNull { (_, words) -> words.any { lower.contains(it) } }?.first ?: UNKNOWN
        }
    }
}

/**
 * Имя сессии, разобранное на название и строку под ним. Понятны три формы, остальное показывается целиком как название —
 * это всегда правда, пусть и некрасивая:
 *
 * - `Iskra/1.0 (iPhone 16 Pro; iOS 26.0)` — форма User-Agent: её шлёт это приложение (`DeviceDescription`);
 * - `Element X iOS: iPhone`, `Element Web: Firefox on Linux` — «приложение: устройство», как у Element;
 * - всё прочее — как есть.
 */
internal class DeviceName(name: String?) {
    val title: String?
    val detail: String?

    init {
        val text = name?.trim().orEmpty()
        var title: String? = null
        var detail: String? = null
        if (text.isNotEmpty()) {
            val open = text.indexOf('(')
            val slash = if (open > 0) text.substring(0, open).indexOf('/') else -1
            val colon = text.indexOf(": ")
            if (open >= 0 && text.endsWith(")") && slash >= 0) {
                val app = text.substring(0, slash).trim()
                val version = text.substring(slash + 1, open).trim()
                val parts = text.substring(open + 1, text.length - 1).split(';').map { it.trim() }
                val model = parts.firstOrNull().orEmpty()
                if (model.isNotEmpty()) {
                    title = model
                    val appLine = listOf(app, version).filter { it.isNotEmpty() }.joinToString(" ")
                    detail = (listOf(appLine) + parts.drop(1)).filter { it.isNotEmpty() }.joinToString(" · ")
                }
            }
            if (title == null && colon >= 0) {
                val app = text.substring(0, colon).trim()
                val device = text.substring(colon + 2).trim()
                if (app.isNotEmpty() && device.isNotEmpty()) {
                    title = device
                    detail = app
                }
            }
            if (title == null) title = text
        }
        this.title = title
        this.detail = detail
    }
}

/** Почему сессия не закончилась — словами, на которые отвечает экран. */
sealed interface AccountDevicesError {
    /** Сервер хочет пароль аккаунта (User-Interactive Auth): экран спросит и повторит. */
    data object NeedsPassword : AccountDevicesError

    /** Спросили — и это не пароль. */
    data object WrongPassword : AccountDevicesError

    /**
     * Сессии этого аккаунта заканчиваются на странице самого сервера — всё, что вошло через OAuth, а на matrix.org сегодня это
     * любой новый аккаунт: `DELETE /devices` там отвечает 404, ручки больше нет. [page] — страница, если сервер её назвал.
     */
    data class ManagedByServer(val page: String?) : AccountDevicesError

    /** Сервер недоступен так, как к нему ходит приложение, или сказал что-то другое. */
    data object Failed : AccountDevicesError
}

class AccountDevicesException(val error: AccountDevicesError) : Exception()

/** Один запрос к серверу и его ответ — то, что умеет [AccountDevices.send]. */
class HomeserverRequest(val method: String, val target: String, val body: String? = null)

class HomeserverResponse(val status: Int, val body: String)

/**
 * Сессии аккаунта: список и завершение — модель экрана «Устройства».
 *
 * **Почему это собственный запрос приложения.** В SDK есть `devices()` и `delete_devices()`, но в биндинги они не вынесены
 * (так же, как в iOS). Поэтому список берётся здесь, под двумя правилами, что делают это безопасным:
 *
 * - **Запрос идёт тем же путём, что запросы SDK** — [send] отдаёт его туда же, куда идёт всё остальное; когда в приложении
 *   появится мост (этап 9), то через него. Приложение, ходящее напрямую ради одного вызова, рассказывало бы серверу с адреса
 *   этого устройства ровно то, что мост включают, чтобы скрыть. Пока моста нет, прямой и путь SDK совпадают.
 * - **Токен берётся на один запрос и не хранится.** Читается из сессии SDK в момент отправки, ложится в один заголовок и
 *   исчезает вместе с запросом. Ничего не логирует — ни запрос, ни ответ.
 */
class AccountDevices(
    private val userId: String,
    private val currentDeviceId: String,
    /** Вошли через OAuth, а значит сессии заканчиваются на странице сервера. */
    val isManagedByServer: Boolean,
    private val send: suspend (HomeserverRequest) -> HomeserverResponse,
    /** Страница сервера для завершения сессии (null — список сессий), если аккаунт — его. */
    private val serverPage: suspend (String?) -> String?,
) {
    /** Все сессии аккаунта: эта первой, остальные — по тому, как недавно их видели. */
    suspend fun list(): List<AccountDevice> {
        val response = send(HomeserverRequest("GET", "/_matrix/client/v3/devices"))
        if (response.status != 200) throw AccountDevicesException(AccountDevicesError.Failed)
        return devices(response.body, currentDeviceId)
    }

    /**
     * Завершает эти сессии. Сначала без пароля — не всем серверам он нужен; ответ скажет, нужен ли
     * ([AccountDevicesError.NeedsPassword]), и экран спросит и вызовет снова с ним.
     */
    suspend fun end(deviceIds: List<String>, password: String? = null) {
        val others = deviceIds.filter { it != currentDeviceId }
        if (others.isEmpty()) return
        if (isManagedByServer) {
            throw AccountDevicesException(AccountDevicesError.ManagedByServer(serverPage(others.singleOrNull())))
        }
        val session = if (password != null) authSession(others) else null
        val body = buildJsonObject {
            putJsonArray("devices") { others.forEach { add(JsonPrimitive(it)) } }
            if (password != null) {
                putJsonObject("auth") {
                    put("type", "m.login.password")
                    putJsonObject("identifier") {
                        put("type", "m.id.user")
                        put("user", userId)
                    }
                    put("password", password)
                    session?.let { put("session", it) }
                }
            }
        }
        val response = send(HomeserverRequest("POST", "/_matrix/client/v3/delete_devices", body.toString()))
        when (val outcome = outcome(response.status, response.body, password != null)) {
            null -> return
            is AccountDevicesError.ManagedByServer ->
                throw AccountDevicesException(AccountDevicesError.ManagedByServer(serverPage(others.singleOrNull())))
            else -> throw AccountDevicesException(outcome)
        }
    }

    /** UIA-сессия, которую сервер открыл под это удаление: просят её, делая запрос один раз без авторизации — так протокол её выдаёт. */
    private suspend fun authSession(deviceIds: List<String>): String? {
        val body = buildJsonObject { putJsonArray("devices") { deviceIds.forEach { add(JsonPrimitive(it)) } } }
        val response = send(HomeserverRequest("POST", "/_matrix/client/v3/delete_devices", body.toString()))
        return runCatching { JSON.parseToJsonElement(response.body).jsonObject["session"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Что ответило удаление, словами экрана. Null — сделано. */
        internal fun outcome(status: Int, body: String, withPassword: Boolean): AccountDevicesError? {
            if (status in 200..299) return null
            val json = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
            val errcode = json?.get("errcode")?.jsonPrimitive?.contentOrNull
            // Ручки нет вовсе — сервер, чьи сессии живут за его собственной страницей.
            if (status == 404 || errcode == "M_UNRECOGNIZED") return AccountDevicesError.ManagedByServer(null)
            if (status == 401) {
                if (withPassword && errcode == "M_FORBIDDEN") return AccountDevicesError.WrongPassword
                val stages = (json?.get("flows") as? JsonArray).orEmpty().flatMap { flow ->
                    (flow.jsonObject["stages"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                }
                if ("m.login.password" in stages) return if (withPassword) AccountDevicesError.WrongPassword else AccountDevicesError.NeedsPassword
                // Спросили то, чего приложение на месте не даст: SSO, веб-страницу.
                return AccountDevicesError.ManagedByServer(null)
            }
            return AccountDevicesError.Failed
        }

        /** Список так, как его прислал сервер. */
        internal fun devices(body: String, current: String): List<AccountDevice> {
            val entries = runCatching { JSON.parseToJsonElement(body).jsonObject["devices"]?.jsonArray }.getOrNull()
                ?: throw AccountDevicesException(AccountDevicesError.Failed)
            val devices = entries.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val id = entry["device_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                AccountDevice(
                    id = id,
                    name = entry["display_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    lastSeenIp = entry["last_seen_ip"]?.jsonPrimitive?.contentOrNull,
                    lastSeenAt = entry["last_seen_ts"]?.jsonPrimitive?.longOrNull,
                    isCurrent = id == current,
                )
            }
            return devices.sortedWith(compareByDescending<AccountDevice> { it.isCurrent }.thenByDescending { it.lastSeenAt ?: Long.MIN_VALUE })
        }
    }
}
