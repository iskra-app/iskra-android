package wtf.iskra.core.support

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Имя акцента для хранения (`AccentTheme.storageName`); null — по умолчанию. */
    val accent: StateFlow<String?> = mutableAccent.asStateFlow()
    val appearance: StateFlow<String?> = mutableAppearance.asStateFlow()

    /** Демо-сервер вместо аккаунта. Настоящий аккаунт не трогается и возвращается, когда выключить. */
    val demoBackend: StateFlow<Boolean> = mutableDemo.asStateFlow()

    /** Существа вместо инициалов у тех, у кого нет картинки (`BetaFeatures.blobatar`), — по умолчанию да. */
    val creatures: StateFlow<Boolean> = mutableCreatures.asStateFlow()

    /** Существа даже вместо картинок — просьба, а не умолчание. */
    val forceCreatures: StateFlow<Boolean> = mutableForceCreatures.asStateFlow()

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
    }
}
