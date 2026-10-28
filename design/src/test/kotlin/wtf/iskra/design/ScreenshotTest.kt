package wtf.iskra.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import wtf.iskra.design.blobatar.Blobatar
import wtf.iskra.design.blobatar.BlobatarFace

/**
 * Скриншот-тесты `:design` (этап 1): как выглядят кнопки, поля и существа в теме приложения — снимком,
 * а не словами. Эталоны лежат в `src/test/screenshots`; `./gradlew :design:recordRoborazziDebug`
 * записывает новые, `verifyRoborazziDebug` сверяет. Без флага Roborazzi тест только рисует — в `check`
 * это проверка, что компонент вообще рисуется.
 *
 * Слова здесь — образцы, а не интерфейс: строки приложения живут в ресурсах `:features`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class ScreenshotTest {
    /**
     * Порог: эталоны пишутся на одной машине, а сверяются и на других (CI на Linux); растеризация
     * сглаживания краёв там на пиксель-другой своя. Пропущенная кнопка или чужой цвет — больше порога.
     */
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    private fun capture(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = options, content = content)

    @Composable
    private fun Page(appearance: AppearanceMode, accent: AccentTheme = AccentTheme.Default, content: @Composable () -> Unit) {
        IskraTheme(accent = accent, appearance = appearance) {
            Column(
                Modifier.background(MaterialTheme.colorScheme.background).padding(Theme.Spacing.roomy),
                verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
            ) { content() }
        }
    }

    @Composable
    private fun Actions() {
        PrimaryActionButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Продолжить") }
        SecondaryActionButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Выбрать сервер") }
        PrimaryActionButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Недоступно") }
    }

    @Test
    fun actionsLight() = capture("actions_light") { Page(AppearanceMode.LIGHT) { Actions() } }

    @Test
    fun actionsDark() = capture("actions_dark") { Page(AppearanceMode.DARK) { Actions() } }

    @Test
    fun fields() = capture("fields") {
        Page(AppearanceMode.LIGHT) {
            EntryField(value = "matrix.org", onValueChange = {}, placeholder = "Адрес сервера")
            EntryField(value = "", onValueChange = {}, placeholder = "Пароль")
            PillField(value = "", onValueChange = {}, placeholder = "Сообщение")
        }
    }

    /** Каждый акцент — главной кнопкой: схема Material строится из него, и это видно сразу. */
    @Test
    fun accents() = capture("accents") {
        Column {
            for (accent in AccentTheme.choices(sdk = 30)) {
                Page(AppearanceMode.LIGHT, accent) {
                    PrimaryActionButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(accent.storageName) }
                }
            }
        }
    }

    /** Существа из нескольких сидов, без подложки и на круге: арифметику держит `BlobatarTest`, это — рисунок. */
    @Test
    fun creatures() = capture("creatures") {
        Page(AppearanceMode.LIGHT) {
            for (backdrop in listOf(Blobatar.Backdrop.NONE, Blobatar.Backdrop.CIRCLE)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal)) {
                    for (seed in SEEDS) BlobatarFace(seed, Modifier.size(Theme.Size.roomAvatar), backdrop)
                }
            }
        }
    }

    private companion object {
        val SEEDS = listOf("@anya:matrix.org", "@misha:matrix.org", "!kitchen:matrix.org", "@sasha:iskra.demo")
    }
}
