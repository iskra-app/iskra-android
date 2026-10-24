package wtf.iskra.features.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.LocalSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import wtf.iskra.design.Theme
import wtf.iskra.features.AppGraphOwner
import wtf.iskra.features.push.PushIntents
import wtf.iskra.core.R as CoreR

/**
 * Виджет «Избранные чаты»: малый — четыре лица, средний — восемь с именами, точка у тех, где есть непрочитанное. Нажатие открывает чат
 * (в его аккаунт, как уведомление). Заперто приложение — вместо лиц и имён одна строка: виджет не показывает того, чего не покажет
 * экран. Читает снимок ([WidgetChats]), а не сеанс.
 */
class FavouritesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetChats(context)
        val graph = (context.applicationContext as? AppGraphOwner)?.graph
        val locked = graph?.appLock?.let { it.isEnabled.value && it.isLocked.value } ?: false
        val chats = if (locked) emptyList() else store.load()
        val faces = chats.associateWith { store.face(it) }
        val words = Words(
            locked = context.getString(CoreR.string.widget_locked),
            empty = context.getString(CoreR.string.widget_empty),
        )
        provideContent {
            GlanceTheme { Content(context, chats, faces, words, locked) }
        }
    }

    private class Words(val locked: String, val empty: String)

    @Composable
    private fun Content(context: Context, chats: List<WidgetChat>, faces: Map<WidgetChat, android.graphics.Bitmap?>, words: Words, locked: Boolean) {
        val medium = LocalSize.current.height >= MEDIUM.height
        val perRow = 4
        val shown = chats.take(if (medium) WidgetChats.MAX else perRow)
        Box(
            GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(CORNER).padding(PAD),
            contentAlignment = Alignment.Center,
        ) {
            if (shown.isEmpty()) {
                Text(
                    if (locked) words.locked else words.empty,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, textAlign = TextAlign.Center),
                )
            } else {
                Column(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    shown.chunked(perRow).forEachIndexed { index, row ->
                        if (index > 0) Spacer(GlanceModifier.size(GAP))
                        Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            row.forEachIndexed { column, chat ->
                                if (column > 0) Spacer(GlanceModifier.width(GAP))
                                Tile(context, chat, faces[chat], medium)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Tile(context: Context, chat: WidgetChat, face: android.graphics.Bitmap?, withName: Boolean) {
        val open = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setAction(Intent.ACTION_VIEW)
            .putExtra(PushIntents.EXTRA_ROOM, chat.roomId)
            .putExtra(PushIntents.EXTRA_ACCOUNT, chat.accountId)
        Column(GlanceModifier.width(TILE).clickable(actionStartActivity(open)), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopEnd) {
                if (face != null) Image(ImageProvider(face), contentDescription = chat.name, modifier = GlanceModifier.size(FACE))
                else Box(GlanceModifier.size(FACE).background(GlanceTheme.colors.secondaryContainer).cornerRadius(FACE / 2)) {}
                if (chat.unread) Box(GlanceModifier.size(DOT).background(GlanceTheme.colors.primary).cornerRadius(DOT / 2)) {}
            }
            if (withName) {
                Text(
                    chat.name,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }

    private companion object {
        val SMALL = DpSize(Theme.Size.widgetMinWidth, Theme.Size.widgetSmallHeight)
        val MEDIUM = DpSize(Theme.Size.widgetMinWidth, Theme.Size.widgetMediumHeight)
        val FACE = Theme.Size.widgetFace
        val TILE = Theme.Size.widgetTile
        val DOT = Theme.Size.widgetDot
        val GAP = Theme.Spacing.tight
        val PAD = Theme.Spacing.close
        val CORNER = Theme.Spacing.generous
    }
}

class FavouritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavouritesWidget()
}
