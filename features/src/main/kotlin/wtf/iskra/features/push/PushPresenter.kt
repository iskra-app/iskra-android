package wtf.iskra.features.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import wtf.iskra.core.notifications.PushNotification
import wtf.iskra.core.support.AppSettings
import wtf.iskra.design.blobatar.BlobatarBitmap
import wtf.iskra.features.R

/**
 * Расшифрованный пуш → уведомление Android. Аналог iOS communication notification — **conversation-
 * уведомление** (docs/PROMPT.md, 10.4): `MessagingStyle` с `Person` и долгоживущий ярлык разговора.
 * Без ярлыка Android 11+ не покажет чат в «Разговорах», не даст ему приоритета и нарисует обычное
 * уведомление приложения.
 *
 * Одно уведомление на чат (тег — аккаунт и комната): новое сообщение дописывается в него, а не
 * встаёт рядом. Звук и важность — системные настройки канала; Искра их не переопределяет.
 */
internal class PushPresenter(
    private val context: Context,
    private val settings: AppSettings,
    /** Значок в строке состояния — знак Искры из `:app` (общий с iOS, сюда не копируется). */
    private val smallIcon: Int,
) {
    private val manager = NotificationManagerCompat.from(context)

    /** Последнее показанное по тегу — чтобы дописать свой ответ, не пересобирая уведомление из ничего. */
    private val shown = HashMap<String, Shown>()

    private class Shown(val notification: PushNotification, val unread: Int?, val accountLabel: String?)

    init {
        createChannels()
    }

    /** [alert] — звенеть ли (по правилам пушей); false — встать на место уже прозвеневшей заглушки. */
    fun show(notification: PushNotification, unread: Int?, accountLabel: String?, alert: Boolean = true) {
        val tag = tag(notification.accountId, notification.roomId)
        synchronized(shown) { shown[tag] = Shown(notification, unread, accountLabel) }
        val sender = person(notification)
        val style = existingStyle(tag) ?: NotificationCompat.MessagingStyle(me())
        style.addMessage(notification.body, System.currentTimeMillis(), sender)
        post(tag, notification, unread, accountLabel, style, sender, alert)
    }

    /**
     * Свой ответ, ушедший из уведомления, — дописать в разговор: иначе Android крутит спиннер на поле
     * ввода, пока уведомление не обновят. Тихо: это не новость.
     */
    fun replied(roomId: String, accountId: String?, text: String) {
        val tag = tag(accountId, roomId)
        val last = synchronized(shown) { shown[tag] }
        if (last == null) {
            // Процесс успел умереть между пушем и ответом: собрать уведомление заново не из чего.
            manager.cancel(tag, ID)
            return
        }
        val style = existingStyle(tag) ?: NotificationCompat.MessagingStyle(me())
        style.addMessage(text, System.currentTimeMillis(), null as Person?)
        post(tag, last.notification, last.unread, last.accountLabel, style, person(last.notification), alert = false)
    }

    /**
     * Не расшифровалось за бюджет (или аккаунта на устройстве нет) — общий текст «Iskra · Новое
     * сообщение», те же слова, что у iOS-заглушки. Не тишина: сообщение, пришедшее молча, хуже
     * описанного плохо. Тот же тег, что у настоящего: расшифрованное потом встанет на его место.
     */
    @SuppressLint("MissingPermission") // canPost() — проверка разрешения на Android 13+.
    fun showFallback(roomId: String?, accountId: String?) {
        if (!canPost()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_OTHER)
            .setSmallIcon(smallIcon)
            .setContentTitle(context.getString(wtf.iskra.core.R.string.push_hidden_title))
            .setContentText(context.getString(wtf.iskra.core.R.string.push_hidden_body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
        if (roomId != null) builder.setContentIntent(open(roomId, accountId))
        manager.notify(tag(accountId, roomId), ID, builder.build())
    }

    /** Чат открыт на экране: его уведомления больше ничего не сообщают. */
    fun clear(roomId: String, accountId: String?) {
        val tag = tag(accountId, roomId)
        synchronized(shown) { shown.remove(tag) }
        manager.cancel(tag, ID)
    }

    /**
     * Всё аккаунта — на выходе из него или когда сервер сказал «непрочитанного нет» (прочитано на другом
     * устройстве). Null — всё приложения.
     */
    fun clearAccount(accountId: String?) {
        val prefix = accountId?.let { "$it$SEPARATOR" }
        synchronized(shown) { shown.keys.removeAll { prefix == null || it.startsWith(prefix) } }
        val active = runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications }.getOrNull().orEmpty()
        for (posted in active) {
            val tag = posted.tag ?: continue
            if (prefix == null || tag.startsWith(prefix)) manager.cancel(tag, posted.id)
        }
    }

    /** Вышли из аккаунта: и уведомления, и ярлыки разговоров — чужие чаты не остаются в «Разговорах». */
    fun forget(accountId: String) {
        clearAccount(accountId)
        val prefix = "$SHORTCUT$SEPARATOR$accountId$SEPARATOR"
        val ids = runCatching { ShortcutManagerCompat.getDynamicShortcuts(context) }.getOrNull().orEmpty()
            .map { it.id }.filter { it.startsWith(prefix) }
        if (ids.isNotEmpty()) runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, ids) }
    }

    @SuppressLint("MissingPermission") // canPost() — проверка разрешения на Android 13+.
    private fun post(
        tag: String,
        notification: PushNotification,
        unread: Int?,
        accountLabel: String?,
        style: NotificationCompat.MessagingStyle,
        sender: Person,
        alert: Boolean,
    ) {
        if (!canPost()) return
        style.isGroupConversation = notification.isGroup
        style.conversationTitle = if (notification.isGroup) notification.title else null
        while (style.messages.size > MESSAGES_KEPT) style.messages.removeAt(0)

        val shortcutId = pushShortcut(notification, sender)
        val builder = NotificationCompat.Builder(context, channel(notification))
            .setSmallIcon(smallIcon)
            .setStyle(style)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setSubText(accountLabel)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setContentIntent(open(notification.roomId, notification.accountId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            // Тише тихого правила не бывает: громкость пришла из правил пушей человека.
            .setSilent(!notification.isNoisy || !alert)
            .setOnlyAlertOnce(!alert)
        unread?.let(builder::setNumber)
        val accountId = notification.accountId
        if (!notification.isInvitation && accountId != null) {
            builder.addAction(replyAction(notification.roomId, accountId))
            builder.addAction(readAction(notification.roomId, accountId))
        }
        manager.notify(tag, ID, builder.build())
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun existingStyle(tag: String): NotificationCompat.MessagingStyle? {
        val active = runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications }.getOrNull().orEmpty()
        val posted = active.firstOrNull { it.tag == tag && it.id == ID } ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted.notification)
    }

    private fun channel(notification: PushNotification): String = when {
        notification.isInvitation -> CHANNEL_INVITATIONS
        notification.isMention -> CHANNEL_MENTIONS
        notification.isGroup -> CHANNEL_GROUPS
        else -> CHANNEL_DIRECT
    }

    private fun me(): Person = Person.Builder().setName(context.getString(R.string.message_spoken_you)).build()

    private fun person(notification: PushNotification): Person = Person.Builder()
        .setName(notification.personName ?: notification.title)
        // Matrix ID — то, по чему система понимает, что двадцать сообщений — от одного человека.
        .setKey(notification.personId)
        .setIcon(face(notification.avatar, notification.personId ?: notification.roomId))
        .build()

    /**
     * Лицо: фото, если успело приехать, иначе существо из того же сида, что в списке чатов (если
     * человек не выключил их в «Бете»); при «Существа всегда» — существо и поверх фото, как везде. Без того и другого система
     * нарисует букву сама.
     */
    private fun face(avatar: ByteArray?, seed: String): IconCompat? {
        val overrides = settings.creatures.value && settings.forceCreatures.value
        val photo = avatar?.takeUnless { overrides }?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        val bitmap: Bitmap = photo ?: if (settings.creatures.value) BlobatarBitmap.render(seed, FACE_SIZE) else return null
        return IconCompat.createWithBitmap(bitmap)
    }

    /** Долгоживущий ярлык разговора: без него нет «Разговоров», приоритета и пузырей. */
    private fun pushShortcut(notification: PushNotification, sender: Person): String {
        val id = listOf(SHORTCUT, notification.accountId ?: NO_ACCOUNT, notification.roomId).joinToString(SEPARATOR)
        val icon = if (notification.isGroup) face(null, notification.roomId) else sender.icon
        val builder = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(notification.title)
            .setLongLived(true)
            .setIntent(launch(notification.roomId, notification.accountId).setAction(Intent.ACTION_VIEW))
            .setLocusId(LocusIdCompat(id))
        if (!notification.isGroup) builder.setPerson(sender)
        icon?.let(builder::setIcon)
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, builder.build()) }
        return id
    }

    private fun launch(roomId: String, accountId: String?): Intent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return intent
            .putExtra(PushIntents.EXTRA_ROOM, roomId)
            .apply { if (accountId != null) putExtra(PushIntents.EXTRA_ACCOUNT, accountId) }
    }

    /** Нажатие — в *этот* разговор, в его аккаунт ([PushRouter]). */
    private fun open(roomId: String, accountId: String?): PendingIntent = PendingIntent.getActivity(
        context,
        tag(accountId, roomId).hashCode(),
        launch(roomId, accountId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, roomId: String, accountId: String) =
        Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(PushIntents.EXTRA_ROOM, roomId)
            .putExtra(PushIntents.EXTRA_ACCOUNT, accountId)

    private fun replyAction(roomId: String, accountId: String): NotificationCompat.Action {
        val label = context.getString(R.string.message_menu_reply)
        // Изменяемый: систему просят вписать в него текст ответа. Компонент назван явно.
        val intent = PendingIntent.getBroadcast(
            context,
            tag(accountId, roomId).hashCode(),
            actionIntent(PushIntents.ACTION_REPLY, roomId, accountId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(smallIcon, label, intent)
            .addRemoteInput(RemoteInput.Builder(PushIntents.KEY_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun readAction(roomId: String, accountId: String): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context,
            tag(accountId, roomId).hashCode() + 1,
            actionIntent(PushIntents.ACTION_READ, roomId, accountId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(smallIcon, context.getString(R.string.room_list_peek_mark_read), intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /**
     * Каналы. Имена — заново при каждом запуске: так они следуют за языком приложения. Важность задаётся
     * только при создании; дальше она — человека, в настройках системы.
     */
    private fun createChannels() {
        val channels = listOf(
            channel(CHANNEL_DIRECT, R.string.notifications_direct, NotificationManagerCompat.IMPORTANCE_HIGH),
            channel(CHANNEL_GROUPS, R.string.notifications_groups, NotificationManagerCompat.IMPORTANCE_HIGH),
            channel(CHANNEL_MENTIONS, R.string.notifications_mentions, NotificationManagerCompat.IMPORTANCE_HIGH),
            channel(CHANNEL_INVITATIONS, R.string.notifications_invitations, NotificationManagerCompat.IMPORTANCE_DEFAULT),
            channel(CHANNEL_OTHER, R.string.notification_channel_other, NotificationManagerCompat.IMPORTANCE_DEFAULT),
        )
        runCatching { manager.createNotificationChannelsCompat(channels) }
    }

    private fun channel(id: String, name: Int, importance: Int) =
        NotificationChannelCompat.Builder(id, importance).setName(context.getString(name)).build()

    private companion object {
        const val ID = 1
        const val SEPARATOR = "/"
        const val NO_ACCOUNT = "-"
        const val SHORTCUT = "room"

        /** Сколько последних сообщений чата держит уведомление: дальше — открыть чат. */
        const val MESSAGES_KEPT = 8

        /** Лицо на уведомлении в пикселях: системе хватает, а существо рисуется вектором. */
        const val FACE_SIZE = 256

        const val CHANNEL_DIRECT = "direct"
        const val CHANNEL_GROUPS = "groups"
        const val CHANNEL_MENTIONS = "mentions"
        const val CHANNEL_INVITATIONS = "invitations"
        const val CHANNEL_OTHER = "other"

        fun tag(accountId: String?, roomId: String?) = "${accountId ?: NO_ACCOUNT}$SEPARATOR${roomId.orEmpty()}"
    }
}

/** Что едет в интентах уведомлений — между [PushPresenter], `MainActivity` и [NotificationActionReceiver]. */
object PushIntents {
    const val EXTRA_ROOM = "room_id"
    const val EXTRA_ACCOUNT = "iskra_account"
    const val KEY_REPLY = "reply"
    const val ACTION_REPLY = "wtf.iskra.push.REPLY"
    const val ACTION_READ = "wtf.iskra.push.READ"
    const val ACTION_CANCEL_MEDIA = "wtf.iskra.media.CANCEL"
    const val EXTRA_JOB = "media_job"
}
