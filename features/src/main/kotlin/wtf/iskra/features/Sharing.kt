package wtf.iskra.features

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Читает то, что прислало общее меню Android, в [SharedContent]. */
object Sharing {
    fun content(intent: Intent): SharedContent {
        val uris = buildList {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
        }.filter { it.scheme == "content" }.distinct()
        // Ссылка из браузера приходит словами с заголовком отдельно: слова важнее, заголовок — только когда слов нет.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() && uris.isEmpty() }
        return SharedContent(text, uris.take(MAX))
    }

    /** Столько же, сколько в композере (`MAX_ATTACHMENTS`). */
    const val MAX = 10
}
