# Продолжение NoteLS из GitHub

Актуальная передача15 ноября2026. Получить свежий main приватного https://github.com/grigorywork/NoteLS, читать сначала AGENTS.md и этот файл, затем docs/CODEX-HANDOFF.md и конец agent.md. Исторический checkpoint65eba51d/этап51 давно пройден; не начинать заново. Старые архивы исходников не использовать. Отвечать кратко по-русски, продолжать разрешённую работу без дополнительных подтверждений.

## Что осталось сейчас

Завершить выдачу **настоящего устанавливаемого APK1.10.37/code60 с новым постоянным ключом**. Пользователь разрешил новую identity и первую переустановку после удаления прежней версии. Он затем попросил перенос в другой чат с GitHub. Перенести сам интерфейс чата автоматически нельзя: этот файл и Git main — источник продолжения.

Оставшихся feature-этапов0. Не добавлять68/iPhone/новые функции. Этап67а завершён ранее; запрошенные4h аудита и отдельные4h исправлений тоже завершены с исключёнными паузами, см. docs/audit-window-2026-11-13.md. Не запускать эти окна заново из-за перехода в чат.

## Ключ уже есть — НЕ создавать другой

- Repository Actions Secret: `NOTELS_SIGNING_MATERIAL`, атомарный JSON `keystore_base64`/`password`.
- Публичный сертификат: `.github/signing-certificate.sha256`.
- Единственный допустимый SHA256: **c94bdfcf4d5a4ce0cfa51809707bf94f9034b1a1a47cd89a2a13e9577845661c**.
- PKCS12/RSA3072/SHA256withRSA, alias `notels-2026`, срок10000 дней; создан один раз, никаких генераций на каждой сборке.
- Успешный setup: https://github.com/grigorywork/NoteLS/actions/runs/43655877564, job132634156985. Сервер подтвердил material_saved=true00:00:03 UTC15 ноября; pin commit **c54a0c739883113bd4ca99d6dba7dab42d132352**.
- Новому чату не нужны owner OAuth/новый device login/запрос private key/password. Прямой облачный Secrets API получает403 из-за служебного GitHub App доступа; это не потеря ключа. Обычный GitHub Actions workflow получает существующий Secret автоматически.
- Owner OAuth передавался в trusted runner только RSA-OAEP ciphertext с binding repository/run/attempt/source/nonce. Orphan transport-ветка `notels-setup/43655877564-1` удалена. Private temp на runner и локальная копия одноразовой owner OAuth удалены. Никакие private key/password не коммитились и не входили в артефакты.

`Configure permanent signing` больше не нужен для первой генерации: она завершена. При отсутствующем material или несовпадении сертификата останавливать выпуск и разбирать причину; не создавать замену. Восстановление public pin из существующего material допускает только ту же identity.

## Текущая сборка: проверить живой статус первой

https://github.com/grigorywork/NoteLS/actions/runs/43656026968 — **Signed NoteLS APK**, source **c54a0c739883113bd4ca99d6dba7dab42d132352**, jobs identity132634608097/release132634639616.

При сохранении передачи15 ноября00:09 UTC identity и проверка существования Secrets/pin прошли, release выполнял Build and lint release. Подписанный артефакт ещё не выдан пользователю; не считать этот промежуточный статус финальным. Он может уже завершиться к новому чату — сначала прочитать actual run/jobs/logs/artifacts, не перезапускать вслепую.

Если успех, скачать артефакт permanent-signature. Проверить ZIP SHA256 против GitHub API digest; внутри только APK, SHA256SUMS, README. Проверить APK digest по SHA256SUMS, README sourceSHA, `com.notessync.app`, versionName1.10.37/versionCode60, отсутствие debuggable и actual apksigner certificate SHA256 **строго равный pin выше**. Это не Android Debug certificate. Старый helper verify-ci-apk.py проверял Debug/NOT-UPDATE и для этого нового APK без изменения не подходит.

Дальше выдать пользователю ссылку на проверенный permanent APK. Первую установку делать после экспорта резервной копии в папку вне app data, затем удаления прежнего приложения с другой подписью. Для encrypted backup нужен известный владельцу пароль; прежняя биометрия не заменяет его после переустановки. Будущие обновления должны ставиться поверх новой версии с ЭТИМ ЖЕ сертификатом.

Если run провалился — исследовать конкретный step/log, исправить причину без смены ключа; при необходимости заново запустить `signed-release.yml` на main. Подключение GitHub уже умеет workflow_dispatch; дополнительный owner login для этого не нужен. Не выдавать cloud-debug-NOT-UPDATE APK вместо permanent APK.

После проверки актуализировать status в AGENTS/README/handoff/этом файле/agent.md и сохранить в main. Для docs-only нового APK/test run не требуется, commit `[skip ci]`. При успешной выдаче текущая задача завершена, feature-очередь0.

## Уже проверено

App source: **e2ed0c8b4c3d2fe2dfa5ec1559574ccddfa18565**, в c54a0c7 app tree идентичен. Full CI https://github.com/grigorywork/NoteLS/actions/runs/43640361858, job132586396896: **662 теста/0 failures/0 ignored**, lint0error/16warnings/2info, build19m17s. Reports14132825380 скачаны: ZIP SHA256 **c9e0643c806db4177f63f23d57e9caebb752ae17394c541a702b0ad3867e3efd**, HTML counters/XML lint проверены.

В подготовке исправлен BackupCoordinator: busy снимался до feedback и снова в finally; при асинхронном retry предыдущий finally мог обнулить занятость нового запроса. Теперь освобождение только в finally после cleanup/feedback. Новый queued-dispatcher Native Room/JCA probe на прежнем коде получил expectedtrue/actualfalse; после исправления все5 coordinator cases прошли, старые15s cap/assertions сохранены. Старый CI43637120071/ad7 с1 отказом из661 остаётся проваленным, не переименовывать его в успех.

Локально5 coordinator cases9.914s, lintDebug и lintRelease каждый0error/15warnings/2info, assembleDebug/Release success10m23s. Unsigned release aa79275ad0f11fc4cde7355a9d9128b7310984fd35808b59adaaf3d598aef9e8/27417744bytes — **не выдавать его как устанавливаемый**.

Скрипты подписания/атомарного material/CI guard:13 реальных контрактов8.200s с одноразовыми тестовыми ключами. Защищённая передача owner auth:12 реальных RSA-OAEP контрактов1.541s. Эти временные ключи не являются ключом NoteLS. Setup source377441f04caff57427e312b1104d14a20efe9bec; изменения signing/helper/workflow не меняли app tree после e2ed, повторять662 только ради документации/подписи не нужно.

Room17/data2/security3. Main Kotlin21702 физических строки/212 файлов, включая пустые/комментарии, исключая tests/XML/Gradle/dependencies; largest VM686. Все проверки облачные. Физического телефона/биосенсора/NSD-роутера/CPU/PSS/батареи не измеряли, платных AI вызовов нет. Сохранять прежние функции/анимации, не выдавать моделирование за замеры телефона.

## Среда, если рабочая папка сохранилась

`/workspace/NoteLS`, JDK17 `/workspace/tools/jdk17`, SDK35 `/workspace/tools/android-sdk`, Gradle8.9. В другой среде брать инструменты из её конфигурации. При необходимости один локальный Gradle одновременно; не редактировать main/test во время проверки. Профиль/lint/source journal находятся в GitHub; временные tool logs не заменяют Git/Actions proof.

Запущенный CLI watch в старой среде81666 лишь читает run43656026968; его отсутствие в новой среде не является сбоем сборки. Сама сборка выполняется в GitHub независимо от чата. Ни один локальный Gradle сейчас не выполняется. Одноразовой owner credential в старой папке больше нет, она не нужна для продолжения.
