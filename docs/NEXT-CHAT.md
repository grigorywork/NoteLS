# NoteLS: текущая задача завершена

15 ноября2026 получен свежий main из https://github.com/grigorywork/NoteLS: e6e5180d44d4c957193df4cb4e6c1e92f9ac0bb8 до фиксации этой проверки. Прочитаны AGENTS.md, CODEX-HANDOFF.md, ПЕРЕДАЧА-ПРОЕКТА.md и конец agent.md. Общаться кратко по-русски.

## Проверенный установочный APK

[GitHub Actions artifact14138093571](https://github.com/grigorywork/NoteLS/actions/runs/43656026968/artifacts/14138093571) — NoteLS-1.10.37-code60-permanent-signature.apk. Run43656026968/sourcec54a0c739883113bd4ca99d6dba7dab42d132352: identity132634608097 и release132634639616 completed/success. Release build/lint12m58s;13 signing contracts11.991s OK.

Независимая облачная проверка15 ноября00:17 UTC:

- ZIP SHA256 `004a68e8bd0cfad443c2e9f3a82278a06c17ce1a331977eacf7a6666b58b6198` совпал с GitHub API digest; внутри ровно APK/README.txt/SHA256SUMS.
- APK27501255 bytes, SHA256 `b3b29d5b2eacecc216206b89ddd0ae35b17533a16e957a1681d3acbe23543709` совпал с SHA256SUMS и CI. README sourceSHA и сертификат совпали.
- Build Tools35 apksigner verify: v2/v3 действительны,1 signer CN=NoteLS/RSA3072. Actual certificate строго равен pin ниже.
- aapt2: `com.notessync.app`, versionName1.10.37/versionCode60, non-debuggable, minSdk26/target35. Room17/data2/security3 прежние.
- App tree совпадает сe2ed0c8b4c3d2fe2dfa5ec1559574ccddfa18565 и свежим main. Полный CI43640361858/job132586396896 success:662 теста без отказов/ignored, lint0error/16warnings/2info, build19m17s. Повторно662 ради подписи/документации не запускались.

Подробности: [подпись и итоговые доказательства](signing-transition-1.10.37.md). Проверенный APK подготовлен к выдаче ссылкой в текущем чате. Установка на физический телефон не выполнялась.

## Постоянный ключ уже сохранён — не заменять

Repository Actions Secret `NOTELS_SIGNING_MATERIAL` хранит атомарный JSON keystore_base64/password. Единственный допустимый публичный SHA256 в `.github/signing-certificate.sha256`:

`c94bdfcf4d5a4ce0cfa51809707bf94f9034b1a1a47cd89a2a13e9577845661c`

Ключ создан один раз15 ноября2026 по разрешению пользователя: PKCS12/RSA3072/SHA256withRSA, aliasnotels-2026, срок10000 дней. Setup43655877564/job132634156985 success, material_saved=true00:00:03 UTC; pin commitc54a0c7. Временные private файлы/owner OAuth копия/transport-ветка удалены. Не запускать первую инициализацию заново, не генерировать другую identity и не просить новый вход. Облачный Secrets API403 связан со служебным доступом; обычный Actions использует сохранённый Secret напрямую. При отсутствии material/несовпадении pin останавливать выпуск и разбирать причину, а не создавать замену.

Перед первой установкой экспортировать резервную копию в папку вне app data и сохранить известный владельцу переносимый пароль. Затем удалить прежнее приложение с другой подписью и установить новый APK. Прежняя биометрия не заменяет пароль зашифрованной копии после переустановки. Будущие обновления подписываются этим же ключом и устанавливаются поверх новой версии.

## Граница работы

Текущая задача проверки и выдачи APK завершена. Оставшихся согласованных feature-этапов0. Этап67а и4h аудита + отдельные4h исправлений завершены ранее, см. audit-window-2026-11-13.md. Без нового задания работу не возобновлять,68/iPhone/новые этапы не начинать.

Старый CI43637120071/ad7 с1 отказом из661 остаётся проваленным; исправление BackupCoordinator подтверждено отдельным probe и полным662-suite выше. Исторические debug/NOT-UPDATE APK не заменяют текущий permanent APK. Все проверки облачные; реального телефона/биосенсора/NSD-роутера/CPU/PSS/батареи не измеряли, платных AI вызовов нет.
