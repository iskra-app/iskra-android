package wtf.iskra.core.session

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Сеансы, которые это устройство уже видело на аккаунте, — чтобы о том, которого не видело, можно было спросить: «Новый вход. Это были вы?» у Telegram,
 * вверху списка чатов. Порт `KnownDevices`.
 *
 * У Matrix нет события «кто-то вошёл в ваш аккаунт»: новый сеанс просто оказывается там в следующий раз, когда список прочитан. Поэтому список читается
 * (на старте и на каждом возвращении на передний план) и сверяется с записанными здесь. Первое чтение аккаунта записывает всё и ни о чём не спрашивает:
 * сеансы, что были у человека до появления баннера, — не новость.
 *
 * Сеанс помнится по ID **и** имени, а не по одному ID. Matrix даёт клиенту выбрать свой `device_id` при входе, а вход с уже существующим ID заменяет тот
 * сеанс — тот, у кого есть пароль и кто взял известный ID, вошёл бы без вопроса. Теперь ему нужно повторить и имя, а у того, чей сеанс заменили, устройство
 * разлогинилось, и это замечают. Переименованный человеком сеанс спросят снова — это правильная сторона для ошибки.
 *
 * Чего не видно: сеанса, что уже был при первом взгляде, — цена того, чтобы не кричать «волки» над каждым устройством, что было до баннера. «Устройства» их
 * перечисляют все. Хранится по аккаунту: ID и имена устройств не секрет (сервер отдаёт их каждому сеансу аккаунта) и принадлежат памяти этого устройства
 * об аккаунте, а не аккаунту.
 */
class KnownDevices(private val store: Store) {
    /** Где лежит память: настоящая — `SharedPreferences`, у теста — в памяти. */
    interface Store {
        fun get(account: String): Set<String>?
        fun put(account: String, fingerprints: Set<String>?)
    }

    constructor(context: Context) : this(PreferencesStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)))

    /** Сеансы, о которых помним для этого аккаунта; null — на него ни разу не смотрели. */
    internal fun known(account: String): Set<String>? = store.get(account)

    /** Забирает с собой память вышедшего аккаунта. */
    fun forget(account: String) = store.put(account, null)

    /**
     * О чём спрашивать, при списке от сервера: каждый сеанс, что не этот и не известен. Записывает ответ на «что есть» по ходу — ушедший сеанс забывается,
     * а на самом первом взгляде известно всё и не спрашивается ничего.
     */
    fun newSessions(devices: List<AccountDevice>, account: String): List<AccountDevice> {
        val listed = devices.map(::fingerprint).toSet()
        val known = store.get(account)
        if (known == null) {
            store.put(account, listed)
            return emptyList()
        }
        val stillThere = known.intersect(listed)
        if (stillThere != known) store.put(account, stillThere)
        return devices.filter { !it.isCurrent && fingerprint(it) !in stillThere }
    }

    fun confirm(device: AccountDevice, account: String) {
        store.put(account, (store.get(account) ?: emptySet()) + fingerprint(device))
    }

    private class PreferencesStore(private val preferences: android.content.SharedPreferences) : Store {
        override fun get(account: String): Set<String>? = preferences.getStringSet(KEY + account, null)?.toSet()

        override fun put(account: String, fingerprints: Set<String>?) {
            preferences.edit().apply { if (fingerprints == null) remove(KEY + account) else putStringSet(KEY + account, fingerprints) }.apply()
        }
    }

    companion object {
        private const val FILE = "iskra.devices"
        private const val KEY = "devices.known."

        /** Как сеанс помнится: ID и имя вместе — см. заметку к типу. */
        internal fun fingerprint(device: AccountDevice): String = device.id + "\u001F" + (device.name ?: "")
    }
}

/**
 * Сеансы аккаунта, которых это устройство не видело, — для баннера вверху списка чатов. «Да, это был я» записывает один; «Нет» — дело экрана «Устройства»:
 * завершение сеанса может потребовать пароль или страницу сервера, и этот путь живёт при [DevicesStore]. Порт `NewSignInsStore`.
 */
class NewSignInsStore internal constructor(
    private val source: AccountDevices,
    private val account: String,
    private val known: KnownDevices,
) {
    private val mutable = MutableStateFlow<List<AccountDevice>>(emptyList())

    /** Самые новые первыми — порядок самого списка, по тому, как недавно каждого видели. */
    val sessions: StateFlow<List<AccountDevice>> = mutable.asStateFlow()

    @Volatile
    private var isChecking = false

    /**
     * Читает список снова. Тихо: список, что не пришёл, не стоит слова здесь — баннер любезность, а «Устройства» скажут как следует, когда за ними
     * придут.
     */
    suspend fun check() {
        if (isChecking) return
        isChecking = true
        try {
            val devices = try {
                source.list()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return
            }
            mutable.value = known.newSessions(devices, account)
        } finally {
            isChecking = false
        }
    }

    fun confirm(id: String) {
        val device = mutable.value.firstOrNull { it.id == id } ?: return
        known.confirm(device, account)
        mutable.update { list -> list.filterNot { it.id == id } }
    }

    /** Сеанс завершён из листа баннера: он больше не новость и не известен — его больше нет. */
    fun ended(id: String) = mutable.update { list -> list.filterNot { it.id == id } }

    /** Стор для завершения одного из них, над тем же источником. */
    fun devicesStore(): DevicesStore = DevicesStore(source)
}
