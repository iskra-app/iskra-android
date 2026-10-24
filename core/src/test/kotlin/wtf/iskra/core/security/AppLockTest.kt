package wtf.iskra.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Когда приложение должно закрыть свой замок. Порт `AppLockTests`: чистая логика, без отпечатков и фона. */
class AppLockTest {
    private var now = 1_000_000L
    @Test
    fun disabledLockNeverLocks() {
        assertFalse(AppLock.shouldLock(false, now, now, AppLockTimeout.IMMEDIATELY))
        assertFalse(AppLock.shouldLock(false, null, now, AppLockTimeout.AFTER_FIVE_MINUTES))
    }

    @Test
    fun noRecordOfLeavingMeansLock() {
        assertTrue(AppLock.shouldLock(true, null, now, AppLockTimeout.AFTER_FIVE_MINUTES))
    }

    @Test
    fun immediatelyLocksOnAnyTripAway() {
        assertTrue(AppLock.shouldLock(true, now - 1_000, now, AppLockTimeout.IMMEDIATELY))
    }

    @Test
    fun withinTheGracePeriodItStaysOpen() {
        assertFalse(AppLock.shouldLock(true, now - 30_000, now, AppLockTimeout.AFTER_MINUTE))
    }

    @Test
    fun pastTheGracePeriodAndExactlyAtItLocks() {
        assertTrue(AppLock.shouldLock(true, now - 120_000, now, AppLockTimeout.AFTER_MINUTE))
        assertTrue(AppLock.shouldLock(true, now - 60_000, now, AppLockTimeout.AFTER_MINUTE))
    }

    @Test
    fun fiveMinuteGraceHoldsForFour() {
        assertFalse(AppLock.shouldLock(true, now - 240_000, now, AppLockTimeout.AFTER_FIVE_MINUTES))
        assertTrue(AppLock.shouldLock(true, now - 360_000, now, AppLockTimeout.AFTER_FIVE_MINUTES))
    }

    @Test
    fun clockThatWentBackwardsLocks() {
        assertTrue(AppLock.shouldLock(true, now + 5_000, now, AppLockTimeout.AFTER_FIVE_MINUTES))
    }

    @Test
    fun launchWithTheLockOnStartsLocked() {
        // Включивший замок здесь и сам прошёл проверку — его не запирают; запуск — холодный, и он заперт.
        val here = AppLock(null) { now }
        here.setEnabled(true)
        assertFalse(here.isLocked.value)
        assertTrue(enabledAtLaunch().isLocked.value)
        assertFalse(AppLock(null) { now }.isLocked.value)
    }

    private fun enabledAtLaunch(): AppLock {
        val store = FakePreferences().apply { edit().putBoolean("appLock.enabled", true).apply() }
        return AppLock(store) { now }
    }

    @Test
    fun unlockingOnAColdLaunchSurvivesTheSceneGoingActiveAgain() {
        val lock = enabledAtLaunch()
        lock.didAuthenticate()
        now += 1_000
        lock.enteredForeground()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun comingBackFromARealTripAwayStillLocks() {
        val lock = enabledAtLaunch()
        lock.didAuthenticate()
        now += 10_000
        lock.enteredBackground()
        now += 10_000
        lock.enteredForeground()
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun aTripInsideTheGracePeriodDoesNotLock() {
        val lock = enabledAtLaunch().also { it.setTimeout(AppLockTimeout.AFTER_MINUTE) }
        lock.didAuthenticate()
        now += 10_000
        lock.enteredBackground()
        now += 10_000
        lock.enteredForeground()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun theSameTripIsNotCountedTwice() {
        val lock = enabledAtLaunch()
        lock.enteredBackground()
        now += 5_000
        lock.enteredForeground()
        assertTrue(lock.isLocked.value)
        now += 1_000
        lock.didAuthenticate()
        now += 1_000
        lock.enteredForeground()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun theAuthenticationSheetsComingsAndGoingsAreNotATrip() {
        val lock = enabledAtLaunch()
        lock.authenticationStarted()
        lock.enteredBackground()
        lock.authenticationFinished(success = true)
        now += 1_000
        lock.enteredForeground()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun notificationsSeeLockedWhileShieldIsUpOrAfterATripAway() {
        val lock = enabledAtLaunch().also { it.setTimeout(AppLockTimeout.AFTER_MINUTE) }
        assertTrue(lock.isLockedNow())
        lock.didAuthenticate()
        assertFalse(lock.isLockedNow())
        now += 1_000
        lock.enteredBackground()
        now += 10_000
        assertFalse(lock.isLockedNow())
        now += 120_000
        assertTrue(lock.isLockedNow())
    }
}

/** Хранилище настроек в памяти: JVM-тесты без Android. */
private class FakePreferences : android.content.SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { values[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = apply { values()[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { values()[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { values()[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { values()[key!!] = value }
        override fun remove(key: String?) = apply { values().remove(key) }
        override fun clear() = apply { values().clear() }
        override fun commit() = true
        override fun apply() = Unit
        private fun values() = this@FakePreferences.values
    }
}
