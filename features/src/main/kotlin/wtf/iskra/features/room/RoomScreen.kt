package wtf.iskra.features.room

import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.filled.PushPin
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Photo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import wtf.iskra.core.timeline.ComposerMention
import wtf.iskra.core.timeline.ComposerMentions
import wtf.iskra.core.timeline.MentionCandidate
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import wtf.iskra.features.support.LocalAppSettings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.platform.LocalContext
import wtf.iskra.core.media.MediaSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.material.icons.filled.Done
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
import androidx.compose.runtime.mutableIntStateOf
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
fun RoomScreen(
    store: TimelineStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    mediaSettings: () -> MediaSettings = { MediaSettings.DEFAULT },
    /** Событие, к которому вести сразу после открытия: найденное поиском. */
    initialJump: String? = null,
) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Открытие — вызов стора: первую страницу он просит сам, в `open()` (docs/PROMPT.md, 4.2).
    LaunchedEffect(store) { store.start() }
    DisposableEffect(store) { onDispose { store.close() } }
    BackHandler(onBack = onBack)

    var field by rememberSaveable(store.roomId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // Кого позвали из списка «@»: рядом с черновиком, а не в нём (`ComposerMention`).
    var mentions by remember(store.roomId) { mutableStateOf<List<ComposerMention>>(emptyList()) }
    // К какому сообщению вести ленту: нажали на закреплённое или на цитату в ответе.
    var jumpTo by remember(store.roomId) { mutableStateOf(initialJump) }
    var candidates by remember(store.roomId) { mutableStateOf<List<MentionCandidate>>(emptyList()) }
    var replyingTo by remember(store.roomId) { mutableStateOf<Message?>(null) }
    var editing by remember(store.roomId) { mutableStateOf<Message?>(null) }
    var draftLoaded by remember(store.roomId) { mutableStateOf(false) }
    var attachments by remember(store.roomId) { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    val context = LocalContext.current
    // Системный выбор: без разрешения на всю галерею, до десяти за раз (docs/PROMPT.md, 11.5).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { picked ->
        attachments = (attachments + picked.map { PendingAttachment(it, isFile = false) }).distinct().take(MAX_ATTACHMENTS)
    }
    val recorder = remember(store.roomId) { VoiceRecorder(context) }
    val recordingElapsed by recorder.elapsed.collectAsStateWithLifecycle()
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) micDenied = true }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    // Файл — системным выбором документов: любой тип, уйдёт как есть.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) attachments = (attachments + PendingAttachment(picked, isFile = true)).distinct().take(MAX_ATTACHMENTS)
    }

    LaunchedEffect(store) {
        if (field.text.isEmpty()) store.draft()?.let { field = TextFieldValue(it, TextRange(it.length)) }
        draftLoaded = true
    }
    val mentionQuery = field.selection.takeIf { it.collapsed }?.let { ComposerMentions.query(field.text, it.end) }
    // Участники — когда впервые набрали «@», а не при каждом открытии комнаты.
    LaunchedEffect(mentionQuery != null) {
        if (mentionQuery != null && candidates.isEmpty()) candidates = store.mentionCandidates()
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Header(state, onBack)
        HorizontalDivider()
        state.latestPinned?.let { pinned -> PinnedStrip(pinned, onClick = { jumpTo = pinned.id }) }
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
                    jumpTo = jumpTo,
                    onJump = { jumpTo = it },
                    onReply = { replyingTo = it; editing = null },
                    onEdit = { message ->
                        editing = message
                        replyingTo = null
                        val (text, linked) = ComposerMentions.unlinked(message.editableText.orEmpty())
                        field = TextFieldValue(text, TextRange(text.length))
                        mentions = linked
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
            val suggestions = mentionQuery?.let { ComposerMentions.matches(it.text, candidates).take(MAX_SUGGESTIONS) }.orEmpty()
            if (suggestions.isNotEmpty() && mentionQuery != null) {
                MentionPicker(suggestions) { member ->
                    val (text, cursor, mention) = ComposerMentions.insert(member, mentionQuery, field.text)
                    field = TextFieldValue(text, TextRange(cursor))
                    mentions = mentions + mention
                }
            }
            Composer(
                field = field,
                onFieldChange = { value ->
                    if (value.text != field.text) store.typed()
                    field = value
                },
                replyingTo = replyingTo,
                editing = editing,
                attachments = attachments,
                onAttachPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onAttachFile = { filePicker.launch(arrayOf(ANY_TYPE)) },
                onRemoveAttachment = { attachment -> attachments = attachments - attachment },
                recordingElapsed = recordingElapsed,
                mic = {
                    MicButton(
                        recording = recordingElapsed != null,
                        hasPermission = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                        onNeedPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onStart = {
                            VoicePlayback.stop()
                            recorder.start(scope)
                        },
                        onFinish = {
                            val reply = replyingTo
                            recorder.finish()?.let { voice ->
                                replyingTo = null
                                scope.launch { store.sendVoice(voice.bytes, voice.filename, VoiceRecorder.MIME_TYPE, voice.durationMs, voice.waveform, reply?.id) }
                            }
                        },
                        onCancel = { recorder.cancel() },
                        modifier = Modifier.padding(bottom = Theme.Spacing.tight),
                    )
                },
                onCancel = {
                    if (editing != null) {
                        field = TextFieldValue()
                        mentions = emptyList()
                    }
                    replyingTo = null
                    editing = null
                },
                onSend = {
                    val text = field.text
                    val called = mentions
                    val reply = replyingTo
                    val edit = editing
                    val photos = attachments
                    field = TextFieldValue()
                    mentions = emptyList()
                    replyingTo = null
                    editing = null
                    attachments = emptyList()
                    scope.launch {
                        when {
                            edit != null -> store.edit(edit.id, text, called)
                            photos.isNotEmpty() -> {
                                // Вложения вперёд; слова — подписью под первым, в том же событии.
                                val settings = mediaSettings()
                                var captioned = false
                                photos.forEachIndexed { index, attachment ->
                                    val (bytes, name) = readPicked(context, attachment.uri) ?: return@forEachIndexed
                                    val caption = text.takeIf { !captioned && it.isNotBlank() }
                                    val replyId = reply?.id.takeIf { index == 0 }
                                    val sent = if (attachment.isFile) {
                                        store.sendFile(bytes, name, context.contentResolver.getType(attachment.uri), caption, replyId)
                                    } else {
                                        store.sendPhoto(bytes, name, settings, caption, replyId)
                                    }
                                    if (sent && caption != null) captioned = true
                                }
                                if (!captioned && text.isNotBlank()) store.send(text, reply?.id, called)
                            }
                            else -> store.send(text, reply?.id, called)
                        }
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
        onDispose { if (draftLoaded && editing == null) scope.launch { store.saveDraft(field.text) } }
    }

    if (micDenied) {
        AlertDialog(
            onDismissRequest = { micDenied = false },
            confirmButton = { TextButton(onClick = { micDenied = false }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(R.string.voice_needs_microphone)) },
        )
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
    jumpTo: String?,
    onJump: (String?) -> Unit,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val newestFirst = remember(state.rows) { state.rows.asReversed() }
    // Переход к сообщению: прокрутить к его строке (или к альбому, где оно лежит) и коротко подсветить.
    // Только в загруженном; не нашлось — ничего не делать, а не прыгать куда попало.
    var highlighted by remember { mutableStateOf<String?>(null) }
    // Не нашлось в загруженном — догрузить историю, страница за страницей, но не без конца: найденное
    // в марте сообщение не повод листать всю комнату с её основания.
    var pagesAsked by remember(jumpTo) { mutableIntStateOf(0) }
    LaunchedEffect(jumpTo, newestFirst) {
        val target = jumpTo ?: return@LaunchedEffect
        val index = newestFirst.indexOfFirst { row ->
            (row as? TimelineRow.MessageRow)?.message?.members?.any { it.id == target } == true
        }
        if (index < 0) {
            if (state.canLoadMore && pagesAsked < MAX_JUMP_PAGES) {
                pagesAsked++
                store.loadMore()
            } else {
                onJump(null)
            }
            return@LaunchedEffect
        }
        // Сброс — после: сброшенный ключ отменил бы этот же эффект до прокрутки.
        try {
            list.animateScrollToItem(index)
            highlighted = newestFirst[index].id
            delay(HIGHLIGHT_MS)
            highlighted = null
        } finally {
            onJump(null)
        }
    }
    var viewing by remember { mutableStateOf<String?>(null) }
    val photos = remember(state.rows) { state.rows.flatMap { (it as? TimelineRow.MessageRow)?.message?.members.orEmpty() }.viewable() }
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

    Box(Modifier.fillMaxSize()) {
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
                        onOpen = { id -> viewing = id },
                    onVote = { answers -> scope.launch { store.vote(row.message.id, answers) } },
                    onEndPoll = { scope.launch { store.endPoll(row.message.id) } },
                    isPinned = row.message.id in state.pinnedIds,
                    highlighted = highlighted == row.id,
                    onQuote = { id -> onJump(id) },
                    onPin = { pinned -> scope.launch { store.setPinned(row.message.id, pinned) } },
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
        FloatingDate(list, newestFirst)
    }
}

/**
 * День верхней видимой строки — над лентой, пока её листают, и ещё немного после: в середине
 * длинной истории иначе не понять, когда это было. Стоя — не мешает: полосы дней в самой ленте.
 */
@Composable
private fun BoxScope.FloatingDate(list: LazyListState, newestFirst: List<TimelineRow>) {
    val top by remember(newestFirst) {
        derivedStateOf {
            // Наверху и так полоса дня — вторая поверх неё читалась бы двоением.
            list.layoutInfo.visibleItemsInfo.lastOrNull { it.index < newestFirst.size }
                ?.let { newestFirst[it.index] }
                ?.takeIf { it !is TimelineRow.DayDividerRow }
                ?.date
        }
    }
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collectLatest { moving ->
            if (moving) {
                scrolling = true
            } else {
                delay(FLOATING_DATE_HOLD_MS)
                scrolling = false
            }
        }
    }
    val day = top
    AnimatedVisibility(scrolling && day != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
        if (day != null) DayLine(day)
    }
}

/** Когда это было — для плавающей даты. У полосы «непрочитанное» своего дня нет. */
private val TimelineRow.date: Instant?
    get() = when (this) {
        is TimelineRow.MessageRow -> message.timestamp
        is TimelineRow.DayDividerRow -> divider.date
        is TimelineRow.EventRow -> event.timestamp
        TimelineRow.UnreadDivider -> null
    }

private const val FLOATING_DATE_HOLD_MS = 1200L

/** Сколько горит подсветка сообщения, к которому привели. */
private const val HIGHLIGHT_MS = 1500L

/** Сколько страниц истории догрузить, ища сообщение, к которому ведут. */
private const val MAX_JUMP_PAGES = 10

/** Сколько вложений за раз — столько же, сколько у системного выбора и в альбоме. */
private const val MAX_ATTACHMENTS = 10

/** Сколько людей показывает список «@»: больше — уже не подсказка, а список участников. */
private const val MAX_SUGGESTIONS = 5

/** Любой тип файла — системному выбору документов. */
private const val ANY_TYPE = "*/*"

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
    onOpen: (String) -> Unit = {},
    onVote: (List<String>) -> Unit = {},
    onEndPoll: () -> Unit = {},
    isPinned: Boolean = false,
    onPin: (Boolean) -> Unit = {},
    highlighted: Boolean = false,
    onQuote: (String) -> Unit = {},
    onUnsent: (UnsentAction) -> Unit = {},
) {
    val accent = LocalAccent.current
    val own = message.isOwn
    val top = if (message.isContinuation) Theme.Spacing.withinRun else Theme.Spacing.betweenSenders / 2
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var revealed by remember(message.id) { mutableStateOf(false) }
    var noteTaps by remember(message.id) { mutableIntStateOf(0) }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * Theme.Size.BUBBLE_WIDTH_FRACTION).toInt()
    val ownUserId = LocalOwnUserId.current

    val wash by animateColorAsState(
        if (highlighted) LocalAccent.current.control.copy(alpha = Theme.Opacity.SELECTED_TAB) else Color.Transparent,
        label = "highlight",
    )
    // Один корень: элемент ленты из двух корней кладёт их друг на друга в обратном порядке.
    Column(Modifier.background(wash)) {
        SwipeToReply(towardStart = own, enabled = message.canReply, onReply = { onReply(message) }) {
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
                    val anchor = remember { AnchorBox() }
                    Box(Modifier.onGloballyPositioned { anchor.rect = it.boundsInWindow() }) {
                        BubbleBody(
                            message = message,
                            interactive = true,
                            revealed = revealed,
                            noteTaps = noteTaps,
                            onReveal = { revealed = true },
                            onNoteTap = { noteTaps++ },
                            onOpen = onOpen,
                            onVote = onVote,
                            onDoubleTap = { if (message.canReact) onReact(QUICK_REACTION) },
                            onLongPress = { menuAnchor = anchor.rect },
                            onQuote = onQuote,
                        )
                    }
                    menuAnchor?.let { rect ->
                        MessageMenu(
                            message = message,
                            anchor = rect,
                            onDismiss = { menuAnchor = null },
                            onReact = onReact,
                            onReply = onReply,
                            onEdit = onEdit,
                            isPinned = isPinned,
                        onPin = onPin,
                        onAskDelete = { confirm = Confirm.DELETE },
                            onAskEndPoll = { confirm = Confirm.END_POLL },
                        ) {
                            BubbleBody(message, interactive = false, revealed = revealed, noteTaps = 0)
                        }
                    }
                    confirm?.let { asked ->
                        ConfirmDialog(
                            title = if (asked == Confirm.DELETE) R.string.message_menu_delete_confirm_title else R.string.poll_end_confirm_title,
                            text = if (asked == Confirm.DELETE) R.string.message_menu_delete_confirm_message else R.string.poll_end_confirm_message,
                            confirm = if (asked == Confirm.DELETE) R.string.message_menu_delete else R.string.poll_end,
                            dismiss = R.string.poll_cancel,
                            onConfirm = {
                                confirm = null
                                if (asked == Confirm.DELETE) onDelete() else onEndPoll()
                            },
                            onDismiss = { confirm = null },
                        )
                    }
                }
                if (!own) MessageMarks(message)
            }
        }
        if (message.reactions.isNotEmpty()) Reactions(message, onReact)
    }
}

/**
 * Закреплённое — полосой под заголовком: черта акцентом, «Закреплено» и само сообщение одной строкой.
 * Показывает самое свежее из того, что уже в ленте.
 */
@Composable
private fun PinnedStrip(message: Message, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(Theme.Size.quoteBar).height(Theme.Size.avatarSmall).background(accent.control))
            Spacer(Modifier.width(Theme.Spacing.close))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.room_pinned), style = MaterialTheme.typography.labelMedium, color = accent.control)
                Text(body(message), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Theme.Size.menuIcon))
        }
        HorizontalDivider()
    }
}

/** Что пузырь спрашивает, прежде чем сделать необратимое. */
private enum class Confirm { DELETE, END_POLL }

@Composable
private fun ConfirmDialog(title: Int, text: Int, confirm: Int, dismiss: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(dismiss)) } },
    )
}

/** Где пузырь на экране — без состояния: пишется на каждом кадре прокрутки, читается раз, при нажатии. */
private class AnchorBox {
    var rect: Rect = Rect.Zero
}

/** Без жестов, когда пузырь — копия в меню: там он показывает, о чём меню, а не отвечает на нажатия. */
private fun Modifier.bubbleGestures(
    interactive: Boolean,
    onClickLabel: String? = null,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = if (!interactive) this else combinedClickable(
    onClickLabel = onClickLabel,
    onClick = onClick,
    onDoubleClick = onDoubleClick,
    onLongClick = onLongClick,
)

/**
 * Сам пузырь: альбом, картинка или слова. Рисуется дважды — в ленте и поднятым в меню долгого
 * нажатия (`interactive = false`), поэтому всё, что он делает, приходит снаружи.
 */
@Composable
private fun BubbleBody(
    message: Message,
    interactive: Boolean,
    revealed: Boolean,
    noteTaps: Int,
    onReveal: () -> Unit = {},
    onNoteTap: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onVote: (List<String>) -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onQuote: (String) -> Unit = {},
) {
    val accent = LocalAccent.current
    val own = message.isOwn
    val maxWidth = (LocalConfiguration.current.screenWidthDp * Theme.Size.BUBBLE_WIDTH_FRACTION).toInt()
    val ownUserId = LocalOwnUserId.current
    val picture = message.kind.picture
    val album = message.album
    if (album != null) {
        Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            AlbumContent(
                album,
                onOpen = { if (interactive) onOpen(it) },
                onDoubleTap = onDoubleTap,
                onLongPress = onLongPress,
            )
            message.albumCaption?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight),
                )
            }
        }
    } else if (picture != null) {
        Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            MediaContent(
                message.kind,
                Modifier.bubbleGestures(
                    interactive = interactive,
                    onClickLabel = stringResource(
                        if (message.kind is MessageKind.VideoNote) R.string.message_media_play else R.string.message_media_open_full_screen,
                    ),
                    onClick = {
                        when (message.kind) {
                            is MessageKind.Image, is MessageKind.Video -> onOpen(message.id)
                            is MessageKind.VideoNote -> onNoteTap()
                            else -> Unit
                        }
                    },
                    onDoubleClick = onDoubleTap,
                    onLongClick = onLongPress,
                ),
                messageId = message.id,
                noteTaps = noteTaps,
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
            .bubbleGestures(
interactive = interactive,
                onClick = onReveal,
                onDoubleClick = onDoubleTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = Theme.Spacing.normal, vertical = Theme.Spacing.close),
    ) {
        val ink = if (own) accent.onBubble else MaterialTheme.colorScheme.onSurface
        message.replyTo?.let { reply ->
            Row(
                Modifier
                    .padding(bottom = Theme.Spacing.tight)
                    .then(if (interactive) Modifier.clickable(onClickLabel = reply.senderName) { onQuote(reply.eventId) } else Modifier),
            ) {
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
        val poll = (message.kind as? MessageKind.Poll)?.poll
        if (poll != null) {
            PollContent(poll, ink = ink, fill = if (own) ink else accent.control, onVote = onVote)
        } else if (message.kind.isAttachmentRow) {
            AttachmentContent(
                message,
                ink = ink,
                fill = if (own) ink else accent.color,
                onFill = if (own) accent.bubble else accent.foreground,
            )
        } else if (words != null) {
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

/**
 * Меню долгого нажатия: собирает, что можно сделать с этим сообщением, и отдаёт `MessageMenuOverlay`.
 * Удаление и конец опроса — необратимы для всей комнаты, поэтому спрашиваются (`onAskDelete`,
 * `onAskEndPoll`): меню к тому времени уже закрыто, вопрос задаёт пузырь.
 */
@Composable
private fun MessageMenu(
    message: Message,
    anchor: Rect,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    isPinned: Boolean,
    onPin: (Boolean) -> Unit,
    onAskDelete: () -> Unit,
    onAskEndPoll: () -> Unit,
    lifted: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val recent by (settings?.recentReactions ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>()) }).collectAsStateWithLifecycle()
    val ownUserId = LocalOwnUserId.current
    val copyable = message.richText(ownUserId)?.text ?: message.editableText
    val actions = buildList {
        if (message.canReply) add(MenuAction(stringResource(R.string.message_menu_reply), Icons.AutoMirrored.Filled.Reply) { onReply(message) })
        if (copyable != null) {
            add(
                MenuAction(stringResource(R.string.message_menu_copy), Icons.Filled.ContentCopy) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, copyable))
                },
            )
        }
        if (message.canEdit) add(MenuAction(stringResource(R.string.message_menu_edit), Icons.Filled.Edit) { onEdit(message) })
        // Закрепить можно то, что есть на сервере, — то же, на что можно ответить.
        if (message.canReply) {
            add(MenuAction(stringResource(if (isPinned) R.string.message_menu_unpin else R.string.message_menu_pin), Icons.Filled.PushPin) { onPin(!isPinned) })
        }
        if (message.canEndPoll) add(MenuAction(stringResource(R.string.poll_end), Icons.Filled.Done, run = onAskEndPoll))
        if (message.canDelete) add(MenuAction(stringResource(R.string.message_menu_delete), Icons.Filled.Delete, destructive = true, run = onAskDelete))
    }
    MessageMenuOverlay(
        anchor = anchor,
        alignEnd = message.isOwn,
        mine = message.reactions.filter { it.isMine }.map { it.key }.toSet(),
        canReact = message.canReact,
        actions = actions,
        recent = recent,
        onReact = { emoji ->
            settings?.rememberReaction(emoji)
            onReact(emoji)
        },
        onDismiss = onDismiss,
        lifted = lifted,
    )
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
    is MessageKind.Poll -> kind.poll.question
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
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    replyingTo: Message?,
    editing: Message?,
    attachments: List<PendingAttachment>,
    onAttachPhoto: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (PendingAttachment) -> Unit,
    recordingElapsed: Long?,
    mic: @Composable () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val accent = LocalAccent.current
    val text = field.text
    val canSend = text.isNotBlank() || (editing == null && attachments.isNotEmpty())
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight)) {
        if (attachments.isNotEmpty() && editing == null) PendingAttachments(attachments, onRemoveAttachment)
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
            // В правке вложению взяться неоткуда: правится слово, а не событие.
            if (editing == null) {
                var choosing by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { choosing = true }, modifier = Modifier.padding(bottom = Theme.Spacing.tight)) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.room_attach), tint = accent.control)
                    }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_attach_photo)) },
                            leadingIcon = { Icon(Icons.Filled.Photo, contentDescription = null) },
                            onClick = { choosing = false; onAttachPhoto() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_attach_file)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                            onClick = { choosing = false; onAttachFile() },
                        )
                    }
                }
            }
            if (recordingElapsed != null) {
                RecordingStrip(recordingElapsed, Modifier.weight(1f))
            } else OutlinedTextField(
                value = field,
                onValueChange = onFieldChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.room_composer_placeholder)) },
                maxLines = COMPOSER_LINES,
                shape = RoundedCornerShape(Theme.Radius.bubble),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.control, cursorColor = accent.control),
            )
            // Нечего отправить — микрофон: голосовое начинается там же, где уходят слова.
            if (!canSend && editing == null) {
                mic()
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.padding(bottom = Theme.Spacing.tight).clip(CircleShape).background(
                        if (canSend) accent.control else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.room_send), tint = accent.foreground)
                }
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

/** Двойное нажатие — как в Telegram: сердце (`QuickReaction.default`). Выбор в настройках — позже. */
private const val QUICK_REACTION = "❤️"

/** За сколько строк до начала просить следующую страницу. */
private const val HISTORY_PREFETCH = 5

private const val COMPOSER_LINES = 6

/** Цитата в сообщении — тише слов вокруг. */
private const val QUOTE_ALPHA = 0.7f
