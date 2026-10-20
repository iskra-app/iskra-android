package wtf.iskra.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `release.json` — что CI приложил к последнему релизу (docs/PROMPT.md, 19.1). Пишет его только workflow
 * релиза (`scripts/release_json.py`), никогда руками: версия, имена, размеры и хэши берутся из только
 * что собранных и подписанных APK.
 *
 * Разбор терпимый: лишние поля игнорируются, недостающие необязательные — пусты. Чего не прощает —
 * [schema] больше известной: тогда приложение не гадает, а говорит «обновите вручную».
 */
@Serializable
data class ReleaseManifest(
    val schema: Int,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int = 0,
    val published: String? = null,
    /** «Что нового» по языкам: `ru`, `en`, `de`, `es`, `fr`, `ja`, `zh-Hans`. */
    val notes: Map<String, String> = emptyMap(),
    /** Ключ — `<flavor>-<abi>`: `gms-arm64-v8a`, `foss-x86_64`. */
    val assets: Map<String, Asset> = emptyMap(),
    /** SHA-256 сертификата подписи, шестнадцатеричный, без двоеточий. */
    val signingCertSha256: String? = null,
) {
    @Serializable
    data class Asset(val name: String, val size: Long, val sha256: String)

    companion object {
        /** Какую схему это приложение понимает. Больше — «обновите вручную», а не попытка угадать. */
        const val KNOWN_SCHEMA = 1

        private val json = Json { ignoreUnknownKeys = true }

        /** Null — не `release.json` вовсе (страница ошибки, обрезанный ответ). */
        fun parse(text: String): ReleaseManifest? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
