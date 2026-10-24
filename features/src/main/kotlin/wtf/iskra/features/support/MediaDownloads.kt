package wtf.iskra.features.support

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Платная ли сеть сейчас (`NetworkCost`): мобильные данные, роуминг или включённая экономия трафика — всё, что Android называет
 * «с учётом тарифа». Wi-Fi и сеть без счётчика — нет. Не знаем — считаем платной: ошибиться лучше в сторону лишней кнопки, чем лишних мегабайт.
 */
object NetworkCost {
    private val mutable = MutableStateFlow(true)
    val isExpensive: StateFlow<Boolean> = mutable.asStateFlow()

    @Volatile
    private var watching = false

    /** Подписывается один раз за процесс; до первого ответа сети считается платной. Разрешение `ACCESS_NETWORK_STATE` объявлено в манифесте `:app`. */
    @android.annotation.SuppressLint("MissingPermission")
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        fun update(active: Network?) {
            val capabilities = active?.let { manager.getNetworkCapabilities(it) }
            mutable.value = capabilities == null ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update(network)
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network)
                override fun onLost(network: Network) = update(null)
            })
        }
        update(manager.activeNetwork)
    }
}

/** Что человек попросил скачать кнопкой, пока автозагрузка стояла: до конца жизни процесса оно качается и без настройки (`ManualDownloads`). */
object ManualDownloads {
    private val asked: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    fun contains(mediaId: String): Boolean = asked.containsKey(mediaId)

    fun ask(mediaId: String) {
        asked[mediaId] = true
    }
}
