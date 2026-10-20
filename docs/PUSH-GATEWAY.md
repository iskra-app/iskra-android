# Пуши Android: шлюз и транспорты

Как пуш доходит до Искры на Android и что нужно сделать **человеку** (не коду), чтобы он дошёл.
Код — `core/.../notifications/`, `features/.../push/`, транспорты — `app/src/gms`, `app/src/foss`.
Документ iOS с тем же именем живёт в `iskra-app/iskra` и описывает Sygnal целиком; здесь — только
то, что добавляет Android.

## Что едет в пуше

Pusher регистрируется с `format = event_id_only` (`PushRegistration`): ни шлюз, ни Google, ни
дистрибьютор не видят ни слова сообщения. В пуше `room_id`, `event_id`, счётчик непрочитанного и
`default_payload` = `{"iskra_account": "<случайный ID сессии>"}` — чей это пуш, без Matrix ID.
Слова собирает `PushDecoder` на телефоне.

`app_id` pusher'а — **`wtf.iskra-android`** в обоих flavor'ах и обеих сборках (решение автора,
PROMPT 10.2). Это не `applicationId` (`wtf.iskra`, `wtf.iskra.dev`): в нём дефис запрещён.

## gms — FCM через наш Sygnal

Путь: homeserver → `https://push.iskra.wtf/_matrix/push/v1/notify` → Sygnal → FCM → `IskraMessagingService`.

Сделать один раз:

1. **Проект Firebase.** Добавить в него два Android-приложения: `wtf.iskra` и `wtf.iskra.dev`.
   Скачать `google-services.json` и положить в `app/src/gms/google-services.json`. Файл не секрет
   и может лежать в репозитории (PROMPT 10.2); без него `gms` собирается без пушей, и экран
   уведомлений говорит, что пушей нет. Плагин google-services применяется только при наличии файла.
2. **Сервисный ключ Firebase** (Project settings → Service accounts → Generate new private key) —
   **секрет**, только на сервере шлюза, никогда в репозитории (`.gitignore` ловит `service-account*.json`).
3. **Вход в `sygnal.yaml`** рядом с входами iOS:

   ```yaml
   apps:
     wtf.iskra-android:
       type: gcm
       api_version: v1
       project_id: <project_id из google-services.json>
       service_account_file: /data/service-account-iskra-android.json
   ```

   Выкатку запускает человек (`sudo bash install.sh` на сервере шлюза), с откатом, который
   возвращает и yaml, и `pushkins/`.

Что проверить на сервере после выкатки:

- gcm-pushkin при `event_id_only` кладёт в `data` FCM плоско: `room_id`, `event_id`, `unread`,
  `prio` и **ключи `default_payload`** (`iskra_account`), все значения — строки (FCM v1). Эту форму
  закрепляет `PushPayloadTest.anFcmMessageSaysWhereAndWhose`; разойдётся — менять разбор и тест вместе.
- Приоритет: FCM-сообщение должно быть **high priority**, иначе в Doze пуш приходит через полчаса.
  Sygnal ставит `android.priority = high`, когда homeserver прислал `prio: high`; Synapse делает так
  для зашифрованных событий и для всего со звуком или подсветкой.
- Тестовый запрос: `{"notification":{"devices":[{"app_id":"wtf.iskra-android","pushkey":"<FCM-токен>"}]}}`
  на `/_matrix/push/v1/notify`. `rejected` с этим токеном — FCM его не принял.

## foss — UnifiedPush

Путь: homeserver → Matrix-шлюз дистрибьютора (`https://<хост дистрибьютора>/_matrix/push/v1/notify`)
→ дистрибьютор на телефоне (ntfy и т. п.) → `UnifiedPushReceiver`. Sygnal в этом пути не участвует,
и настраивать на сервере нечего.

- Протокол — свой receiver (`FlavorPush`, `UnifiedPushReceiver`), без библиотеки `connector`: её
  третья версия тянет Tink (Google), а `foss` — без единой библиотеки Google (решение 2026-10-20).
- `pushkey` — endpoint дистрибьютора, `data.url` — его Matrix-шлюз на том же хосте. Шлюз
  спрашивается (`{"unifiedpush":{"gateway":"matrix"}}`); не подтвердился — адрес тот же, а в логе
  предупреждение. На общий чужой шлюз endpoint не отдаётся.
- Нет ни одного дистрибьютора — экран уведомлений так и говорит и ведёт на unifiedpush.org.

Проверить на телефоне: поставить ntfy (F-Droid или Play), собрать `./android.sh`, войти, в
«Настройки → Уведомления» выбрать ntfy. Написать себе с другого клиента, убить приложение,
`adb shell dumpsys deviceidle force-idle` — уведомление приходит расшифрованным, с именем и лицом.

## Не успели расшифровать

Транспорт даёт обработчику около десяти секунд. `Push` ждёт восемь (`PushDecoder.INLINE_BUDGET`);
не успел — заглушка «Iskra · Новое сообщение» сразу и повтор `PushRetryJob` (платформенный
`JobScheduler`, expedited с Android 12) с полным бюджетом, который тихо ставит настоящее уведомление
на место заглушки.

## Пробы

```sh
./android.sh -- --es probe push-sample          # как выглядят уведомления, без аккаунта
./android.sh -- --es probe push --es room '!id:server' --es event '$id'   # настоящее событие
adb logcat -s Iskra:*
```
