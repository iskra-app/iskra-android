package wtf.iskra

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import wtf.iskra.features.IskraRoot
import wtf.iskra.features.Probes
import wtf.iskra.features.signin.CustomTabsAuthentication
import wtf.iskra.features.signin.MockSignIn

class MainActivity : ComponentActivity() {
    /** Корень переживает поворот экрана: сеанс не пересобирается из-за смены конфигурации. */
    class RootHolder(application: IskraApplication, demo: Boolean, mockSignIn: String?) : ViewModel() {
        val root = application.graph.rootModel(viewModelScope, demo, mockSignIn)
    }

    private lateinit var browser: CustomTabsAuthentication
    private lateinit var holder: RootHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as IskraApplication
        holder = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RootHolder(
                app,
                intent.getBooleanExtra(EXTRA_MOCK_BACKEND, false),
                intent.getStringExtra(MockSignIn.EXTRA),
            ) as T
        })[RootHolder::class.java]
        browser = CustomTabsAuthentication(this, this)
        setContent { IskraRoot(holder.root, browser, appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})") }
        handle(intent)
    }

    // Синхронизация живёт, пока приложение видно: onStart/onStop, а не onResume/onPause — диалог
    // поверх или разделённый экран не повод её гасить.
    override fun onStart() {
        super.onStart()
        holder.root.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        holder.root.setForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (browser.handle(intent.data)) return
        val probe = intent.getStringExtra(EXTRA_PROBE) ?: return
        val extras = intent.extras?.keySet().orEmpty().associateWith { intent.extras?.getString(it).orEmpty() }
        lifecycleScope.launch { Probes.run(probe, extras, (application as IskraApplication).graph) }
    }

    private companion object {
        const val EXTRA_PROBE = "probe"

        /** `./android.sh -- --ez mock_backend true` — демо-сервер вместо аккаунта (docs/PROMPT.md, 14). */
        const val EXTRA_MOCK_BACKEND = "mock_backend"
    }
}
