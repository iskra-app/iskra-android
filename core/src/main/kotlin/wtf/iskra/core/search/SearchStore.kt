package wtf.iskra.core.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.SearchRoomFilter
import org.matrix.rustcomponents.sdk.TimelineItemContent
import wtf.iskra.core.rooms.MessagePreview
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.withTimeLimit
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Найденное сообщение: где, от кого, его слова и когда. `id` — событие, к которому вести. */
data class SearchHit(val id: String, val roomId: String, val senderName: String, val excerpt: String, val timestamp: Instant)

data class SearchState(
    val hits: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,
    /** Законченный поиск не нашёл ничего — экран говорит это, а не показывает пустоту. */
    val foundNothing: Boolean = false,
)

/**
 * Поиск по своим сообщениям — порт `SearchStore.swift`. Целиком на устройстве, по локальному индексу
 * SDK, зашифрованному ключом из хранилища секретов (`withSearchIndexStore`): запрос на сервер не
 * уходит никогда (docs/PROMPT.md, 8.5) — в зашифрованной комнате сервер и не мог бы искать, а спросить
 * его значило бы рассказать, что человек ищет.
 */
class SearchStore internal constructor(
    private val client: Client,
    private val scope: CoroutineScope,
    private val strings: Strings,
) {
    private val mutableState = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Ищет по мере набора, отменяя то, что было в пути. Короче двух букв — не поиск. */
    fun search(query: String) {
        job?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY) {
            mutableState.value = SearchState()
            return
        }
        mutableState.value = mutableState.value.copy(isSearching = true, foundNothing = false)
        job = scope.launch {
            // Дождаться паузы в наборе, а не дёргать индекс на каждую букву.
            delay(DEBOUNCE_MS)
            val hits = try {
                withTimeLimit(LIMIT) {
                    val iterator = client.searchMessages(trimmed, SearchRoomFilter.ROOMS, BATCH.toUInt())
                    iterator.nextEvents().orEmpty().mapNotNull { result ->
                        val event = result.result
                        val excerpt = excerpt(event.content, strings) ?: return@mapNotNull null
                        val name = (event.senderProfile as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotBlank() } ?: event.sender
                        SearchHit(event.eventId, result.roomId, name, excerpt, MessagePreview.instant(event.timestamp.toULong()))
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "поиск не удался: ${Log.describe(error)}")
                emptyList()
            }
            // Только текущий поиск гасит свой спиннер: отменённый сюда не доходит.
            mutableState.value = SearchState(hits = hits, isSearching = false, foundNothing = hits.isEmpty())
        }
    }

    fun clear() {
        job?.cancel()
        mutableState.value = SearchState()
    }

    companion object {
        private const val MIN_QUERY = 2
        private const val DEBOUNCE_MS = 200L
        private const val BATCH = 50
        private val LIMIT = 30.seconds

        /**
         * Слова одного результата — или null для события, которое результатом быть не может.
         *
         * Индекс хранит слова по ID события и больше ничего; SDK потом грузит событие *каким оно стало*.
         * Удалённое приходит отредактированным — но найдено по словам, которые в нём были, а SDK
         * выкидывает их из индекса, только если держит оригинал в кэше. Строка списка честно сказала
         * бы «Сообщение удалено» — и удалённое встало бы в выдачу по своим прежним словам, под именем
         * отправителя. Поэтому правило уже, чем у превью: результат — только живое сообщение.
         */
        fun excerpt(content: TimelineItemContent, strings: Strings): String? {
            val kind = (content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message ?: return null
            return MessagePreview.text(kind.content.msgType, strings)
        }
    }
}
