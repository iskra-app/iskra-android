package moscow.swag.iskra

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Правила, которые нарушаются тихо и далеко от причины, закреплённые тестом. */
class SourceRulesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun kotlinSources(vararg modules: String) = modules.flatMap { module ->
        File(root, "$module/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
    }

    /**
     * Ни одной строки, которую видит человек, в composable (docs/PROMPT.md, 7). lint `HardcodedText`
     * смотрит только XML-разметку, поэтому `Text("…")` ловится здесь.
     */
    @Test
    fun noLiteralTextInComposables() {
        val literal = Regex("""\bText\(\s*(text\s*=\s*)?"""")
        val offenders = kotlinSources("features", "design", "app").flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (literal.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}" else null
            }
        }
        assertEquals(emptyList(), offenders, "строка в composable вместо ресурса")
    }

    /**
     * Размеры — только из `Theme` (docs/PROMPT.md, 6.2). `.padding(12.dp)` на экране — это баг, который
     * ждёт, когда разойдётся с соседним. Внутри `:design` числа живут в `Theme.kt`; вне его литерал
     * в `dp` не пишется вовсе.
     */
    @Test
    fun noLiteralSizesOutsideDesign() {
        val literal = Regex("""\b\d+(\.\d+)?f?\.dp\b""")
        val offenders = kotlinSources("features", "app").flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                if (literal.containsMatchIn(code)) "${file.relativeTo(root)}:${index + 1}" else null
            }
        }
        assertEquals(emptyList(), offenders, "размер числом вместо Theme")
    }

    /**
     * Бэкап выключен целиком (docs/PROMPT.md, 5.1): иначе зашифрованный стор уедет в облако без
     * ключа Keystore, и восстановленное приложение откроется с мусором.
     */
    @Test
    fun backupIsOff() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue("""android:allowBackup="false"""" in manifest)
        assertTrue("""android:dataExtractionRules="@xml/data_extraction_rules"""" in manifest)
        assertTrue("""android:fullBackupContent="@xml/backup_rules"""" in manifest)

        val domains = setOf("root", "file", "database", "sharedpref", "external")
        val rules = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "app/src/main/res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            val excludes = node.getElementsByTagName("exclude")
            val excluded = (0 until excludes.length).map { excludes.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
            assertEquals(domains, excluded, "$section выпускает что-то из бэкапа")
            assertEquals(0, node.getElementsByTagName("include").length, "$section что-то включает")
        }
        val legacy = File(root, "app/src/main/res/xml/backup_rules.xml").readText()
        for (domain in domains) assertTrue("""domain="$domain" path="."""" in legacy, "backup_rules без $domain")
    }
}
