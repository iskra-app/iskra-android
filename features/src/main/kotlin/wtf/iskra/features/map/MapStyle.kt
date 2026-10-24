package wtf.iskra.features.map

/**
 * Откуда карта берёт вид: один адрес, чтобы сменить провайдера одной строкой (docs/PROMPT.md, 11.6). OpenFreeMap — векторные тайлы
 * по данным OpenStreetMap, без ключа и без регистрации. Цена: сервер тайлов видит IP и *какой район смотрят*, поэтому карта никогда не
 * грузится сама по себе вне ленты, а в ленте подчиняется той же автозагрузке, что фото. Атрибуция OpenStreetMap и OpenFreeMap — на карте
 * (кнопка «i» MapLibre) и в «О приложении».
 */
object MapStyle {
    private const val BASE = "https://tiles.openfreemap.org/styles/"
    const val LIBERTY = BASE + "liberty"
    const val DARK = BASE + "dark"

    fun forTheme(dark: Boolean): String = if (dark) DARK else LIBERTY

    /** Приближение снимка в пузыре: примерно пятьсот метров поперёк, как на iOS. */
    const val BUBBLE_ZOOM = 15.0

    /** Куда смотрит выбор места, пока нет ни точки, ни разрешения: мир целиком. */
    const val WORLD_ZOOM = 1.0

    /** Куда прилетает выбор места на «Моё местоположение». */
    const val ME_ZOOM = 16.0
}
