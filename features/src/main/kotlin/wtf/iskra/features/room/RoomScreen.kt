package wtf.iskra.features.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import wtf.iskra.core.timeline.DeliveryStatus
import wtf.iskra.core.timeline.HtmlWords
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.core.timeline.RichText
import wtf.iskra.core.timeline.SendFailure
import wtf.iskra.core.timeline.TimelineRow
import wtf.iskra.core.timeline.TimelineState
import wtf.iskra.core.timeline.TimelineStore
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar
import wtf.iskra.features.support.AvatarShape
import wtf.iskra.features.support.LocalOwnUserId
import wtf.iskra.features.support.RoomAvatar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import wtf.iskra.core.R as CoreR

/**
 * Переписка (`RoomView` на iOS, docs/PROMPT.md, 12.3). `LazyColumn` с `reverseLayout`: низ — живой
 * конец, страница истории сверху не двигает то, что читающий видит. Ключи — стабильные ID строк.
 *
 * Пока — основа этапа 4: пузыри, служебные строки, даты, полоса непрочитанного, ответ, правка,
 * удаление, быстрая реакция, «печатает». Своё меню сообщения, форматирование (`MatrixHTML`), медиа и
 * плавающая дата — следующими пунктами.
 */
@Composable
fun RoomScreen(store: TimelineStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Открытие — вызов стора: первую страницу он просит сам, в `open()` (docs/PROMPT.md, 4.2).
    LaunchedEffect(store) { store.start() }
    DisposableEffect(store) { onDispose { store.close() } }
    BackHandler(onBack = onBack)

    var draft by rememberSaveable(store.roomId) { mutableStateOf("") }
    var replyingTo by remember(store.roomId) { mutableStateOf<Message?>(null) }
    var editing by remember(store.roomId) { mutableStateOf<Message?>(null) }
    var draftLoaded by remember(store.roomId) { mutableStateOf(false) }

    LaunchedEffect(store) {
        if (draft.isEmpty()) store.draft()?.let { draft = it }
        draftLoaded = true
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Header(state, onBack)
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            when {
                state.didFailToLoad -> Failed { scope.launch { store.retryLoading() } }
                !state.hasLoaded && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalAccent.current.control)
                }
                state.rows.isEmpty() -> Empty()
                else -> Conversation(
                    state = state,
                    store = store,
                    onReply = { replyingTo = it; editing = null },
                    onEdit = { message ->
                        editing = message
                        replyingTo = null
                        draft = message.editableText.orEmpty()
                    },
                )
            }
        }
        if (state.typing.isNotEmpty()) {
            Text(
                if (state.typing.size == 1 && !state.header.isOneToOne) {
                    stringResource(R.string.room_typing_by, state.typing[0])
                } else {
                    stringResource(R.string.room_typing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.tight),
            )
        }
        if (state.canPost) {
            Composer(
                text = draft,
                onTextChange = { text ->
                    draft = text
                    store.typed()
                },
                replyingTo = replyingTo,
                editing = editing,
                onCancel = {
                    if (editing != null) draft = ""
                    replyingTo = null
                    editing = null
                },
                onSend = {
                    val text = draft
                    val reply = replyingTo
                    val edit = editing
                    draft = ""
                    replyingTo = null
                    editing = null
                    scope.launch {
                        if (edit != null) store.edit(edit.id, text) else store.send(text, reply?.id)
                        store.clearDraft()
                    }
                },
            )
        } else {
            Text(
                stringResource(R.string.room_read_only),
                modifier = Modifier.fillMaxWidth().padding(Theme.Spacing.roomy),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Черновик — в зашифрованный стор SDK, когда комнату оставляют, и не раньше, чем он прочитан.
    DisposableEffect(store, draftLoaded) {
        onDispose { if (draftLoaded && editing == null) scope.launch { store.saveDraft(draft) } }
    }

    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = store::clearFailure,
            confirmButton = { TextButton(onClick = store::clearFailure) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(failure.message)) },
        )
    }
}

@Composable
private fun Header(state: TimelineState, onBack: () -> Unit) {
    val header = state.header
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.tight, vertical = Theme.Spacing.close),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
        }
        RoomAvatar(
            name = header.name,
            url = header.avatarUrl,
            size = Theme.Size.avatarToolbar,
            shape = AvatarShape.CIRCLE,
            seed = header.avatarSeed,
            faces = header.faces,
        )
        Column(Modifier.weight(1f)) {
            Text(header.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                state.typing.isNotEmpty() -> stringResource(R.string.room_typing)
                !header.isOneToOne && header.memberCount > 0 ->
                    pluralStringResource(R.plurals.room_participants, header.memberCount, header.memberCount)
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.typing.isNotEmpty()) LocalAccent.current.control else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Conversation(
    state: TimelineState,
    store: TimelineStore,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val newestFirst = remember(state.rows) { state.rows.asReversed() }
    var viewing by remember { mutableStateOf<String?>(null) }
    val photos = remember(state.rows) { state.rows.mapNotNull { (it as? TimelineRow.MessageRow)?.message }.viewable() }
    viewing?.let { id -> MediaViewer(photos, id, onClose = { viewing = null }) }

    // У начала истории — следующая страница. Прокрутка просит *следующие*; первую попросил `open()`.
    val nearTop by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - HISTORY_PREFETCH
        }
    }
    LaunchedEffect(nearTop, state.rows.size) {
        if (nearTop && state.canLoadMore && !state.isPaginating) store.loadMore()
    }

    // Новое у живого конца: если читающий был там, лента идёт за ним. Список держит место за ключом
    // прежней нижней строки, и без этого новое сообщение вставало бы ниже края экрана. Читающий,
    // ушедший в историю, остаётся где был — его место не трогают.
    val newest = newestFirst.firstOrNull()
    LaunchedEffect(newest?.id) {
        if (list.firstVisibleItemIndex <= 1 || newest?.message?.isOwn == true) list.animateScrollToItem(0)
    }

    // Читающий у живого конца и видит последнее — прочитано.
    val latest by rememberUpdatedState(state.rows.lastOrNull()?.id)
    LaunchedEffect(store) {
        snapshotFlow { (list.firstVisibleItemIndex == 0) to latest }
            .distinctUntilChanged()
            .filter { it.first }
            .collect { store.markAsRead() }
    }

    LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
        items(newestFirst, key = { it.id }) { row ->
            when (row) {
                is TimelineRow.MessageRow -> Bubble(
                    message = row.message,
                    showsSender = !state.header.isOneToOne,
                    onReact = { key -> scope.launch { store.toggleReaction(row.message.id, key) } },
                    onReply = onReply,
                    onEdit = onEdit,
                    onDelete = { scope.launch { store.delete(row.message.id) } },
                    onOpen = { viewing = row.message.id },
                    onUnsent = { action ->
                        scope.launch {
                            when (action) {
                                UnsentAction.RETRY -> store.retrySending(row.message.id)
                                UnsentAction.SEND_ANYWAY -> store.sendAnyway(row.message.id)
                                UnsentAction.DISCARD -> store.discard(row.message.id)
                            }
                        }
                    },
                )
                is TimelineRow.EventRow -> ServiceLine(row.event.text(stringsOf()))
                is TimelineRow.DayDividerRow -> DayLine(row.divider.date)
                TimelineRow.UnreadDivider -> UnreadLine()
            }
        }
        item(key = "history") {
            when {
                state.isPaginating -> Box(Modifier.fillMaxWidth().padding(Theme.Spacing.roomy), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(Theme.Size.voiceButton), color = LocalAccent.current.control)
                }
                state.historyIsStranded -> ServiceLine(stringResource(R.string.room_earlier_unavailable))
                state.needsMoreHistory -> TextButton(onClick = { scope.launch { store.loadMoreHistory() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.room_try_again), color = LocalAccent.current.control)
                }
                else -> Spacer(Modifier.height(Theme.Spacing.close))
            }
        }
    }
}

/** Строки ядра для служебных строк — из ресурсов, на языке приложения. */
@Composable
private fun stringsOf(): wtf.iskra.core.support.Strings {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { wtf.iskra.core.support.Strings.of(context) }
}

@Composable
private fun Bubble(
    message: Message,
    showsSender: Boolean,
    onReact: (String) -> Unit,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit = {},
    onUnsent: (UnsentAction) -> Unit = {},
) {
    val accent = LocalAccent.current
    val own = message.isOwn
    val top = if (message.isContinuation) Theme.Spacing.withinRun else Theme.Spacing.betweenSenders / 2
    var menu by remember { mutableStateOf(false) }
    var revealed by remember(message.id) { mutableStateOf(false) }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * Theme.Size.BUBBLE_WIDTH_FRACTION).toInt()
    val ownUserId = LocalOwnUserId.current

    // Один корень: элемент ленты из двух корней кладёт их друг на друга в обратном порядке.
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Theme.Spacing.close, end = Theme.Spacing.close, top = top),
            horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close, if (own) Alignment.End else Alignment.Start),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Время — снаружи пузыря, со стороны, противоположной краю переписки: столбец времён
            // тихо стоит рядом с сообщениями, а не режет их, и не спорит с последней строкой за место.
            if (own) {
                (message.delivery as? DeliveryStatus.Failed)?.let { failed ->
                    FailedBadge(failed.reason, onUnsent, Modifier.align(Alignment.CenterVertically))
                }
                MessageMarks(message)
            }
            // Лицо — у последнего сообщения захода, у его подножия, как в Telegram.
            if (!own && showsSender) {
                if (!message.isContinued) {
                    Avatar(message.senderName, message.senderAvatarUrl, Theme.Size.avatarSmall, seed = message.senderId)
                } else {
                    Spacer(Modifier.width(Theme.Size.avatarSmall))
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false),
                horizontalAlignment = if (own) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(Theme.Spacing.withinRun),
            ) {
                if (showsSender && !own && !message.isContinuation) {
                    Text(
                        message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = Theme.Spacing.close),
                    )
                }
                Box {
                    val picture = message.kind.picture
                    if (picture != null) {
                        Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                            MediaContent(
                                message.kind,
                                Modifier.combinedClickable(
                                    onClickLabel = stringResource(R.string.message_media_open_full_screen),
                                    onClick = { if (message.kind is MessageKind.Image) onOpen() },
                                    onDoubleClick = { if (message.canReact) onReact(QUICK_REACTION) },
                                    onLongClick = { menu = true },
                                ),
                            )
                            picture.caption?.takeIf { message.kind !is MessageKind.Sticker }?.let { caption ->
                                Text(
                                    caption,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight),
                                )
                            }
                        }
                    } else Column(
                        Modifier
                            .widthIn(max = Dp(maxWidth.toFloat()))
                            .clip(RoundedCornerShape(Theme.Radius.bubble))
                            .background(if (own) accent.bubble else MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(
                                onClick = { revealed = true },
                                onDoubleClick = { if (message.canReact) onReact(QUICK_REACTION) },
                                onLongClick = { menu = true },
                            )
                            .padding(horizontal = Theme.Spacing.normal, vertical = Theme.Spacing.close),
                    ) {
                        val ink = if (own) accent.onBubble else MaterialTheme.colorScheme.onSurface
                        message.replyTo?.let { reply ->
                            Row(Modifier.padding(bottom = Theme.Spacing.tight)) {
                                Box(Modifier.width(Theme.Size.quoteBar).height(Theme.Size.avatarSmall).background(if (own) ink else accent.control))
                                Spacer(Modifier.width(Theme.Spacing.close))
                                Column {
                                    reply.senderName?.let {
                                        Text(it, style = MaterialTheme.typography.labelMedium, color = if (own) ink else accent.control, maxLines = 1)
                                    }
                                    Text(reply.text, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        val words = message.richText(ownUserId)
                        if (words != null) {
                            Text(
                                words.annotated(
                                    ink = ink,
                                    accent = if (own) ink else accent.control,
                                    quiet = ink.copy(alpha = QUOTE_ALPHA),
                                    mention = if (own) ink else accent.control,
                                    revealed = revealed,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = ink,
                            )
                        } else {
                            Text(body(message), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = ink)
                        }
                    }
                    MessageMenu(message, menu, onDismiss = { menu = false }, onReact, onReply, onEdit, onDelete)
                }
            }
            if (!own) MessageMarks(message)
        }
        if (message.reactions.isNotEmpty()) Reactions(message, onReact)
    }
}

/** Слова пузыря с разметкой — для текста и уведомления; null для остального (вложения — словом). */
private fun Message.richText(ownUserId: String): RichText? = when (val kind = kind) {
    is MessageKind.Text -> RichText.of(kind.body, formattedBody, ownUserId)
    is MessageKind.Notice -> RichText.of(kind.body, formattedBody, ownUserId)
    else -> null
}

/**
 * Тихий серый столбец фактов о сообщении, снаружи пузыря: «изменено», время и — у своих — как далеко
 * ушло. Порт `MessageMarks` и `DeliveryTick`: часы → галочка → глаз. Глаз — только на новейшем
 * прочитанном: выше поле молчит, это и есть состояние, к которому стремятся. Сбой сюда не входит —
 * у него значок рядом с пузырём.
 */
@Composable
private fun MessageMarks(message: Message) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.padding(bottom = Theme.Spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.isEdited) Text(stringResource(R.string.message_edited), style = MaterialTheme.typography.labelSmall, color = quiet)
        Text(clock(message.timestamp), style = MaterialTheme.typography.labelSmall, color = quiet)
        if (message.isOwn) DeliveryTick(message, quiet)
    }
}

@Composable
private fun DeliveryTick(message: Message, tint: Color) {
    val (icon, label) = when (message.delivery) {
        DeliveryStatus.Sending -> Icons.Outlined.Schedule to R.string.message_delivery_sending
        DeliveryStatus.Sent -> Icons.Filled.Check to R.string.message_delivery_sent
        // Взгляд, а не вторая галочка: «отправлено» и «прочитано» различимы издалека.
        DeliveryStatus.Read -> if (message.carriesReadMark) Icons.Filled.Visibility to R.string.message_delivery_read else return
        else -> return
    }
    Icon(icon, contentDescription = stringResource(label), tint = tint, modifier = Modifier.size(Theme.Size.audioScrubber - Theme.Spacing.tight))
}

/** Что можно сделать с неушедшим сообщением. */
internal enum class UnsentAction { RETRY, SEND_ANYWAY, DISCARD }

/**
 * Не ушло: тревога, а не сноска, — значок рядом с пузырём, по его середине. Нажатие объясняет, *что*
 * не так, — причина и есть то, что делать, — и предлагает только честные действия: «ещё раз» — где
 * препятствие может уйти, «всё равно» — где решение о доверии за человеком, «удалить» — всегда.
 */
@Composable
private fun FailedBadge(reason: SendFailure, onAction: (UnsentAction) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(Theme.Size.minimumTapTarget)) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = stringResource(R.string.message_delivery_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Theme.Size.audioScrubber),
        )
    }
    if (!open) return
    fun act(action: UnsentAction) {
        open = false
        onAction(action)
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.room_send_failed_title)) },
        text = { Text(stringResource(reason.explanation)) },
        confirmButton = {
            when {
                reason.canSendAnyway -> TextButton(onClick = { act(UnsentAction.SEND_ANYWAY) }) {
                    Text(stringResource(R.string.message_delivery_send_anyway))
                }
                reason.canRetry -> TextButton(onClick = { act(UnsentAction.RETRY) }) {
                    Text(stringResource(R.string.message_delivery_send_again))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { act(UnsentAction.DISCARD) }) {
                Text(stringResource(R.string.message_delivery_discard), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun MessageMenu(
    message: Message,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (message.canReply) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_reply)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                onClick = { onDismiss(); onReply(message) },
            )
        }
        if (message.canReact) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_add_quick, QUICK_REACTION)) },
                onClick = { onDismiss(); onReact(QUICK_REACTION) },
            )
        }
        if (message.canEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { onDismiss(); onEdit(message) },
            )
        }
        if (message.canDelete) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_menu_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { onDismiss(); onDelete() },
            )
        }
    }
}

@Composable
private fun Reactions(message: Message, onReact: (String) -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.withinRun),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight, if (message.isOwn) Alignment.End else Alignment.Start),
    ) {
        message.reactions.forEach { reaction ->
            Text(
                stringResource(R.string.room_reaction_pill, reaction.key, reaction.count),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (reaction.isMine) accent.control.copy(alpha = Theme.Opacity.SELECTED_TAB) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = message.canReact) { onReact(reaction.key) }
                    .padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.withinRun),
            )
        }
    }
}

/** Слова пузыря. Вложения пока — словом («Фото»), сами картинки и плееры — этап 6. */
@Composable
private fun body(message: Message): String = when (val kind = message.kind) {
    // Слова из HTML, а не разметка Markdown из запасного `body`; жирный и ссылки — с `MatrixHTML`.
    is MessageKind.Text -> HtmlWords.words(kind.body, message.formattedBody)
    is MessageKind.Notice -> HtmlWords.words(kind.body, message.formattedBody)
    is MessageKind.Emote -> stringResource(R.string.room_emote, message.senderName, kind.body)
    is MessageKind.Image -> kind.attachment.caption ?: stringResource(CoreR.string.preview_photo)
    is MessageKind.Sticker -> kind.attachment.caption ?: stringResource(CoreR.string.preview_sticker)
    is MessageKind.Video -> kind.attachment.caption ?: stringResource(CoreR.string.preview_video)
    is MessageKind.VideoNote -> stringResource(CoreR.string.preview_video_note)
    is MessageKind.Voice -> stringResource(CoreR.string.preview_voice)
    is MessageKind.Audio -> kind.attachment.caption ?: stringResource(CoreR.string.preview_audio)
    is MessageKind.File -> kind.attachment.filename
    is MessageKind.Location -> kind.name ?: stringResource(CoreR.string.preview_location)
    is MessageKind.Poll -> kind.question
    MessageKind.Unreadable -> stringResource(R.string.message_unreadable)
    MessageKind.Deleted -> stringResource(R.string.message_deleted)
    MessageKind.Unsupported -> stringResource(R.string.message_unsupported)
}

@Composable
private fun ServiceLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.generous, vertical = Theme.Spacing.close),
    )
}

@Composable
private fun DayLine(date: Instant) {
    val day = date.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    val label = when (day) {
        today -> stringResource(R.string.room_today)
        today.minusDays(1) -> stringResource(R.string.room_yesterday)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(day)
    }
    Box(Modifier.fillMaxWidth().padding(vertical = Theme.Spacing.close), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = Theme.Spacing.normal, vertical = Theme.Spacing.tight),
        )
    }
}

@Composable
private fun UnreadLine() {
    Text(
        stringResource(R.string.room_unread_messages),
        style = MaterialTheme.typography.labelMedium,
        color = LocalAccent.current.control,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Theme.Spacing.close)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = Theme.Spacing.tight),
    )
}

@Composable
private fun clock(at: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at.atZone(ZoneId.systemDefault()))
}

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    replyingTo: Message?,
    editing: Message?,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight)) {
        val context = replyingTo ?: editing
        if (context != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Theme.Spacing.close)) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = accent.control)
                Spacer(Modifier.width(Theme.Spacing.close))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (editing != null) stringResource(R.string.room_editing_message) else context.senderName,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent.control,
                        maxLines = 1,
                    )
                    Text(body(context), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.room_stop_replying)) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.room_composer_placeholder)) },
                maxLines = COMPOSER_LINES,
                shape = RoundedCornerShape(Theme.Radius.bubble),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.control, cursorColor = accent.control),
            )
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.padding(bottom = Theme.Spacing.tight).clip(CircleShape).background(
                    if (text.isNotBlank()) accent.control else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.room_send), tint = accent.foreground)
            }
        }
    }
}

@Composable
private fun Empty() {
    Column(
        Modifier.fillMaxSize().padding(Theme.Spacing.generous),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.room_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.room_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Failed(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Theme.Spacing.generous),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.room_load_failed_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.room_load_failed_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryActionButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.room_try_again)) }
    }
}

/** Двойное нажатие — как в Telegram: сердце. Своё меню реакций — следующим пунктом. */
private const val QUICK_REACTION = "❤️"

/** За сколько строк до начала просить следующую страницу. */
private const val HISTORY_PREFETCH = 5

private const val COMPOSER_LINES = 6

/** Цитата в сообщении — тише слов вокруг. */
private const val QUOTE_ALPHA = 0.7f
