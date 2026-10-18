package wtf.iskra.core.support

import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.TracingFileConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Включает журнал Rust SDK, один раз за процесс. Порт `SDKLogging.swift`.
 *
 * Пока `initPlatform` никто не вызывает, SDK молчит — и вход, который застрял на телефоне, не видно
 * ничем. Уровень **не выше `INFO`**, и это не настройка: на `DEBUG`/`TRACE` SDK пишет тела запросов
 * и ответов, а в этом приложении тела — это сообщения людей. Параметра уровня нет намеренно:
 * правило, которое держится, только пока все помнят про значение по умолчанию, — не правило.
 * Чтобы поднять уровень, придётся править этот файл, и это ровно то трение, которого это заслуживает.
 *
 * Пишет в logcat и в кольцевые файлы в [directory] — их забирает «Отправить журнал» (позже).
 */
object SdkLogging {
    private val started = AtomicBoolean(false)

    /** Предел файлов журнала на диске: несколько дней обычной работы, а не история всей жизни. */
    private val MAX_TOTAL_BYTES: ULong = 16uL * 1024uL * 1024uL

    /** И не старше недели: журнал — для отладки того, что случилось недавно. */
    private val MAX_AGE_SECONDS: ULong = 7uL * 24uL * 60uL * 60uL

    fun start(directory: File) {
        if (!started.compareAndSet(false, true)) return
        directory.mkdirs()
        try {
            initPlatform(
                config = TracingConfiguration(
                    logLevel = LogLevel.INFO,
                    traceLogPacks = emptyList(),
                    extraTargets = emptyList(),
                    writeToStdoutOrSystem = true,
                    writeToFiles = TracingFileConfiguration(
                        path = directory.absolutePath,
                        filePrefix = "sdk",
                        fileSuffix = "log",
                        maxTotalSizeBytes = MAX_TOTAL_BYTES,
                        maxAgeSeconds = MAX_AGE_SECONDS,
                    ),
                    sentryConfig = null,
                ),
                useLightweightTokioRuntime = false,
            )
        } catch (error: Exception) {
            // Бросает, только если платформа уже поднята, а это флаг выше исключает.
            Log.error(Log.Category.SESSION, "журнал SDK не включился: ${Log.describe(error)}")
        }
    }
}
