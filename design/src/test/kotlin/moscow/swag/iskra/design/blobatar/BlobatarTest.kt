//  Tests for the blobatar port (https://blobatar.dev), MIT licence,
//  Copyright (c) 2026 Alain. See Licenses/blobatar.txt.

package moscow.swag.iskra.design.blobatar

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Держит порт на golden-фикстуре самого blobatar (`blobatar-gen2-golden.txt`, привезена
 * `sync-from-ios`), как `BlobatarTests` на iOS. blobatar — это *личность*: тот же человек должен
 * выйти тем же существом здесь, на iPhone и везде, где blobatar работает. Один ULP косинуса или
 * правило округления не из того языка — и несколько сотен человек тихо получают другое лицо.
 */
class BlobatarTest {
    private val golden: Map<String, List<Pair<String, String>>> by lazy {
        val text = javaClass.getResourceAsStream("/blobatar-gen2-golden.txt")!!.bufferedReader().readText()
        val sections = linkedMapOf<String, MutableList<Pair<String, String>>>()
        var current = ""
        for (line in text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                current = line.drop(1).dropLast(1)
                sections[current] = mutableListOf()
                continue
            }
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) continue
            sections.getOrPut(current) { mutableListOf() } += parts[0] to parts[1]
        }
        sections
    }

    /** 16 шестнадцатеричных знаков SHA-256 — так фикстура записывает тысячу сидов. */
    private fun digest(markup: String): String =
        MessageDigest.getInstance("SHA-256").digest(markup.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Корпус сидов фикстуры, восстановленный по шаблонам, которые его породили. */
    private val seeds: List<String> = (0 until 1000).map { i ->
        when (i % 10) {
            0 -> "user-$i"
            1 -> "alain$i@example.com"
            // `(i * 2654435761 >>> 0).toString(16).padStart(8, "0").repeat(4)`.
            2 -> "%08x".format((i.toLong() * 2_654_435_761L).toUInt().toLong()).repeat(4)
            3 -> "Team Rocket $i"
            4 -> "café-$i"
            5 -> "Ünïcødé $i"
            6 -> "🦊$i🌱"
            7 -> "$i"
            8 -> "00000000-0000-4000-8000-" + "%012d".format(i)
            else -> "  Mixed Case $i  "
        }
    }

    @Test
    fun normalizationFoldsWhatAPersonConsidersTheSameName() {
        assertEquals(BlobatarHash.normalize("café"), BlobatarHash.normalize("café"))
        assertEquals("@boris:matrix.org", BlobatarHash.normalize("  @Boris:Matrix.org  "))
    }

    @Test
    fun oneLetterChangesTheWholeCreature() {
        val boris = Blobatar.figure("@boris:matrix.org")
        val borys = Blobatar.figure("@borys:matrix.org")
        assertTrue(BlobatarPalette.hex(boris.palette.head) != BlobatarPalette.hex(borys.palette.head) || boris.shape != borys.shape)
    }

    @Test
    fun theSameSeedIsTheSameFaceEveryTime() {
        assertEquals(Blobatar.svg("@boris:matrix.org"), Blobatar.svg("@boris:matrix.org"))
        assertNotEquals(Blobatar.svg("@boris:matrix.org"), Blobatar.svg("@borys:matrix.org"))
    }

    /** Таблица полос на двадцати тысячах сидов: ловит край, сдвинутый на тысячную. */
    @Test
    fun shapeDistributionMatchesTheFixture() {
        val counts = hashMapOf<String, Int>()
        for (i in 0 until 20_000) counts.merge(Blobatar.figure("histogram-$i").shape, 1, Int::plus)
        val expected = golden.getValue("histogram")
        assertTrue(expected.isNotEmpty())
        for ((shape, count) in expected) assertEquals(count.toInt(), counts[shape], shape)
    }

    /** Десять силуэтов и параметры рисования — целой разметкой. Выражения пропускаются, как на iOS. */
    @Test
    fun markupMatchesTheFixture() {
        val midpoints = mapOf(
            "round" to 0.11, "organic" to 0.35, "boxy" to 0.54, "capsule" to 0.65, "nub" to 0.745,
            "cloud" to 0.825, "droplet" to 0.888, "hexagon" to 0.933, "sun" to 0.965, "triangle" to 0.99,
        )
        var checked = 0
        for ((label, expected) in golden.getValue("markup")) {
            var seed = "alain"
            val shape = if (label.startsWith("shape:")) midpoints[label.removePrefix("shape:")] else null
            val options = if (shape != null) {
                Blobatar.Options(traits = mapOf("shape" to listOf(shape)))
            } else {
                when (label) {
                    "plain" -> Blobatar.Options()
                    "backdrop" -> Blobatar.Options(backdrop = Blobatar.Backdrop.SQUIRCLE)
                    "titled" -> Blobatar.Options(title = "Alain")
                    "sized" -> Blobatar.Options(size = 64)
                    "configured" -> Blobatar.Options(traits = mapOf("shape" to listOf(0.96), "eye.ratio" to listOf(0.0), "hue" to listOf(0.5)))
                    "astral" -> {
                        seed = "🦊"
                        Blobatar.Options()
                    }
                    else -> continue
                }
            }
            assertEquals(expected, Blobatar.svg(seed, options), "case $label")
            checked += 1
        }
        assertEquals(16, checked)
    }

    /** Тысяча сидов по дайджесту — широкий прогон, ради которого фикстура и существует. */
    @Test
    fun everySeedInTheFixtureRendersTheSameBytes() {
        val expected = golden.getValue("hashes").toMap()
        val wrong = seeds.filter { seed -> expected[seed]?.let { digest(Blobatar.svg(seed)) != it } ?: false }
        assertEquals(1000, seeds.count { it in expected })
        assertEquals(emptyList(), wrong.take(10), "${wrong.size} сидов разошлись")
    }

    @Test
    fun renderingOptionsMatchTheFixture() {
        val expected = golden.getValue("hashes").toMap()
        val cases = listOf(
            "bg:none" to Blobatar.Options(backdrop = Blobatar.Backdrop.NONE),
            "bg:square" to Blobatar.Options(backdrop = Blobatar.Backdrop.SQUARE),
            "bg:circle" to Blobatar.Options(backdrop = Blobatar.Backdrop.CIRCLE),
            "bg:squircle" to Blobatar.Options(backdrop = Blobatar.Backdrop.SQUIRCLE),
            "hue:0" to Blobatar.Options(hue = 0.0),
            "hue:210" to Blobatar.Options(hue = 210.0),
            "hue:359" to Blobatar.Options(hue = 359.0),
            "tone:0" to Blobatar.Options(tone = 0.0),
            "tone:0.5" to Blobatar.Options(tone = 0.5),
            "tone:0.999" to Blobatar.Options(tone = 0.999),
            "size:8" to Blobatar.Options(size = 8),
            "size:1024" to Blobatar.Options(size = 1024),
            "normalize:false" to Blobatar.Options(normalize = false),
        )
        var checked = 0
        val wrong = mutableListOf<String>()
        for (seed in seeds.take(12)) {
            for ((label, options) in cases) {
                val want = expected["$seed\u0000$label"] ?: continue
                if (digest(Blobatar.svg(seed, options)) != want) wrong += "$seed/$label"
                checked += 1
            }
        }
        assertEquals(12 * cases.size, checked)
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun aCreatureGrowsToFillItsBoxWithoutLeavingIt() {
        for (i in 0 until 200) {
            val figure = Blobatar.figure("grow-$i")
            val m = figure.magnification(1.3)
            assertTrue(m in 1.0..1.3 || m < 1.0, "seed $i: $m")
        }
    }
}
