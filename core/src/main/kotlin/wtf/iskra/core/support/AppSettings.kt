package wtf.iskra.core.support

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import wtf.iskra.core.media.MediaCompression
import wtf.iskra.core.media.MediaSettings
import wtf.iskra.core.session.PrivacySettings
import wtf.iskra.core.timeline.ThreadSettings
import wtf.iskra.core.update.UpdateStore

/**
 * Настройки приложения, а не аккаунта: оформление, бета-функции. Не секреты — поэтому обычные
 * `SharedPreferences`; секреты живут в Keystore (`KeystoreSecrets`), а бэкап выключен целиком.
 *
 * Значения — строками и флагами: `:core` не знает, что такое акцент (`AccentTheme` — в `:design`),
 * экран переводит сам. Каждое — `StateFlow`, чтобы перемена в настройках сразу перекрашивала всё.
 */
class AppSettings(context: Context) : UpdateStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        // Выбора шрифта больше нет: что человек выбрал раньше, не должно лежать мёртвым грузом.
        if (preferences.contains(LEGACY_FONT)) preferences.edit().remove(LEGACY_FONT).apply()
    }

    private fun string(key: String) = MutableStateFlow(preferences.getString(key, null))
    private fun flag(key: String, default: Boolean) = MutableStateFlow(preferences.getBoolean(key, default))

    private val mutableAccent = string(ACCENT)
    private val mutableAppearance = string(APPEARANCE)
    private val mutableDemo = flag(DEMO, false)
    private val mutableCreatures = flag(CREATURES, CREATURES_BY_DEFAULT)
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

    /**
     * Существа вместо инициалов у тех, у кого нет картинки (`BetaFeatures.blobatar`), — по умолчанию нет,
     * как на iOS (`blobatarByDefault`). Они меняют лицо каждого человека в приложении: это то, о чём просят,
     * а не то, что находят. Остаются в «Бета» для того, кто пойдёт искать; выключено — везде сразу (список,
     * уведомления), все откатываются к инициалам вместе.
     */
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

    private val mutableThreads = MutableStateFlow(
        ThreadSettings(
            shown = ThreadSettings.Shown.of(preferences.getString(THREADS_SHOWN, null)) ?: ThreadSettings.SHOWN_BY_DEFAULT,
            hidesReplies = preferences.getBoolean(THREADS_HIDE_REPLIES, ThreadSettings.HIDES_REPLIES_BY_DEFAULT),
        ),
    )

    /** Где треды и уходят ли ответы из ленты (`ThreadSettings`). Читается, когда комната открывается. */
    val threads: StateFlow<ThreadSettings> = mutableThreads.asStateFlow()

    fun setThreads(settings: ThreadSettings) {
        preferences.edit()
            .putString(THREADS_SHOWN, settings.shown.storageName)
            .putBoolean(THREADS_HIDE_REPLIES, settings.hidesReplies)
            .apply()
        mutableThreads.value = settings
    }

    private val mutablePrivacy = MutableStateFlow(
        PrivacySettings(
            hidesPresence = preferences.getBoolean(PRIVACY_PRESENCE, PrivacySettings.HIDES_PRESENCE_BY_DEFAULT),
            hidesTyping = preferences.getBoolean(PRIVACY_TYPING, PrivacySettings.HIDES_TYPING_BY_DEFAULT),
        ),
    )

    /** Что другие узнают о вас без вашего слова (`PrivacySettings`). Читается в момент, когда это важно. */
    val privacy: StateFlow<PrivacySettings> = mutablePrivacy.asStateFlow()

    fun setPrivacy(settings: PrivacySettings) {
        preferences.edit()
            .putBoolean(PRIVACY_PRESENCE, settings.hidesPresence)
            .putBoolean(PRIVACY_TYPING, settings.hidesTyping)
            .apply()
        mutablePrivacy.value = settings
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

    private val mutableWallpaper = string(WALLPAPER)
    private val mutableWallpaperDimming = MutableStateFlow(preferences.getFloat(WALLPAPER_DIMMING, NO_DIMMING).takeIf { it >= 0f })

    /** Имя обоев для хранения (`Wallpaper.storageName`); null — без обоев. Одни на всё приложение, как акцент. */
    val wallpaper: StateFlow<String?> = mutableWallpaper.asStateFlow()

    /** Насколько обои приглушены, 0..; null — по умолчанию (решает экран: `Wallpaper.DEFAULT_DIMMING`). */
    val wallpaperDimming: StateFlow<Float?> = mutableWallpaperDimming.asStateFlow()

    fun setWallpaper(name: String) = save(WALLPAPER, name, mutableWallpaper)

    fun setWallpaperDimming(value: Float) {
        preferences.edit().putFloat(WALLPAPER_DIMMING, value).apply()
        mutableWallpaperDimming.value = value
    }

    fun setAccent(name: String) = save(ACCENT, name, mutableAccent)
    fun setAppearance(name: String) = save(APPEARANCE, name, mutableAppearance)
    fun setDemoBackend(on: Boolean) = save(DEMO, on, mutableDemo)

    private val mutableUpdateChecks = flag(UPDATE_CHECK, true)

    /** «Проверять обновления» (docs/PROMPT.md, 19.1): включено по умолчанию. */
    val updateChecks: StateFlow<Boolean> = mutableUpdateChecks.asStateFlow()

    fun setUpdateChecks(on: Boolean) = save(UPDATE_CHECK, on, mutableUpdateChecks)

    override val checksForUpdates: Boolean get() = mutableUpdateChecks.value

    override var lastUpdateCheck: Long
        get() = preferences.getLong(UPDATE_LAST_CHECK, 0L)
        set(value) = preferences.edit().putLong(UPDATE_LAST_CHECK, value).apply()

    override var cachedRelease: String?
        get() = preferences.getString(UPDATE_CACHED, null)
        set(value) = preferences.edit().putString(UPDATE_CACHED, value).apply()

    override var dismissedUpdate: Long?
        get() = preferences.getLong(UPDATE_DISMISSED, -1L).takeIf { it >= 0 }
        set(value) = preferences.edit().apply { if (value == null) remove(UPDATE_DISMISSED) else putLong(UPDATE_DISMISSED, value) }.apply()

    /**
     * Спрашивали ли уже разрешение на уведомления. Один раз, после первого входа: система показывает
     * вопрос не больше двух раз за жизнь приложения, и спрашивать на каждом запуске — растратить оба.
     */
    val askedForNotifications: Boolean get() = preferences.getBoolean(NOTIFICATIONS_ASKED, false)

    fun setAskedForNotifications() {
        preferences.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
    }
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
        // Ключи — те же, что у iOS (`Wallpaper.storageKey`, `.dimmingKey`).
        const val WALLPAPER = "appearance.wallpaper"
        const val WALLPAPER_DIMMING = "appearance.wallpaperDimming"
        const val NO_DIMMING = -1f
        const val APPEARANCE = "appearance.mode"
        /** Прежний выбор шрифта (Inter или системный): выбора больше нет (2026-10-24), запись убирается. */
        const val LEGACY_FONT = "appearance.font"
        const val DEMO = "beta.mockBackend"
        const val NOTIFICATIONS_ASKED = "notifications.permissionAsked"
        const val UPDATE_CHECK = "update.check"
        const val UPDATE_LAST_CHECK = "update.lastCheck"
        const val UPDATE_CACHED = "update.cached"
        const val UPDATE_DISMISSED = "update.dismissed"
        const val CREATURES = "beta.blobatar"
        const val CREATURES_BY_DEFAULT = false
        const val FORCE_CREATURES = "beta.blobatar.force"

        // Ключи — те же, что у iOS (`MediaSettings.*Key`).
        const val MEDIA_STRIPS = "media.stripsMetadata"
        const val MEDIA_COMPRESSES = "media.compresses"
        const val MEDIA_COMPRESSION = "media.compression"
        // Ключи — те же, что у iOS (`PrivacySettings.*Key`).
        const val PRIVACY_PRESENCE = "privacy.hidesPresence"
        const val PRIVACY_TYPING = "privacy.hidesTyping"
        const val RECENT_REACTIONS = "reactions.recent"
        const val THREADS_SHOWN = "chats.threads.shown"
        const val THREADS_HIDE_REPLIES = "chats.threads.hidesReplies"
    }
}
