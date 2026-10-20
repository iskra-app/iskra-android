package wtf.iskra.features.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.Log
import wtf.iskra.core.update.ApkCheck
import wtf.iskra.core.update.ApkProblem
import wtf.iskra.core.update.PackageFacts
import wtf.iskra.core.update.UpdateChecker
import wtf.iskra.core.update.UpdateOffer
import wtf.iskra.core.update.UpdateSource
import wtf.iskra.core.update.archive
import wtf.iskra.core.update.installed
import java.io.File

/**
 * Обновление изнутри приложения (docs/PROMPT.md, 19.1): проверка ([UpdateChecker]) и установка через
 * `PackageInstaller` — скачать, проверить **до** установки, закрыть сеанс, поставить. Магазина нет, и
 * без этого первый релиз стал бы последним, который люди поставят.
 */
class Updates(
    context: Context,
    val checker: UpdateChecker,
    private var source: UpdateSource,
) {
    /**
     * Проба `update` (только debug): релиз из [other] вместо GitHub — чтобы пройти лист и установку на
     * эмуляторе с локальным сервером. В сборке с настоящей проверкой отказывает: интент с пробой может
     * прислать кто угодно.
     */
    suspend fun probe(other: UpdateSource): UpdateOffer? {
        if (checker.isInBuild) return null
        source = other
        return checker.probe(other)
    }

    private val context = context.applicationContext

    /** Где установка сейчас — для листа обновления. */
    sealed interface Install {
        data object Idle : Install

        /** Android ещё не разрешил Искре ставить приложения — экран объясняет до перехода в настройки. */
        data object NeedsPermission : Install

        data class Downloading(val fraction: Float) : Install

        data object Verifying : Install

        data object Installing : Install

        data class Refused(val problem: ApkProblem) : Install

        /** Не скачалось — сеть. */
        data object DownloadFailed : Install

        /** Система не поставила (отказ человека, нет места, конфликт). */
        data object InstallFailed : Install
    }

    private val mutableInstall = MutableStateFlow<Install>(Install.Idle)
    val install: StateFlow<Install> = mutableInstall.asStateFlow()

    private val folder = File(context.cacheDir, FOLDER)

    init {
        // Скачанное прошлым запуском больше не нужно: поставилось или нет — его ждать некому.
        folder.deleteRecursively()
    }

    /** Может ли Искра ставить приложения — разрешение «из этого источника», один раз. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Системный экран, где это разрешают. */
    fun permissionSettings(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Установка живёт дольше экрана, с которого её начали: закрытие сеанса перед ней уводит корень в
     * «Запуск», лист исчезает, и область экрана отменила бы установку на полпути.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** «Установить» — в своей области, см. [scope]. Вторая, пока идёт первая, не начинается. */
    fun startInstall(offer: UpdateOffer.Available, beforeInstall: suspend () -> Unit, afterFailure: suspend () -> Unit) {
        val state = mutableInstall.value
        if (state is Install.Downloading || state == Install.Verifying || state == Install.Installing) return
        scope.launch { install(offer, beforeInstall, afterFailure) }
    }

    /**
     * Скачать, проверить и поставить. [beforeInstall] закрывает сеанс — иначе процесс убьют посреди
     * записи в SQLite; [afterFailure] открывает его снова, если установка не случилась.
     */
    internal suspend fun install(offer: UpdateOffer.Available, beforeInstall: suspend () -> Unit, afterFailure: suspend () -> Unit) {
        if (!canInstall()) {
            mutableInstall.value = Install.NeedsPermission
            return
        }
        folder.deleteRecursively()
        val file = File(folder, SAFE_NAME)
        try {
            mutableInstall.value = Install.Downloading(0f)
            source.download(offer.asset.name, file) { done ->
                mutableInstall.value = Install.Downloading((done.toFloat() / offer.asset.size.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.UPDATE, "the update did not download: ${Log.describe(error)}")
            file.delete()
            mutableInstall.value = Install.DownloadFailed
            return
        }

        mutableInstall.value = Install.Verifying
        val problem = withContext(Dispatchers.IO) {
            val installed = PackageFacts.installed(context)
                ?: return@withContext ApkProblem.UNREADABLE
            ApkCheck.check(
                asset = offer.asset,
                size = file.length(),
                sha256 = ApkCheck.sha256(file),
                archive = PackageFacts.archive(context, file),
                installed = installed,
                manifestCertificate = offer.signingCertSha256,
            )
        }
        if (problem != null) {
            Log.error(Log.Category.UPDATE, "the downloaded update was refused: $problem")
            file.delete()
            mutableInstall.value = Install.Refused(problem)
            return
        }

        mutableInstall.value = Install.Installing
        beforeInstall()
        try {
            withContext(Dispatchers.IO) { commit(file) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.UPDATE, "the installer would not take the update: ${Log.describe(error)}")
            file.delete()
            mutableInstall.value = Install.InstallFailed
            afterFailure()
        }
    }

    /** Установка не случилась (ответ системы в [UpdateInstallReceiver]). */
    internal fun installFailed() {
        folder.deleteRecursively()
        mutableInstall.value = Install.InstallFailed
        onInstallFailed?.invoke()
    }

    /** Кто откроет сеанс снова, если система отказала уже после закрытия. */
    internal var onInstallFailed: (() -> Unit)? = null

    fun reset() {
        if (mutableInstall.value !is Install.Downloading && mutableInstall.value != Install.Installing) mutableInstall.value = Install.Idle
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Когда Искра уже установщик самой себя, Android 12+ обновляет без лишнего вопроса. Первый APK,
            // поставленный из браузера, этого права не даёт — тогда система спросит, и это нормально.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite(SAFE_NAME, 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Изменяемый: система вписывает в него статус. Компонент назван явно.
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    private companion object {
        const val FOLDER = "update"
        const val SAFE_NAME = "iskra-update.apk"
    }
}
