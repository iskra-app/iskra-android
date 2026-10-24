package wtf.iskra.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlin.math.pow

/**
 * Что за разговором. Порт `Wallpaper`.
 *
 * По умолчанию — ничего, и это честная точка отсчёта: чат — это слова, а страница, на которой они написаны, должна уйти с дороги. Но страница,
 * на которой идёт разговор, — единственная поверхность в мессенджере, что ничья и общая, она и есть комната, и сделать её своей — самая старая
 * настройка.
 *
 * Восемь вариантов и не больше: без обоев, шесть градиентов и фотография самого человека. Градиенты — не картинки нарочно. Чужая фотография —
 * чужая: она стареет, весит мегабайт в комплекте и спорит с сообщениями перед ней, а два цвета, перетекающие по экрану, ничего не стоят, подгоняются
 * под любое окно и тихи настолько, чтобы писать поверх. Единственная картинка, которую стоит класть за разговор, — выбранная самим человеком.
 *
 * Выбор один на всё приложение, не по комнатам: это решение, а не ограничение. Обои — как выглядит *это приложение* для того, кто им пользуется,
 * как акцент, а восемь комнат с восемью фонами — другое приложение в каждом разговоре.
 */
enum class Wallpaper(val storageName: String, private val colors: List<List<Float>>) {
    NONE("none", emptyList()),
    SUNSET("sunset", listOf(listOf(0.98f, 0.55f, 0.35f), listOf(0.54f, 0.24f, 0.47f))),
    SEA("sea", listOf(listOf(0.36f, 0.68f, 0.86f), listOf(0.10f, 0.28f, 0.52f))),
    FOREST("forest", listOf(listOf(0.51f, 0.74f, 0.51f), listOf(0.11f, 0.31f, 0.27f))),
    DUSK("dusk", listOf(listOf(0.44f, 0.45f, 0.74f), listOf(0.16f, 0.15f, 0.33f))),
    SAND("sand", listOf(listOf(0.93f, 0.85f, 0.70f), listOf(0.66f, 0.51f, 0.38f))),
    NIGHT("night", listOf(listOf(0.22f, 0.24f, 0.30f), listOf(0.05f, 0.05f, 0.09f))),

    /** Картинка из библиотеки человека, хранится на этом устройстве (`WallpaperStore`). */
    PHOTO("photo", emptyList());

    /** Нарисовано ли за разговором вообще что-нибудь. */
    val isSet: Boolean get() = this != NONE

    /** Два цвета сверху вниз. Не системные именованные: те — *управляющие*, настроены на нажатие, а два таких рядом — фон, что похож на растянутую кнопку. */
    internal val gradient: List<Color> get() = colors.map { Color(it[0], it[1], it[2]) }

    /**
     * Яркость градиента, 0..1, — среднее относительной яркости двух его цветов. Null у фотографии, чью яркость надо мерить, и у «без обоев».
     */
    val luminance: Float?
        get() = colors.takeIf { it.isNotEmpty() }?.map { luminance(it[0], it[1], it[2]) }?.average()?.toFloat()

    companion object {
        /** Обои по умолчанию: ничего, страница, на которой сидят все остальные экраны. */
        val DEFAULT = NONE

        /** Не дальше этого: чтобы яркая фотография стала такой, на которой читается серый пузырь, а не чёрным экраном со слухом о картинке за ним. */
        const val MAXIMUM_DIMMING = 0.8f

        /**
         * Четверть. Градиенту она не нужна, а фотографии обычно нужна, и начинать с нуля значило бы, что первое, что человек видит после выбора своей
         * картинки, — её самая нечитаемая версия, а читается это как отсутствие ответа у приложения, а не как ползунок, который ещё не двигали.
         */
        const val DEFAULT_DIMMING = 0.25f

        fun fromStorage(name: String?): Wallpaper = entries.firstOrNull { it.storageName == name } ?: DEFAULT

        /** Относительная яркость цвета sRGB по определению WCAG. */
        fun luminance(red: Float, green: Float, blue: Float): Float {
            fun linear(channel: Float) = if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
            return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
        }

        /**
         * Светлы ли должны быть слова на этих обоях. Мерится после вуали: та темнит в тёмной теме и светлит в светлой ([lightens]). Порог — где
         * белый и чёрный текст контрастны одинаково (WCAG): ниже лучше читается белый, выше — чёрный.
         */
        fun isDark(luminance: Float, dimming: Float, lightens: Boolean = false): Boolean {
            val veiled = if (lightens) luminance * (1 - dimming) + dimming else luminance * (1 - dimming)
            return veiled < THRESHOLD
        }

        private const val THRESHOLD = 0.179f
    }
}

/**
 * Обои, нарисованные. Картинку человека берёт снаружи, а не грузит: загрузка — это файловая система, а она — дело приложения, не этого слоя.
 * Здесь — градиент, картинка и насколько её затемнить.
 *
 * Вуаль идёт в сторону темы: к чёрному в тёмной, к белому в светлой, — так «приглушить» значит «сделать тише» и в той, и в другой.
 */
@Composable
fun WallpaperBackdrop(wallpaper: Wallpaper, photo: ImageBitmap?, dimming: Float, modifier: Modifier = Modifier) {
    val dark = LocalAccent.current.isDark
    Box(modifier.fillMaxSize()) {
        when (wallpaper) {
            Wallpaper.NONE -> Unit
            Wallpaper.PHOTO ->
                // Заполняет, а не вписывает: обои с полосами по двум сторонам — не обои.
                if (photo != null) Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(wallpaper.gradient)))
        }
        if (wallpaper.isSet && dimming > 0f && (wallpaper != Wallpaper.PHOTO || photo != null)) {
            Box(Modifier.fillMaxSize().background((if (dark) Color.Black else Color.White).copy(alpha = dimming)))
        }
    }
}
