# CLAUDE.md

Операционная сводка для работы в этом репозитории. Искра для Android — нативный Matrix-клиент на
Kotlin и Jetpack Compose; весь протокол Matrix и вся его криптография — `matrix-rust-sdk` через
официальные Kotlin-биндинги (`org.matrix.rustcomponents:sdk-android`).

**`docs/PROMPT.md` — задание целиком**: что строить, почему так и какие решения автор уже принял
(раздел 21 — не пересматривать мимоходом). Читать до первой строчки кода. Эталон поведения —
iOS-репозиторий `iskra-app/iskra` (локально `../iskra`, ветка `dev`): когда задание говорит «как в
`TimelineStore`», открыть этот файл и прочитать его — комментарии там объясняют *почему*.

`ROADMAP.md` — этапы до MVP. Закрытый пункт отмечается галочкой **в том же коммите**, что его закрывает.

## Команды

```sh
./gradlew check                     # юнит-тесты, lint, правило модулей, foss без Google
./gradlew assembleFossDebug         # APK для телефона без сервисов Google
./gradlew assembleFossRelease       # релиз без подписи: R8 ломает uniffi/JNA только здесь
./gradlew :core:testDebugUnitTest --tests '*TimeoutTest*'   # один класс
./gradlew :core:connectedDebugAndroidTest                   # SDK на эмуляторе/телефоне
./android.sh [--release] [-- <extras>]                      # собрать, поставить, запустить

scripts/sync-from-ios.sh ../iskra   # привезти общее из iOS (строки, фикстуры, протокол, иконку)
```

JDK 21. `local.properties` (`sdk.dir=…`) не коммитится.

## Модули и одно правило

| Модуль | Что в нём | Что может импортировать |
|---|---|---|
| `:core` | сессии, секреты, комнаты, лента, медиа, транспорт | SDK. **Никогда Compose.** |
| `:design` | `Theme`, акценты, пузыри, blobatar, `Chrome.kt` | Compose. **Никогда Matrix и `:core`.** |
| `:features` | экраны | оба; единственное место, где они встречаются |
| `:app` | `Application`, `Activity`, манифест, сервисы, flavor'ы | без логики: логика в `:features` |
| `:tunnel` | uniffi-обёртка крейта клиента моста (этап 9) | только крейт |

Правило проверяет `checkModuleRules` (корневой `build.gradle.kts`) по *разрешённому* графу, а не по
объявленным зависимостям — транзитивный Compose в `:core` тоже ловится. Там же: `foss` без единой
библиотеки Google. **Ни один тип SDK не выходит выше `:core`**: экранам отдаются value-типы.

## Что общее с iOS и как оно сюда попадает

Общее **не правится здесь руками**. `scripts/sync-from-ios.sh <iskra> [rev]` читает *коммит*
(не рабочее дерево) и кладёт рядом `UPSTREAM` с его хэшем:

- строки семи языков → `{core,features,app}/src/main/res/values*/strings.xml`;
- golden-фикстура blobatar → `design/src/test/resources/`; векторы NIP-44 → `core/src/test/resources/`;
- крейт `iskra-tunnel-proto` из `../iskra-bridge` → `third_party/iskra-tunnel-proto/` (MIT);
- знак Искры → adaptive icon (`app/src/main/res/drawable/ic_launcher_*.xml`).

Новая строка добавляется **в iOS-репозитории** во все семь `.strings`, потом синхронизируется.
Строки, нужные только Android, — `values*/strings_android.xml`, тоже на семи языках. CI проверяет,
что синхронизация на том же коммите не меняет дерево.

## Инварианты, которые ломаются тихо

- **Ни одной строки в composable.** Только ресурсы. `SourceRulesTest` ловит `Text("…")`.
- **Семь языков полные.** Android тихо берёт английский вместо пропавшего ключа — `LocalizationTest`
  и lint `MissingTranslation`/`MissingQuantity` (ошибки) не дают.
- **Размеры только из `Theme`**, все кратны 4 (исключения названы в `Theme.kt`, держит `ThemeTest`).
- **Главная кнопка — `PrimaryActionButton`**, не системный `Button`: на моно-акценте тот выходит
  белой плитой с белыми словами. Акцент — из `LocalAccent`, не из `colorScheme.primary`.
- **Каждое ожидание SDK ограничено** `withTimeLimit` (не `kotlinx.coroutines.withTimeout`: тот ждёт
  зависший вызов FFI и бросает отмену, которую никто не увидит).
- **Бэкап выключен** целиком (`SourceRulesTest.backupIsOff`).
- **Не логировать** сообщения, токены, пароли, OAuth callback URL; логи SDK не выше `info`.
- Полный список «чего не делать» — `docs/PROMPT.md`, раздел 22.

## Код чужих проектов

Element X (AGPL-3.0) и Telegram (GPL) — **только читать**, не копировать. Зависимость под GPL/AGPL
без вопроса автору не добавлять. blobatar (MIT) портирован с атрибуцией в каждом файле порта.

## Лицензия

GPL-3.0 с дополнительным разрешением по разделу 7 (`LICENSE`, `LICENSE-EXCEPTION.md`) — та же, что у
iOS-репозитория. Решено автором 2026-10-17; это заменяет раздел 19.2 задания, где лицензия ещё не была
выбрана. Вклад принимается с `Signed-off-by` (`CONTRIBUTING.md`).

## Git

Работа в `dev`, `main` — только релизы. Коммиты и комментарии в коде — по-русски, в стиле
iOS-репозитория («Мост: …», «Вход: …»). **Ни одного секрета в истории**: репозиторий откроется
целиком (ключ подписи, сервисный ключ Firebase, ключи мостов — только в секретах CI и у автора).
