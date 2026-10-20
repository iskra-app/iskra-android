package wtf.iskra

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import wtf.iskra.features.push.PushTransport
import java.util.UUID

/**
 * Пуши `foss` — UnifiedPush (docs/PROMPT.md, 10.2): телефону без сервисов Google пуши везёт приложение-
 * дистрибьютор (ntfy и другие), а Matrix-шлюз у дистрибьютора свой. Sygnal в этом пути не участвует.
 *
 * Протокол — свой, а не библиотека `connector`: её третья версия тянет Tink (Google), а `foss` собирается
 * без единой библиотеки Google (решение 2026-10-20). Протокол на Android — несколько broadcast-интентов
 * (https://unifiedpush.org/developers/spec/android/): REGISTER/UNREGISTER — дистрибьютору,
 * NEW_ENDPOINT/MESSAGE/UNREGISTERED — от него в [UnifiedPushReceiver].
 */
object FlavorPush : PushTransport {
    override fun start(context: Context) {
        val distributor = distributor(context)?.takeIf { installed(context, it) }
            ?: distributors(context).firstOrNull()?.packageName
            ?: return
        register(context, distributor)
    }

    override fun stop(context: Context) {
        val distributor = distributor(context) ?: return
        val token = preferences(context).getString(TOKEN, null) ?: return
        send(context, distributor, ACTION_UNREGISTER, token)
        preferences(context).edit().remove(TOKEN).remove(DISTRIBUTOR).apply()
    }

    override fun state(context: Context): PushTransport.State = when {
        distributors(context).isEmpty() -> PushTransport.State.NO_DISTRIBUTOR
        preferences(context).getString(ENDPOINT, null) != null -> PushTransport.State.READY
        else -> PushTransport.State.WAITING
    }

    override fun distributors(context: Context): List<PushTransport.Distributor> {
        val manager = context.packageManager
        return manager.queryBroadcastReceivers(Intent(ACTION_REGISTER), PackageManager.GET_RESOLVED_FILTER)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { PushTransport.Distributor(it.packageName, manager.getApplicationLabel(it).toString()) }
    }

    override fun distributor(context: Context): String? = preferences(context).getString(DISTRIBUTOR, null)

    override fun choose(context: Context, packageName: String) {
        val previous = distributor(context)
        if (previous != null && previous != packageName) stop(context)
        register(context, packageName)
    }

    /** Адрес от дистрибьютора — только по нашему токену: остальное кто угодно мог прислать. */
    internal fun isOurs(context: Context, token: String?): Boolean =
        token != null && token == preferences(context).getString(TOKEN, null)

    internal fun rememberEndpoint(context: Context, endpoint: String?) {
        val edit = preferences(context).edit()
        if (endpoint == null) edit.remove(ENDPOINT) else edit.putString(ENDPOINT, endpoint)
        edit.apply()
    }

    internal fun acknowledge(context: Context, token: String, id: String?) {
        val distributor = distributor(context) ?: return
        if (id == null) return
        context.sendBroadcast(Intent(ACTION_MESSAGE_ACK).setPackage(distributor).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_ID, id))
    }

    private fun register(context: Context, distributor: String) {
        val stored = preferences(context)
        // Токен — наш секрет соединения с дистрибьютором: по нему отличаются его сообщения от чужих.
        val token = stored.getString(TOKEN, null) ?: UUID.randomUUID().toString()
        stored.edit().putString(TOKEN, token).putString(DISTRIBUTOR, distributor).apply()
        send(context, distributor, ACTION_REGISTER, token)
    }

    private fun send(context: Context, distributor: String, action: String, token: String) {
        val intent = Intent(action)
            .setPackage(distributor)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_APPLICATION, context.packageName)
            // AND_3: дистрибьютор узнаёт приложение по создателю этого интента, а не по словам в extras.
            .putExtra(EXTRA_PI, PendingIntent.getBroadcast(context, 0, Intent(PI_ACTION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE))
            .putStringArrayListExtra(EXTRA_FEATURES, arrayListOf(FEATURE_BYTES_MESSAGE))
        context.sendBroadcast(intent)
    }

    private fun installed(context: Context, packageName: String) = distributors(context).any { it.packageName == packageName }

    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "iskra.unifiedpush"
    private const val TOKEN = "token"
    private const val DISTRIBUTOR = "distributor"
    private const val ENDPOINT = "endpoint"

    private const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    private const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    private const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    private const val FEATURE_BYTES_MESSAGE = "org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"
    private const val PI_ACTION = "org.unifiedpush.android.connector.AUTH"

    internal const val EXTRA_TOKEN = "token"
    internal const val EXTRA_ID = "id"
    private const val EXTRA_APPLICATION = "application"
    private const val EXTRA_PI = "pi"
    private const val EXTRA_FEATURES = "features"
}
