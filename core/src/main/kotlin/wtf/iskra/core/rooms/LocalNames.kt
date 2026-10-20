package wtf.iskra.core.rooms

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Имена, которые личным чатам дали на этом устройстве: «Мама» вместо `anna_1987`. Порт `LocalNames.swift`.
 *
 * Только личные чаты: имя группы общее для всех в ней, и частная версия у одного — способ двум людям
 * перестать понимать, о какой комнате речь. Не синхронизируется, не секрет, у каждого аккаунта своя
 * таблица (`rooms.localNames.<сессия>`, как на iOS), и выход из аккаунта её забывает.
 *
 * Читают список чатов, заголовок и строки переписки, поиск и **пуш** — уведомление с прозвищем там, где
 * человек его не исправит, иначе обещание «только так и зовётся» нарушалось бы ровно на экране блокировки.
 */
class LocalNames(private val storage: Storage) {
    /** Где лежат таблицы. На устройстве — `SharedPreferences`, в тестах — память. */
    interface Storage {
        fun read(key: String): String?
        fun write(key: String, value: String?)
    }

    /** Чьё и какой чат переименовали. */
    data class Change(val account: String, val roomId: String)

    private val mutableChanges = MutableSharedFlow<Change>(extraBufferCapacity = CHANGES_BUFFER)

    /** Объявляется только о **настоящей** перемене: тот же ответ второй раз — не новость. */
    val changes: SharedFlow<Change> = mutableChanges.asSharedFlow()

    /** Прозвище чата; null — его нет (пустое и из пробелов — тоже нет). */
    fun name(account: String, roomId: String): String? = table(account)[roomId]?.trim()?.ifEmpty { null }

    /** Дать или снять прозвище. Пустое и из пробелов — снять. */
    @Synchronized
    fun set(account: String, roomId: String, name: String?) {
        val trimmed = name?.trim().orEmpty()
        val table = table(account).toMutableMap()
        val previous = table[roomId]
        if (trimmed.isEmpty()) table.remove(roomId) else table[roomId] = trimmed
        if (previous == table[roomId]) return
        storage.write(key(account), JsonObject(table.mapValues { JsonPrimitive(it.value) }).toString())
        mutableChanges.tryEmit(Change(account, roomId))
    }

    /** Аккаунт вышел — его прозвища уходят вместе с ним. */
    fun forget(account: String) = storage.write(key(account), null)

    /** Прозвища одного аккаунта — то, что получает сеанс. */
    fun of(account: String): Account = Account(this, account)

    class Account internal constructor(private val names: LocalNames, val account: String) {
        fun name(roomId: String): String? = names.name(account, roomId)
        fun set(roomId: String, name: String?) = names.set(account, roomId, name)

        /** Какие чаты этого аккаунта переименовали. */
        val changes: Flow<String> = names.changes.filter { it.account == account }.map { it.roomId }
    }

    private fun table(account: String): Map<String, String> {
        val stored = storage.read(key(account)) ?: return emptyMap()
        val parsed = runCatching { Json.parseToJsonElement(stored).jsonObject }.getOrNull() ?: return emptyMap()
        return parsed.mapNotNull { (room, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { room to it } }.toMap()
    }

    private fun key(account: String) = "$PREFIX.$account"

    companion object {
        private const val PREFIX = "rooms.localNames"
        private const val FILE = "iskra.localNames"
        private const val CHANGES_BUFFER = 16

        fun of(context: Context): LocalNames {
            val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return LocalNames(object : Storage {
                override fun read(key: String) = preferences.getString(key, null)
                override fun write(key: String, value: String?) {
                    preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
                }
            })
        }
    }
}
