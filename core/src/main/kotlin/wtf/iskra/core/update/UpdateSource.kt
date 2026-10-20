package wtf.iskra.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Откуда берутся `release.json` и APK. За интерфейсом — чтобы тесты считали запросы на фейке. */
interface UpdateSource {
    suspend fun latestRelease(): String

    /** Скачивает [name] из последнего релиза в [target]; [progress] — скачано байт. */
    suspend fun download(name: String, target: File, progress: (Long) -> Unit)
}

/**
 * GitHub Releases самого `iskra-app/iskra-android` (docs/PROMPT.md, 19). `latest/download/…` — редирект
 * на CDN GitHub, без API и его лимита; пре-релизы и черновики в `latest` не попадают — это и есть канал.
 *
 * Уходит только сам GET: `User-Agent` — `Iskra` без версии, без языка и без идентификаторов.
 */
class GitHubUpdateSource(private val base: String = LATEST) : UpdateSource {
    override suspend fun latestRelease(): String = withContext(Dispatchers.IO) {
        open("$base/$MANIFEST").run {
            try {
                if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP $responseCode")
                // С пределом: по адресу может оказаться что угодно, и «что угодно» — не размер.
                val bytes = java.io.ByteArrayOutputStream()
                inputStream.use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes.write(buffer, 0, read)
                        if (bytes.size() > MANIFEST_LIMIT) throw IOException("release.json is too large")
                    }
                }
                bytes.toByteArray().decodeToString()
            } finally {
                disconnect()
            }
        }
    }

    override suspend fun download(name: String, target: File, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        open("$base/$name").run {
            try {
                if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP $responseCode")
                inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            total += read
                            progress(total)
                        }
                    }
                }
            } finally {
                disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("User-Agent", USER_AGENT)
        useCaches = false
    }

    companion object {
        const val LATEST = "https://github.com/iskra-app/iskra-android/releases/latest/download"
        private const val MANIFEST = "release.json"
        private const val USER_AGENT = "Iskra"
        private const val MANIFEST_LIMIT = 256 * 1024
        private const val BUFFER = 64 * 1024
        private const val TIMEOUT_MS = 15_000
    }
}
