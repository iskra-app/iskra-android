package wtf.iskra.core.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Встроенная проверка обновлений (`UpdateCheckTests` из docs/PROMPT.md, 19.1). Своё, не из iOS: там
 * обновляет App Store.
 */
class UpdateCheckTest {
    private val asset = ReleaseManifest.Asset("iskra-1.4.0-foss-arm64-v8a.apk", 100, "ab".repeat(32))

    private fun manifest(
        versionCode: Long = 140,
        schema: Int = 1,
        minSdk: Int = 29,
        assets: Map<String, ReleaseManifest.Asset> = mapOf("foss-arm64-v8a" to asset, "gms-arm64-v8a" to asset.copy(name = "gms")),
    ) = ReleaseManifest(
        schema = schema,
        versionName = "1.4.0",
        versionCode = versionCode,
        minSdk = minSdk,
        notes = mapOf("ru" to "Что нового", "en" to "What's new", "zh-Hans" to "新内容"),
        assets = assets,
        signingCertSha256 = "cd".repeat(32),
    )

    private fun evaluate(
        manifest: ReleaseManifest = manifest(),
        installed: Long = 130,
        flavor: String = "foss",
        abis: List<String> = listOf("arm64-v8a"),
        sdk: Int = 34,
        dismissed: Long? = null,
        language: String = "ru",
    ) = UpdatePolicy.evaluate(manifest, installed, flavor, abis, sdk, dismissed, language)

    // Решение

    @Test
    fun aNewerVersionCodeIsAnUpdate() {
        val offer = evaluate() as UpdateOffer.Available
        assertEquals("1.4.0", offer.versionName)
        assertEquals("Что нового", offer.notes)
    }

    /** Сравнивается versionCode: «1.10» меньше «1.9» строкой, и это не повод ставить старое. */
    @Test
    fun theSameOrOlderCodeIsUpToDate() {
        assertEquals(UpdateOffer.UpToDate, evaluate(installed = 140))
        assertEquals(UpdateOffer.UpToDate, evaluate(installed = 150))
    }

    @Test
    fun theAssetFollowsTheFlavorAndTheAbiOrder() {
        val both = mapOf("foss-x86_64" to asset.copy(name = "x86"), "foss-arm64-v8a" to asset)
        val offer = evaluate(manifest(assets = both), abis = listOf("x86_64", "arm64-v8a")) as UpdateOffer.Available
        assertEquals("x86", offer.asset.name)
        val gms = evaluate(flavor = "gms") as UpdateOffer.Available
        assertEquals("gms", gms.asset.name)
    }

    /** Чужая ABI не предлагается: ссылка на страницу релиза. */
    @Test
    fun noFileForThisDeviceIsSaid() {
        assertEquals(UpdateOffer.NoAssetForDevice("1.4.0"), evaluate(abis = listOf("x86_64")))
    }

    @Test
    fun anAndroidTooOldIsSaid() {
        assertEquals(UpdateOffer.UnsupportedAndroid("1.4.0", 35), evaluate(manifest(minSdk = 35), sdk = 34))
    }

    /** «Не сейчас» скрывает ровно эту версию; следующая снова предлагается. */
    @Test
    fun notNowHidesExactlyThatVersion() {
        assertEquals(UpdateOffer.UpToDate, evaluate(dismissed = 140))
        assertTrue(evaluate(manifest(versionCode = 141), dismissed = 140) is UpdateOffer.Available)
    }

    @Test
    fun anUnknownSchemaIsNotGuessed() {
        assertEquals(UpdateOffer.UnknownSchema, evaluate(manifest(schema = 2)))
    }

    @Test
    fun notesFallBackToEnglish() {
        assertEquals("What's new", (evaluate(language = "de") as UpdateOffer.Available).notes)
        assertEquals("新内容", (evaluate(language = "zh") as UpdateOffer.Available).notes)
    }

    // Разбор

    @Test
    fun parsingIsTolerantOfFieldsItDoesNotKnow() {
        val parsed = ReleaseManifest.parse(
            """{"schema":1,"versionName":"1.4.0","versionCode":140,"minSdk":29,"somethingNew":[1,2],
               "notes":{"ru":"…"},"assets":{"foss-arm64-v8a":{"name":"a.apk","size":1,"sha256":"00","extra":true}},
               "signingCertSha256":"cd"}""",
        )
        assertEquals(140L, parsed?.versionCode)
        assertEquals("a.apk", parsed?.assets?.get("foss-arm64-v8a")?.name)
    }

    /** Ровно та форма, что пишет `scripts/release_json.py` (прогнан на подписанных тестовым ключом APK). */
    @Test
    fun theReleaseScriptsOutputParses() {
        val parsed = ReleaseManifest.parse(
            """
            {
              "schema": 1,
              "versionName": "0.3.0",
              "versionCode": 1,
              "minSdk": 29,
              "published": "2026-10-20T19:32:31Z",
              "notes": {"ru": "Проба", "en": "Test", "de": "Test", "es": "Prueba", "fr": "Essai", "ja": "テスト", "zh-Hans": "测试"},
              "assets": {
                "foss-arm64-v8a": {"name": "iskra-0.3.0-foss-arm64-v8a.apk", "size": 71525937, "sha256": "ed18bf5e90a5f5a35ca6fbecde16738d764f0a4f49000ac3ee274be9a0ef222a"},
                "gms-arm64-v8a": {"name": "iskra-0.3.0-gms-arm64-v8a.apk", "size": 72009395, "sha256": "4d807e104118a34b8c981485de211bac0d8c1ed80f05ee42e5ab4c7aee2c20f0"}
              },
              "signingCertSha256": "a1996fd1c020ea8dca1802c67f90203820ea409be10f9f68cfe9545061c42ad8"
            }
            """.trimIndent(),
        )
        assertEquals(71525937L, parsed?.assets?.get("foss-arm64-v8a")?.size)
        assertEquals("测试", parsed?.let { UpdatePolicy.notes(it.notes, "zh") })
    }

    @Test
    fun anErrorPageIsNotAManifest() {
        assertNull(ReleaseManifest.parse("<html>Not Found</html>"))
    }

    // Когда спрашивать. `runBlocking`, не `runTest`: `withTimeLimit` ждёт работу на настоящем IO, а
    // виртуальное время `runTest` «истекает» его предел мгновенно.

    private class MemoryStore(override var checksForUpdates: Boolean = true) : UpdateStore {
        override var lastUpdateCheck = 0L
        override var cachedRelease: String? = null
        override var dismissedUpdate: Long? = null
    }

    private class CountingSource(private val text: String) : UpdateSource {
        var requests = 0
        override suspend fun latestRelease(): String {
            requests += 1
            return text
        }
        override suspend fun download(name: String, target: File, progress: (Long) -> Unit) = error("not here")
    }

    private val json = """{"schema":1,"versionName":"1.4.0","versionCode":140,"assets":{"foss-arm64-v8a":{"name":"a","size":1,"sha256":"00"}}}"""

    private fun checker(
        source: UpdateSource,
        store: UpdateStore = MemoryStore(),
        enabled: Boolean = true,
        bridge: Boolean = false,
        now: () -> Long = { 1_000_000_000L },
    ) = UpdateChecker(source, store, InstalledBuild(enabled, "foss", 130, listOf("arm64-v8a"), 34), { "ru" }, { bridge }, now)

    @Test
    fun onceADayAtMost() = runBlocking {
        val source = CountingSource(json)
        var now = 1_000_000_000L
        val checker = checker(source, now = { now })
        checker.checkInBackground()
        now += UpdateChecker.INTERVAL.inWholeMilliseconds - 1
        checker.checkInBackground()
        assertEquals(1, source.requests)
        assertTrue(checker.offer.value is UpdateOffer.Available)
        now += 1
        checker.checkInBackground()
        assertEquals(2, source.requests)
    }

    /** Выключено человеком, выключено сборкой, включён мост — ни одного запроса. */
    @Test
    fun offMeansNotASingleRequest() = runBlocking {
        val source = CountingSource(json)
        checker(source, store = MemoryStore(checksForUpdates = false)).checkInBackground()
        checker(source, enabled = false).checkInBackground()
        checker(source, bridge = true).checkInBackground()
        assertEquals(0, source.requests)
    }

    @Test
    fun theAnswerOutlivesTheProcess() = runBlocking {
        val store = MemoryStore()
        checker(CountingSource(json), store).checkInBackground()
        val later = checker(CountingSource(json), store)
        assertTrue(later.offer.value is UpdateOffer.Available)
    }

    @Test
    fun aBackgroundFailureIsQuiet() = runBlocking {
        val broken = object : UpdateSource {
            override suspend fun latestRelease(): String = error("no network")
            override suspend fun download(name: String, target: File, progress: (Long) -> Unit) = Unit
        }
        checker(broken).checkInBackground()
    }

    // Проверка скачанного

    private val installed = PackageFacts("wtf.iskra", 130, setOf("cd".repeat(32)))
    private val good = PackageFacts("wtf.iskra", 140, setOf("cd".repeat(32)))

    private fun apk(
        size: Long = 100,
        sha: String = "ab".repeat(32),
        archive: PackageFacts? = good,
        certificate: String? = "cd".repeat(32),
    ) = ApkCheck.check(asset, size, sha, archive, installed, certificate)

    @Test
    fun theRightFilePasses() {
        assertNull(apk())
        assertNull(apk(certificate = "CD:".repeat(31) + "CD"))
    }

    @Test
    fun aDamagedDownloadIsRefused() {
        assertEquals(ApkProblem.DAMAGED, apk(size = 99))
        assertEquals(ApkProblem.DAMAGED, apk(sha = "00".repeat(32)))
    }

    @Test
    fun anotherSignatureIsRefused() {
        assertEquals(ApkProblem.FOREIGN_SIGNATURE, apk(archive = good.copy(certificates = setOf("ee".repeat(32)))))
        assertEquals(ApkProblem.FOREIGN_SIGNATURE, apk(certificate = "ee".repeat(32)))
    }

    @Test
    fun theSameVersionAndAnotherAppAreRefused() {
        assertEquals(ApkProblem.NOT_NEWER, apk(archive = good.copy(versionCode = 130)))
        assertEquals(ApkProblem.OTHER_APP, apk(archive = good.copy(packageName = "wtf.other")))
        assertEquals(ApkProblem.UNREADABLE, apk(archive = null))
    }
}
