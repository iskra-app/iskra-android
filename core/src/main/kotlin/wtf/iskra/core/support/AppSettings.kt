package wtf.iskra.core.support

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import wtf.iskra.core.media.MediaCompression
import wtf.iskra.core.media.MediaSettings

/**
 * Настройки приложения, а не аккаунта: оформление, бета-функции. Не секреты — поэтому обычные
 * `SharedPreferences`; секреты живут в Keystore (`KeystoreSecrets`), а бэкап выключен целиком.
 *
 * Значения — строками и флагами: `:core` не знает, что такое акцент (`AccentTheme` — в `:design`),
 * экран переводит сам. Каждое — `StateFlow`, чтобы перемена в настройках сразу перекрашивала всё.
 */
class AppSettings(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun string(key: String) = MutableStateFlow(preferences.getString(key, null))
    private fun flag(key: String, default: Boolean) = MutableStateFlow(preferences.getBoolean(key, default))

    private val mutableAccent = string(ACCENT)
    private val mutableAppearance = string(APPEARANCE)
    private val mutableDemo = flag(DEMO, false)
    private val mutableCreatures = flag(CREATURES, true)
    private val mutableForceCreatures = flag(FORCE_CREATURES, false)
    private val mutableMedia = MutableStateFlow(
        MediaSettings(
            stripsMetadata = preferences.getBoolean(MEDIA_STRIPS, MediaSettings.STRIPS_METADATA_BY_DEFAULT),
            compresses = preferences.getBoolean(MEDIA_COMPRESSES, MediaSettings.COMPRESSES_BY_DEFAULT),
            compression = MediaCompression.of(preferences.getString(MEDIA_COMPRESSION, null)) ?: MediaSettings.COMPRESSION_BY_DEFAULT,
        ),
    )

    /** Имя акцента для хранения (`AccentTheme.storageName`); null — по умолчанию. */
    val accent: StateFlow<String?> = mutableAccent.asStateFlow()
    val appearance: StateFlow<String?> = mutableAppearance.asStateFlow()

    /** Демо-сервер вместо аккаунта. Настоящий аккаунт не трогается и возвращается, когда выключить. */
    val demoBackend: StateFlow<Boolean> = mutableDemo.asStateFlow()

    /** Существа вместо инициалов у тех, у кого нет картинки (`BetaFeatures.blobatar`), — по умолчанию да. */
    val creatures: StateFlow<Boolean> = mutableCreatures.asStateFlow()

    /** Существа даже вместо картинок — просьба, а не умолчание. */
    val forceCreatures: StateFlow<Boolean> = mutableForceCreatures.asStateFlow()

    private val mutableRecentReactions = MutableStateFlow(RecentReactions.list(preferences.getString(RECENT_REACTIONS, null).orEmpty()))

    /** Эмодзи, за которыми человек тянулся недавно, — вверху панели эмодзи (`RecentEmoji`). */
    val recentReactions: StateFlow<List<String>> = mutableRecentReactions.asStateFlow()

    fun rememberReaction(emoji: String) {
        val stored = RecentReactions.adding(emoji, preferences.getString(RECENT_REACTIONS, null).orEmpty())
        preferences.edit().putString(RECENT_REACTIONS, stored).apply()
        mutableRecentReactions.value = RecentReactions.list(stored)
    }

    /** Что делать с фото и видео по дороге наружу. Читается в момент отправки. */
    val media: StateFlow<MediaSettings> = mutableMedia.asStateFlow()

    fun setMedia(settings: MediaSettings) {
        preferences.edit()
            .putBoolean(MEDIA_STRIPS, settings.stripsMetadata)
            .putBoolean(MEDIA_COMPRESSES, settings.compresses)
            .putString(MEDIA_COMPRESSION, settings.compression.storageName)
            .apply()
        mutableMedia.value = settings
    }

    fun setAccent(name: String) = save(ACCENT, name, mutableAccent)
    fun setAppearance(name: String) = save(APPEARANCE, name, mutableAppearance)
    fun setDemoBackend(on: Boolean) = save(DEMO, on, mutableDemo)
    fun setCreatures(on: Boolean) = save(CREATURES, on, mutableCreatures)
    fun setForceCreatures(on: Boolean) = save(FORCE_CREATURES, on, mutableForceCreatures)

    private fun save(key: String, value: String, flow: MutableStateFlow<String?>) {
        preferences.edit().putString(key, value).apply()
        flow.value = value
    }

    private fun save(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) {
        preferences.edit().putBoolean(key, value).apply()
        flow.value = value
    }

    private companion object {
        const val FILE = "iskra.settings"
        const val ACCENT = "appearance.accent"
        const val APPEARANCE = "appearance.mode"
        const val DEMO = "beta.mockBackend"
        const val CREATURES = "beta.blobatar"
        const val FORCE_CREATURES = "beta.blobatar.force"

        // Ключи — те же, что у iOS (`MediaSettings.*Key`).
        const val MEDIA_STRIPS = "media.stripsMetadata"
        const val MEDIA_COMPRESSES = "media.compresses"
        const val MEDIA_COMPRESSION = "media.compression"
        const val RECENT_REACTIONS = "reactions.recent"
    }
}
