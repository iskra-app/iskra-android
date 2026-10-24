package wtf.iskra.features.support

import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Клавиатура в окне, которое её ждёт: берёт фокус, когда окно появилось, и отдаёт нажатия [onKey]. Без фокуса `Escape` и стрелки
 * уходят в никуда: у диалога на планшете с клавиатурой фокус сам не встаёт (`ESC` в `MessageMenu`, стрелки в `MediaViewer`).
 */
fun Modifier.keyboardFocus(onKey: (KeyEvent) -> Boolean): Modifier = composed {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    this.focusRequester(focus).focusable().onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event) }
}

/** `Escape` закрывает. Стрелки и прочее — снаружи, [extra]. */
fun Modifier.closesOnEscape(onClose: () -> Unit, extra: (KeyEvent) -> Boolean = { false }): Modifier = keyboardFocus { event ->
    if (event.key == Key.Escape) {
        onClose()
        true
    } else {
        extra(event)
    }
}

/** `Ctrl+Z` (и `Cmd+Z` на ChromeOS/Mac) — вернуть. */
val KeyEvent.isUndo: Boolean get() = key == Key.Z && (isCtrlPressed || isMetaPressed)

/**
 * Правая кнопка мыши — то же, что долгое нажатие: где мыши место, там контекстное меню ищут по ней (`contextMenu` на Mac). Нажатие
 * смотрится *до* потомков (`Initial`) и съедается, чтобы пузырь не принял его за обычное; пальцем и стилусом не срабатывает.
 */
fun Modifier.secondaryClick(onSecondary: () -> Unit): Modifier = pointerInput(onSecondary) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onSecondary()
            }
        }
    }
}
