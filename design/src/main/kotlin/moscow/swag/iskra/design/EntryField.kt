package moscow.swag.iskra.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Поле, в которое что-то вписывают на экранах входа и настройки. Порт `EntryField.swift`: одна форма
 * и одни цвета для всех полей, акцент — у рамки в фокусе.
 */
@Composable
fun EntryField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val accent = LocalAccent.current
    val ink = PageColors.primary(accent.isDark)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(Theme.Radius.control),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent.control,
            unfocusedBorderColor = ink.copy(alpha = Theme.Opacity.SELECTED_TAB),
            cursorColor = accent.control,
        ),
    )
}
