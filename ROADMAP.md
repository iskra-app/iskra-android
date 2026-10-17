# ROADMAP

Путь Искры для Android до MVP. Файл рабочий: закрыли пункт — поставили галочку здесь же, в том же
коммите. Этапы и что значит «готово» — `docs/PROMPT.md`, раздел 20. Каждый этап заканчивается **на
настоящем телефоне**, а не только тестами.

**MVP** — этапы 1–7, плюс выход и несколько аккаунтов из 8, плюс первый релиз в GitHub Releases
(подписанный в CI, оба flavor'а, `release.json` и встроенная проверка обновлений).

## 1. Каркас

- [x] Gradle-модули `:core`, `:design`, `:features`, `:app`; правило зависимостей проверяется
      сборкой (`checkModuleRules`, по разрешённому графу); `foss` без библиотек Google — там же
- [x] Version catalog: minSdk 29 с обоснованием, compileSdk/targetSdk 37, AGP 9.4, Kotlin 2.4
- [x] SDK подключён: `sdk-android 26.06.3` — тот же коммит `matrix-rust-sdk` (1c44fb6), что Swift `26.06.06`
- [x] `Theme` (все размеры и движение из `Theme.swift`, сетка держится `ThemeTest`), `Motion` —
      перевод пружин SwiftUI в Compose
- [x] `AccentTheme` (семь цветов, моно, `system`/Material You с API 31), `AppearanceMode`,
      `IskraTheme`, `LocalReduceMotion`
- [x] `PrimaryActionButton`/`SecondaryActionButton` — не системный `Button`
- [x] `scripts/sync-from-ios.sh`: строки (конвертер `.strings`/`.stringsdict` → `strings.xml` с
      `<plurals>`), фикстура blobatar, векторы NIP-44, крейт `iskra-tunnel-proto`, иконка; с `UPSTREAM`;
      идемпотентен
- [x] Семь языков полные: `LocalizationTest`, lint `MissingTranslation`/`MissingQuantity` — ошибки;
      `localeConfig` + `localeFilters`
- [x] Нет строк в composable (`SourceRulesTest`), бэкап выключен целиком (тот же тест)
- [x] `withTimeLimit` — порт `withTimeout` с тестами `TimeoutTests`
- [x] Adaptive icon из знака Искры (с монохромным слоем)
- [x] CI: `check` + релизная сборка + размер APK + выравнивание на 16 KB; синхронизация
      идемпотентна (при токене на iOS-репозиторий); gitleaks
- [x] Лицензия (GPL-3.0 + разрешение по разделу 7, как у iOS), `README`, `CLAUDE.md`,
      `CONTRIBUTING`, `SECURITY`, `docs/PROMPT.md`
- [ ] Тест «SDK линкуется и отвечает» (`SdkLinkageTest`) пройден на эмуляторе
- [ ] Пустое приложение запущено на телефоне на русском и английском
- [ ] lint/detekt-правило на числовые литералы в `padding`/`size`/`RoundedCornerShape` вне `:design`
- [ ] Проверка ELF `LOAD`-сегментов `.so` на 16 KB (`llvm-objdump -p`) в CI — вместе с NDK на этапе 9
- [ ] Workflow релиза: тег `v*` на `main`, проверка, что репозиторий публичный, подпись v2+v3,
      `SHA256SUMS`, `release.json` на семи языках
- [ ] `app.cash.licensee` в CI и `aboutlibraries` для экрана «О приложении»
- [ ] Скриншот-тесты (Roborazzi)

## 2. Вход и сессия

- [ ] `AuthenticationService`: `.well-known`, OIDC (Custom Tabs), legacy SSO, пароль, регистрация
      через `prompt=create`; единственное место сборки `ClientBuilder`
- [ ] `SessionStore` по аккаунтам в Keystore + Tink; ключ nostr отдельно
- [ ] Восстановление сессии, выход, таймауты, `SDKLogging` (не выше `info`)
- [ ] Пробы `signin` и `verify-storage`; сервер входа (`SignInMockServer`)
- [ ] *Готово:* вход в matrix.org на телефоне, перезапуск восстанавливает сессию, выход чистит всё

## 3. Список чатов

- [ ] `RoomListStore` с диффами и склеиванием 50 мс, `ListDiff` с тестами
- [ ] Строки с `RoomPeek`, непрочитанное в трёх цветах, приглашения, поиск
- [ ] Blobatar на golden-фикстуре, аватарки через `MediaLoader`
- [ ] Демо-сервер (`MockHomeserver`, `MockWorld`)
- [ ] *Готово:* демо и настоящий аккаунт выглядят как на iPhone рядом

## 4. Переписка

- [ ] `TimelineStore`: явная первая страница в `open()`, пагинация, удержание места
- [ ] Все типы сообщений, `MatrixHTML`, служебные события, ответы, правки, удаление, реакции
- [ ] Своё меню сообщения, композер с упоминаниями и черновиками, ошибки отправки

## 5. Восстановление и доверие

- [ ] `.settingUp`, код восстановления, верификация в обе стороны, смена ключей, `EncryptionPolicy`
- [ ] *Готово:* второй телефон читает старую переписку; Android и iPhone подтверждают друг друга эмодзи

## 6. Медиа

- [ ] Загрузка, галерея, отправка фото/видео с очисткой и проверкой, подписи, альбомы
- [ ] Голосовые, кружочки, файлы, геопозиция, опросы, стикеры, фоновая отправка

## 7. Пуши

- [ ] FCM (`gms`) и UnifiedPush (`foss`), pusher с `append = true` и `event_id_only`
- [ ] `PushDecoder`, conversation-уведомления с лицами, ответ из уведомления, тап открывает чат
- [ ] Шлюз: `app_id` `moscow.swag.iskra-android`
- [ ] *Готово:* на убитом приложении в Doze приходит расшифрованное уведомление с именем и лицом

## 8. Настройки и управление

- [ ] Выход, несколько аккаунтов (входит в MVP)
- [ ] «О чате», роли, участники, папки, устройства, конфиденциальность, блокировка, оформление,
      хранилище, бета

## 9. Мост

- [ ] `:tunnel` (Rust-крейт + uniffi, `cargo-ndk`), `LocalConnectProxy`, relay-пул без ожидания OK
- [ ] Пробуждение на переднем плане, `BridgeCheck`, экран «Подключение»
- [ ] *Готово:* `BlindRouteTests` и вход + sync на телефоне через настоящий мост

## 10. Вне приложения

- [ ] Share target, виджет, ярлыки, восемь иконок (`activity-alias`), трансляция геопозиции в фоне

## 11. Планшеты и ChromeOS

- [ ] List-detail, клавиатура, мышь, drag-and-drop

## 12. Звонки (после MVP)

- [ ] Element Call в `WebView`, `androidx.core.telecom`, начиная с исходящего
