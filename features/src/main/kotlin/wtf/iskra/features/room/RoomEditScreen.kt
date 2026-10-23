package wtf.iskra.features.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.rooms.RoomDetailsStore
import wtf.iskra.core.rooms.RoomKind
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.settings.Footer
import wtf.iskra.features.settings.SectionTitle
import wtf.iskra.features.support.AvatarShape
import wtf.iskra.features.support.EditableAvatar
import wtf.iskra.features.support.rememberAvatarPicker

/**
 * Картинка, имя и описание чата — на экране, который для этого и открывают (`RoomEditView` на iOS).
 * Открывается карандашом на «О чате», и только тому, кому комната правда что-то менять разрешает;
 * каждое поле — на своих условиях, потому что Matrix даёт три права порознь (`RoomPermissions`).
 *
 * Кнопки «Сохранить» нет: поле уходит, когда его *оставили*, и уход с экрана — тоже «оставили».
 * Без сети поля выключены: имя, набранное в никуда, выглядело бы принятым, а потом тихо пропало бы.
 * Адрес, доступ и роли — следующими пунктами этапа 8.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun RoomEdit(store: RoomDetailsStore, isOffline: Boolean, onBack: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    BackHandler(onBack = onBack)

    var name by rememberSaveable(store.roomId) { mutableStateOf(state.serverName) }
    var topic by rememberSaveable(store.roomId) { mutableStateOf(state.topic.orEmpty()) }
    var naming by remember { mutableStateOf(false) }
    var describing by remember { mutableStateOf(false) }
    // С сервера (или после отказа) — в поле, но не поверх того, что сейчас набирают.
    LaunchedEffect(state.serverName) { if (!naming) name = state.serverName }
    LaunchedEffect(state.topic) { if (!describing) topic = state.topic.orEmpty() }

    val latestName by rememberUpdatedState(name)
    val latestTopic by rememberUpdatedState(topic)
    val stillNaming by rememberUpdatedState(naming)
    val stillDescribing by rememberUpdatedState(describing)
    // Ни `rename`, ни `setTopic` не шлют то, что не менялось, — двойной отправки не будет.
    DisposableEffect(store) {
        onDispose {
            if (stillNaming) store.rename(latestName)
            if (stillDescribing) store.setTopic(latestTopic)
        }
    }

    // Лицо пространства — скруглённый квадрат, и контур в обрезке говорит то же.
    val shape = if (state.kind == RoomKind.SPACE) AvatarShape.ROUNDED_SQUARE else AvatarShape.CIRCLE
    val pick = rememberAvatarPicker(
        shape = shape,
        onCropped = store::setPicture,
        onUnreadable = store::failPicture,
    )

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text(stringResource(R.string.room_manage_edit_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            windowInsets = WindowInsets(0),
        )
        // Картинка уходит заметное время, а лицо до ответа сервера старое: пусть будет видно, что идёт.
        if (state.isUploadingPicture) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            if (state.permissions.canChangePicture) {
                Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
                    EditableAvatar(
                        name = state.name,
                        url = state.avatarUrl,
                        seed = if (state.kind == RoomKind.SPACE) null else state.avatarSeed,
                        shape = shape,
                        canRemove = state.avatarUrl != null,
                        enabled = !isOffline && !state.isUploadingPicture,
                        onChange = pick,
                        onRemove = store::removePicture,
                    )
                }
            }

            if (state.permissions.canRename) {
                SectionTitle(R.string.room_manage_name_header)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.room_manage_name_placeholder)) },
                    singleLine = true,
                    enabled = !isOffline,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Theme.Spacing.roomy)
                        .onFocusChanged { field ->
                            if (naming && !field.isFocused) store.rename(name)
                            naming = field.isFocused
                        },
                )
            }

            if (state.permissions.canChangeTopic) {
                SectionTitle(R.string.room_about)
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    placeholder = { Text(stringResource(R.string.room_manage_topic_placeholder)) },
                    enabled = !isOffline,
                    minLines = TOPIC_LINES,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Theme.Spacing.roomy)
                        .onFocusChanged { field ->
                            if (describing && !field.isFocused) store.setTopic(topic)
                            describing = field.isFocused
                        },
                )
            }

            if (isOffline) Footer(R.string.room_manage_offline)
        }
    }
}

/** Описание — несколько строк, а не строка: его читают, а не только узнают. */
private const val TOPIC_LINES = 3
