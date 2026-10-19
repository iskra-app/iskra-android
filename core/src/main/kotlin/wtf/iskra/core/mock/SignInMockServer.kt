package wtf.iskra.core.mock

import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Сервер внутри приложения — пройти экраны входа и регистрации без настоящего. Порт
 * `SignInMockServer.swift`.
 *
 * Не подмена `AuthenticationService`: ненастоящий здесь только *сервер*. Он стоит за тем же
 * loopback-листенером, что демо ([LoopbackHttpServer]), и настоящий SDK говорит с ним настоящим HTTP:
 * поиск, `/versions`, способы входа, OIDC, whoami. Каждая строчка `AuthenticationService` и
 * `SignInModel` работает, как против matrix.org, и вход кончается настоящим `UserSession`.
 *
 * Половина в браузере — тоже настоящая: OIDC отвечает маленькой HTML-страницей, её кнопка
 * перенаправляет на `wtf.iskra:/oauth-callback`, и Custom Tabs ловит это, как ловит Matrix
 * Authentication Service.
 *
 * Чем он намеренно не является — Matrix-сервером. После whoami он отвечает только то, что нужно
 * приложению, чтобы осесть на пустом аккаунте, — через первый запуск к коду восстановления и в пустой
 * список чатов; на остальное — `M_UNRECOGNIZED`, как и настоящий, и [unanswered] это записывает.
 * Криптография ничья: ключи принимаются и запоминаются, не проверяются. Ничего не уходит с loopback.
 */
class SignInMockServer(val configuration: Configuration = Configuration()) {
    data class Configuration(
        /** Что стоит после двоеточия в каждом выданном ID. */
        val serverName: String = "iskra.test",
        /** Имя → пароль. Вход паролем и через OIDC сверяются с ними. */
        val accounts: Map<String, String> = mapOf("alice" to "password"),
        val offersPassword: Boolean = true,
        /** Старый `m.login.sso`: перенаправление на страницу, токен входа обратно. */
        val offersLegacySso: Boolean = false,
        /** OIDC через `/auth_metadata`, как у Matrix Authentication Service. */
        val offersOAuth: Boolean = true,
        /** Объявляет ли OIDC `prompt=create`. Имеет смысл только с [offersOAuth]. */
        val offersRegistration: Boolean = true,
        /** Добавляется к каждому ответу — чтобы на спиннер можно было посмотреть. */
        val latencyMillis: Long = 0,
    )

    private val lock = Any()
    private val accountsByName = configuration.accounts.toMutableMap()

    /** Токен доступа → ID пользователя. */
    private val tokens = mutableMapOf<String, String>()

    /** Одноразовые коды — коды авторизации OIDC и токены старого входа — → ID пользователя. */
    private val codes = mutableMapOf<String, String>()

    /** Токен доступа → ID устройства, для whoami. OIDC кладёт устройство в scope. */
    private val devices = mutableMapOf<String, String>()

    /** Код авторизации → устройство, которое клиент назвал в scope. */
    private val codeDevices = mutableMapOf<String, String>()
    private val requestLog = mutableListOf<String>()
    private val unansweredLog = mutableListOf<String>()

    /** Account data по пути запроса, как клиент записал последним. */
    private val accountData = mutableMapOf<String, ByteArray>()

    /** Пользователь → ID устройства → подписанные ключи устройства. */
    private val deviceKeys = mutableMapOf<String, MutableMap<String, JSON>>()

    /** Пользователь → `master_key` / `self_signing_key` / `user_signing_key` → ключ. */
    private val crossSigning = mutableMapOf<String, MutableMap<String, JSON>>()

    /**
     * Версии бэкапа каждого пользователя, старые первыми; номер версии — позиция плюс один. По
     * пользователям, а не одна на сервер (в iOS-оригинале — одна): второй аккаунт видел бэкап первого,
     * SDK отвечал `BackupExistsOnServer`, и его первый запуск сдавался.
     */
    private val backups = mutableMapOf<String, MutableList<JSON>>()

    /** Где сервер живёт: адрес листенера, когда у того есть порт. OIDC-метаданные — абсолютные URL. */
    @Volatile
    private var base = "http://127.0.0.1"

    private var listener: LoopbackHttpServer? = null

    /** Каждый запрос SDK, старые первыми, — `МЕТОД путь`. По нему тест видит, какой путь прошёл. */
    val requests: List<String> get() = synchronized(lock) { requestLog.toList() }

    /** Запросы без ответа (`M_UNRECOGNIZED`). Пусто — цель; новое после обновления SDK видно здесь. */
    val unanswered: List<String> get() = synchronized(lock) { unansweredLog.toList() }

    /** Аккаунты как есть сейчас, со сделанными регистрацией. */
    val accounts: Map<String, String> get() = synchronized(lock) { accountsByName.toMap() }

    /** Поднимает сервер на loopback и возвращает адрес, который вводят в поле входа. */
    @Synchronized
    fun start(): String {
        listener?.let { return base }
        val server = LoopbackHttpServer { handle(it) }
        val port = server.start()
        listener = server
        base = "http://127.0.0.1:$port"
        return base
    }

    @Synchronized
    fun stop() {
        listener?.stop()
        listener = null
    }

    internal suspend fun handle(request: HttpRequest): HttpResponse {
        val (path, query) = split(request.target)
        synchronized(lock) { requestLog += "${request.method} $path" }
        if (configuration.latencyMillis > 0) delay(configuration.latencyMillis)
        val response = if (path.startsWith("/_matrix/client/unstable/org.matrix.simplified_msc3575/sync")) {
            sync(request, query)
        } else {
            route(request, path, query)
        }
        if (response === UNRECOGNIZED) synchronized(lock) { unansweredLog += "${request.method} $path" }
        return response
    }

    // MARK: Маршруты

    @Suppress("CyclomaticComplexMethod")
    private fun route(request: HttpRequest, path: String, query: Map<String, String>): HttpResponse {
        val method = request.method.uppercase()
        return when {
            method == "GET" && path == "/.well-known/matrix/client" -> ok(json("m.homeserver" to json("base_url" to base)))
            method == "GET" && path == "/_matrix/client/versions" -> ok(
                json(
                    "versions" to listOf("v1.1", "v1.5", "v1.11", "v1.12", "v1.13"),
                    "unstable_features" to json("org.matrix.simplified_msc3575" to true),
                ),
            )
            method == "GET" && path.isLogin() -> loginFlows()
            method == "POST" && path.isLogin() -> login(request.body)
            method == "GET" && (path == "/_matrix/client/v3/login/sso/redirect" || path == "/_matrix/client/r0/login/sso/redirect") ->
                if (configuration.offersLegacySso) page(Page.LEGACY_SSO, query) else UNRECOGNIZED
            method == "GET" && (path == "/_matrix/client/v1/auth_metadata" || path == "/_matrix/client/unstable/org.matrix.msc2965/auth_metadata") ->
                if (configuration.offersOAuth) authMetadata() else UNRECOGNIZED
            method == "GET" && (path == "/_matrix/client/v1/auth_issuer" || path == "/_matrix/client/unstable/org.matrix.msc2965/auth_issuer") ->
                if (configuration.offersOAuth) ok(json("issuer" to "$base/")) else UNRECOGNIZED
            method == "GET" && path == "/_mock/oauth/jwks" -> ok(json("keys" to emptyList<Any>()))
            method == "POST" && path == "/_mock/oauth/register" -> {
                // Динамическая регистрация клиента: кто спросил, тот получил ID. Присланное — эхом:
                // SDK сверяет, что получил те адреса возврата, что регистрировал.
                val client = JsonValues.decodeObject(request.body.decodeToString())
                client["client_id"] = "mock-client"
                ok(client, status = 201)
            }
            method == "GET" && path == "/_mock/oauth/authorize" -> page(if (query["prompt"] == "create") Page.REGISTER else Page.SIGN_IN, query)
            method == "GET" && path == "/_mock/oauth/approve" -> approve(query)
            method == "POST" && path == "/_mock/oauth/token" -> token(form(request.body))
            method == "POST" && path == "/_mock/oauth/revoke" -> ok(json())
            method == "GET" && (path == "/_matrix/client/v3/account/whoami" || path == "/_matrix/client/r0/account/whoami") -> {
                val token = bearer(request) ?: return UNKNOWN_TOKEN
                val (user, device) = synchronized(lock) { tokens[token] to devices[token] }
                if (user == null) UNKNOWN_TOKEN else ok(json("user_id" to user, "device_id" to (device ?: "MOCKDEVICE"), "is_guest" to false))
            }
            method == "POST" && (path == "/_matrix/client/v3/logout" || path == "/_matrix/client/r0/logout") -> {
                bearer(request)?.let { token -> synchronized(lock) { tokens.remove(token) } }
                ok(json())
            }
            else -> afterSignIn(method, path, request)
        }
    }

    private fun String.isLogin() = this == "/_matrix/client/v3/login" || this == "/_matrix/client/r0/login"

    /**
     * Что нужно пустому аккаунту, чтобы приложение сидело на нём, не поднимая тревоги.
     *
     * Сказать «да» на всё — мало, это пробовали первым. SDK, едва человек вошёл, настраивает
     * cross-signing и бэкап ключей и *перечитывает* записанное: запрос ключей без только что
     * загруженных или account data, всё ещё 404 после PUT, — аккаунт, чьё шифрование не взялось, и
     * первый запуск на нём сдаётся. Поэтому запоминаются ровно эти три вещи, и ничего о комнатах.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun afterSignIn(method: String, path: String, request: HttpRequest): HttpResponse {
        val user = bearer(request)?.let { token -> synchronized(lock) { tokens[token] } }
        val body = JsonValues.decodeObject(request.body.decodeToString())

        // Account data, общая и комнатная: ключ — путь.
        if ("/account_data/" in path) {
            return if (method == "PUT") {
                synchronized(lock) { accountData[path] = request.body }
                ok(json())
            } else {
                synchronized(lock) { accountData[path] }?.let(::raw) ?: NOT_FOUND
            }
        }
        if ("/presence/" in path && path.endsWith("/status")) {
            return if (method == "PUT") ok(json()) else ok(json("presence" to "online"))
        }
        if (path.endsWith("/keys/upload")) {
            val device = body["device_keys"].obj()
            val id = device?.get("device_id").str()
            if (user != null && device != null && id != null) synchronized(lock) { deviceKeys.getOrPut(user) { mutableMapOf() }[id] = device }
            return ok(json("one_time_key_counts" to json("signed_curve25519" to 50)))
        }
        if (path.endsWith("/keys/device_signing/upload")) {
            user ?: return UNKNOWN_TOKEN
            synchronized(lock) {
                for (kind in CROSS_SIGNING_KINDS) body[kind].obj()?.let { crossSigning.getOrPut(user) { mutableMapOf() }[kind] = it }
            }
            return ok(json())
        }
        if (path.endsWith("/keys/signatures/upload")) {
            synchronized(lock) {
                for ((owner, objects) in body) {
                    for ((keyId, signed) in objects.obj().orEmpty()) signed.obj()?.let { addSignatures(it, owner, keyId) }
                }
            }
            return ok(json("failures" to json()))
        }
        if (path.endsWith("/keys/query")) {
            val asked = body["device_keys"].obj()?.keys?.toList().orEmpty()
            return ok(synchronized(lock) { keysQuery(asked) })
        }
        if (path.endsWith("/keys/claim")) return ok(json("one_time_keys" to json(), "failures" to json()))
        if (path.endsWith("/room_keys/version") || "/room_keys/version/" in path) {
            user ?: return UNKNOWN_TOKEN
            return when (method) {
                "POST" -> ok(json("version" to synchronized(lock) { backups.getOrPut(user) { mutableListOf() }.run { add(body); size.toString() } }))
                "DELETE", "PUT" -> ok(json())
                else -> {
                    val (latest, count) = synchronized(lock) { backups[user].orEmpty().let { it.lastOrNull()?.copy() to it.size } }
                    latest ?: return NOT_FOUND
                    latest["version"] = count.toString()
                    latest["count"] = 0
                    latest["etag"] = "0"
                    ok(latest)
                }
            }
        }
        if ("/room_keys/keys" in path) return ok(json("count" to 0, "etag" to "0"))
        if ("/profile/" in path) return ok(json())
        if (path.endsWith("/pushrules/") || path.endsWith("/pushrules")) return ok(json("global" to json()))
        if (path.endsWith("/pushers/set") || "/sendToDevice/" in path) return ok(json())
        if ("/capabilities" in path) return ok(json("capabilities" to json()))
        return UNRECOGNIZED
    }

    /** Подписи подписанного объекта — к хранимой копии. Объект назван ID устройства или самим ключом. */
    private fun addSignatures(signed: JSON, owner: String, keyId: String) {
        val signatures = signed["signatures"].obj() ?: return
        fun merge(stored: JSON) {
            val existing = stored["signatures"].obj() ?: json().also { stored["signatures"] = it }
            for ((signer, keys) in signatures) {
                val mine = existing[signer].obj() ?: json().also { existing[signer] = it }
                mine.putAll(keys.obj().orEmpty())
            }
        }
        deviceKeys[owner]?.get(keyId)?.let { return merge(it) }
        for (key in crossSigning[owner]?.values.orEmpty()) {
            if (keyId in JsonValues.encode(key)) merge(key)
        }
    }

    /** Ответ на `/keys/query` о тех, о ком спросили. */
    private fun keysQuery(users: List<String>): JSON {
        val devicesOut = json()
        val masters = json()
        val selfSigning = json()
        val userSigning = json()
        for (user in users) {
            devicesOut[user] = json(*deviceKeys[user].orEmpty().map { (id, keys) -> id to keys }.toTypedArray())
            val keys = crossSigning[user].orEmpty()
            keys["master_key"]?.let { masters[user] = it }
            keys["self_signing_key"]?.let { selfSigning[user] = it }
            keys["user_signing_key"]?.let { userSigning[user] = it }
        }
        return json(
            "device_keys" to devicesOut,
            "master_keys" to masters,
            "self_signing_keys" to selfSigning,
            "user_signing_keys" to userSigning,
            "failures" to json(),
        )
    }

    /**
     * Sliding sync для аккаунта без комнат. Каждому списку — счёт ноль: это и говорит списку
     * комнат, что он загрузился, а не не начинал. Запрос с позицией и таймаутом держится этот
     * таймаут: это long-poll, о котором нечего сказать, и мгновенный ответ гонял бы SDK по кругу.
     */
    private suspend fun sync(request: HttpRequest, query: Map<String, String>): HttpResponse {
        val timeout = query["timeout"]?.toLongOrNull() ?: 0
        if (query["pos"] != null && timeout > 0) delay(minOf(timeout, MAX_POLL_MS))
        val body = JsonValues.decodeObject(request.body.decodeToString())
        val lists = json(*body["lists"].obj().orEmpty().keys.map { it to json("count" to 0) }.toTypedArray())
        val position = (query["pos"]?.toIntOrNull() ?: 0) + 1
        return ok(json("pos" to position.toString(), "lists" to lists, "rooms" to json(), "extensions" to json()))
    }

    // MARK: Пароль и старый SSO

    private fun loginFlows(): HttpResponse {
        val flows = mutableListOf<JSON>()
        if (configuration.offersPassword) flows += json("type" to "m.login.password")
        if (configuration.offersLegacySso) {
            flows += json("type" to "m.login.sso", "identity_providers" to emptyList<Any>())
            flows += json("type" to "m.login.token")
        }
        return ok(json("flows" to flows))
    }

    private fun login(raw: ByteArray): HttpResponse {
        val request = JsonValues.decodeObject(raw.decodeToString())
        val user = when (request["type"].str()) {
            "m.login.password" -> {
                if (!configuration.offersPassword) return error(400, "M_UNKNOWN", "Unsupported login type")
                val typed = request["identifier"].obj()?.get("user").str() ?: request["user"].str().orEmpty()
                val name = localPart(typed)
                val expected = synchronized(lock) { accountsByName[name] }
                if (expected == null || expected != request["password"].str()) return FORBIDDEN
                userId(name)
            }
            "m.login.token" -> {
                if (!configuration.offersLegacySso) return error(400, "M_UNKNOWN", "Unsupported login type")
                val code = request["token"].str()
                synchronized(lock) { code?.let(codes::remove) } ?: return FORBIDDEN
            }
            null -> return BAD_JSON
            else -> return error(400, "M_UNKNOWN", "Unsupported login type")
        }
        val device = request["device_id"].str() ?: mint("MOCK", 6)
        return ok(json("user_id" to user, "access_token" to issueToken(user, device), "device_id" to device, "home_server" to configuration.serverName))
    }

    // MARK: OIDC

    private fun authMetadata(): HttpResponse = ok(
        json(
            "issuer" to "$base/",
            "authorization_endpoint" to "$base/_mock/oauth/authorize",
            "token_endpoint" to "$base/_mock/oauth/token",
            "registration_endpoint" to "$base/_mock/oauth/register",
            "revocation_endpoint" to "$base/_mock/oauth/revoke",
            "jwks_uri" to "$base/_mock/oauth/jwks",
            "account_management_uri" to "$base/_mock/account",
            "response_types_supported" to listOf("code"),
            "response_modes_supported" to listOf("query", "fragment"),
            "grant_types_supported" to listOf("authorization_code", "refresh_token"),
            "code_challenge_methods_supported" to listOf("S256"),
            "token_endpoint_auth_methods_supported" to listOf("none"),
            "prompt_values_supported" to if (configuration.offersRegistration) listOf("create") else emptyList(),
        ),
    )

    /** Сюда ведёт кнопка страницы. Проверяет пароль — или заводит аккаунт — и отправляет браузер домой с кодом. */
    private fun approve(query: Map<String, String>): HttpResponse {
        val redirect = query["redirect_uri"]?.takeIf { it.isNotEmpty() } ?: return BAD_JSON
        val name = localPart(query["username"].orEmpty())
        val password = query["password"].orEmpty()
        val flow = Page.entries.firstOrNull { it.wire == query["flow"] } ?: Page.SIGN_IN
        if (name.isEmpty()) return page(flow, query, problem = "Имя не может быть пустым.")

        val accepted = synchronized(lock) {
            when (flow) {
                Page.REGISTER -> (accountsByName[name] == null).also { free -> if (free) accountsByName[name] = password }
                Page.SIGN_IN, Page.LEGACY_SSO -> accountsByName[name] == password
            }
        }
        if (!accepted) {
            return page(flow, query, problem = if (flow == Page.REGISTER) "Имя «$name» уже занято." else "Неверное имя или пароль.")
        }

        val code = mint("code_", 16)
        val device = query["scope"]?.let(::deviceInScope)
        synchronized(lock) {
            codes[code] = userId(name)
            if (device != null) codeDevices[code] = device
        }
        val added = if (flow == Page.LEGACY_SSO) {
            listOf("loginToken" to code)
        } else {
            listOfNotNull("code" to code, query["state"]?.let { "state" to it })
        }
        val separator = if ('?' in redirect) "&" else "?"
        val location = redirect + separator + added.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return HttpResponse(302, mapOf("Location" to location))
    }

    private fun token(form: Map<String, String>): HttpResponse {
        val (user, device) = when (form["grant_type"]) {
            "authorization_code" -> {
                val code = form["code"].orEmpty()
                synchronized(lock) { codes.remove(code) to codeDevices.remove(code) }
            }
            "refresh_token" -> synchronized(lock) { codes.remove(form["refresh_token"].orEmpty()) } to null
            else -> return oauthError("unsupported_grant_type")
        }
        user ?: return oauthError("invalid_grant")
        val access = issueToken(user, device ?: mint("MOCK", 6))
        val refresh = mint("refresh_", 16)
        synchronized(lock) { codes[refresh] = user }
        return ok(
            json(
                "access_token" to access,
                "refresh_token" to refresh,
                "token_type" to "Bearer",
                "expires_in" to 3600,
                "scope" to form["scope"].orEmpty(),
            ),
        )
    }

    // MARK: Страница в браузере

    private enum class Page(val wire: String) { SIGN_IN("signIn"), REGISTER("register"), LEGACY_SSO("legacySSO") }

    private fun page(flow: Page, query: Map<String, String>, problem: String? = null): HttpResponse {
        val (title, button) = when (flow) {
            Page.REGISTER -> "Новый аккаунт на ${configuration.serverName}" to "Создать"
            Page.SIGN_IN, Page.LEGACY_SSO -> "Вход на ${configuration.serverName}" to "Войти"
        }
        // Всё, что нужно шагу подтверждения, едет через форму: сервер не держит состояния браузера,
        // и страницу можно перезагрузить, ничего не сломав.
        val carried = query.toMutableMap()
        carried["flow"] = flow.wire
        if (flow == Page.LEGACY_SSO) query["redirectUrl"]?.let { redirect ->
            carried["redirect_uri"] = redirect
            carried.remove("redirectUrl")
        }
        carried.remove("username")
        carried.remove("password")
        val hidden = carried.entries.sortedBy { it.key }
            .joinToString("\n") { (key, value) -> """<input type="hidden" name="${escape(key)}" value="${escape(value)}">""" }
        val suggestion = if (flow == Page.REGISTER) "" else configuration.accounts.keys.minOrNull().orEmpty()
        val note = problem?.let { """<p class="problem">${escape(it)}</p>""" }.orEmpty()
        val html = """
            <!doctype html>
            <html lang="ru"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escape(title)}</title>
            <style>
            :root { color-scheme: light dark; }
            body { margin: 0; padding: 32px 16px; font-family: system-ui, sans-serif; }
            main { max-width: 360px; margin: 0 auto; }
            h1 { font-size: 22px; } .tag { font-size: 13px; opacity: .6; }
            input:not([type=hidden]) { display: block; box-sizing: border-box; width: 100%; font-size: 17px;
              padding: 12px; margin: 8px 0; border-radius: 12px; border: 1px solid #8884; }
            button { width: 100%; font-size: 17px; padding: 14px; border-radius: 14px; border: 0;
              background: #f60; color: #fff; margin-top: 8px; }
            .problem { color: #d33; }
            </style></head><body><main>
            <p class="tag">Тестовый сервер Iskra — всё это понарошку</p>
            <h1>${escape(title)}</h1>
            $note
            <form method="get" action="/_mock/oauth/approve">
            $hidden
            <input name="username" placeholder="Имя" autocapitalize="none" autocorrect="off" value="${escape(suggestion)}">
            <input name="password" type="password" placeholder="Пароль">
            <button type="submit">$button</button>
            </form>
            </main></body></html>
        """.trimIndent()
        return HttpResponse(200, mapOf("Content-Type" to "text/html; charset=utf-8"), html.toByteArray())
    }

    // MARK: Помощники

    private fun issueToken(user: String, device: String): String {
        val token = mint("mock_", 24)
        synchronized(lock) {
            tokens[token] = user
            devices[token] = device
        }
        return token
    }

    private fun userId(name: String) = "@$name:${configuration.serverName}"

    internal companion object {
        private const val MAX_POLL_MS = 30_000L
        private val CROSS_SIGNING_KINDS = listOf("master_key", "self_signing_key", "user_signing_key")
        private val random = SecureRandom()

        private val UNRECOGNIZED = error(404, "M_UNRECOGNIZED", "Not implemented by the mock server")
        private val NOT_FOUND = error(404, "M_NOT_FOUND", "Nothing here")
        private val FORBIDDEN = error(403, "M_FORBIDDEN", "Invalid username or password")
        private val UNKNOWN_TOKEN = error(401, "M_UNKNOWN_TOKEN", "Unknown access token")
        private val BAD_JSON = error(400, "M_BAD_JSON", "Could not read that")

        /** «alice», «@alice» и «@alice:iskra.test» — всё Алиса. */
        fun localPart(typed: String): String = typed.trim().lowercase().removePrefix("@").substringBefore(':')

        /**
         * OIDC называет устройство в scope: `urn:matrix:org.matrix.msc2967.client:device:ABCDEF` или
         * стабильное `urn:matrix:client:device:ABCDEF`.
         */
        fun deviceInScope(scope: String): String? =
            scope.split(' ').firstOrNull { ":device:" in it }?.substringAfterLast(':')

        fun split(target: String): Pair<String, Map<String, String>> {
            val path = target.substringBefore('?')
            val query = if ('?' in target) parseQuery(target.substringAfter('?')) else emptyMap()
            return path to query
        }

        private fun parseQuery(text: String): Map<String, String> = text.split('&').filter { it.isNotEmpty() }.associate { pair ->
            decode(pair.substringBefore('=')) to (if ('=' in pair) decode(pair.substringAfter('=')) else "")
        }

        private fun form(body: ByteArray): Map<String, String> = parseQuery(body.decodeToString())

        private fun decode(text: String): String = URLDecoder.decode(text, "UTF-8")

        private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

        private fun bearer(request: HttpRequest): String? =
            request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")

        private fun mint(prefix: String, length: Int): String =
            prefix + String(CharArray(length) { ('A' + random.nextInt(26)) })

        private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun body(value: Any?) = JsonValues.encode(value).toByteArray()

        fun ok(value: JSON, status: Int = 200) = HttpResponse(status, mapOf("Content-Type" to "application/json"), body(value))

        private fun raw(bytes: ByteArray) = HttpResponse(200, mapOf("Content-Type" to "application/json"), bytes)

        fun error(status: Int, code: String, message: String) = ok(json("errcode" to code, "error" to message), status)

        private fun oauthError(code: String) = ok(json("error" to code), 400)
    }
}
