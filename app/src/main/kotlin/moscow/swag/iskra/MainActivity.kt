package moscow.swag.iskra

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
import moscow.swag.iskra.features.IskraRoot
import moscow.swag.iskra.features.Probes
import moscow.swag.iskra.features.RootModel
import moscow.swag.iskra.features.signin.CustomTabsAuthentication

class MainActivity : ComponentActivity() {
    /** Корень переживает поворот экрана: сеанс не пересобирается из-за смены конфигурации. */
    class RootHolder(application: IskraApplication) : ViewModel() {
        val root = RootModel(application.graph.authentication, viewModelScope)
    }

    private lateinit var browser: CustomTabsAuthentication
    private lateinit var holder: RootHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as IskraApplication
        holder = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RootHolder(app) as T
        })[RootHolder::class.java]
        browser = CustomTabsAuthentication(this, this)
        setContent { IskraRoot(holder.root, browser) }
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
    }
}
