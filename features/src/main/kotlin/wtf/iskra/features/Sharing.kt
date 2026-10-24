package wtf.iskra.features

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import wtf.iskra.features.push.ShortcutIds

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
        // Direct Share называет ярлык, на который нажали: это чат (`ShortcutIds`), и выбирать его снова незачем.
        val target = ShortcutIds.parse(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        return SharedContent(text, uris.take(MAX), target?.second, target?.first, intent.type)
    }

    /** Столько же, сколько в композере (`MAX_ATTACHMENTS`). */
    const val MAX = 10
}
