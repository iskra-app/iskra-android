package moscow.swag.iskra.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight

/**
 * Главная кнопка экрана (docs/PROMPT.md, 6.4). Порт `PrimaryActionStyle`.
 *
 * Заливка — акцент, слова — то, что на нём читается: та же пара, что у своего пузыря. Системный
 * `Button` с цветами по умолчанию на экранах не используется: с `colorScheme.primary` на моно-акценте
 * он заливался тем же, чем написан. Выключенная кнопка *выглядит* выключенной: серая, без акцента.
 */
@Composable
fun PrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val accent = LocalAccent.current
    val disabledFill = PageColors.primary(accent.isDark).copy(alpha = DISABLED_FILL)
    val disabledText = PageColors.primary(accent.isDark).copy(alpha = DISABLED_TEXT)
    ActionFace(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fill = if (enabled) accent.color else disabledFill,
        text = if (enabled) accent.foreground else disabledText,
        content = content,
    )
}

/**
 * Вторая кнопка рядом с главной: той же высоты и формы, без акцента. Две равнозначные кнопки
 * (например, «ввести код» и «подтвердить с другого устройства») — это две таких, а не главная и вторая.
 */
@Composable
fun SecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val accent = LocalAccent.current
    val primary = PageColors.primary(accent.isDark)
    ActionFace(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fill = primary.copy(alpha = SECONDARY_FILL),
        text = if (enabled) primary else primary.copy(alpha = DISABLED_TEXT),
        content = content,
    )
}

@Composable
private fun ActionFace(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    fill: Color,
    text: Color,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = Theme.Size.minimumTapTarget)
            .clip(CircleShape)
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (pressed) PRESSED else 1f)
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides text,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = text,
            ),
        ) {
            content()
        }
    }
}

private const val DISABLED_FILL = 0.08f
private const val DISABLED_TEXT = 0.38f
private const val SECONDARY_FILL = 0.08f
private const val PRESSED = 0.8f
