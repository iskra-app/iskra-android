package wtf.iskra.features.room

import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Forum
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import wtf.iskra.design.pillColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Poll
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
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
import wtf.iskra.core.timeline.ThreadSettings
import wtf.iskra.core.rooms.IdentityChangeException
import wtf.iskra.core.rooms.IdentityChangesStore
import wtf.iskra.core.rooms.RoomDetailsStore
import wtf.iskra.core.stickers.StickerLibrary
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
    /** Стрелка «назад» в шапке. Скрыта, когда комната — правая колонка рядом со списком: возвращаться некуда. */
    showsBack: Boolean = true,
    modifier: Modifier = Modifier,
    mediaSettings: () -> MediaSettings = { MediaSettings.DEFAULT },
    /** Событие, к которому вести сразу после открытия: найденное поиском. */
    initialJump: String? = null,
    /** Лента треда по ID корня (`UserSession.threadTimeline`). Null — тредов отсюда не открыть. */
    openThread: ((String) -> TimelineStore?)? = null,
    /** Нажали на шапку — «О чате». Null — его отсюда нет (тред). */
    onOpenDetails: (() -> Unit)? = null,
    /** У кого в комнате сменились ключи (`UserSession.identityChanges`). Null — у треда: баннер у комнаты под ним. */
    identityChanges: IdentityChangesStore? = null,
    /** Стикеры аккаунта (`UserSession.stickers`). Null — кнопки стикеров нет. */
    stickers: StickerLibrary? = null,
    /** Задачи отправки медиа сеанса (`UserSession.mediaJobs`): видео и альбом идут, пока человек не здесь. Null — не следим. */
    mediaJobs: wtf.iskra.core.media.MediaSendJobs? = null,
    /** Живая геопозиция сеанса: полоса «Вы делитесь…» и пункт «Делиться» в «Месте». Null — без неё (демо). */
    liveLocation: wtf.iskra.core.rooms.LiveLocationSharer? = null,
    /** Блокировка аккаунта (`ignore`): личный чат с заблокированным показывает плашку вместо поля ввода. Null — без неё. */
    blocked: wtf.iskra.core.session.BlockStore? = null,
    /** Чем поделились из другого приложения: ложится в поле ввода и под миниатюры, отправляет человек. */
    share: wtf.iskra.features.SharedContent? = null,
    onShareTaken: () -> Unit = {},
    /** Сервер недоступен / список догоняет его — строка под названием говорит об этом вместо участников (`RoomListStatus`). */
    isOffline: Boolean = false,
    isUpdating: Boolean = false,
    /** Открыться сразу в поиске — «Поиск» в плитках «О чате». */
    startSearching: Boolean = false,
    onSearchStarted: () -> Unit = {},
    /** Хранилище участников комнаты для карточки по нажатию на лицо (`UserSession.details`). Null — нажимать нечего. */
    members: ((String) -> RoomDetailsStore?)? = null,
    /** «Написать» в карточке: личный чат с этим человеком. Null — кнопки нет. */
    onMessagePerson: ((String) -> Unit)? = null,
) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Этот экран — тред, а не комната (`TimelineStore.threadRootId`).
    val isThread = store.threadRootId != null
    val appSettings = LocalAppSettings.current
    val threadSettings by (appSettings?.threads ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ThreadSettings()) }).collectAsStateWithLifecycle()
    var openedThread by remember(store.roomId) { mutableStateOf<TimelineStore?>(null) }
    // Тред — визит внутри визита: поверх комнаты, которая остаётся открытой под ним.
    openedThread?.let { thread ->
        Dialog(
            onDismissRequest = { openedThread = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                RoomScreen(thread, onBack = { openedThread = null }, mediaSettings = mediaSettings, stickers = stickers)
            }
        }
    }
    val onOpenThread: ((Message) -> Unit)? =
        if (openThread == null || isThread || !threadSettings.usesThreads(state.header.kind, state.header.memberCount)) {
            null
        } else {
            { root -> openThread(root.id)?.let { openedThread = it } }
        }

    // Открытие — вызов стора: первую страницу он просит сам, в `open()` (docs/PROMPT.md, 4.2).
    LaunchedEffect(store) { store.start() }
    // «в сети» / «был(а) …» у собеседника личного чата — пока кто-то смотрит: опрос, потока нет (`TimelineStore.watchPresence`).
    val counterpart = state.header.isOneToOne && !isThread
    LaunchedEffect(store, counterpart, state.header.name) { if (counterpart) store.watchPresence() }
    DisposableEffect(store) { onDispose { store.close() } }
    if (identityChanges != null) {
        LaunchedEffect(identityChanges) { identityChanges.start() }
        DisposableEffect(identityChanges) { onDispose { identityChanges.stop() } }
    }
    BackHandler(onBack = onBack)

    var field by rememberSaveable(store.roomId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // Кого позвали из списка «@»: рядом с черновиком, а не в нём (`ComposerMention`).
    var mentions by remember(store.roomId) { mutableStateOf<List<ComposerMention>>(emptyList()) }
    // К какому сообщению вести ленту: нажали на закреплённое или на цитату в ответе.
    var jumpTo by remember(store.roomId) { mutableStateOf(initialJump) }
    var searching by remember(store.roomId) { mutableStateOf(startSearching) }
    LaunchedEffect(startSearching) { if (startSearching) onSearchStarted() }
    // Закреплённые — своя лента SDK, и открывается, только когда закреплённое есть (`PinnedStore`).
    val pinnedStore = remember(store) { store.pinned() }
    val pinned by pinnedStore.state.collectAsStateWithLifecycle()
    val hasPinned = state.pinnedIds.isNotEmpty()
    LaunchedEffect(pinnedStore, hasPinned) { if (hasPinned) pinnedStore.start() }
    var showsPinned by remember(store.roomId) { mutableStateOf(false) }
    // Читающий у живого конца: только там строка «печатает…» может менять высоту ленты.
    var atLiveEnd by remember(store.roomId) { mutableStateOf(true) }
    if (showsPinned) {
        PinnedMessages(
            pinnedStore,
            isOneToOne = state.header.isOneToOne,
            onOpen = { id ->
                showsPinned = false
                jumpTo = id
            },
            onClose = { showsPinned = false },
        )
    }
    if (searching) {
        RoomSearch(
            store,
            onOpen = { id ->
                searching = false
                jumpTo = id
            },
            onClose = { searching = false },
        )
    }
    var candidates by remember(store.roomId) { mutableStateOf<List<MentionCandidate>>(emptyList()) }
    var replyingTo by remember(store.roomId) { mutableStateOf<Message?>(null) }
    var editing by remember(store.roomId) { mutableStateOf<Message?>(null) }
    var draftLoaded by remember(store.roomId) { mutableStateOf(false) }
    var attachments by remember(store.roomId) { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    val context = LocalContext.current
    // Системный выбор: без разрешения на всю галерею, до десяти за раз (docs/PROMPT.md, 11.5).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { picked ->
        attachments = (attachments + picked.map { PendingAttachment.visual(context, it) }).distinct().take(MAX_ATTACHMENTS)
    }
    val recorder = remember(store.roomId) { VoiceRecorder(context) }
    val recordingElapsed by recorder.elapsed.collectAsStateWithLifecycle()
    // Жест вверх к замку (−1 — не идёт) и пойманный замок: запись идёт без пальца.
    var lockReach by remember(store.roomId) { mutableFloatStateOf(-1f) }
    var voiceLocked by remember(store.roomId) { mutableStateOf(false) }
    var justLocked by remember(store.roomId) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Закрытый замок виден миг — подтверждение, — потом пилюля уходит.
    LaunchedEffect(justLocked) {
        if (justLocked) {
            delay(LOCK_CONFIRM_MS)
            justLocked = false
            lockReach = -1f
        }
    }
    // Запись кончилась (ушла, отменена, комнату оставили) — замка больше нет.
    LaunchedEffect(recordingElapsed == null) { if (recordingElapsed == null) voiceLocked = false }
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) micDenied = true }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    // Кружок: тот же рычаг, что у голосового, но пишет камера, а кнопка — и переключатель режима
    // (короткое нажатие меняет, зажатие пишет). Режим помнится на комнату, как в Telegram.
    var videoMode by rememberSaveable(store.roomId) { mutableStateOf(false) }
    val noteRecorder = remember(store.roomId) { VideoNoteRecorder(context) }
    val noteElapsed by noteRecorder.elapsed.collectAsStateWithLifecycle()
    var noteLocked by remember(store.roomId) { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    // Камеры нет или она не поднялась: оверлей уже закрылся сам — сказать почему.
    var noCamera by remember { mutableStateOf(false) }
    val cameraUnavailable by noteRecorder.unavailable.collectAsStateWithLifecycle()
    LaunchedEffect(cameraUnavailable) { if (cameraUnavailable) noCamera = true }
    val sendNote: () -> Unit = {
        val reply = replyingTo
        scope.launch {
            val recorded = noteRecorder.finish()
            noteLocked = false
            lockReach = -1f
            if (recorded != null) {
                replyingTo = null
                store.sendVideoNote(context, recorded, reply?.id)
                recorded.path?.let { java.io.File(it).delete() }
            }
        }
        Unit
    }
    val cancelNote: () -> Unit = {
        noteRecorder.cancel()
        noteLocked = false
        lockReach = -1f
    }
    LaunchedEffect(noteElapsed == null) { if (noteElapsed == null) noteLocked = false }
    DisposableEffect(noteRecorder) {
        noteRecorder.onLimit = sendNote
        onDispose {
            noteRecorder.onLimit = null
            noteRecorder.cancel()
        }
    }
    val cameraPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { !it }) cameraDenied = true
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    // Раз приложение объявляет CAMERA (кружки), системная камера без выданного разрешения бросает
    // SecurityException, а не открывается: спросить сначала, и открыть, когда выдали.
    var afterCamera by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraOnly = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val next = afterCamera
        afterCamera = null
        if (allowed) next?.invoke() else cameraDenied = true
    }
    fun withCamera(action: () -> Unit) {
        if (granted(Manifest.permission.CAMERA)) {
            action()
        } else {
            afterCamera = action
            cameraOnly.launch(Manifest.permission.CAMERA)
        }
    }
    // Файл — системным выбором документов: любой тип, уйдёт как есть.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) attachments = (attachments + PendingAttachment(picked, PendingAttachment.Kind.FILE)).distinct().take(MAX_ATTACHMENTS)
    }
    // «Место»: точка, которую человек видит, прежде чем отправить.
    var locating by remember(store.roomId) { mutableStateOf(false) }
    if (locating) {
        MyLocationDialog(
            onShareLive = liveLocation?.takeIf { it.isAvailable && !isThread }?.let { sharer ->
                { duration ->
                    locating = false
                    scope.launch { sharer.start(store.roomId, duration) }
                }
            },
            onSend = { latitude, longitude ->
                locating = false
                val reply = replyingTo
                replyingTo = null
                scope.launch { store.sendLocation(latitude, longitude, replyingTo = reply?.id) }
            },
            onDismiss = { locating = false },
        )
    }
    // Правка фото из вложений: исходник не трогается, правленое встаёт на его место.
    var editingPhoto by remember(store.roomId) { mutableStateOf<PendingAttachment?>(null) }
    editingPhoto?.let { original ->
        PhotoEditorDialog(
            source = original.uri,
            onDone = { edited ->
                attachments = attachments.map { if (it == original) PendingAttachment(edited, PendingAttachment.Kind.PHOTO) else it }
                Camera.discard(context, original.uri)
                editingPhoto = null
            },
            onCancel = { editingPhoto = null },
        )
    }
    // «Опрос»: вопрос, варианты и две настройки; только в группе — в личном чате это один человек, голосующий за вопрос другого.
    var composingPoll by remember(store.roomId) { mutableStateOf(false) }
    if (composingPoll) {
        PollComposerSheet(
            onSend = { draft ->
                val reply = replyingTo
                replyingTo = null
                scope.launch { store.sendPoll(draft) }
            },
            onDismiss = { composingPoll = false },
        )
    }
    // Камера — системным приложением, в свой файл: снятое встаёт в композер, как выбранное из галереи.
    var capturing by remember(store.roomId) { mutableStateOf<PendingAttachment?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = capturing
        capturing = null
        if (shot == null) return@rememberLauncherForActivityResult
        if (taken) attachments = (attachments + shot).distinct().take(MAX_ATTACHMENTS) else Camera.discard(context, shot.uri)
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { taken ->
        val clip = capturing
        capturing = null
        if (clip == null) return@rememberLauncherForActivityResult
        if (taken) attachments = (attachments + clip).distinct().take(MAX_ATTACHMENTS) else Camera.discard(context, clip.uri)
    }

    LaunchedEffect(store) {
        if (field.text.isEmpty()) store.draft()?.let { field = TextFieldValue(it, TextRange(it.length)) }
        draftLoaded = true
    }
    // Общее меню: к черновику дописывается, а не затирает его; слова — в поле, файлы — под миниатюры.
    LaunchedEffect(share, draftLoaded) {
        val content = share ?: return@LaunchedEffect
        if (!draftLoaded) return@LaunchedEffect
        content.text?.let { words ->
            val joined = if (field.text.isBlank()) words else field.text.trimEnd() + "\n" + words
            field = TextFieldValue(joined, TextRange(joined.length))
        }
        attachments = (attachments + content.uris.map { PendingAttachment.shared(context, it, content.type) }).distinct().take(MAX_ATTACHMENTS)
        onShareTaken()
    }
    val mentionQuery = field.selection.takeIf { it.collapsed }?.let { ComposerMentions.query(field.text, it.end) }
    // Участники — когда впервые набрали «@», а не при каждом открытии комнаты.
    LaunchedEffect(mentionQuery != null) {
        if (mentionQuery != null && candidates.isEmpty()) candidates = store.mentionCandidates()
    }

    var choosingSticker by remember(store.roomId) { mutableStateOf(false) }
    if (choosingSticker && stickers != null) {
        StickerPicker(
            library = stickers,
            roomId = store.roomId,
            onPick = { sticker ->
                choosingSticker = false
                val reply = replyingTo
                replyingTo = null
                scope.launch { if (store.sendSticker(sticker, reply?.id)) stickers.used(sticker) }
            },
            onDismiss = { choosingSticker = false },
        )
    }

    val compactLayout by (LocalAppSettings.current?.compactLayout ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    // «Бета → JSON сообщения»: событие так, как его прислал сервер.
    val showsSource by (LocalAppSettings.current?.showsSource ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    var sourceOf by remember(store.roomId) { mutableStateOf<String?>(null) }
    // Лист «Кто отреагировал»: сообщение и эмодзи, с которого он начинается.
    var reactorsOf by remember(store.roomId) { mutableStateOf<Pair<String, String?>?>(null) }
    // Карточка человека, на чьё лицо или имя нажали. Хранилище участников открывается по первому нажатию и закрывается с экраном.
    var profileOf by remember(store.roomId) { mutableStateOf<String?>(null) }
    val memberStore = remember(store.roomId, profileOf != null) { if (profileOf != null) members?.invoke(store.roomId) else null }
    DisposableEffect(memberStore) { onDispose { memberStore?.stop() } }
    LaunchedEffect(memberStore) { memberStore?.load() }
    val openProfile: ((String) -> Unit)? = if (members != null && !isThread) { id -> profileOf = id } else null
    Box(modifier.fillMaxSize()) {
    ChatWallpaper()
    sourceOf?.let { json -> EventSourceSheet(json, onDismiss = { sourceOf = null }) }
    reactorsOf?.let { (messageId, key) ->
        val reactions = state.rows.firstNotNullOfOrNull { (it as? TimelineRow.MessageRow)?.message?.takeIf { m -> m.id == messageId } }?.reactions.orEmpty()
        ReactorsSheet(
            reactions, key,
            load = { store.reactors(messageId) },
            onDismiss = { reactorsOf = null },
            loadReaders = if (state.header.isOneToOne) null else ({ store.readers(messageId) }),
        )
    }
    val profileId = profileOf
    if (profileId != null && memberStore != null) {
        val memberState by memberStore.state.collectAsStateWithLifecycle()
        val known = memberState.members.firstOrNull { it.userId == profileId }
        // Ушедшего из комнаты в списке нет — карточка называет его по тому, что было у сообщения.
        val shown = known ?: wtf.iskra.core.rooms.RoomMemberSummary(
            userId = profileId,
            name = state.rows.firstNotNullOfOrNull { (it as? TimelineRow.MessageRow)?.message?.takeIf { m -> m.senderId == profileId }?.senderName } ?: profileId,
            avatarUrl = null,
            role = wtf.iskra.core.rooms.RoomMemberSummary.Role.MEMBER,
            isInvited = false,
        )
        MemberSheet(
            memberStore, shown, isOffline, blocked,
            onMessage = onMessagePerson?.takeIf { profileId != LocalOwnUserId.current }?.let { message -> { message(profileId); profileOf = null } },
            onDismiss = { profileOf = null },
        )
    }
    CompositionLocalProvider(
        LocalChatInk provides wallpaperInk(),
        LocalOpenProfile provides openProfile,
        LocalDiscardUpload provides { id -> scope.launch { store.discard(id) } },
        LocalReaderCount provides if (isThread || state.header.isOneToOne) null else { id -> store.readerIds(id).size },
        LocalShowReactors provides if (isThread) null else { id, key -> reactorsOf = id to key },
        LocalCompactLayout provides compactLayout,
        LocalShowSource provides if (showsSource) ({ id -> sourceOf = store.sourceJson(id) }) else null,
    ) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Header(state, isThread, onBack, showsBack, onSearch = { searching = true }, onOpenDetails = onOpenDetails, isOffline = isOffline, isUpdating = isUpdating)
        // По одному человеку, самому давнему: ответ на него поднимает следующего.
        identityChanges?.let { identity ->
            val waiting by identity.changes.collectAsStateWithLifecycle()
            waiting.firstOrNull()?.let { change ->
                var failed by remember(change.userId) { mutableStateOf(false) }
                IdentityChangeBanner(change, failed) {
                    scope.launch {
                        failed = try {
                            identity.acknowledge(change)
                            false
                        } catch (_: IdentityChangeException) {
                            true
                        }
                    }
                }
            }
        }
        liveLocation?.let { sharer ->
            val sharing by sharer.state.collectAsStateWithLifecycle()
            sharing.shares[store.roomId]?.takeIf { sharing.isSharing(store.roomId, java.time.Instant.now()) }?.let { until ->
                LiveLocationStrip(until) { scope.launch { sharer.stop(store.roomId) } }
            }
            sharing.failure?.let { failure ->
                AlertDialog(
                    onDismissRequest = sharer::dismissFailure,
                    confirmButton = { TextButton(onClick = sharer::dismissFailure) { Text(stringResource(android.R.string.ok)) } },
                    text = { Text(stringResource(failure.explanation)) },
                )
            }
        }
        // Полоса верит и комнате: открепили последнее — `pinnedIds` пуст раньше, чем лента закреплённых ответит.
        pinned.messages.lastOrNull()?.takeIf { hasPinned && !isThread }?.let { latest ->
            PinnedStrip(latest, count = pinned.messages.size, onClick = { jumpTo = latest.id }, onOpenList = { showsPinned = true })
        }
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
                    onOpenThread = onOpenThread,
                    onAtLiveEnd = { atLiveEnd = it },
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
        // Строка под лентой сжимает её окно, а лента держит низ — у прокрученного вверх читателя всё,
        // что он видит, прыгало на строку каждый раз, как кто-то начинал или бросал писать (вторая
        // причина «скачущей ленты» на iOS). Поэтому только у конца переписки; выше — в шапке.
        if (state.typing.isNotEmpty() && atLiveEnd) {
            // Три точки в пузыре, а имя — в заголовке (`headerSubtitle`): две подачи одного факта, одна из них без слов, лишние.
            if (compactLayout) {
                // Строкой, как всё остальное в компактном виде (`CompactTypingRow`): кто пишет, словами.
                Text(
                    if (state.typing.size == 1 && !state.header.isOneToOne) stringResource(R.string.room_typing_by, state.typing[0]) else stringResource(R.string.room_typing),
                    style = MaterialTheme.typography.bodySmall,
                    color = quietInk(),
                    modifier = Modifier.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.tight),
                )
            } else {
                TypingBubble(Modifier.padding(horizontal = Theme.Spacing.normal).padding(top = Theme.Spacing.withinRun))
            }
        }
        // Личный чат с тем, кого заблокировали: плашка вместо поля ввода. В группе один заблокированный не мешает писать остальным.
        val blockedId = state.header.counterpartId?.takeIf { state.header.isOneToOne && blocked != null }
        val blockedState by (blocked?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(wtf.iskra.core.session.BlockStore.State()) }).collectAsStateWithLifecycle()
        if (blockedId != null && blockedId in blockedState.blockedUserIds) {
            BlockedBanner { scope.launch { blocked?.unblock(blockedId) } }
        } else if (state.canPost) {
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
                onAttachPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onTakePhoto = {
                    withCamera {
                        val uri = Camera.newCapture(context, video = false)
                        capturing = PendingAttachment(uri, PendingAttachment.Kind.PHOTO)
                        runCatching { takePhoto.launch(uri) }.onFailure { capturing = null }
                    }
                },
                onRecordVideo = {
                    withCamera {
                        val uri = Camera.newCapture(context, video = true)
                        capturing = PendingAttachment(uri, PendingAttachment.Kind.VIDEO)
                        runCatching { recordVideo.launch(uri) }.onFailure { capturing = null }
                    }
                },
                onLocation = { locating = true },
                onPoll = if (state.header.isOneToOne) null else ({ composingPoll = true }),
                onStickers = if (stickers != null) ({ choosingSticker = true }) else null,
                canPaste = { Camera.clipboardHasMedia(context) },
                onPaste = {
                    scope.launch {
                        Camera.paste(context)?.let { pasted ->
                            attachments = (attachments + PendingAttachment.visual(context, pasted)).distinct().take(MAX_ATTACHMENTS)
                        }
                    }
                },
                onAttachFile = { filePicker.launch(arrayOf(ANY_TYPE)) },
                onRemoveAttachment = { attachment -> attachments = attachments - attachment },
                onEditAttachment = { attachment -> editingPhoto = attachment },
                recordingElapsed = recordingElapsed,
                onCancelLocked = if (voiceLocked) ({ voiceLocked = false; recorder.cancel() }) else null,
                mic = {
                    val sendRecording = {
                        val reply = replyingTo
                        recorder.finish()?.let { voice ->
                            replyingTo = null
                            scope.launch { store.sendVoice(voice.bytes, voice.filename, VoiceRecorder.MIME_TYPE, voice.durationMs, voice.waveform, reply?.id) }
                        }
                        Unit
                    }
                    Box(contentAlignment = Alignment.BottomCenter) {
                        if (voiceLocked && recordingElapsed != null) {
                            // Без пальца: отправка — кнопкой на месте микрофона.
                            FilledIconButton(
                                onClick = { voiceLocked = false; sendRecording() },
                                modifier = Modifier.padding(bottom = Theme.Spacing.tight),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.recording_send))
                            }
                        } else {
                            MicButton(
                                recording = if (videoMode) noteElapsed != null else recordingElapsed != null,
                                hasPermission = {
                                    granted(Manifest.permission.RECORD_AUDIO) && (!videoMode || granted(Manifest.permission.CAMERA))
                                },
                                onNeedPermission = {
                                    if (videoMode) {
                                        cameraPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                    } else {
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onStart = {
                                    VoicePlayback.stop()
                                    if (videoMode) {
                                        noteRecorder.begin()
                                        true
                                    } else {
                                        recorder.start(scope)
                                    }
                                },
                                onFinish = if (videoMode) sendNote else sendRecording,
                                onCancel = { if (videoMode) cancelNote() else recorder.cancel() },
                                onLock = {
                                    if (videoMode) noteLocked = true else voiceLocked = true
                                    justLocked = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                video = videoMode,
                                onToggle = { videoMode = !videoMode },
                                onReach = { lockReach = it },
                                modifier = Modifier.padding(bottom = Theme.Spacing.tight),
                            )
                        }
                        // Пилюля — над кнопкой, вне её рамки: композер не растёт от жеста.
                        if (lockReach >= 0f || justLocked) {
                            RecordLock(
                                reach = lockReach,
                                locked = justLocked,
                                modifier = Modifier.offset(y = -(Theme.Size.minimumTapTarget + Theme.Spacing.close)),
                            )
                        }
                    }
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
                                val job = mediaJobs?.begin(
                                    store.roomId,
                                    photos.map { pickedItem(context, it) },
                                )
                                photos.forEachIndexed { index, attachment ->
                                    val caption = text.takeIf { !captioned && it.isNotBlank() }
                                    val replyId = reply?.id.takeIf { index == 0 }
                                    val sent = when (attachment.kind) {
                                        // Видео — адресом, не байтами: его готовит и читает подготовка, а не память.
                                        PendingAttachment.Kind.VIDEO ->
                                            store.sendVideo(context, attachment.uri, pickedName(context, attachment.uri), settings, caption, replyId)
                                        PendingAttachment.Kind.FILE -> readPicked(context, attachment.uri)?.let { (bytes, name) ->
                                            store.sendFile(bytes, name, context.contentResolver.getType(attachment.uri), caption, replyId)
                                        } ?: false
                                        PendingAttachment.Kind.PHOTO -> readPicked(context, attachment.uri)?.let { (bytes, name) ->
                                            store.sendPhoto(bytes, name, settings, caption, replyId)
                                        } ?: false
                                    }
                                    Camera.discard(context, attachment.uri)
                                    mediaJobs?.handed(job, sent)
                                    if (sent && caption != null) captioned = true
                                }
                                mediaJobs?.doneHanding(job)
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
                color = quietInk(),
            )
        }
    }
    }
    }

    // Черновик — в зашифрованный стор SDK, когда комнату оставляют, и не раньше, чем он прочитан.
    DisposableEffect(store, draftLoaded) {
        onDispose { if (draftLoaded && editing == null) scope.launch { store.saveDraft(field.text) } }
    }

    if (noteElapsed != null) {
        VideoNoteOverlay(
            recorder = noteRecorder,
            locked = noteLocked,
            lockReach = lockReach,
            justLocked = justLocked,
            onSend = sendNote,
            onCancel = cancelNote,
        )
    }
    if (noCamera) {
        AlertDialog(
            onDismissRequest = { noCamera = false },
            confirmButton = { TextButton(onClick = { noCamera = false }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(R.string.video_note_no_camera)) },
        )
    }
    if (cameraDenied) {
        AlertDialog(
            onDismissRequest = { cameraDenied = false },
            confirmButton = { TextButton(onClick = { cameraDenied = false }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(R.string.video_note_needs_camera)) },
        )
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
@OptIn(ExperimentalMaterial3Api::class)
private fun Header(state: TimelineState, isThread: Boolean, onBack: () -> Unit, showsBack: Boolean, onSearch: () -> Unit, onOpenDetails: (() -> Unit)? = null, isOffline: Boolean = false, isUpdating: Boolean = false) {
    val header = state.header
    TopAppBar(
        navigationIcon = {
            if (showsBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            }
        },
        title = {
            Row(
                modifier = if (onOpenDetails != null) Modifier.clickable(onClick = onOpenDetails) else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
            ) {
                RoomAvatar(
                    name = header.name,
                    url = header.avatarUrl,
                    size = Theme.Size.avatarToolbar,
                    shape = AvatarShape.CIRCLE,
                    seed = header.avatarSeed,
                    faces = header.faces,
                )
                Column {
                    // В треде заголовок — «Тред», а под ним — чей он: комната.
                    Text(
                        if (isThread) stringResource(R.string.thread_title) else header.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Порядок — `headerSubtitle` на iOS: сеть, тред, кто пишет, присутствие или Matrix ID собеседника, участники.
                    val presence = state.counterpartPresence?.let { presenceText(it) }
                    val status = when {
                        isOffline -> stringResource(R.string.room_list_waiting_for_network)
                        isUpdating -> stringResource(R.string.room_list_updating)
                        isThread -> header.name
                        state.typing.isNotEmpty() ->
                            if (header.isOneToOne) {
                                stringResource(R.string.room_typing)
                            } else {
                                stringResource(R.string.room_typing_by, android.icu.text.ListFormatter.getInstance().format(state.typing))
                            }
                        header.isOneToOne -> presence ?: header.counterpartId
                        header.memberCount > 0 ->
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
        },
        actions = {
            // Поиск — по комнате; из треда он вёл бы туда, куда тред не покажет.
            if (!isThread) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.room_search_in_chat))
                }
            }
        },
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun Conversation(
    state: TimelineState,
    store: TimelineStore,
    jumpTo: String?,
    onJump: (String?) -> Unit,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onOpenThread: ((Message) -> Unit)? = null,
    onAtLiveEnd: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }
            .distinctUntilChanged()
            .collect(onAtLiveEnd)
    }
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
    // По ключу рисования: эхо, сменившее наш пузырь, — та же строка, и второй прокрутки не просит.
    val newest = newestFirst.firstOrNull()
    LaunchedEffect(newest?.let(state::viewKey)) {
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
            items(newestFirst, key = { state.viewKey(it) }) { row ->
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
                    onOpenThread = onOpenThread,
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
                    is TimelineRow.EventRow -> ServiceLine(row.event.text(stringsOf()), row.event.people)
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

        // Кнопка «вниз» (`scrollToBottom`): появляется, когда читающий ушёл от живого конца, и считает, сколько чужих сообщений пришло, пока он там.
        val away by remember { derivedStateOf { list.firstVisibleItemIndex > 1 } }
        var unseen by remember { mutableIntStateOf(0) }
        LaunchedEffect(newest?.let(state::viewKey)) {
            if (away && newest?.message?.isOwn == false) unseen++
        }
        LaunchedEffect(away) { if (!away) unseen = 0 }
        AnimatedVisibility(away, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(Theme.Spacing.roomy)) {
            val label = stringResource(R.string.room_scroll_to_bottom)
            Box(
                Modifier
                    .size(Theme.Size.composerControl)
                    .shadow(Theme.Spacing.tight, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = label) { scope.launch { list.animateScrollToItem(0); unseen = 0 } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = label)
                if (unseen > 0) {
                    Text(
                        unseen.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalAccent.current.foreground,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = -Theme.Size.unreadBadge / 2)
                            .sizeIn(minWidth = Theme.Size.unreadBadge, minHeight = Theme.Size.unreadBadge)
                            .background(LocalAccent.current.control, CircleShape)
                            .padding(horizontal = Theme.Spacing.tight),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
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
        // Плавающая дата — капсула над перепиской (`FloatingDateLabel`), а не голые слова: под ней идут пузыри.
        if (day != null) {
            Text(
                dayLabel(day),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = Theme.Spacing.close)
                    .shadow(Theme.Spacing.tight, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .padding(horizontal = Theme.Spacing.normal, vertical = Theme.Spacing.tight),
            )
        }
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
/** Сколько виден закрытый замок, прежде чем пилюля уйдёт. */
private const val LOCK_CONFIRM_MS = 600L

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
    onOpenThread: ((Message) -> Unit)? = null,
) {
    val quickReaction by (LocalAppSettings.current?.quickReaction ?: remember { kotlinx.coroutines.flow.MutableStateFlow("❤️") }).collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val own = message.isOwn
    val top = if (message.isContinuation) (if (LocalCompactLayout.current) Theme.Spacing.withinCompactRun else Theme.Spacing.withinRun) else Theme.Spacing.betweenSenders / 2
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var revealed by remember(message.id) { mutableStateOf(false) }
    var noteTaps by remember(message.id) { mutableIntStateOf(0) }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * Theme.Size.BUBBLE_WIDTH_FRACTION).toInt().coerceAtMost(Theme.Size.BUBBLE_MAX_DP)
    val ownUserId = LocalOwnUserId.current
    // «Компактный вид»: все строкой слева направо, без пузырей и без деления на своё и чужое (`CompactMessageRow`).
    val compact = LocalCompactLayout.current
    val atEnd = own && !compact

    val wash by animateColorAsState(
        if (highlighted) LocalAccent.current.control.copy(alpha = Theme.Opacity.SELECTED_TAB) else Color.Transparent,
        label = "highlight",
    )
    // Один корень: элемент ленты из двух корней кладёт их друг на друга в обратном порядке.
    Column(Modifier.background(wash)) {
        SwipeToReply(towardStart = atEnd, enabled = message.canReply, onReply = { onReply(message) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Theme.Spacing.close, end = Theme.Spacing.close, top = top),
                horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close, if (atEnd) Alignment.End else Alignment.Start),
                verticalAlignment = if (compact) Alignment.Top else Alignment.Bottom,
            ) {
                // Время — снаружи пузыря, со стороны, противоположной краю переписки: столбец времён
                // тихо стоит рядом с сообщениями, а не режет их, и не спорит с последней строкой за место.
                if (atEnd) {
                    (message.delivery as? DeliveryStatus.Failed)?.let { failed ->
                        FailedBadge(failed.reason, onUnsent, Modifier.align(Alignment.CenterVertically))
                    }
                    MessageMarks(message)
                }
                // Лицо — у последнего сообщения захода, у его подножия, как в Telegram; в компактном виде — у первого, у всех.
                if ((!own || compact) && (showsSender || compact)) {
                    if (if (compact) !message.isContinuation else !message.isContinued) {
                        val openProfile = LocalOpenProfile.current
                        Avatar(
                            message.senderName, message.senderAvatarUrl, Theme.Size.avatarSmall, seed = message.senderId,
                            modifier = if (openProfile != null && message.senderId != null) Modifier.clip(CircleShape).clickable(role = Role.Button) { openProfile(message.senderId!!) } else Modifier,
                        )
                    } else {
                        Spacer(Modifier.width(Theme.Size.avatarSmall))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f, fill = compact),
                    horizontalAlignment = if (atEnd) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(if (compact) Theme.Spacing.tight else Theme.Spacing.withinRun),
                ) {
                    if ((showsSender || compact) && (!own || compact) && !message.isContinuation) {
                        val openProfile = LocalOpenProfile.current
                        Text(
                            message.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = quietInk(),
                            maxLines = 1,
                            modifier = Modifier
                                .then(if (openProfile != null && message.senderId != null) Modifier.clickable(role = Role.Button) { openProfile(message.senderId!!) } else Modifier)
                                .padding(start = Theme.Spacing.close),
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
                            onDoubleTap = { if (message.canReact) onReact(quickReaction) },
                            onLongPress = { menuAnchor = anchor.rect },
                            onQuote = onQuote,
                            plain = compact,
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
                            onOpenThread = onOpenThread,
                            onAskDelete = { confirm = Confirm.DELETE },
                            onAskEndPoll = { confirm = Confirm.END_POLL },
                        ) {
                            BubbleBody(message, interactive = false, revealed = revealed, noteTaps = 0, plain = compact)
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
                    // Ответы, собранные под ним, — в одно нажатие.
                    val thread = message.thread
                    if (thread != null && onOpenThread != null) ThreadLine(thread) { onOpenThread(message) }
                }
                if (!atEnd) MessageMarks(message)
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
private fun PinnedStrip(message: Message, count: Int, onClick: () -> Unit, onOpenList: () -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = Theme.Spacing.roomy, end = Theme.Spacing.tight, top = Theme.Spacing.tight, bottom = Theme.Spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(Theme.Size.quoteBar).height(Theme.Size.avatarSmall).background(accent.control))
            Spacer(Modifier.width(Theme.Spacing.close))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.room_pinned), style = MaterialTheme.typography.labelMedium, color = accent.control)
                    // Только где их больше одного: «Закреплено 1» — число, о котором не спрашивали, а
                    // «Закреплено 4» — причина нажать на стрелку.
                    if (count > 1) {
                        Spacer(Modifier.width(Theme.Spacing.tight))
                        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(body(message), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Полоса ведёт к сообщению, стрелка — к остальным закреплённым.
            IconButton(onClick = onOpenList) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.room_pinned_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** «Вы делитесь геопозицией до 18:30» и «Остановить» — над лентой, пока трансляция идёт. Порт `LiveLocationStrip`. */
@Composable
private fun LiveLocationStrip(until: java.time.Instant, onStop: () -> Unit) {
    val time = remember(until) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date.from(until)) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = Theme.Spacing.roomy, end = Theme.Spacing.tight, top = Theme.Spacing.tight, bottom = Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.NearMe, contentDescription = null, tint = LocalAccent.current.control, modifier = Modifier.size(Theme.Size.menuIcon))
        Spacer(Modifier.width(Theme.Spacing.close))
        Text(stringResource(R.string.location_you_are_sharing, time), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onStop) { Text(stringResource(R.string.location_stop), color = MaterialTheme.colorScheme.error) }
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
    /** Без пузыря: слова прямо на странице (компактный вид, `BubbleStyle.plain`). */
    plain: Boolean = false,
) {
    val accent = LocalAccent.current
    val own = message.isOwn && !plain
    val maxWidth = (LocalConfiguration.current.screenWidthDp * Theme.Size.BUBBLE_WIDTH_FRACTION).toInt().coerceAtMost(Theme.Size.BUBBLE_MAX_DP)
    val ownUserId = LocalOwnUserId.current
    val picture = message.kind.picture
    val album = message.album
    if (album != null) {
        Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            UploadOverlay(message) {
                AlbumContent(
                    album,
                    onOpen = { if (interactive) onOpen(it) },
                    onDoubleTap = onDoubleTap,
                    onLongPress = onLongPress,
                )
            }
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
            UploadOverlay(message) {
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
            }
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
            .then(if (plain) Modifier.fillMaxWidth() else Modifier.widthIn(max = Dp(maxWidth.toFloat())).clip(RoundedCornerShape(Theme.Radius.bubble)))
            // Удалённое — без заливки, пунктирным контуром (`deletedBubble` на iOS): заливка весила бы, как всё, что в чате ещё есть,
            // а пунктир — старейший способ нарисовать то, чего нет. Контур тише слов внутри.
            .then(
                if (plain) {
                    Modifier
                } else if (message.kind is MessageKind.Deleted) {
                    Modifier.deletedOutline(MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Modifier.background(if (own) accent.bubble else accent.incomingBubble)
                },
            )
            .bubbleGestures(
interactive = interactive,
                onClick = onReveal,
                onDoubleClick = onDoubleTap,
                onLongClick = onLongPress,
            )
            .then(if (plain) Modifier else Modifier.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close + Theme.Spacing.withinRun)),
    ) {
        val ink = if (message.kind is MessageKind.Deleted) MaterialTheme.colorScheme.onSurfaceVariant else if (plain) MaterialTheme.colorScheme.onSurface else if (own) accent.onBubble else accent.onIncomingBubble
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
            message.uploadProgress?.let { UploadBar(it, Modifier.padding(top = Theme.Spacing.tight)) }
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

/** Кольцо загрузки поверх картинки своего сообщения, пока оно уходит (`UploadRing`). */
@Composable
private fun UploadOverlay(message: Message, content: @Composable () -> Unit) {
    val progress = message.uploadProgress
    val discard = LocalDiscardUpload.current
    Box(contentAlignment = Alignment.Center) {
        content()
        if (progress != null) {
            UploadRing(progress, onCancel = { message.members.forEach { discard?.invoke(it.id) } })
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
    val quiet = quietInk()
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
    onOpenThread: ((Message) -> Unit)?,
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
        // Ответить в треде, а не в комнате: тот же тред, что открывает строка под сообщением, — или
        // новый, если его ещё нет. Только на дошедшем до сервера и не на ответе в треде.
        if (message.canReply && message.threadRootId == null && onOpenThread != null) {
            add(MenuAction(stringResource(R.string.thread_reply_in_thread), Icons.Filled.Forum) { onOpenThread(message) })
        }
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
        // «12 прочитали · 5 реакций» — в группе: кто прочёл и кто что оставил, чего пилюля чисел и две галочки не скажут.
        val showReactors = LocalShowReactors.current
        val countReaders = LocalReaderCount.current
        if (showReactors != null && countReaders != null && message.canReact) {
            val reactions = message.reactions.sumOf { it.count }
            val seen = countReaders(message.id)
            val title = when {
                seen > 0 && reactions > 0 -> stringResource(
                    R.string.message_menu_seen_and_reacted,
                    pluralStringResource(R.plurals.message_menu_seen, seen, seen),
                    pluralStringResource(R.plurals.message_menu_reactors, reactions, reactions),
                )
                seen > 0 -> pluralStringResource(R.plurals.message_menu_seen, seen, seen)
                reactions > 0 -> pluralStringResource(R.plurals.message_menu_reactors, reactions, reactions)
                else -> null
            }
            if (title != null) add(0, MenuAction(title, if (seen > 0) Icons.Filled.DoneAll else Icons.Filled.EmojiEmotions) { showReactors(message.id, null) })
        }
        LocalShowSource.current?.let { show -> add(MenuAction(stringResource(R.string.beta_view_source), Icons.Filled.Code) { show(message.id) }) }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Reactions(message: Message, onReact: (String) -> Unit) {
    val showReactors = LocalShowReactors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.withinRun),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight, if (message.isOwn) Alignment.End else Alignment.Start),
    ) {
        message.reactions.forEach { reaction ->
            Text(
                stringResource(R.string.room_reaction_pill, reaction.key, reaction.count),
                style = MaterialTheme.typography.labelLarge,
                color = if (reaction.isMine) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (reaction.isMine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .then(
                        if (reaction.isMine) {
                            Modifier.border(Theme.Border.hairline, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    // Нажатие — своя реакция снимается или ставится; долгое — кто отреагировал (`ReactionPill`).
                    .combinedClickable(
                        enabled = message.canReact || showReactors != null,
                        onClick = { if (message.canReact) onReact(reaction.key) },
                        onLongClick = showReactors?.let { show -> { show(message.id, reaction.key) } },
                    )
                    .padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight),
            )
        }
    }
}

/** Слова пузыря. Вложения пока — словом («Фото»), сами картинки и плееры — этап 6. */
@Composable
internal fun body(message: Message): String = when (val kind = message.kind) {
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
private fun ServiceLine(text: String, people: List<wtf.iskra.core.timeline.RoomEventPerson> = emptyList()) {
    val openProfile = LocalOpenProfile.current
    val ink = quietInk()
    // Имена людей в строке — полужирные и нажимаются (`RoomEventRow.linked` на iOS): первое вхождение каждого, по порядку.
    val styled = remember(text, people, openProfile, ink) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            var cursor = 0
            for (person in people) {
                if (person.name.isEmpty()) continue
                val at = text.indexOf(person.name, cursor)
                if (at < 0) continue
                val end = at + person.name.length
                addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold), at, end)
                if (openProfile != null) {
                    addLink(androidx.compose.ui.text.LinkAnnotation.Clickable("person-${person.userId}") { openProfile(person.userId) }, at, end)
                }
                cursor = end
            }
        }
    }
    // `RoomEventRow` на iOS: caption по центру, поля normal и tight; воздух сверху даёт соседний ряд (`TimelineRows`).
    Text(
        styled,
        style = MaterialTheme.typography.bodySmall,
        color = ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.normal, vertical = Theme.Spacing.tight),
    )
}

@Composable
private fun dayLabel(date: Instant): String {
    val day = date.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    return when (day) {
        today -> stringResource(R.string.room_today)
        today.minusDays(1) -> stringResource(R.string.room_yesterday)
        // «24 октября» в этом году и «24 октября 2025» в другом — как `DayDividerView` на iOS.
        else -> {
            val skeleton = if (day.year == today.year) "dMMMM" else "dMMMMy"
            DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
        }
    }
}

@Composable
private fun DayLine(date: Instant) {
    val label = dayLabel(date)
    // Голые слова, как время у пузыря: без плашки. Воздух неровный нарочно — он над датой: дата закрывает день до себя и открывает тот, что под ней.
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = quietInk(),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = Theme.Spacing.roomy, bottom = Theme.Spacing.close),
    )
}

@Composable
private fun UnreadLine() {
    // Во всю ширину экрана, за поля переписки: полоса, что кончалась бы там же, где пузыри, была бы ещё одной фигурой в их колонке.
    Text(
        stringResource(R.string.room_unread_messages),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Theme.Spacing.roomy, bottom = Theme.Spacing.close)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Theme.Backdrop.WASH))
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
    onTakePhoto: () -> Unit = {},
    onRecordVideo: () -> Unit = {},
    onLocation: () -> Unit = {},
    /** «Опрос» в меню «+»; null — в личном чате его нет. */
    onPoll: (() -> Unit)? = null,
    /** Лист стикеров; null — стикеров здесь нет (нет библиотеки). */
    onStickers: (() -> Unit)? = null,
    canPaste: () -> Boolean = { false },
    onPaste: () -> Unit = {},
    onRemoveAttachment: (PendingAttachment) -> Unit,
    /** Правка фото во вложениях. Null — карандаша нет. */
    onEditAttachment: ((PendingAttachment) -> Unit)? = null,
    recordingElapsed: Long?,
    onCancelLocked: (() -> Unit)? = null,
    mic: @Composable () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val accent = LocalAccent.current
    val text = field.text
    val canSend = text.isNotBlank() || (editing == null && attachments.isNotEmpty())
    val sendsWithCtrl by (LocalAppSettings.current?.sendsWithCtrlReturn ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight)) {
        if (attachments.isNotEmpty() && editing == null) PendingAttachments(attachments, onRemoveAttachment, onEditAttachment)
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
                // Буфер спрашивается, когда меню открывают: что в нём, меняется без нас.
                var pastable by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { pastable = canPaste(); choosing = true },
                        modifier = Modifier.padding(bottom = Theme.Spacing.tight),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.room_attach))
                    }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_attach_photo)) },
                            leadingIcon = { Icon(Icons.Filled.Photo, contentDescription = null) },
                            onClick = { choosing = false; onAttachPhoto() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_attach_take_photo)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            onClick = { choosing = false; onTakePhoto() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_attach_record_video)) },
                            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                            onClick = { choosing = false; onRecordVideo() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_attach_file)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                            onClick = { choosing = false; onAttachFile() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.location_attach)) },
                            leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null) },
                            onClick = { choosing = false; onLocation() },
                        )
                        if (onPoll != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.poll_attach)) },
                                leadingIcon = { Icon(Icons.Filled.Poll, contentDescription = null) },
                                onClick = { choosing = false; onPoll() },
                            )
                        }
                        if (pastable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.room_paste_image)) },
                                leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                                onClick = { choosing = false; onPaste() },
                            )
                        }
                    }
                }
            }
            if (recordingElapsed != null) {
                RecordingStrip(recordingElapsed, Modifier.weight(1f), onCancel = onCancelLocked)
            } else TextField(
                // Пилюля без рамки, как в Google Messages: поле — поверхность, а не обведённая форма.
                value = field,
                onValueChange = onFieldChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Theme.Size.composerControl)
                    .onPreviewKeyEvent { event -> hardwareReturn(event, sendsWithCtrl) { if (canSend) onSend() } },
                placeholder = { Text(stringResource(R.string.room_composer_placeholder)) },
                maxLines = COMPOSER_LINES,
                shape = RoundedCornerShape(Theme.Radius.bubble),
                colors = pillColors(),
                // Мордочка в конце поля, как в Telegram. В правке стикеру взяться неоткуда.
                trailingIcon = if (onStickers != null && editing == null) {
                    {
                        IconButton(onClick = onStickers) {
                            Icon(Icons.Filled.EmojiEmotions, contentDescription = stringResource(R.string.stickers_open))
                        }
                    }
                } else null,
            )
            // Нечего отправить — микрофон: голосовое начинается там же, где уходят слова.
            if (!canSend && editing == null) {
                mic()
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.padding(bottom = Theme.Spacing.tight),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.room_send))
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
            color = quietInk(),
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
            color = quietInk(),
            textAlign = TextAlign.Center,
        )
        PrimaryActionButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.room_try_again)) }
    }
}

/** За сколько строк до начала просить следующую страницу. */
private const val HISTORY_PREFETCH = 5

private const val COMPOSER_LINES = 6

/** Цитата в сообщении — тише слов вокруг. */
private const val QUOTE_ALPHA = 0.7f

/** «в сети» · «был(а) 5 мин назад» · «был(а) в 10:20» · «был(а) 12.07.25». Null — сказать нечего: шапка рисует то, что рисовала. */
@Composable
private fun presenceText(presence: wtf.iskra.core.session.Presence): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = wtf.iskra.core.session.PresenceLabel.of(presence) ?: return null
    return when (label) {
        wtf.iskra.core.session.PresenceLabel.Online -> stringResource(R.string.presence_online)
        wtf.iskra.core.session.PresenceLabel.JustNow -> stringResource(R.string.presence_just_now)
        is wtf.iskra.core.session.PresenceLabel.MinutesAgo -> stringResource(R.string.presence_minutes_ago, label.minutes)
        is wtf.iskra.core.session.PresenceLabel.HoursAgo -> stringResource(R.string.presence_hours_ago, label.hours)
        is wtf.iskra.core.session.PresenceLabel.AtTime -> stringResource(
            R.string.presence_at_time,
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(label.at.toEpochMilli())),
        )
        wtf.iskra.core.session.PresenceLabel.Yesterday -> stringResource(R.string.presence_yesterday)
        is wtf.iskra.core.session.PresenceLabel.OnDate -> stringResource(
            R.string.presence_at,
            java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(label.at.toEpochMilli())),
        )
    }
}

/** Обои за разговором: выбор приложения, одна картинка на всё. Своя `Composable`, чтобы чтение настроек перестраивало её, а не всю комнату. */
@Composable
private fun ChatWallpaper() {
    val settings = wtf.iskra.features.support.LocalAppSettings.current ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val name by settings.wallpaper.collectAsStateWithLifecycle()
    val dimming by settings.wallpaperDimming.collectAsStateWithLifecycle()
    val photo by wtf.iskra.features.support.WallpaperStore.photo.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { wtf.iskra.features.support.WallpaperStore.load(context) }
    wtf.iskra.design.WallpaperBackdrop(
        wtf.iskra.design.Wallpaper.fromStorage(name),
        photo,
        dimming ?: wtf.iskra.design.Wallpaper.DEFAULT_DIMMING,
    )
}

/** Вместо поля ввода: человек заблокирован, его новые сообщения не приходят, а путь назад — одно нажатие. Порт `BlockedBanner`. */
@Composable
private fun BlockedBanner(onUnblock: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.profile_blocked_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = quietInk(),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onUnblock) { Text(stringResource(R.string.profile_unblock_user)) }
    }
}

/**
 * Цвет подписей *вне пузырей* — время, имена, «печатает…», плашки: серый, когда за разговором страница приложения, и светлый или тёмный по яркости
 * обоев, когда они есть (`Wallpaper.isDark` после вуали). Серый на тёмно-синем или на фото не читается. Порт `onWallpaperInk`. Null — обоев нет,
 * и подписи берут цвет схемы.
 */
private val LocalChatInk = androidx.compose.runtime.compositionLocalOf<Color?> { null }

@Composable
private fun quietInk(): Color = LocalChatInk.current ?: MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun wallpaperInk(): Color? {
    val settings = wtf.iskra.features.support.LocalAppSettings.current ?: return null
    val name by settings.wallpaper.collectAsStateWithLifecycle()
    val storedDimming by settings.wallpaperDimming.collectAsStateWithLifecycle()
    val measured by wtf.iskra.features.support.WallpaperStore.luminance.collectAsStateWithLifecycle()
    val wallpaper = wtf.iskra.design.Wallpaper.fromStorage(name)
    if (!wallpaper.isSet) return null
    val lightens = !LocalAccent.current.isDark
    val dimming = storedDimming ?: wtf.iskra.design.Wallpaper.DEFAULT_DIMMING
    val luminance = if (wallpaper == wtf.iskra.design.Wallpaper.PHOTO) measured else wallpaper.luminance
    // Фото, которое ещё не измерено, считается тёмным под темнящей вуалью и светлым под светлящей: туда её и ведёт вуаль, а догадка о неувиденной картинке
    // должна идти в ту же сторону.
    val dark = luminance?.let { wtf.iskra.design.Wallpaper.isDark(it, dimming, lightens) } ?: !lightens
    return if (dark) Color.White.copy(alpha = INK_ON_DARK) else Color.Black.copy(alpha = INK_ON_LIGHT)
}

private const val INK_ON_DARK = 0.82f
private const val INK_ON_LIGHT = 0.62f

/** Три пульсирующие точки в серой плашке входящего: кто-то пишет (`TypingBubble` на iOS). */
@Composable
private fun TypingBubble(modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(
        modifier
            .clip(RoundedCornerShape(Theme.Radius.bubble))
            .background(LocalAccent.current.incomingBubble)
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.normal),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val pulse by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(TYPING_PERIOD_MS, delayMillis = index * TYPING_STAGGER_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(Theme.Spacing.close)
                    .graphicsLayer { alpha = pulse; scaleX = 0.8f + 0.2f * (pulse - 0.35f) / 0.65f; scaleY = scaleX }
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

private const val TYPING_PERIOD_MS = 600
private const val TYPING_STAGGER_MS = 180

/** Нажатие на лицо или имя человека в ленте: карточка участника. Null — нажимать нечего (тред, демо). */
internal val LocalOpenProfile = androidx.compose.runtime.compositionLocalOf<((String) -> Unit)?> { null }

/** Долгое нажатие на пилюлю реакции: сообщение и эмодзи, с которого начать лист. Null — листа нет (тред). */
/** Сколько человек прочли сообщение (`TimelineStore.readerIds`). Null — не спрашиваем: личный чат, тред. */
internal val LocalReaderCount = androidx.compose.runtime.compositionLocalOf<((String) -> Int)?> { null }
internal val LocalShowReactors = androidx.compose.runtime.compositionLocalOf<((String, String?) -> Unit)?> { null }

/** «Бета → JSON»: показать событие сообщения. Null — не включено. */
internal val LocalShowSource = androidx.compose.runtime.compositionLocalOf<((String) -> Unit)?> { null }

/**
 * Enter на аппаратной клавиатуре (`composerReturnKey` на iOS): отправка или перенос строки по настройке. Экранная клавиатура шлёт Enter с виртуального
 * устройства — её событие не трогаем: там Enter всегда новая строка, а отправляет кнопка.
 */
private fun hardwareReturn(event: androidx.compose.ui.input.key.KeyEvent, sendsWithCtrl: Boolean, send: () -> Unit): Boolean {
    if (event.key != androidx.compose.ui.input.key.Key.Enter && event.key != androidx.compose.ui.input.key.Key.NumPadEnter) return false
    val device = event.nativeKeyEvent.device
    if (device == null || device.isVirtual) return false
    val action = wtf.iskra.core.support.ReturnKey.action(sendsWithCtrl, ctrl = event.isCtrlPressed || event.isMetaPressed, shift = event.isShiftPressed)
    if (action == wtf.iskra.core.support.ReturnKey.Action.NEWLINE) return false
    if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) send()
    return true
}

/** Пунктирный контур пузыря удалённого сообщения: волосяная линия, штрих и пропуск по [Theme.Border.dash], вполсилы. */
private fun Modifier.deletedOutline(color: Color): Modifier = this.drawBehind {
    val stroke = Theme.Border.hairline.toPx()
    val inset = stroke / 2
    drawRoundRect(
        color = color.copy(alpha = DELETED_OUTLINE_ALPHA),
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(Theme.Radius.bubble.toPx() - inset),
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(Theme.Border.dash.map { it.toPx() }.toFloatArray()),
        ),
    )
}

private const val DELETED_OUTLINE_ALPHA = 0.5f

/** «Компактный вид» ленты (`MessageLayout.compact`): строки вместо пузырей. */
internal val LocalCompactLayout = androidx.compose.runtime.compositionLocalOf { false }
