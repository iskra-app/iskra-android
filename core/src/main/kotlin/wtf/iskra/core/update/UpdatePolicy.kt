package wtf.iskra.core.update

/** Что сказать человеку о последнем релизе. */
sealed interface UpdateOffer {
    /** Своя версия — последняя (или новее: debug, собранный руками). */
    data object UpToDate : UpdateOffer

    /** Есть новее, и она встанет на это устройство. */
    data class Available(
        val versionName: String,
        val versionCode: Long,
        val asset: ReleaseManifest.Asset,
        /** «Что нового» на языке приложения; нет его — по-английски; нет и того — null. */
        val notes: String?,
        val signingCertSha256: String?,
    ) : UpdateOffer

    /** Новая версия просит Android новее, чем на телефоне. Так и сказано, а не молчание. */
    data class UnsupportedAndroid(val versionName: String, val minSdk: Int) : UpdateOffer

    /** Для этого flavor'а и ABI файла нет. Ведёт на страницу релиза, а не на чужую ABI. */
    data class NoAssetForDevice(val versionName: String) : UpdateOffer

    /** `release.json` новой схемы, которую эта версия не понимает: «обновите вручную». */
    data object UnknownSchema : UpdateOffer
}

/**
 * Решение по `release.json` — чистая функция, закреплённая `UpdatePolicyTest` (docs/PROMPT.md, 19.1).
 * Сравнивается `versionCode`, не `versionName`: строки не сравнивают, «1.10» меньше «1.9».
 */
object UpdatePolicy {
    /** Где лежит релиз для тех, кому файл не подошёл. */
    const val RELEASES_PAGE = "https://github.com/iskra-app/iskra-android/releases/latest"

    /**
     * @param flavor `gms` или `foss` (`BuildConfig.FLAVOR`).
     * @param supportedAbis `Build.SUPPORTED_ABIS` по порядку предпочтения.
     * @param dismissedCode версия, которую человек отложил «Не сейчас», — она скрыта, следующая нет.
     * @param language язык приложения (`ru`, `zh` и т. п.).
     */
    fun evaluate(
        manifest: ReleaseManifest,
        installedCode: Long,
        flavor: String,
        supportedAbis: List<String>,
        sdkInt: Int,
        dismissedCode: Long?,
        language: String,
    ): UpdateOffer {
        if (manifest.schema > ReleaseManifest.KNOWN_SCHEMA) return UpdateOffer.UnknownSchema
        if (manifest.versionCode <= installedCode) return UpdateOffer.UpToDate
        if (manifest.versionCode == dismissedCode) return UpdateOffer.UpToDate
        if (manifest.minSdk > sdkInt) return UpdateOffer.UnsupportedAndroid(manifest.versionName, manifest.minSdk)
        val asset = supportedAbis.firstNotNullOfOrNull { abi -> manifest.assets["$flavor-$abi"] }
            ?: return UpdateOffer.NoAssetForDevice(manifest.versionName)
        return UpdateOffer.Available(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            asset = asset,
            notes = notes(manifest.notes, language),
            signingCertSha256 = manifest.signingCertSha256,
        )
    }

    /** Язык приложения → ключ в `notes`. Китайский в приложении один — упрощённый. */
    internal fun notes(notes: Map<String, String>, language: String): String? {
        val key = if (language == "zh") "zh-Hans" else language
        return notes[key]?.takeIf { it.isNotBlank() } ?: notes["en"]?.takeIf { it.isNotBlank() }
    }
}
