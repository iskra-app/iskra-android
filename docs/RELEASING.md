# Как выпустить релиз

Релиз собирает и публикует только CI (`.github/workflows/release.yml`) по тегу `v*` на `main`
(docs/PROMPT.md, 19). С ноутбука релизы не выпускаются: ключ подписи живёт в секретах CI и у автора.

## Один раз

1. **Ключ подписи.** Сгенерировать и **не потерять**: без Play App Signing он единственный, и с
   потерей ни один установленный APK больше не обновится поверх — только удаление со всей перепиской.

   ```sh
   keytool -genkeypair -keystore iskra-release.jks -alias iskra -keyalg RSA -keysize 4096 -validity 10000
   ```

   Хранить у автора и зашифрованной копией в другом месте. **Никогда в репозитории** (`.gitignore`
   ловит `*.jks`, CI — gitleaks).
2. **Секреты** (Settings → Secrets and variables → Actions):
   - `ISKRA_KEYSTORE_BASE64` — `base64 -i iskra-release.jks`;
   - `ISKRA_KEYSTORE_PASSWORD`, `ISKRA_KEY_ALIAS`, `ISKRA_KEY_PASSWORD`.
3. **Переменная** `ISKRA_CERT_SHA256` — отпечаток сертификата (не секрет): workflow сверяет с ним
   подпись каждого APK и пишет его в описание релиза.

   ```sh
   keytool -list -v -keystore iskra-release.jks -alias iskra | grep SHA256
   ```

4. **Firebase для `gms`:** `app/src/gms/google-services.json` в репозитории и вход
   `wtf.iskra-android` в Sygnal (`docs/PUSH-GATEWAY.md`). Без файла workflow релиз не выпускает:
   `gms` без пушей — не релиз.
5. **Открыть репозиторий** (docs/PROMPT.md, 19.2). Workflow проверяет `visibility == PUBLIC` и
   отказывает: релиз в закрытом репозитории никто не скачает, а проверка обновлений получит 404.
   Репозиторий после этого не переименовывается и не переносится — старые версии ищут обновления
   только по адресу `iskra-app/iskra-android`.

## Каждый релиз

1. `versionName` и `versionCode` в `app/build.gradle.kts`. `versionName` — синхронно с iOS,
   `versionCode` — только растёт: встроенная проверка обновлений сравнивает именно его.
2. `release-notes/<versionName>.json` — «Что нового» на семи языках, русский пишется первым:

   ```json
   {"ru": "…", "en": "…", "de": "…", "es": "…", "fr": "…", "ja": "…", "zh-Hans": "…"}
   ```

   Проверить: `scripts/release_json.py --check-notes release-notes/1.4.0.json`.
3. Влить в `main`, поставить тег `v<versionName>` на коммит в `main`, отправить тег.

Workflow:
- прогоняет `check`;
- собирает `gms` и `foss` (`arm64-v8a`, `x86_64`), подписывает схемой v3;
- проверяет выравнивание `.so` на 16 KB и отпечаток;
- кладёт в релиз `iskra-<версия>-<flavor>-<abi>.apk`, `SHA256SUMS` и `release.json`.

Схема v3 даёт ротацию ключа. Схемы v2 при minSdk 29 apksigner не ставит: все поддерживаемые Android
проверяют v3.

`release.json` пишет `scripts/release_json.py` из готовых APK — никогда руками. Встроенная проверка
обновлений читает `releases/latest/download/release.json`. Пре-релизы и черновики в `latest` не
попадают, поэтому опубликованный релиз — это и есть канал.

## Проверить workflow без релиза

Локально, тестовым ключом во временном каталоге:

```sh
ISKRA_KEYSTORE_FILE=/tmp/test.jks ISKRA_KEYSTORE_PASSWORD=… ISKRA_KEY_ALIAS=… ISKRA_KEY_PASSWORD=… \
  ./gradlew assembleGmsRelease assembleFossRelease
```

Затем скопировать APK в `dist/` под именами `iskra-<версия>-<flavor>-<abi>.apk` и прогнать
`scripts/release_json.py --dist dist --notes … --apksigner … --aapt2 …`.
