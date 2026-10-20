package wtf.iskra.core.support

import wtf.iskra.core.R
import java.io.File

/**
 * Настоящие строки `:core` на JVM, без Android: читает `res/values[-xx]/strings.xml` и сопоставляет
 * их с `R.string` по имени. Для тестов, которым важно *предложение* — «Аня приглашает вас в чат», а не
 * «#2131…»: на iOS такие тесты читают `.strings` с локалью, и порт проверяет то же.
 */
class ResourceStrings(folder: String = "values") : Strings {
    private val byName: Map<String, String> = read(File(root, "core/src/main/res/$folder/strings.xml"))
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    override fun get(id: Int): String = byName.getValue(names.getValue(id))

    override fun format(id: Int, vararg args: Any): String = String.format(get(id), *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String = error("plurals are not read here")

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

        val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        fun read(file: File): Map<String, String> = entry.findAll(file.readText()).associate { match ->
            match.groupValues[1] to match.groupValues[2]
                .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        }
    }
}
