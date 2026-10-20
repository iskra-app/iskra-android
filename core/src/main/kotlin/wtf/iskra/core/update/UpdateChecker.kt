package wtf.iskra.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Что проверка помнит между запусками. Хранит `AppSettings`; тесты — память. */
interface UpdateStore {
    /** Переключатель «Проверять обновления» — включён по умолчанию. */
    val checksForUpdates: Boolean
    var lastUpdateCheck: Long
    var cachedRelease: String?
    var dismissedUpdate: Long?
}

/** Что это за сборка — всё, что нужно, чтобы выбрать свой файл. */
data class InstalledBuild(
    /** `BuildConfig.UPDATE_CHECK`: false в debug и в сборке для магазина, который обновляет сам. */
    val checksEnabled: Boolean,
    val flavor: String,
    val versionCode: Long,
    val supportedAbis: List<String>,
    val sdkInt: Int,
)

/**
 * Встроенная проверка обновлений (docs/PROMPT.md, 19.1). Магазина нет, и без неё обновления не придут
 * вовсе. Но это запрос к третьей стороне (GitHub видит адрес и факт «здесь стоит Искра»), поэтому:
 * только `GET` `release.json`, без идентификаторов и языка, не чаще раза в сутки, и при мосте — ни
 * одного фонового запроса: прямой поход мимо моста — тот самый тихий обход, который мост запрещает.
 */
class UpdateChecker(
    private val source: UpdateSource,
    private val store: UpdateStore,
    private val build: InstalledBuild,
    private val language: () -> String,
    /** Указан ли мост (этап 9). Пока моста нет — никогда. */
    val bridgeEnabled: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableOffer = MutableStateFlow<UpdateOffer?>(cached())

    /** Что известно о последнем релизе. Null — ещё не проверяли (или проверка выключена). */
    val offer: StateFlow<UpdateOffer?> = mutableOffer.asStateFlow()

    private val checking = Mutex()

    /** Есть ли проверка в этой сборке вообще (нет в debug). Нет — и экран о ней молчит. */
    val isInBuild: Boolean get() = build.checksEnabled

    /** Проверять ли вообще: сборка разрешает и человек не выключил. */
    val isEnabled: Boolean get() = build.checksEnabled && store.checksForUpdates

    /**
     * Фоновая проверка — при выходе на передний план. Молчит при неудаче (только лог) и ничего не делает
     * чаще раза в сутки, выключенная, в debug и при мосте.
     */
    suspend fun checkInBackground() {
        if (!isEnabled || bridgeEnabled()) return
        if (clock() - store.lastUpdateCheck < INTERVAL.inWholeMilliseconds) return
        try {
            check()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.info(Log.Category.UPDATE, "the update check did not answer: ${Log.describe(error)}")
        }
    }

    /**
     * «Проверить сейчас». Неудача — исключением: экран скажет словами. При мосте экран сначала спрашивает
     * «проверить напрямую?» — это решение человека в момент нажатия.
     */
    suspend fun checkNow(): UpdateOffer {
        if (!build.checksEnabled) return UpdateOffer.UpToDate
        return check()
    }

    /**
     * Проба `update`: спросить [other] вместо GitHub, даже в сборке без проверки. Только для debug —
     * зовущий проверяет, что проверки в сборке нет ([isInBuild]).
     */
    suspend fun probe(other: UpdateSource): UpdateOffer {
        val manifest = ReleaseManifest.parse(withTimeLimit(LIMIT) { other.latestRelease() }) ?: error("release.json did not parse")
        return evaluate(manifest).also { mutableOffer.value = it }
    }

    /** «Не сейчас»: эта версия скрыта, следующая — нет. */
    fun dismiss(versionCode: Long) {
        store.dismissedUpdate = versionCode
        mutableOffer.value = cached()
    }

    private suspend fun check(): UpdateOffer = checking.withLock {
        val text = withTimeLimit(LIMIT) { source.latestRelease() }
        val manifest = ReleaseManifest.parse(text) ?: error("release.json did not parse")
        store.cachedRelease = text
        store.lastUpdateCheck = clock()
        val offer = evaluate(manifest)
        mutableOffer.value = offer
        Log.info(Log.Category.UPDATE, "the latest release is ${manifest.versionName} (${manifest.versionCode}): ${offer.javaClass.simpleName}")
        offer
    }

    private fun cached(): UpdateOffer? {
        if (!build.checksEnabled) return null
        val manifest = store.cachedRelease?.let(ReleaseManifest::parse) ?: return null
        return evaluate(manifest)
    }

    private fun evaluate(manifest: ReleaseManifest) = UpdatePolicy.evaluate(
        manifest = manifest,
        installedCode = build.versionCode,
        flavor = build.flavor,
        supportedAbis = build.supportedAbis,
        sdkInt = build.sdkInt,
        dismissedCode = store.dismissedUpdate,
        language = language(),
    )

    companion object {
        /** Чаще — частота запросов сама становится отпечатком. */
        val INTERVAL = 24.hours
        private val LIMIT = 20.seconds
    }
}
