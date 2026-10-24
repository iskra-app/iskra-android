package wtf.iskra.core.session

/**
 * Как сервер готов пустить человека. Порт `SignInOptions` из `AuthenticationService.swift`.
 *
 * У Matrix три ответа на этот вопрос, и человеку не нужно знать, какой достался: оба вида SSO для
 * него выглядят одинаково — «Продолжить в браузере», — а пароль — это форма.
 */
data class SignInOptions(
    /** OAuth 2.0 / OIDC, как у Matrix Authentication Service. */
    val supportsOAuth: Boolean,
    /** Старый SSO с переходом на страницу. */
    val supportsLegacySso: Boolean,
    val supportsPassword: Boolean,
    /**
     * Даст ли сервер завести аккаунт. Ответ сервера, а не догадка: OIDC перечисляет prompts, и
     * `create` значит именно это. Без OIDC — никак: в FFI нет ни `register`, ни UIA, и кнопки нет.
     */
    val supportsRegistration: Boolean,
    /** Адрес, который на самом деле нашёлся: «example.com» может стать «https://matrix.example.com». */
    val resolvedHomeserverUrl: String,
) {
    val canUseSingleSignOn: Boolean get() = supportsOAuth || supportsLegacySso
}

/** Почему не вышло. Каждый случай — отдельный совет человеку, поэтому они не сливаются. */
sealed class AuthenticationError(cause: Throwable? = null) : Exception(cause) {
    /** По этому адресу не нашёлся сервер Matrix. Совет — проверить, что напечатано. */
    class HomeserverNotFound : AuthenticationError()

    /** Нашлось, у кого спросить, но он не ответил. Совет другой: дело в сети или в сервере. */
    class HomeserverUnreachable : AuthenticationError()

    /**
     * Не открылся зашифрованный стор или хранилище секретов. Отдельно от «не найден» намеренно:
     * иначе беда с Keystore дошла бы до человека как «проверьте адрес», а это неправда.
     */
    class StorageUnavailable(cause: Throwable) : AuthenticationError(cause)

    /**
     * Сеанс цел, сети нет. Только при *восстановлении*: человек в поезде — не тот, кого разлогинили,
     * и показывать ему экран входа нельзя.
     */
    class NetworkUnavailable : AuthenticationError()

    /** Сервер не предлагает ни одного способа войти, который мы умеем. */
    class NoSupportedSignInMethod : AuthenticationError()

    /**
     * Мост включён, адрес указан, а туннель не поднялся. Отказ, и нарочно не откат: пока за
     * переключателем ничего не было, прямое соединение ничего не стоило. Теперь включённый мост — это
     * решение о том, кто видит трафик, и тихо пойти напрямую значило бы сказать человеку, что он
     * защищён, когда это не так (docs/PROMPT.md, 9.5).
     */
    class BridgeUnavailable(cause: Throwable?) : AuthenticationError(cause)

    /**
     * Мост на месте и ответил, и не повезёт это устройство к этому серверу: нет в списке, не Matrix, не
     * 443, не в публичном интернете. Отдельный случай, потому что SDK назвал бы это «сервер не найден», и
     * совет проверить адрес был бы неверен.
     */
    class BridgeRefused(val refusal: wtf.iskra.core.transport.nostr.BridgeRefusal) : AuthenticationError()

    /** `startSignIn` не вызывали, или попытку бросили. */
    class NoSignInInProgress : AuthenticationError()

    /** Этот аккаунт уже на устройстве: два экземпляра одного человека — это вдвое каждое сообщение. */
    class AlreadySignedIn : AuthenticationError()

    /**
     * Сервер больше не принимает токены этого аккаунта: сеанс завершили с другого устройства, или
     * аккаунта нет. Только при восстановлении, и единственная причина честно попросить войти снова.
     */
    class SessionEnded : AuthenticationError()

    /** Ошибка SDK, которой нет имени выше. */
    class Sdk(cause: Throwable) : AuthenticationError(cause)
}
