package wtf.iskra.core

/**
 * Что за SDK лежит в приложении.
 *
 * Android и iOS сидят на одном коммите `matrix-rust-sdk` (docs/PROMPT.md, 2.2): тогда API совпадает
 * почти один в один, и поведение одной платформы можно сверять по другой. Коммит пинится здесь, а
 * инструментальный тест `SdkLinkageTest` проверяет, что собранная библиотека — именно с него: номер
 * версии Kotlin-биндингов от номера Swift-пакета отличается, и сверить их можно только так.
 */
object MatrixSdk {
    /** Коммит, с которого собран Swift-пакет `26.06.06` в iskra/Core/Package.swift. */
    const val EXPECTED_COMMIT = "1c44fb66214667c6d00acaf72ab592493653708b"

    /**
     * Коммит библиотеки, которая на самом деле загрузилась, — сокращённый (`1c44fb662`), каким его
     * отдаёт сам SDK. Первый вызов грузит нативный код.
     */
    fun commit(): String = org.matrix.rustcomponents.sdk.sdkGitSha()

    /** Тот ли это SDK, что у iOS: сокращённый хэш — начало полного. */
    fun isExpectedCommit(commit: String = commit()): Boolean =
        commit.length >= MIN_SHORT_SHA && EXPECTED_COMMIT.startsWith(commit)

    private const val MIN_SHORT_SHA = 7
}
