package wtf.iskra.design

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Главная кнопка экрана (docs/PROMPT.md, 6.4): заполненная кнопка Material 3. Цвета — из схемы
 * акцента, где у моно своя пара `primary`/`onPrimary`, поэтому белой плиты с белыми словами нет.
 */
@Composable
fun PrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Theme.Size.actionButton),
        enabled = enabled,
        content = content,
    )
}

/**
 * Вторая кнопка рядом с главной: тональная, той же высоты и формы. Две равнозначные кнопки
 * (например, «ввести код» и «подтвердить с другого устройства») — это две таких, а не главная и вторая.
 */
@Composable
fun SecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Theme.Size.actionButton),
        enabled = enabled,
        content = content,
    )
}
