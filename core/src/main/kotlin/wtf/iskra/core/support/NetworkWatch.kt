package wtf.iskra.core.support

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Сеть устройства — только чтобы заметить, что она *вернулась*, и не ждать паузы (`ConnectivityManager.NetworkCallback`,
 * docs/PROMPT.md, 4.6). Дойдёт ли до сервера, говорит синхронизация; интерфейс поднят и за captive portal, и в VPN без
 * маршрута, и перед сервером, который просто лёг. Порт `watchPath` (`NWPathMonitor`).
 */
// ACCESS_NETWORK_STATE объявлен манифестом `:app`, у библиотеки своего манифеста с разрешениями нет.
@SuppressLint("MissingPermission")
class NetworkWatch(context: Context) {
    private val manager: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Есть ли сейчас сеть с выходом в интернет. */
    fun isAvailable(): Boolean {
        val manager = manager ?: return true
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * [onBack] зовётся, когда сеть *появилась* после того, как пропадала: не при подписке. Вызов —
     * с потока системы; тот, кто зовёт, переносит работу на свой.
     */
    fun watch(onBack: () -> Unit): AutoCloseable {
        val manager = manager ?: return AutoCloseable { }
        var was = isAvailable()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val came = !was
                was = true
                if (came) onBack()
            }

            override fun onLost(network: Network) {
                // Безусловно: в миг `onLost` система ещё считает пропавшую сеть активной, и спросить её нельзя.
                // Лишнее «вернулась» после смены Wi-Fi на мобильную безвредно — перезапуск идёт, только если сервер
                // и так не отвечает, — а пропущенное стоило бы «Ожидания сети» до следующей паузы.
                was = false
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            AutoCloseable { runCatching { manager.unregisterNetworkCallback(callback) } }
        } catch (_: SecurityException) {
            // Нет ACCESS_NETWORK_STATE — без слежения живём: сеть заметит синхронизация, с паузой.
            AutoCloseable { }
        }
    }
}
