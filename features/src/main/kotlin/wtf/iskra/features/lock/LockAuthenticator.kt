package wtf.iskra.features.lock

import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import wtf.iskra.core.security.AppLock
import wtf.iskra.features.R

/**
 * Замок, которым владеет окно: `BiometricPrompt` и то, что окно показывает системе. `MainActivity` только зовёт [LockHooks.of] и
 * передаёт события жизни ([AppLock.enteredForeground], [AppLock.enteredBackground]).
 */
internal object LockAuthenticator {
    /** Как `.deviceOwnerAuthentication` на iOS: отпечаток или лицо, а без них — пин устройства. */
    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Есть ли у устройства хоть какая-то защита, которой проверить владельца: отпечаток, лицо или пин. */
    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Отпечаток или лицо, а при их отсутствии или отказе — пин устройства: пин сильнее пина приложения, и приложению нечего хранить.
     * Окно проверки принадлежит системе и убирает приложение с экрана, чтобы показаться, — пока оно открыто, замок глух к приходам и уходам.
     */
    fun authenticate(activity: FragmentActivity, appLock: AppLock, onResult: (Boolean) -> Unit) {
        if (!canAuthenticate(activity)) {
            // Проверять нечем: замок, который нельзя открыть, не оставляет человека за дверью — он открывается, как только пин снова есть.
            appLock.authenticationFinished(success = true)
            onResult(true)
            return
        }
        appLock.authenticationStarted()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLock.authenticationFinished(success = true)
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    appLock.authenticationFinished(success = false)
                    onResult(false)
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_reason))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    /**
     * В «Недавних» не видно содержимого: на Android 13+ система сама не снимает окно, пока замок включён; ниже — `FLAG_SECURE`, пока он
     * включён (картинку берут, когда приложение уходит, а запирается оно уже на возврате). Заперто — `FLAG_SECURE` везде.
     */
    fun keepRecentsPrivate(activity: FragmentActivity, appLock: AppLock) {
        activity.lifecycleScope.launch {
            combine(appLock.isEnabled, appLock.isLocked) { enabled, locked -> enabled to locked }.collect { (enabled, locked) ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(!enabled)
                val secure = locked || (enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                if (secure) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}
