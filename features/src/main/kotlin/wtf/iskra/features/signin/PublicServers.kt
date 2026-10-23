package wtf.iskra.features.signin

import wtf.iskra.features.R
import java.util.Locale

/**
 * Публичный сервер, куда можно отправить человека без своего адреса. Порт `PublicServer`.
 *
 * **Список короткий нарочно, и каждый пункт проверен на этом приложении, а не по каталогу.** Он выбран из
 * servers.joinmatrix.org — серверов открытых, с людьми и обновляемых, — и урезан до тех, на которых «Создать аккаунт»
 * в Искре действительно работает: вход у них — OAuth с `prompt=create` (другого способа сделать аккаунт у
 * `registrationURL` нет) и они говорят на sliding sync. Большинство публичных серверов регистрирует через свою форму в
 * Element, и предложить такой здесь значило бы отправить человека на сервер, который отвечает «аккаунт отсюда не
 * сделать», — то единственное, чему такой список и должен мешать.
 *
 * Проверка перед добавлением: `GET /_matrix/client/v1/auth_metadata` обязан назвать `create` в `prompt_values_supported`.
 */
internal data class PublicServer(
    /** Что попадает в поле адреса. */
    val address: String,
    val about: Int,
    /** Где он находится. */
    val whereabouts: Int,
    /** Что стоит знать до выбора, — своим цветом, а не среди подробностей, где это читалось бы ещё одной из них. */
    val warning: Int? = null,
) {
    companion object {
        fun all(isRussian: Boolean): List<PublicServer> = listOf(
            PublicServer(
                "matrix.org", R.string.servers_matrix_org, R.string.servers_matrix_org_where,
                // Может не отвечать из России, и кому оттуда, лучше знать до выбора. Только на телефоне, в котором есть что-то
                // русское: остальным это шум.
                warning = if (isRussian) R.string.servers_matrix_org_blocked else null,
            ),
            PublicServer("tchncs.de", R.string.servers_tchncs, R.string.servers_tchncs_where),
            PublicServer("quassel.io", R.string.servers_quassel, R.string.servers_quassel_where),
            PublicServer("nope.chat", R.string.servers_nope_chat, R.string.servers_nope_chat_where),
        )

        /** Русский среди языков телефона или Россия его регионом. */
        fun isRussian(languages: List<Locale>): Boolean = languages.any { it.language == "ru" || it.country == "RU" }

        /**
         * Куда отправлен тот, кто хочет держать свой: наш собственный разбор, который говорит, что Искре нужно от сервера
         * (родной sliding sync), и сам выбирает язык.
         */
        const val OWN_SERVER_GUIDE = "https://iskra.wtf/server.html"
    }
}
