package moscow.swag.iskra.features.room

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import moscow.swag.iskra.core.timeline.RichText

/**
 * [RichText] ядра → `AnnotatedString` пузыря. Решение, *что* это за отрезок, — в `:core`
 * (`MatrixHtml`); здесь — только как его покрасить.
 *
 * Спойлер, пока не раскрыт, закрашен цветом самого текста — слова есть, прочесть нельзя; раскрывает
 * нажатие на пузырь (`revealed`).
 */
internal fun RichText.annotated(
    ink: Color,
    accent: Color,
    quiet: Color,
    mention: Color,
    revealed: Boolean,
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        val style = span.style
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { style.underline || style.link != null },
            TextDecoration.LineThrough.takeIf { style.strikethrough },
        )
        addStyle(
            SpanStyle(
                fontWeight = if (style.bold || style.heading != null || style.mentionUserId != null) FontWeight.SemiBold else null,
                fontStyle = if (style.italic) FontStyle.Italic else null,
                fontFamily = if (style.code) FontFamily.Monospace else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                color = when {
                    style.spoiler != null && !revealed -> ink
                    style.mentionUserId != null -> mention
                    style.link != null -> accent
                    style.quote -> quiet
                    else -> Color.Unspecified
                },
                background = if (style.spoiler != null && !revealed) ink else Color.Unspecified,
            ),
            span.start,
            span.end,
        )
        style.link?.let { url ->
            addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent))), span.start, span.end)
        }
    }
}
