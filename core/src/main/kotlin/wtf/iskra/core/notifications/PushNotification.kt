package wtf.iskra.core.notifications

/**
 * Один пуш, расшифрованный, словами, которые человек прочтёт. Порт `PushNotification.swift`.
 *
 * Пуш Matrix не несёт сообщения: в нём ID комнаты и ID события, и каждое слово на экране блокировки
 * достаётся и расшифровывается на устройстве — сервер не смог бы написать это предложение, даже если
 * бы захотел: в зашифрованной комнате он его не читает. Простые значения, уже на языке приложения, без
 * единого типа SDK: показывает это `:features`, а `:core` не отдаёт SDK наверх.
 */
data class PushNotification(
    val roomId: String,
    val eventId: String,
    /** Верхняя строка: имя чата — кроме личного, где чат *и есть* человек и имя уже его. */
    val title: String,
    /** Кто сказал, когда заголовок — комната с людьми. Null в личном: второй раз то же имя ничего не даёт. */
    val sender: String?,
    val body: String,
    /** Просили ли правила пушей человека звук. Читается из правил, а не решается здесь. */
    val isNoisy: Boolean,
    /**
     * Кто сказал — именем, *всегда*, и в личном чате тоже, где [sender] намеренно null. Это разные
     * вопросы: [sender] — «что на второй строке», а это — «чьё сообщение»; уведомлению с лицом нужен
     * ответ, даже когда писать его негде.
     */
    val personName: String? = null,
    /** Matrix ID отправителя: то, по чему система понимает, что двадцать сообщений — от *одного* человека. */
    val personId: String? = null,
    /** Аватар отправителя адресом `mxc://`, не байтами: маппер — чистая функция и ничего не качает. */
    val avatarUrl: String? = null,
    /**
     * Аватар, скачанный и расшифрованный. Null, когда не успел, — и это не ошибка: уведомление без
     * картинки — уведомление, а опоздавшее из-за картинки — нет.
     */
    val avatar: ByteArray? = null,
    /** Групповой ли это разговор — для `MessagingStyle.isGroupConversation`. */
    val isGroup: Boolean = sender != null,
    /** Приглашение в чат, а не сообщение: свой канал, и ответить на него нечем. */
    val isInvitation: Boolean = false,
    /** Упомянули ли читателя (или весь чат) — у упоминаний свой канал, громче остальных. */
    val isMention: Boolean = false,
    /** Чей пуш — ID сессии аккаунта, расшифровавшего его. Ставит [PushDecoder]; нажатие ведёт в этот аккаунт. */
    val accountId: String? = null,
) {
    // ByteArray сравнивается по ссылке; для тестов и замены уведомления важно содержимое.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushNotification) return false
        return roomId == other.roomId && eventId == other.eventId && title == other.title &&
            sender == other.sender && body == other.body && isNoisy == other.isNoisy &&
            personName == other.personName && personId == other.personId && avatarUrl == other.avatarUrl &&
            avatar.contentEquals(other.avatar) && isGroup == other.isGroup &&
            accountId == other.accountId && isMention == other.isMention &&
            isInvitation == other.isInvitation
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + eventId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        return result
    }
}

/**
 * Что делать с пришедшим пушем. Порт `PushOutcome`.
 *
 * Разница между двумя последними ответами и есть та, что видна на экране блокировки. Пуш, который мы
 * *решили* не показывать, — система работает, и не появляется ничего. Пуш, который мы *не смогли
 * прочесть*, — сбой, и что-то появиться должно: «Новое сообщение», потому что сообщение, пришедшее
 * молча, хуже сообщения, описанного плохо.
 */
sealed interface PushOutcome {
    /** Расшифрован и стоит того, чтобы разбудить. */
    data class Show(val notification: PushNotification) : PushOutcome

    /** Расшифрован и намеренно не показан. */
    data class Silence(val reason: SilenceReason) : PushOutcome

    /**
     * На устройстве никто не вошёл в этот аккаунт: нет стора и нет ключей. Отдельно от [Failed]: пуш
     * после выхода — обычная гонка, не поломка.
     */
    data object NotSignedIn : PushOutcome

    /**
     * До события не дотянуться или его не прочесть. [timedOut] — не успели в бюджет: тогда стоит
     * попробовать ещё раз, дольше, и поставить настоящее уведомление на место заглушки.
     */
    data class Failed(val reason: String, val timedOut: Boolean = false) : PushOutcome
}

/** Почему чисто расшифрованный пуш всё равно не показан. Ни одна причина не ошибка — каждая правило. */
enum class SilenceReason {
    /** Правила пушей человека сказали «нет», или отправитель в игноре. Заглушённый чат — здесь. */
    FILTERED_OUT,

    /** Забрали раньше, чем мы дошли. Показать сейчас — отменить удаление ровно на важный зазор. */
    REDACTED,

    /** Не повод будить телефон: реакция на чужое, переговоры звонка, шаг подтверждения ключей. */
    NOT_WORTH_WAKING,
}
