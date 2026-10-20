package wtf.iskra.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** Что известно о пакете: из скачанного файла или об установленном приложении. */
data class PackageFacts(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 сертификатов подписи, строчными шестнадцатеричными, без двоеточий. */
    val certificates: Set<String>,
) {
    companion object
}

/** Почему скачанный APK не ставится. Каждая причина — своими словами на экране. */
enum class ApkProblem {
    /** Скачалось не то или не целиком: размер или SHA-256 не те, что в `release.json`. */
    DAMAGED,

    /** Файл не читается как APK. */
    UNREADABLE,

    /** Это не Искра (другой `applicationId`). */
    OTHER_APP,

    /** Версия не новее установленной. */
    NOT_NEWER,

    /**
     * Подписан не тем ключом. `release.json` и APK лежат в одном месте, и подменивший одно подменит и
     * другое, — а ключ подписи не подменит. Android сам отказал бы, но его ошибка — «конфликт пакетов»
     * без объяснений, и она толкает «удалить и поставить заново», что стёрло бы переписку.
     */
    FOREIGN_SIGNATURE,
}

/**
 * Проверка скачанного обновления **до** установки (docs/PROMPT.md, 19.1, шаг 3): отказ при любом
 * несовпадении. Решение — чистая функция фактов (`ApkCheckTest`); факты достаёт [PackageFacts.of].
 */
object ApkCheck {
    fun check(
        asset: ReleaseManifest.Asset,
        size: Long,
        sha256: String,
        archive: PackageFacts?,
        installed: PackageFacts,
        manifestCertificate: String?,
    ): ApkProblem? {
        if (size != asset.size || !sha256.equals(asset.sha256, ignoreCase = true)) return ApkProblem.DAMAGED
        archive ?: return ApkProblem.UNREADABLE
        if (archive.packageName != installed.packageName) return ApkProblem.OTHER_APP
        if (archive.versionCode <= installed.versionCode) return ApkProblem.NOT_NEWER
        if (archive.certificates.isEmpty() || archive.certificates != installed.certificates) return ApkProblem.FOREIGN_SIGNATURE
        val expected = manifestCertificate?.lowercase()?.replace(":", "")
        if (expected != null && expected !in archive.certificates) return ApkProblem.FOREIGN_SIGNATURE
        return null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val BUFFER = 64 * 1024
}

/** Факты о пакете от `PackageManager`. */
fun PackageFacts.Companion.installed(context: Context): PackageFacts? =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).facts()
    }.getOrNull()

/** Факты о скачанном файле. Null — не читается как APK. */
fun PackageFacts.Companion.archive(context: Context, file: File): PackageFacts? =
    runCatching {
        context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)?.facts()
    }.getOrNull()

private fun PackageInfo.facts(): PackageFacts {
    val signing = signingInfo
    val signers = when {
        signing == null -> emptyArray()
        signing.hasMultipleSigners() -> signing.apkContentsSigners
        // Текущий сертификат, а не вся история ротации: сравниваются ключи, которыми подписано сейчас.
        else -> signing.signingCertificateHistory.takeLast(1).toTypedArray()
    }
    val digest = MessageDigest.getInstance("SHA-256")
    return PackageFacts(
        packageName = packageName,
        versionCode = longVersionCode,
        certificates = signers.map { signer -> with(ApkCheck) { digest.digest(signer.toByteArray()).toHex() } }.toSet(),
    )
}
