package wtf.iskra.features.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import wtf.iskra.core.support.Log
import wtf.iskra.features.R

/**
 * Восемь иконок приложения на выбор (`HomeIcon`). Каждая — `<activity-alias>` в манифесте `:app`, и включён ровно один:
 * какая сейчас, спрашивается у системы, а не хранится в настройках, — иначе два источника правды разошлись бы после
 * «Очистить данные» или обновления. Превью — PNG из iOS-ресурсов, а не попытка достать иконку у лаунчера.
 */
internal enum class AppIcon(val alias: String, @StringRes val title: Int, @DrawableRes val preview: Int) {
    SPARK("wtf.iskra.IconSpark", R.string.app_icon_spark, R.drawable.app_icon_spark),
    CHROME("wtf.iskra.IconChrome", R.string.app_icon_chrome, R.drawable.app_icon_chrome),
    GARNET("wtf.iskra.IconGarnet", R.string.app_icon_garnet, R.drawable.app_icon_garnet),
    LETTERS("wtf.iskra.IconLetters", R.string.app_icon_letters, R.drawable.app_icon_letters),
    PINE("wtf.iskra.IconPine", R.string.app_icon_pine, R.drawable.app_icon_pine),
    RETRO("wtf.iskra.IconRetro", R.string.app_icon_retro, R.drawable.app_icon_retro),
    RIO("wtf.iskra.IconRio", R.string.app_icon_rio, R.drawable.app_icon_rio),
    BLACK_WHITE("wtf.iskra.IconBlackWhite", R.string.app_icon_black_white, R.drawable.app_icon_black_white),
    ;

    companion object {
        private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, icon.alias)

        /** Какая включена сейчас. Пока ничего не выбирали, у всех «по умолчанию» и в лаунчере стоит знак Искры (его включил манифест). */
        fun current(context: Context): AppIcon = entries.firstOrNull { isExplicitlyEnabled(context, it) } ?: SPARK

        private fun isExplicitlyEnabled(context: Context, icon: AppIcon) =
            runCatching { context.packageManager.getComponentEnabledSetting(component(context, icon)) }.getOrNull() ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        /**
         * Включить [icon] и выключить остальные. Сначала включается новая, потом выключаются старые: на секунду
         * включены две, но не ноль — значок не пропадёт, если процесс убьют посередине. Не удалось — false.
         */
        fun select(context: Context, icon: AppIcon): Boolean = try {
            val manager = context.packageManager
            manager.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filter { it != icon }.forEach {
                manager.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            true
        } catch (error: Exception) {
            Log.error(Log.Category.SESSION, "иконка не сменилась: ${Log.describe(error)}")
            false
        }
    }
}
