package wtf.iskra.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сколько приложение может пробыть в фоне, прежде чем запрётся само. Значения — те, в которых человек думает: сразу, через минуту, через
 * пять; «сразу» — ноль, то есть любой выход из приложения запирает его, а остальные дают льготу для «глянул в другое приложение и
 * вернулся», что не должно стоить отпечатка.
 */
enum class AppLockTimeout(val seconds: Long, val storageName: String) {
    IMMEDIATELY(0, "immediately"),
    AFTER_MINUTE(60, "afterMinute"),
    AFTER_FIVE_MINUTES(5 * 60, "afterFiveMinutes");

    companion object {
        fun of(name: String?): AppLockTimeout? = entries.firstOrNull { it.storageName == name }
    }
}

/**
 * Замок над всем приложением: отпечаток, лицо или пин устройства — на запуске и на возвращении из фона после выбранной льготы. Порт `AppLock`.
 *
 * Чем проверять — дело `features` (`BiometricPrompt` с откатом на пин устройства: пин устройства сильнее пина приложения и приложению
 * нечего хранить). Здесь — только решение запереться, чистая функция того, когда приложение было активно последний раз, часов и льготы
 * ([shouldLock]), чтобы закрепить её тестом без часов и без фона.
 *
 * Время — `SystemClock.elapsedRealtime`: идёт и во сне, не зависит от перевода часов. Оно сбрасывается при перезагрузке, и «до» тогда
 * оказывается «после» — это читается как «запереть».
 */
class AppLock internal constructor(private val preferences: SharedPreferences?, private val clock: () -> Long) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
        { SystemClock.elapsedRealtime() },
    )

    private val mutableEnabled = MutableStateFlow(preferences?.getBoolean(ENABLED, false) ?: false)
    private val mutableTimeout = MutableStateFlow(AppLockTimeout.of(preferences?.getString(TIMEOUT, null)) ?: AppLockTimeout.IMMEDIATELY)

    /** Включён ли замок вообще. Хранится: переживает перезапуск. */
    val isEnabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** Сколько фон прощается, прежде чем замок закроется. */
    val timeout: StateFlow<AppLockTimeout> = mutableTimeout.asStateFlow()

    // Запуск — это холодный возврат: с включённым замком приложение закрыто до первой проверки. Ничто, что было до запуска, не поручится,
    // кто держит телефон.
    private val mutableLocked = MutableStateFlow(mutableEnabled.value)

    /** Заперто ли приложение *сейчас* и стоит ли щит. */
    val isLocked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    /** Когда приложение ушло в фон; null — не уходило с тех пор, как поднялось, или поездка уже отвечена проверкой. */
    private var lastActive: Long? = null

    /** Открыто ли системное окно проверки. Пока оно открыто, приход и уход активности принадлежат ему и не значат, что человек ушёл. */
    @Volatile
    private var isAuthenticating = false

    fun setEnabled(on: Boolean) {
        preferences?.edit()?.putBoolean(ENABLED, on)?.apply()
        mutableEnabled.value = on
        // Выключенный замок не держит щит; включённый не запирает того, кто только что его включил: он здесь и сам прошёл проверку.
        if (!on) mutableLocked.value = false
    }

    fun setTimeout(timeout: AppLockTimeout) {
        preferences?.edit()?.putString(TIMEOUT, timeout.storageName)?.apply()
        mutableTimeout.value = timeout
    }

    /**
     * Приложение ушло в фон: запомнить когда. Глухо к экрану, пока открыто окно проверки: оно принадлежит другому процессу и убирает
     * приложение с экрана, чтобы показаться, — приложению говорят, что оно ушло и вернулось, а оно не уходило, и «сразу» прочло бы это
     * как повод запереться снова в ту секунду, как отпечаток принят.
     */
    fun enteredBackground() {
        if (isAuthenticating) return
        val now = clock()
        lastActive = now
        preferences?.edit()?.putLong(LAST_ACTIVE, now)?.apply()
    }

    /**
     * Приложение вернулось. Запереть, если оно отсутствовало достаточно долго ([shouldLock]). Возврат без ухода за спиной не запирает
     * ничего — и этот сторож и есть весь цикл «открыл — заперло»: [shouldLock] читает отсутствие `lastActive` как «запереть», что верно для
     * тех, кто спрашивает холодным (запуск, уведомление), и неверно здесь, где приложение всё время было на экране.
     */
    fun enteredForeground() {
        val left = lastActive
        if (isAuthenticating || left == null) return
        if (shouldLock(mutableEnabled.value, left, clock(), mutableTimeout.value)) mutableLocked.value = true
    }

    /** Заперто ли приложение *с точки зрения того, кто не может спросить у самого замка*, — уведомления: пуш при запертом не показывает содержимого. */
    fun isLockedNow(): Boolean {
        if (!mutableEnabled.value) return false
        // До первой проверки с запуска (щит ещё стоит) и после ухода в фон — заперто; живое приложение на экране — нет.
        if (mutableLocked.value) return true
        val stamp = preferences?.getLong(LAST_ACTIVE, NEVER)?.takeIf { it != NEVER }
        // Приложение сейчас на экране: ушедшего не было, или поездка отвечена проверкой.
        return lastActive != null && shouldLock(true, stamp, clock(), mutableTimeout.value)
    }

    /** Окно проверки открылось — его приходы и уходы не наши. */
    fun authenticationStarted() {
        isAuthenticating = true
    }

    /** Окно проверки закрылось. Успех — щит падает, а поездка, что его подняла, потрачена. */
    fun authenticationFinished(success: Boolean) {
        isAuthenticating = false
        if (success) didAuthenticate()
    }

    /**
     * Щит падает, и поездка, что его подняла, потрачена. Забыть `lastActive` — смысл: это запись об уходе, на него уже ответили, а оставшись,
     * она заставила бы следующий возврат мерить ту же поездку второй раз и запереть снова. Отметка для уведомлений — *сейчас*: они видят
     * приложение в работе, а не всё ещё вне комнаты.
     */
    internal fun didAuthenticate() {
        mutableLocked.value = false
        lastActive = null
        preferences?.edit()?.putLong(LAST_ACTIVE, clock())?.apply()
    }

    companion object {
        private const val FILE = "iskra.lock"
        private const val ENABLED = "appLock.enabled"
        private const val TIMEOUT = "appLock.timeout"
        private const val LAST_ACTIVE = "appLock.lastActive"
        private const val NEVER = Long.MIN_VALUE

        /**
         * Вся логика запирания, чистая: включён и либо не видели, чтобы приложение уходило и возвращалось в пределах льготы, либо льгота
         * истекла. «Сразу» (ноль) запирает любой уход. Время «до», что оказалось «после» (перезагрузка сбросила часы), — запереть.
         */
        fun shouldLock(enabled: Boolean, lastActive: Long?, now: Long, timeout: AppLockTimeout): Boolean {
            if (!enabled) return false
            if (lastActive == null) return true
            if (now < lastActive) return true
            return now - lastActive >= timeout.seconds * MILLIS
        }

        private const val MILLIS = 1000L
    }
}
