package moscow.swag.iskra

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Семь языков, и все полные (docs/PROMPT.md, 7). Порт `otherLanguagesAreComplete`.
 *
 * На iOS пропавший ключ виден на экране как ключ. На Android он **тихо** берётся из английского
 * `values/`, и русский человек получает английскую кнопку посреди русского экрана. Поэтому наборы
 * ключей сверяются здесь (и lint `MissingTranslation` — ошибка), а не на глаз.
 */
class LocalizationTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val folders = mapOf(
        "en" to "values", "ru" to "values-ru", "de" to "values-de", "es" to "values-es",
        "fr" to "values-fr", "ja" to "values-ja", "zh-Hans" to "values-b+zh+Hans",
    )

    /** Какие формы плюралей обязаны быть у языка (CLDR). Русские one/few/many/other — один в один. */
    private val requiredQuantities = mapOf(
        "en" to setOf("one", "other"), "ru" to setOf("one", "few", "many", "other"),
        "de" to setOf("one", "other"), "es" to setOf("one", "many", "other"),
        "fr" to setOf("one", "many", "other"), "ja" to setOf("other"), "zh-Hans" to setOf("other"),
    )

    private data class Resources(val strings: Set<String>, val plurals: Map<String, Set<String>>)

    private fun read(file: File): Resources {
        if (!file.exists()) return Resources(emptySet(), emptyMap())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val elements = document.documentElement.childNodes.let { nodes ->
            (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
        }
        val strings = elements
            .filter { it.tagName == "string" && it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }.toSet()
        val plurals = elements.filter { it.tagName == "plurals" }.associate { plural ->
            val items = plural.getElementsByTagName("item")
            plural.getAttribute("name") to (0 until items.length).map {
                (items.item(it) as Element).getAttribute("quantity")
            }.toSet()
        }
        return Resources(strings, plurals)
    }

    private val sources = listOf("core", "features", "app").flatMap { module ->
        listOf("strings.xml", "strings_android.xml").map { "$module/src/main/res" to it }
    }

    @Test
    fun everyLanguageHasEveryKey() {
        var checked = 0
        for ((res, name) in sources) {
            val english = read(File(root, "$res/values/$name"))
            for ((language, folder) in folders) {
                val translated = read(File(root, "$res/$folder/$name"))
                assertEquals(english.strings, translated.strings, "$res/$folder/$name: строки не совпадают с английскими")
                assertEquals(english.plurals.keys, translated.plurals.keys, "$res/$folder/$name: плюрали не совпадают")
                checked += translated.strings.size
            }
        }
        assertTrue(checked > 7_000, "проверено подозрительно мало строк: $checked")
    }

    @Test
    fun pluralsHaveTheFormsTheLanguageNeeds() {
        for ((res, name) in sources) {
            for ((language, folder) in folders) {
                for ((key, quantities) in read(File(root, "$res/$folder/$name")).plurals) {
                    val missing = requiredQuantities.getValue(language) - quantities
                    if (missing.isNotEmpty()) fail("$res/$folder/$name: у $key нет форм $missing")
                }
            }
        }
    }

    /** Аналог CFBundleLocalizations: без него язык приложения не выбрать в системных настройках. */
    @Test
    fun localeConfigListsTheSevenLanguages() {
        val config = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        assertEquals(folders.keys, listed)
        val gradle = File(root, "app/build.gradle.kts").readText()
        for (folder in folders.values.drop(1)) {
            val qualifier = folder.removePrefix("values-")
            assertTrue("\"$qualifier\"" in gradle, "localeFilters в app/build.gradle.kts без $qualifier — перевод выкинется из сборки")
        }
    }
}
