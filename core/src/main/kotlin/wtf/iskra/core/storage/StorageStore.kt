package wtf.iskra.core.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.SyncService
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Что приложение хранит на этом устройстве. Три вида, потому что три хранилища отвечают на три разных вопроса — и разница между ними
 * нужна тому, кто решает, нажимать ли «очистить». Потерять кэш стоит загрузки. Потерять поисковый индекс стоит пересборки. Потерять данные
 * сеанса стоит личности этого устройства в комнате, поэтому здесь ничего не предлагает этого сделать.
 */
enum class StorageKind {
    /** Криптохранилище, состояние комнат, очередь отправки. Не одноразовое: ключи, которыми это устройство расшифровывает, и сообщения, что ждут отправки. */
    SESSION,

    /** События и медиа, что SDK скачал. Одноразово по замыслу: SDK соберёт их заново с сервера — поэтому кнопка чистит именно его. */
    CACHE,

    /** Локальный полнотекстовый индекс. Тоже одноразов, но собирается *повторным чтением* сообщений, а не запросом к серверу. */
    SEARCH,
}

/** Сколько места занимает один из них. */
data class StorageAmount(val kind: StorageKind, val bytes: Long)

/**
 * Что приложение держит на устройстве и единственный способ вернуть часть места. Порт `StorageStore`.
 *
 * Свой на экран, а не принадлежит сеансу: больше он никому не нужен, а ответ устаревает сразу — SDK пишет в эти хранилища непрерывно.
 */
class StorageStore internal constructor(
    private val client: Client,
    /** Запущенная синхронизация или, если её нет, новая нестартовавшая: `clearCaches` обязан получить сервис. */
    private val syncService: suspend () -> SyncService?,
    private val locations: Map<StorageKind, File>,
) {
    data class State(
        val amounts: List<StorageAmount> = emptyList(),
        val isMeasuring: Boolean = false,
        val isClearing: Boolean = false,
    ) {
        /** Всё, включая то, чего кнопка не тронет. */
        val total: Long get() = amounts.sumOf { it.bytes }

        /**
         * Сколько вернёт очистка кэша — на кнопке, потому что «очистить кэш» без числа просит действия, единственное видимое следствие
         * которого — что фото придётся качать заново. Индекс тоже считается, раз кнопка его берёт: число без него было бы меньше на вес слов
         * каждого сообщения, а этот экран не должен преуменьшать то, что собирается удалить.
         */
        val reclaimable: Long get() = amounts.filter { it.kind == StorageKind.CACHE || it.kind == StorageKind.SEARCH }.sumOf { it.bytes }

        /** Сколько весит один индекс — число на кнопке, что чистит только его. */
        val searchIndexBytes: Long get() = amounts.firstOrNull { it.kind == StorageKind.SEARCH }?.bytes ?: 0L
    }

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    /**
     * Складывает то, что на диске. Не на главном потоке — не потому, что несколько каталогов обходить долго: медиакэш живого аккаунта — тысячи
     * файлов, и обход — по одному `stat` на каждый.
     */
    suspend fun measure() {
        mutable.update { it.copy(isMeasuring = true) }
        try {
            val measured = withContext(Dispatchers.IO) {
                StorageKind.entries.mapNotNull { kind -> locations[kind]?.let { StorageAmount(kind, size(it)) } }
            }
            mutable.update { it.copy(amounts = measured) }
        } finally {
            mutable.update { it.copy(isMeasuring = false) }
        }
    }

    /**
     * Выбрасывает кэш — средствами SDK, а не руками. Нарочно `Client.clearCaches`, а не удаление каталога: приложение *работает*, пока это
     * нажато, клиент держит эти SQLite-файлы открытыми и цикл синхронизации в них пишет.
     *
     * **Клиент, на котором это шло, после этого кончен, и вызвавший обязан его заменить.** SDK так и говорит: клиент останется в неустойчивом
     * состоянии, и звавший должен создать новый. Поэтому экран отдаёт приложение `restore()`, а тот строит клиент заново по сеансу из хранилища
     * секретов. Выхода нет: токены и ключи нетронуты, ушло только то, что можно скачать снова.
     *
     * А потом уходит сам каталог — не про запас, а единственное, что возвращает место: `clearCaches` опустошает *таблицы*, SQLite освобождает
     * страницы внутри своего файла и ничего не отдаёт файловой системе. Вакуумировать нельзя — клиент, что держит соединение, нарочно мёртв.
     * Это тот самый момент, когда удалить каталог безопасно: синхронизация остановлена, клиент негоден, а новый, что встанет, создаст каталог заново.
     *
     * **Индекс уходит вместе с ним, и это не уборка, а смысл.** Индекс — собственная копия слов каждого виденного сообщения, `clearCaches` его не
     * трогает. Он снимает сообщение, когда видит удаление, но для этого должен найти исходное событие в кэше событий, а тот только что опустошён.
     * Очистить кэш и оставить индекс — единственное сочетание, что оставляет на телефоне слова удалённых сообщений.
     */
    suspend fun clearCache() {
        mutable.update { it.copy(isClearing = true) }
        try {
            val service = syncService() ?: throw IllegalStateException("sync is not running")
            withTimeLimit(LIMIT) { client.clearCaches(service) }
            withContext(Dispatchers.IO) {
                for (kind in listOf(StorageKind.CACHE, StorageKind.SEARCH)) locations[kind]?.deleteRecursively()
            }
            measure()
        } finally {
            mutable.update { it.copy(isClearing = false) }
        }
    }

    /**
     * Выбрасывает один поисковый индекс, а кэш оставляет. Единственное, что в приложении способно забрать слова удалённого сообщения: SDK
     * снимает сообщение, когда видит удаление, — но только если ещё держит исходное событие, а для удалённого спустя долго — не держит.
     * Документ по одному не удалить: индекс целиком — наименьшее, что можно убрать.
     *
     * **Клиент после этого кончен, и вызвавший обязан его заменить** — не потому, что так говорит SDK (`clearCaches` не зовётся), а потому, что
     * хранилище выдернуто из-под клиента, у которого оно открыто. Синхронизация остановлена первой — это и важно: SDK пишет в индекс по мере
     * прихода событий, а каталог, удалённый из-под живого индексатора, — так половина индекса записывается обратно в дыру. Остановить, удалить,
     * отдать приложение `restore()`.
     */
    suspend fun clearSearchIndex() {
        mutable.update { it.copy(isClearing = true) }
        try {
            val service = syncService()
            runCatching { withTimeLimit(LIMIT) { service?.stop() } }
            val index = locations[StorageKind.SEARCH]
            if (index != null) {
                val removed = withContext(Dispatchers.IO) { !index.exists() || index.deleteRecursively() }
                if (!removed) {
                    // Ничего не очищено, значит и нового клиента никто не ждёт — а цикл, остановленный выше, надо вернуть, или список чатов
                    // замёрзнет, пока приложение не убьют.
                    runCatching { withTimeLimit(LIMIT) { service?.start() } }
                    throw java.io.IOException("the search index could not be removed")
                }
            }
            measure()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "индекс не очистился: ${Log.describe(error)}")
            throw error
        } finally {
            mutable.update { it.copy(isClearing = false) }
        }
    }

    companion object {
        private val LIMIT = 60.seconds

        /**
         * Размер дерева каталога, в байтах на диске. Файл, который не удалось прочесть, считается нулём, а не отказом всего ответа: экран
         * хранилища, что говорит «недоступно», потому что один файл переехал во время чтения, хуже того, что промахнулся на блок.
         */
        internal fun size(directory: File): Long {
            if (!directory.exists()) return 0L
            var total = 0L
            directory.walkTopDown().forEach { file -> if (file.isFile) total += runCatching { file.length() }.getOrDefault(0L) }
            return total
        }
    }
}
