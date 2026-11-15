# Новая постоянная подпись NoteLS1.10.37/code60

Пользователь14 ноября2026 явно разрешил новый постоянный ключ и переустановку. Старый ключ1.8.1 в доступной Git-истории/выпусках не найден. После первой установки все будущие APK должны иметь один и тот же новый сертификат. Room17/data2/security3 прежние.

**Текущее состояние15 ноября2026:** постоянный ключ УЖЕ создан и сохранён. Secret NOTELS_SIGNING_MATERIAL, public pin c94bdfcf4d5a4ce0cfa51809707bf94f9034b1a1a47cd89a2a13e9577845661c. Setup43655877564/job132634156985 success, материал сохранён00:00:03 UTC; pin commitc54a0c739883113bd4ca99d6dba7dab42d132352. Временные private receiver/PKCS12/password удалены на runner, одноразовая encrypted transport-ветка удалена, локальная owner OAuth копия удалена. Не генерировать другой ключ/не повторять login. Signed NoteLS APK run43656026968/sourcec54a0c7 завершился успешно; [установочный артефакт](https://github.com/grigorywork/NoteLS/actions/runs/43656026968/artifacts/14138093571) скачан и независимо проверен15 ноября00:17 UTC. Проверенный APK подготовлен к выдаче в текущем чате; текущая задача завершена, см. [NEXT-CHAT](NEXT-CHAT.md).

Облачный HTTP-канал подставляет служебную GitHub App identity: Secrets API403 даже после успешного owner login не является признаком отсутствия Secret. Настройка уже выполнена на trusted GitHub runner с одноразовой OAEP-передачей подтверждённого owner OAuth. Прокси/сетевая политика не менялись. Обычным сборкам доступ к административному API Secrets и новый owner login не нужны.

## Подтверждённые проверки

### Установочный APK из постоянного Secret: итог15 ноября2026

- Run43656026968: jobs identity132634608097/release132634639616 completed/success. Release build/lint SUCCESS12m58s;13 signing guard contracts OK11.991s. Полный662-suite относится к CI43640361858, здесь повторно не запускался.
- Artifact14138093571, sourcec54a0c739883113bd4ca99d6dba7dab42d132352,27501255 bytes APK. App tree без различий относительноe2ed0c8b4c3d2fe2dfa5ec1559574ccddfa18565 и полученного свежего maine6e5180d44d4c957193df4cb4e6c1e92f9ac0bb8.
- ZIP SHA256 `004a68e8bd0cfad443c2e9f3a82278a06c17ce1a331977eacf7a6666b58b6198` совпал с GitHub API digest. В ZIP ровно APK/README.txt/SHA256SUMS. README sourceSHA/public certificate сверены.
- APK SHA256 `b3b29d5b2eacecc216206b89ddd0ae35b17533a16e957a1681d3acbe23543709` совпал с SHA256SUMS и CI signing output.
- Независимый Android Build Tools35.0.0 apksigner verify: Verifies, v2=true/v3=true,1 signer, DN CN=NoteLS, RSA3072. Actual certificate SHA256 `c94bdfcf4d5a4ce0cfa51809707bf94f9034b1a1a47cd89a2a13e9577845661c` строго равен закреплённому pin. Ключ и Secrets не запрашивались/не менялись.
- aapt2 badging/xmltree: com.notessync.app, versionName1.10.37/versionCode60, minSdk26/target35; application-debuggable отсутствует, debuggable в manifest не включён. Установка на физический телефон не выполнялась.
- Исходники приложения, версия и Room17 не менялись. Обновление документов не требует нового CI/APK. Новых этапов нет, согласованная очередь0.


CI43640361858/job132586396896 наe2ed0c8b4c3d2fe2dfa5ec1559574ccddfa18565 success:662 теста,0 failures/ignored; lint0error/16warnings/2info, build19m17s. Отчёты14132825380 ZIPsha c9e0643c806db4177f63f23d57e9caebb752ae17394c541a702b0ad3867e3efd скачаны, digest/HTML counters/XML lint проверены. Его Android Debug/NOT-UPDATE APK не является новым постоянным выпуском.

В этой версии исправлена прежняя несогласованность busy у BackupCoordinator. Ранний сброс до feedback и второй сброс в finally позволяли асинхронному повторному запросу увидеть ложное завершение. Теперь одна точка освобождения в finally после cleanup/feedback. До исправления детерминированный Native Room/JCA probe получил expectedbusytrue/actualfalse; после него5 проверок coordinator прошли9.914s, оба lint0error/15warnings/2info, Debug и Release собраны10m23s. Прежний CI43637120071/ad7 с1 отказом из661 не считается успехом.

Текущий unsigned release27417744 bytes/SHA256aa79275ad0f11fc4cde7355a9d9128b7310984fd35808b59adaaf3d598aef9e8. Его не выдавать как устанавливаемый APK. Kotlin main21702 физических строки/212 файлов (пустые/комментарии включены; tests/XML/Gradle/dependencies исключены). Новые изменения setup/signing scripts и workflows не меняют app source послеe2ed; тесты приложения повторно ради них не запускаются. Для подписания выполняются настоящие проверки apksigner на одноразовых тестовых ключах, для канала настройки — настоящие RSA-OAEP контракты; эти ключи не становятся identity приложения.

## Одноразовая настройка владельцем

Workflow `Configure permanent signing` запускается только явным workflow_dispatch на main сinitialize=true. Обычные проверки и signed release никогда не создают новый ключ при отсутствии Secrets. Скрипт bootstrap разрешает CI-инициализацию только в этом конкретном workflow/ref/repository, после проверки GitHub owner identity и права Secrets; во всех обычных CI вызовах генерация запрещена.

Runner создаёт временный RSA4096 receiver и публикует только public.pem/request.json. В уже подтверждённом owner OAuth токене нет нового пароля или ключа приложения. Он передаётся receiver только как RSA-OAEP-SHA256 ciphertext, связанный с repository/run/attempt/sourceSHA/nonce. Sender проверяет ZIP digest публичного артефакта и его identity до шифрования. Временная orphan-ветка notels-setup/<run>-<attempt> содержит только ciphertext payload.json, никогда не сливается в main и удаляется после получения. Никакие открытые credential values не входят в Git, вывод инструментов или артефакты. Приватный receiver key остаётся на trusted runner и удаляется после работы.

На GitHub runner owner OAuth работает непосредственно с GitHub Secrets API. После проверки владельца создаётся PKCS12/RSA3072/SHA256withRSA, aliasnotels-2026, срок10000 дней и случайный пароль. Ключ и пароль сохраняются атомарно в одной записи repository Actions Secret `NOTELS_SIGNING_MATERIAL` (JSON keystore_base64/password). Это предотвращает половинчатую пару при разрыве между двумя отдельными Secrets. Старые тестовые/предложенные имена NOTELS_KEYSTORE_BASE64/NOTELS_SIGNING_PASSWORD не являются текущей конфигурацией и не содержали созданного нами permanent key.

В main коммитится только публичный SHA256 `.github/signing-certificate.sha256`. Если material уже есть, повторная настройка извлекает тот же публичный сертификат и не генерирует новый ключ. При отличающемся pin либо pin без material операция останавливается. Если material сохранён, но коммит pin прервался, следующая настройка восстанавливает тот же pin из существующего material. Private keystore/password и временная owner credential не прикладываются к исходникам, журналам или выпускам; временные приватные файлы runner удаляются.

## Будущие устанавливаемые APK

`Signed NoteLS APK` запускается после успешного main push `Android checks` или вручную на main. Pull requests/forks не получают Secrets. Без pin выпуск пропускается; с pin отсутствие/ошибка material блокирует выпуск. Существующий локальный keystore.properties поддерживается; без него Gradle создаёт unsigned release, без подменного ключа.

`scripts/sign_apk.py` проверяет package/version/non-debuggable, использует существующий material, затем проверяет настоящий сертификат apksigner против pin до помещения APK в upload directory. Выход содержит только APK/SHA256SUMS/README с sourceSHA/публичным сертификатом. Неверный пароль, другой private key, malformed material или другой сертификат не должны дать артефакт. После первой настройки необходимо проверить реальный CI APK из сохранённого Secret, а не только локальную подпись кандидатом.

## Переустановка

Перед удалением старого приложения экспортировать копию в Загрузки/другую папку вне app data. Внутренние автокопии удаляются с приложением. Для зашифрованной копии нужен известный владельцу переносимый пароль: прежняя биометрическая привязка может исчезнуть при переустановке. Сначала получить проверенный новый APK. Его первая установка требует удаления прежней версии с другой подписью; applicationId остаётся com.notessync.app. Будущие обновления используют этот же новый сертификат и не требуют очередной смены ключа.

Все проверки облачные; установки и измерений CPU/PSS/батареи на физическом телефоне нет. Пароль копии/постоянный private key в чате не запрашиваются.
