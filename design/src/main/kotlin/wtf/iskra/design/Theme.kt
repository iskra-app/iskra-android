package wtf.iskra.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Числа, о которых договорилось всё приложение. Порт `Sources/IskraDesign/Theme.swift`.
 *
 * Отступы и размеры кратны 4, и нигде больше они не пишутся: пузырь, который отступает от края на
 * голые двенадцать dp, — это баг, который ждёт, когда разойдётся с соседним.
 *
 * Два числа здесь **не** кратны 4, и это сказано вслух, чтобы их никто не «исправил»:
 * `Spacing.withinRun` = 2 (зазор внутри серии сообщений одного человека *теснее* сетки, а 4 читается
 * как две отдельные реплики) и `Size.recordingDot` = 10 (точка подбирается на глаз к тексту рядом).
 * Сетка — для того, что стоит в линию с другим. Эти двое ни с чем в линию не стоят.
 * Так же вне сетки `Size.unreadDot` = 10, `Spacing.albumGap` = 2, `Spacing.groupGap` = 2 (шов между
 * строками группы настроек), `Size.menuIcon` = 26 и
 * доли/коэффициенты — это не отступы.
 *
 * *Почему* у каждого числа именно это значение — в комментариях `Theme.swift`; здесь только то,
 * что нужно, чтобы не сломать его при переносе. Значения должны совпадать с iOS: тест
 * `ThemeTests` держит сетку.
 */
object Theme {
    object Radius {
        /** Пузыри, на всех углах. Больше половины высоты однострочного пузыря, чтобы он был капсулой. */
        val bubble = 24.dp

        /** Картинка внутри пузыря: меньше пузыря на отступ между ними, чтобы дуги были концентричны. */
        val picture = 20.dp

        /** Нижние углы картинок альбома, когда под ними подпись. */
        val pictureAboveCaption = 8.dp

        /** Угол иконки приложения размера [side]: доля стороны, а не константа. */
        fun appIcon(side: Float): Float = side * 0.2237f

        val card = 16.dp
        val control = 12.dp

        /** Меню долгого нажатия и панель эмодзи рядом с ним (`MessageMenu`). */
        val menu = 28.dp

        /**
         * Группа строк настроек, как в настройках Android: внешние углы группы крупные, а между
         * строками внутри неё — маленькие, чтобы строки читались отдельными плитками одной группы.
         */
        val group = 24.dp
        val groupInner = 4.dp
    }

    object Opacity {
        /** Цвет предупреждения под его словами. */
        const val WARNING_WASH = 0.14f

        /** Пилюля под открытой вкладкой папки (`FolderTabs`). */
        const val SELECTED_TAB = 0.2f
    }

    object Spacing {
        val tight = 4.dp
        val close = 8.dp
        val normal = 12.dp
        val roomy = 16.dp
        val generous = 24.dp

        /** Между сообщениями разных людей. */
        val betweenSenders = 12.dp

        /** Между сообщениями одного человека. Вне сетки — см. заголовок. */
        val withinRun = 2.dp

        /** Между сообщениями одного человека в компактном виде (пузыри остаются на [withinRun]). */
        val withinCompactRun = 8.dp

        /** Между строками одной группы настроек: шов, а не отступ. Вне сетки — см. заголовок. */
        val groupGap = 2.dp

        /** Между картинками альбома: волосок пузыря, как в Telegram. */
        val albumGap = 2.dp

        /** Вокруг подписи под картинкой — слова стоят от краёв так же, как в текстовом пузыре. */
        val captionHorizontal = roomy - tight
        val captionVertical = normal - tight

        /** Между мозаикой альбома и подписью под ней. */
        val albumCaptionTop = normal

        /** Насколько реакции заходят обратно на пузырь. */
        val reactionOverlap = 8.dp

        /** Насколько реакции отступают внутрь от края своего сообщения. */
        val reactionInset = 12.dp
    }

    /**
     * Длительности и движение. Пружины и кривые записаны словами SwiftUI, переводит [Motion].
     * На Android «уменьшить движение» уважается в корне (`ReduceMotion.kt`), а не здесь.
     */
    object Timing {
        /** Сколько палец держит сообщение, прежде чем меню его поднимет. Четверть секунды — решение. */
        val menuLift = 250.milliseconds

        /** Сколько палец держит чат в списке, прежде чем поднимется превью (`ChatPeek`). */
        val peekLift = 500.milliseconds

        /** Меню открывается: пружина Telegram, число в число. */
        val menuOpens = Motion.Spring(response = 0.265f, dampingFraction = 0.775f)

        /** Пилюля под вкладками папок переезжает, чаты новой вкладки въезжают за ней. */
        val folderSwitches = Motion.spring(duration = 0.35f, bounce = 0.15f)

        /** Меню закрывается: без пружины. Положенное не бросают. */
        val menuCloses = Motion.easeInOut(0.2f)

        /** Действия меню проявляются почти сразу. */
        val menuListShows = Motion.linear(0.05f)

        /** Через сколько пустое стекло меню наполняется содержимым. */
        val menuFills = 150.milliseconds

        val menuContentShows = Motion.easeOut(0.15f)
        val menuBarFades = Motion.easeOut(0.15f)

        /** Один эмодзи появляется в ряду реакций. */
        val reactionPops = Motion.spring(duration = 0.4f, bounce = 0.3f)

        /** Реакции появляются и исчезают. */
        val reaction = 250.milliseconds

        /** Обвязка перестраивается: композер, кнопка отправки, поле поиска. Одно решение, не девять. */
        val chrome = 200.milliseconds

        /** Сообщение приходит в переписку. Единственная анимация, оставшаяся в ленте. */
        val arrival = Motion.spring(duration = 0.32f, bounce = 0.18f)

        /** Колонка уступает место новому сообщению: кривая Telegram. */
        val messageMakesRoom = Motion.timingCurve(0.199f, 0.011f, 0.279f, 0.910f, duration = 0.3f)

        /** Своё сообщение переезжает из композера на свою сторону. */
        val messageCrosses = Motion.timingCurve(0.23f, 1f, 0.32f, 1f, duration = 0.3f)

        /** Чужое сообщение проявляется в освобождённом месте. */
        val messageAppears = Motion.easeOut(0.2f)

        val floatingDateIn = Motion.easeOut(0.3f)
        val floatingDateOut = Motion.easeIn(0.4f)

        /** Сколько список стоит неподвижно, прежде чем плавающая дата уйдёт. */
        val floatingDateLingers = 800.milliseconds

        /** Сколько переписка держится у конца после того, как там приземлилось сообщение. */
        val endIsHeld = 700.milliseconds

        /** Сколько отмеченное место читателя ещё можно вернуть. */
        val placeIsHeld = 600.milliseconds

        val endIsCheckedEvery = 50.milliseconds

        /** Сколько только что открытая комната стоит неподвижно, прежде чем ей можно что-то анимировать. */
        val timelineSettles = 400.milliseconds

        /** Дольше этого комната не ждёт, чтобы показаться целиком. */
        val roomWaitsToBeSeen = 1500.milliseconds

        /** Через сколько ожидание комнаты становится видно. */
        val roomWaitIsShown = 400.milliseconds

        /** Кружочек растёт при старте и садится обратно. */
        val videoNote = Motion.spring(duration = 0.4f, bounce = 0.15f)

        /** Сколько светится сообщение, к которому перешли. */
        val highlight = 1600.milliseconds

        /** Сколько палец лежит на кнопке записи, прежде чем это запись, а не переключение. */
        val holdBegins = 150.milliseconds

        /** Через сколько после нажатия будится микрофон. */
        val prewarmAfter = 90.milliseconds

        /** Один слайд приветствия. */
        val welcomeSlide = 5.seconds

        /** Пауза между вопросом и ответом в превью первого запуска. */
        val previewReplies = 450.milliseconds

        /** Один экран первого запуска сменяет другой. */
        val stepChanges = Motion.spring(duration = 0.45f, bounce = 0f)

        /** Один кадр при шестидесяти герцах. */
        val aFrame = 16.milliseconds

        /** Насколько разнесены вещи, которые приходят вместе. */
        val stagger = 120.milliseconds

        /** Сколько «привет» стоит на экране, прежде чем первый запуск пойдёт дальше. */
        val greeting = 3.seconds
    }

    /** То, что рисуется другого размера, чем есть, — как отклик. */
    object Scale {
        /** Насколько вздрагивает пузырь превью, когда на него ложится новый акцент. */
        const val NOD = 1.04f
    }

    /** Линии *вокруг* вещей. Их мало: рамка — то, за что хватается поверхность без заливки. */
    object Border {
        val hairline = 1.dp

        /** Кольцо выбранной иконки приложения: видно через сетку. */
        val thick = 2.dp

        /** Штрихи контура удалённого сообщения: штрих и пропуск поровну. */
        val dash = listOf(4.dp, 4.dp)
    }

    /**
     * Картинка за картинкой: собственный пузырь фото — это само фото, размытое и затемнённое.
     * Это **не** хром (см. `Chrome.kt`): пузырь — содержимое, а содержимое хрома не получает.
     */
    object Backdrop {
        /** Цвет под текстом *панелью*, а не заливкой. */
        const val WASH = 0.12f

        /** Настолько, чтобы не уцелело ни лицо, ни слово. */
        val blur = 24.dp

        /** Тёмная капсула с белым текстом поверх фото. Одно число, а было три. */
        const val PLATE = 0.45f

        /** И насколько затемняется фон под подписью. */
        const val DIM = 0.45f

        /** Фон рисуется на треть больше места и обрезается: края размытия уходят за обрез. */
        const val OVERDRAW = 1.3f

        /** Насколько темнеет переписка за открытым меню. */
        const val MENU_SCRIM = 0.4f

        /** Вуаль обрезки аватара (`AvatarCropper`): то, что не войдёт в лицо, видно, но под тенью. */
        const val CROP_VEIL = 0.55f

        /** Контур лица в обрезке: заметен на любом фото, но не спорит с ним. */
        const val CROP_OUTLINE = 0.8f

        /** Тень под карандашом на картинке, которую можно сменить (`EditableAvatar`). */
        const val EDITABLE = 0.4f
    }

    object Tint {
        /** Сколько акцента ложится на всё приложение при «Окрасить интерфейс». */
        const val INTERFACE = 0.08f

        /** То же на моно, где это не цвет, а затемнение: вдвое слабее. */
        const val MONO = 0.04f
    }

    object Size {
        val avatar = 40.dp

        /** Лицо в виджете избранного. */
        val widgetFace = 48.dp

        /** Лицо в постоянном уведомлении (аналог Live Activity). */
        val activityFace = 44.dp

        /** Лицо рядом с чатом в списке: по лицам чат и ищут. */
        val roomAvatar = 56.dp

        /** Высота счётчика непрочитанного и ширина «@» рядом с ним. */
        val unreadBadge = 20.dp

        /** Точка «непрочитано» без числа. Вне сетки — как было всегда. */
        val unreadDot = 10.dp

        /** Насколько въезжают чаты новой папки. */
        val folderSlide = 40.dp

        val avatarSmall = 28.dp

        /** Картинка, которая сама кнопка в верхней панели. */
        val avatarToolbar = 36.dp

        /** Своя картинка вверху настроек. */
        val avatarLarge = 64.dp

        /** Лицо вверху профиля: портрет, а не строка списка. */
        val avatarProfile = 96.dp

        /** Диаметр кружочка в ленте. */
        val videoNote = 200.dp

        /** Длинная сторона стикера: реплика, а не плакат. */
        val sticker = 160.dp
        val stickerCell = 72.dp
        val stickerTab = 32.dp

        /** Во сколько раз растёт играющий кружочек там, где он не на весь экран. */
        const val VIDEO_NOTE_GROWTH = 2f

        /** Место часов рядом с пузырём. */
        val clockColumn = 56.dp

        /** Кольцо загрузки над своим фото или видео. */
        val uploadRing = 48.dp

        /** Круглая кнопка под портретом человека. */
        val profileAction = 56.dp

        val qrCode = 240.dp
        val shareFace = 60.dp

        /** Ширина пузыря опроса: полоски сравнимы, только если нарисованы на одной ширине. */
        val poll = 264.dp
        val pollBar = 4.dp

        /** Высота главной и второй кнопки экрана: на ступень выше 40 dp Material, кнопка — цель экрана. */
        val actionButton = 48.dp

        /** Меньше этого ничто нажимаемое не бывает. На Android Material говорит 48; Искра держит 44, как на iOS. */
        val minimumTapTarget = 44.dp

        /** Образец акцента в «Оформлении». */
        val swatch = 44.dp

        /** Плитка обоев: в пропорции экрана. */
        val wallpaperTile = DpSize(72.dp, 128.dp)

        val appIconTile = 60.dp

        /** Черта сбоку цитаты. */
        val quoteBar = 4.dp

        /** Кнопка голосового и самая высокая полоса его волны. */
        val voiceButton = 28.dp

        val audioTrack = 160.dp
        val audioScrubber = 20.dp
        val waveform = 24.dp
        val waveformBar = 2.dp
        val waveformBarMinimum = 3.dp

        /** Красная точка «микрофон включён». Вне сетки — см. заголовок. */
        val recordingDot = 10.dp

        /** Пилюля над кнопкой записи, к которой ведёт свайп вверх. */
        val recordLock = DpSize(40.dp, 64.dp)

        /** Предел ширины цитаты. */
        val quote = 260.dp

        /** Ширина фото или кадра видео в ленте. */
        val picture = 280.dp

        /** Какую долю ширины переписки может занять сообщение. */
        const val BUBBLE_WIDTH_FRACTION = 0.9f

        /** Высота карты. */
        val map = 160.dp

        /** Булавка посередине выбора места на карте. */
        val crosshair = 32.dp

        /** Предел высоты фото: половина экрана. */
        const val PICTURE_HEIGHT_FRACTION = 0.5f

        /** И альбома — чуть больше. */
        const val ALBUM_HEIGHT_FRACTION = 0.6f

        /** И потолок обоих в dp. */
        val pictureMaxHeight = 320.dp

        /** Самый маленький открытый видеоплеер. */
        val viewer = 480.dp

        /** Чёрный зазор между страницами просмотрщика. */
        val galleryPageGap = 20.dp
        val galleryThumbnail = DpSize(28.dp, 40.dp)
        val galleryThumbnailCurrent = 56.dp

        /** Лист с кодом восстановления. */
        val keySheet = 360.dp

        /** Колонка входа и восстановления: одно действие для человека, одна ширина. */
        val signInColumn = 420.dp

        val signInMark = 56.dp

        /** Одна строка основного текста по умолчанию, округлённая к сетке. */
        val bodyLine = 24.dp

        val signInLogo = 112.dp
        val welcomeMark = 132.dp
        val pageDot = 8.dp

        /** Красный значок у неотправленного сообщения. */
        val failureBadge = 20.dp
        val failurePopover = 280.dp

        /** Высота поля композера и диаметр кнопок по бокам: одно число, а не три. */
        val composerControl = 44.dp

        /** Вложение, ждущее в композере. */
        val attachmentTile = 64.dp

        val inkSwatch = 28.dp
        val cropCorner = 20.dp

        /** Поле поиска внизу списка чатов. */
        val searchField = 44.dp

        /** Эмодзи верификации: крупно, на них нужно *смотреть*. */
        val verificationEmoji = 40.dp

        /** Насколько должен уйти палец посреди удержания, чтобы движение что-то значило. */
        val gestureReach = 60.dp

        /** Потолок камеры при записи кружочка. */
        val videoNoteCamera = 440.dp
        val videoNoteHint = 300.dp

        /** Кнопка отправки записываемого. */
        val recordingSend = 72.dp

        /** Насколько близко к началу переписки — уже просьба о следующей странице. */
        val paginationReach = 2400.dp

        /** Насколько близко к новейшему сообщению — уже «стою на нём». */
        val newestReach = 80.dp

        val keyboardDismissDrag = 48.dp

        /** На сколько переписка гаснет под панелью — у шапки и у композера одинаково. */
        val timelineFade = 56.dp

        /** Предел ширины имени чата в шапке. */
        val roomHeader = 260.dp

        val storageBar = 16.dp
        val storageBarLeast = 4.dp

        /** Колонка списка чатов на планшетах и ChromeOS. */
        val sidebarMinimum = 280.dp
        val sidebarIdeal = 320.dp
        val sidebarMaximum = 400.dp

        /** Список действий над сообщением. */
        val menuWidth = 240.dp
        val menuRow = 44.dp

        /** Колонка иконок меню. Вне сетки: по ширине самых широких значков. */
        val menuIcon = 26.dp

        /** Потолки превью чата (`ChatPeek`). */
        val peekWidth = 440.dp
        val peekHeight = 640.dp

        /** Ряд эмодзи над поднятым сообщением: число, а не измерение — меню считает место заранее. */
        val reactionBar = 52.dp

        /** Кнопка под строкой чата, открытая смахиванием (`RoomListSwipeActions`). */
        val swipeAction = 76.dp

        /** Насколько потянуть сообщение к середине, чтобы отпускание ответило (`SwipeToReply`). */
        val swipeToReply = 56.dp
        val reactionCell = 44.dp
        val emojiPanel = 268.dp
        val emojiHeader = 28.dp
        val emojiPanelWidth = menuWidth * 1.5f
    }

    /**
     * Цвета полосы хранилища — единственное место, где цвет несёт смысл, а не тон. Не акцент: акцент
     * меняется, а ключ диаграммы меняться не должен. Порядок фиксирован, назначение по позиции.
     */
    object Storage {
        val bands = listOf(
            Color(0xFFFF3B30), // red
            Color(0xFFFFCC00), // yellow
            Color(0xFF34C759), // green
            Color(0xFF007AFF), // blue
            Color(0xFFAF52DE), // purple
        )

        /** Цвет n-й полосы. По кругу, а не падение на экране про место на диске. */
        fun band(index: Int): Color = bands[index % bands.size]
    }
}
