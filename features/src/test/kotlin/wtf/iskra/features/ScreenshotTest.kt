package wtf.iskra.features

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import wtf.iskra.core.rooms.LastMessageMark
import wtf.iskra.core.rooms.RoomKind
import wtf.iskra.core.rooms.RoomMembership
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.core.session.AuthenticationError
import wtf.iskra.core.session.SignInFlow
import wtf.iskra.core.session.SignInOptions
import wtf.iskra.core.timeline.DeliveryStatus
import wtf.iskra.core.timeline.ForwardOrigin
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.core.timeline.MessageReaction
import wtf.iskra.core.timeline.Poll
import wtf.iskra.core.timeline.ReplyPreview
import wtf.iskra.core.timeline.SendFailure
import wtf.iskra.design.AppearanceMode
import wtf.iskra.design.IskraTheme
import wtf.iskra.features.room.Bubble
import wtf.iskra.features.rooms.ArchiveRow
import wtf.iskra.features.rooms.InviteRow
import wtf.iskra.features.rooms.RoomActions
import wtf.iskra.features.rooms.RoomRow
import wtf.iskra.features.signin.SignInModel
import wtf.iskra.features.signin.SignInScreen
import wtf.iskra.features.support.LocalOwnUserId
import java.time.Instant
import java.util.TimeZone

/**
 * Скриншот-тесты `:features`: то, что люди видят чаще всего, — вход, строки списка чатов, пузыри. Целые экраны
 * держат сторы на клиенте SDK, их на JVM не поднять; эти куски рисуются из value-типов, и снимок — тот же,
 * что на экране. Вложения (фото, голос, файл) не снимаются: `Attachment` строится только из `MediaSource` SDK.
 *
 * Эталоны — `src/test/screenshots`; `./gradlew :features:recordRoborazziDebug` записывает, `verifyRoborazziDebug`
 * сверяет. Слова здесь — образцы данных, а не интерфейс: всё, что от приложения, — из ресурсов, по-русски.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ru-w360dp-h640dp-xhdpi")
class ScreenshotTest {
    /** Тот же порог, что в `:design`: сглаживание краёв на Linux своё, пропавший значок — больше порога. */
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    /** Время в строках и пузырях — по часам машины; эталон от них не зависит. */
    @Before
    fun fixClock() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))

    private fun capture(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = options, content = content)

    /** `Surface`, как у экранов приложения: он задаёт цвет слов по умолчанию, голый фон — нет. */
    @Composable
    private fun Page(appearance: AppearanceMode, content: @Composable () -> Unit) {
        IskraTheme(appearance = appearance) {
            CompositionLocalProvider(LocalOwnUserId provides OWN) {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) { Column { content() } }
            }
        }
    }

    // Вход

    private class FakeFlow(private val options: SignInOptions) : SignInFlow<String> {
        override suspend fun startSignIn(address: String) = options
        override suspend fun singleSignOnUrl() = "https://auth.example.org/login"
        override suspend fun registrationUrl() = "https://auth.example.org/register"
        override suspend fun completeSingleSignOn(callbackUrl: String) = "session"
        override suspend fun signIn(username: String, password: String): String = throw AuthenticationError.Sdk(RuntimeException())
        override suspend fun cancelSignIn() = Unit
    }

    /** Фейк не ждёт, и на `Unconfined` модель доходит до нужного шага прямо в вызове. */
    private fun signIn(options: SignInOptions, steps: SignInModel<String>.() -> Unit = {}) =
        SignInModel(FakeFlow(options), { null }, CoroutineScope(Dispatchers.Unconfined), {}).apply(steps)

    @Composable
    private fun SignInPage(model: SignInModel<String>) {
        IskraTheme(appearance = AppearanceMode.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { SignInScreen(model) }
        }
    }

    @Test
    fun signInAddress() = capture("signin_address") { SignInPage(signIn(OIDC)) }

    @Test
    fun signInDoor() = capture("signin_door") {
        SignInPage(
            signIn(OIDC) {
                setAddress("example.org")
                findServer()
            },
        )
    }

    /** Сервер только с паролем: форма, и неверный пароль сказан словами под ней. */
    @Test
    fun signInPassword() = capture("signin_password") {
        SignInPage(
            signIn(PASSWORD_ONLY) {
                setAddress("example.org")
                findServer()
                begin(SignInModel.Intent.SIGN_IN)
                setUsername("anya")
                setPassword("secret")
                signInWithPassword()
            },
        )
    }

    // Список чатов

    @Composable
    private fun Rows() {
        val actions = RoomActions({}, {}, {}, {}, {})
        for (room in ROOMS) RoomRow(room, NOW, typing = false, actions = actions) {}
        InviteRow(INVITE, onAccept = {}, onDecline = {})
        ArchiveRow(count = 3, hasUnread = true) {}
    }

    @Test
    fun roomRows() = capture("room_rows") { Page(AppearanceMode.LIGHT) { Rows() } }

    @Test
    fun roomRowsDark() = capture("room_rows_dark") { Page(AppearanceMode.DARK) { Rows() } }

    // Пузыри

    @Composable
    private fun Bubbles() {
        for (message in MESSAGES) Bubble(message, showsSender = true, onReact = {}, onReply = {}, onEdit = {}, onDelete = {})
    }

    /** Лента длиннее телефона: экран повыше, чтобы в снимок вошли и «отправляется», и «не дошло». */
    @Test
    @Config(qualifiers = "+h1100dp")
    fun bubbles() = capture("bubbles") { Page(AppearanceMode.LIGHT) { Bubbles() } }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun bubblesDark() = capture("bubbles_dark") { Page(AppearanceMode.DARK) { Bubbles() } }

    private companion object {
        const val OWN = "@sasha:iskra.demo"
        val NOW: Instant = Instant.parse("2026-10-28T12:00:00Z")
        val OIDC = SignInOptions(true, false, false, true, "https://matrix-client.example.org")
        val PASSWORD_ONLY = SignInOptions(false, false, true, false, "https://example.org")

        val ROOMS = listOf(
            RoomSummary(
                id = "!anya:iskra.demo", name = "Аня Смирнова", avatarSeed = "@anya:iskra.demo", kind = RoomKind.DIRECT,
                unreadCount = 2, unreadMessageCount = 2, lastMessage = "И захвати, пожалуйста, зарядку 🙌",
                lastActivity = NOW.minusSeconds(5 * 60),
            ),
            RoomSummary(
                id = "!family:iskra.demo", name = "Семья 🏡", unreadCount = 4, unreadMessageCount = 4, mentionCount = 1,
                lastMessage = "@Саша, ты в субботу с нами?", lastSender = "Мама", lastActivity = NOW.minusSeconds(40 * 60),
            ),
            RoomSummary(
                id = "!work:iskra.demo", name = "Работа", unreadCount = 0, unreadMessageCount = 12, isMuted = true,
                lastMessage = "Созвон перенесли на четверг", lastSender = "Миша", lastActivity = NOW.minusSeconds(3 * 3600),
            ),
            RoomSummary(
                id = "!misha:iskra.demo", name = "Миша", avatarSeed = "@misha:iskra.demo", kind = RoomKind.DIRECT,
                isFavourite = true, lastMessage = "Договорились!", lastMessageMark = LastMessageMark.READ,
                lastActivity = NOW.minusSeconds(26 * 3600),
            ),
            RoomSummary(
                id = "!kitchen:iskra.demo", name = "Кухня", draft = "Купить хлеб и", lastMessage = "Кто последний брал сковородку?",
                lastSender = "Лена", lastActivity = NOW.minusSeconds(4 * 86400),
            ),
        )
        val INVITE = RoomSummary(
            id = "!books:iskra.demo", name = "Книжный клуб 📚", membership = RoomMembership.INVITED, invitedBy = "Лена",
            lastActivity = NOW.minusSeconds(2 * 3600),
        )

        private fun message(
            id: String,
            kind: MessageKind,
            own: Boolean = false,
            minutesAgo: Long,
            delivery: DeliveryStatus? = if (own) DeliveryStatus.Sent else null,
        ) = Message(
            id = id, kind = kind, senderId = if (own) OWN else "@anya:iskra.demo", senderName = if (own) "Саша" else "Аня",
            senderAvatarUrl = null, isOwn = own, timestamp = NOW.minusSeconds(minutesAgo * 60), delivery = delivery,
        )

        val MESSAGES = listOf(
            message("1", MessageKind.Text("Привет! Ты сегодня в офисе?"), minutesAgo = 50),
            message("2", MessageKind.Text("Буду к трём"), own = true, minutesAgo = 48, delivery = DeliveryStatus.Read)
                .copy(replyTo = ReplyPreview("1", "Аня", text = "Привет! Ты сегодня в офисе?")),
            message("3", MessageKind.Text("Смотри, что нашла"), minutesAgo = 40)
                .copy(forwardedFrom = ForwardOrigin(senderName = "Миша"), reactions = listOf(MessageReaction("👍", 2, isMine = true))),
            message("4", MessageKind.Text("Поправил время встречи"), own = true, minutesAgo = 30).copy(isEdited = true),
            message("5", MessageKind.Deleted, minutesAgo = 25),
            message("6", MessageKind.Unreadable, minutesAgo = 20),
            message(
                "7",
                MessageKind.Poll(
                    Poll(
                        question = "Куда идём в пятницу?",
                        answers = listOf(
                            Poll.Answer("a", "В кино", votes = 2, isChosen = true, isWinner = false),
                            Poll.Answer("b", "В парк", votes = 1, isChosen = false, isWinner = false),
                        ),
                        maxSelections = 1, isDisclosed = true, isEnded = false, voters = 3,
                    ),
                ),
                minutesAgo = 15,
            ),
            message("8", MessageKind.Text("Отправляю…"), own = true, minutesAgo = 2, delivery = DeliveryStatus.Sending),
            message("9", MessageKind.Text("Не дошло"), own = true, minutesAgo = 1, delivery = DeliveryStatus.Failed(SendFailure.SERVER)),
        )
    }
}
