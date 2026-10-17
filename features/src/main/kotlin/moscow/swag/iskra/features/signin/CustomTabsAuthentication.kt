package moscow.swag.iskra.features.signin

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import moscow.swag.iskra.core.support.AppEnvironment

/**
 * Вход через браузер на Android: Custom Tabs туда, intent-filter на `moscow.swag.iskra:/oauth-callback`
 * обратно (docs/PROMPT.md, 2.2). Сам OIDC делает SDK; здесь только поход и возвращение.
 *
 * У Custom Tabs нет «браузер закрыли» — есть только то, что [Activity] снова на экране без callback.
 * Поэтому: вкладка открыта → activity ушла с экрана → вернулась, и за [GRACE_MS] callback так и не
 * пришёл → человек закрыл браузер сам (это не ошибка). Callback, пришедший первым, побеждает.
 */
class CustomTabsAuthentication(private val activity: Activity, lifecycle: LifecycleOwner) : WebAuthentication {
    private var waiting: CompletableDeferred<String?>? = null
    private var leftForBrowser = false

    init {
        lifecycle.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (waiting != null) leftForBrowser = true
            }

            override fun onResume(owner: LifecycleOwner) {
                val pending = waiting ?: return
                if (!leftForBrowser) return
                activity.window.decorView.postDelayed({ if (pending.isActive) pending.complete(null) }, GRACE_MS)
            }
        })
    }

    override suspend fun run(url: String): String? {
        val deferred = CompletableDeferred<String?>()
        waiting = deferred
        leftForBrowser = false
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(url))
        return try {
            deferred.await()
        } finally {
            waiting = null
        }
    }

    /**
     * Activity получила intent. Если это наш callback — отдаёт его ожидающему и возвращает true.
     * Сам URL никуда не пишется: в нём код авторизации.
     */
    fun handle(uri: Uri?): Boolean {
        if (uri == null || !isCallback(uri)) return false
        waiting?.complete(uri.toString())
        return true
    }

    companion object {
        private const val GRACE_MS = 600L
        private val callback = Uri.parse(AppEnvironment.OAUTH_REDIRECT_URI)

        fun isCallback(uri: Uri) = uri.scheme == callback.scheme && uri.path == callback.path
    }
}
