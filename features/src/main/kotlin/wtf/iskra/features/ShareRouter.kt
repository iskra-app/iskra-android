package wtf.iskra.features

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Что отдало другое приложение: слова и/или адреса `content://`. Байты не читаются, пока человек не выбрал чат и не нажал «отправить». */
data class SharedContent(val text: String?, val uris: List<Uri>) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()
}

/**
 * Общее меню Android (`ACTION_SEND`, `SEND_MULTIPLE`) → корень: тот же приём, что у [LinkRouter]. Холодный запуск покрыт —
 * содержимое приходит раньше экранов и ждёт, пока появится дерево вошедшего (и пока снят замок приложения).
 */
class ShareRouter {
    private val pending = MutableStateFlow<SharedContent?>(null)

    /** Ждущее содержимое — чтобы корень заметил его и в уже открытом приложении. */
    val request: StateFlow<SharedContent?> = pending.asStateFlow()

    fun opened(content: SharedContent) {
        if (!content.isEmpty) pending.value = content
    }

    /** Забирает и забывает: второй возврат в приложение не предложит его снова. */
    fun take(): SharedContent? = pending.getAndUpdate { null }
}
