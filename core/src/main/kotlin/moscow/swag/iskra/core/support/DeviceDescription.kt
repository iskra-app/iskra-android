package moscow.swag.iskra.core.support

import android.os.Build

/**
 * Что сервер пишет в списке устройств человека. Порт `DeviceDescription.swift`.
 *
 * Без своего User-Agent SDK шлёт свой, который описывает библиотеку на Rust, и страница аккаунта
 * говорит «Неизвестное устройство». Для входа через браузер это **единственный** рычаг: у
 * `urlForOauth` нет параметра имени устройства, а переименовать устройство потом FFI не умеет.
 */
object DeviceDescription {
    fun userAgent(appVersion: String): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
        return "Iskra/$appVersion (${name.ifEmpty { "Android" }}; Android ${Build.VERSION.RELEASE})"
    }
}
