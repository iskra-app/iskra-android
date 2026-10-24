package wtf.iskra.core.search

import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.SearchService
import org.matrix.rustcomponents.sdk.SearchServiceResult
import org.matrix.rustcomponents.sdk.SearchServiceResultsListener
import org.matrix.rustcomponents.sdk.SearchServiceResultsUpdate
import uniffi.matrix_sdk_ui.SearchServicePaginationState
import wtf.iskra.core.rooms.MessagePreview
import wtf.iskra.core.support.Strings
import java.util.concurrent.atomic.AtomicReference

/**
 * Запрос к локальному индексу SDK — порт `MessageSearch.swift`. С 26.08 SDK отдаёт один `SearchService`
 * на все комнаты (страница — сотня совпадений по всем), а поиск по одной комнате из FFI убрал. Поэтому
 * чат ищет тем же сервисом и оставляет своё, листая, пока не наберёт или пока индекс не кончится.
 *
 * Результат — только живое сообщение ([SearchStore.excerpt]).
 */
internal class MessageSearch(private val client: Client, private val strings: Strings) {
    /** До [limit] совпадений по [query]; с [roomId] — только из этой комнаты. Ограничивает время вызывающий. */
    suspend fun hits(query: String, limit: Int, roomId: String? = null): List<SearchHit> =
        client.searchService().use { service ->
            // Первая страница грузится внутри setQuery.
            service.setQuery(query)
            var pages = 1
            var hits = snapshot(service, roomId)
            while (roomId != null && hits.size < limit && !ended(service) && pages < MAX_PAGES) {
                service.paginate()
                pages++
                hits = snapshot(service, roomId)
            }
            hits.take(limit)
        }

    private fun ended(service: SearchService): Boolean =
        (service.paginationState() as? SearchServicePaginationState.Idle)?.endReached == true

    /**
     * Найденное на сейчас. Подписка отдаёт текущий список одним `Reset` ещё до возврата — его и берём,
     * а саму подписку сразу гасим: дальнейшие изменения придут уже после следующей страницы.
     */
    private suspend fun snapshot(service: SearchService, roomId: String?): List<SearchHit> {
        val values = AtomicReference<List<SearchServiceResult>>(emptyList())
        val handle = service.subscribeToResults(object : SearchServiceResultsListener {
            override fun onUpdate(updates: List<SearchServiceResultsUpdate>) {
                updates.filterIsInstance<SearchServiceResultsUpdate.Reset>().lastOrNull()?.let { values.set(it.values) }
            }
        })
        handle.cancel()
        handle.close()
        return values.get().mapNotNull { result ->
            val message = result as? SearchServiceResult.Message ?: return@mapNotNull null
            if (roomId != null && message.roomId != roomId) return@mapNotNull null
            val event = message.result
            val excerpt = SearchStore.excerpt(event.content, strings) ?: return@mapNotNull null
            val name = (event.senderProfile as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotBlank() } ?: event.sender
            SearchHit(event.eventId, message.roomId, name, excerpt, MessagePreview.instant(event.timestamp.toULong()))
        }
    }

    private companion object {
        /** Сколько страниц (по сотне совпадений на все комнаты) листать ради одной комнаты. */
        const val MAX_PAGES = 10
    }
}
