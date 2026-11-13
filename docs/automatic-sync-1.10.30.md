# Автообмен и доверенные устройства — этап65,1.10.30/code53

Room17, JSONdata2/security envelope3. Постоянная подпись не менялась. Новое успешное ручное сопряжение двух устройств65 сохраняет отдельный HKDF resumption secret; старые приглашения/история обмена доверия не создают. Каждый автоматический обмен заново выполняет взаимный PSK+ephemeral ECDH handshake64 и проверяет ожидаемый deviceUUID до обращения к данным. Prelude/NSD содержат только публичные идентификаторы; они не аутентификация.

## Хранение и отзыв

Список максимум32 устройств/64KiB в отдельном private sync_trust, целиком AES256-GCM/randomIV/AAD, master key AndroidKeyStore notels.sync.trust.v1. Нет plaintext/software fallback, восстановления ключа или скрытого сброса при ошибке. Пустой список ключ не создаёт; чтение только при foreground. UI содержит summary без secret. Повторное сопряжение меняет revision; удаление/ошибка/поворот revision закрывают активный owner, поздний completion не возвращает удалённое доверие. Сброс очищает только доверие/собственный syncUUID и выключает автообмен; заметки сохраняет. sync_trust/sync_prefs исключены из OS backup/device-transfer, trust отсутствует в ручном архиве. Подпись APK к этим device keys отношения не имеет.

## Жизненный цикл и ресурсы

Только STARTED Activity + включённый переключатель + доступное доверие + privateIPv4 Wi-Fi без cellular/VPN. Системные callback LinkProperties/Capabilities, NSD _notels._tcp. и порт47384; selected Network socket factory, проверка serviceUUID/version/port/privateaddress. Нет фонового Service/Worker, периодического опроса или wake lock. Выход, потеря/смена сети, ручное сопряжение, отзыв и пауза закрывают sockets/NSD/callback. Старые callbacks игнорируются, поздняя регистрация немедленно снимается. На API26–33 системный legacy resolve не отменяется самим API; поздний ответ не используется. Очередь resolve8/успешных адресов64, resolve/handshake5s, I/O120s, сеанс10min, максимум3 попытки с2/5s, throttling плохих входящих.

Одновременные подключения разрешаются стабильным UUID-порядком, один auto mutex и process transfer gate; отмена владельца до закрытия сокета, completion освобождает ресурсы и при отмене до старта. Изменения Room conflated/debounce1500ms и один rerun-bit на peer. Нет повторной UPDATE/invalidation уже подтверждённой dirty=0 записи.

## Согласованность данных

ACK version stamps только после взаимного DONE. Условные dirty/version UPDATE заметок и блокнотов не очищают более новую локальную правку. Полный snapshot Long.MIN_VALUE намеренно исключает потерю данных из-за общих курсоров разных peers/часов; пока это wholebatch metadata, оптимизация в67. Вложения передаются только при несовпадении наличия/размера/SHA, а не просто по существованию UUID. Конфликтная копия сохраняет формат/таблицы/теги/блокнот/защиту, получает новые note/attachmentUUID и независимые физические файлы; копия dirty и доступна для следующего обмена. Rollback/замена чистят только созданные/неиспользуемые файлы.

Редактор и быстрая задача держат counted pauselease автообмена. Начальная загрузка дожидается уже принятой транзакции обмена; закрытие снимает lease после завершения текущего save mutex. Автообмен возобновляется после закрытия редакторов, это явно объяснено в UI. Успешное восстановление архива оставляет автообмен выключенным для просмотра восстановленных данных и сообщает об этом. Ручной обмен также использует process gate.

## Проверки и ограничения

18 новых проверок: JCA ciphertext/randomIV/tamper/missingkey/revision/reset/cap; ACK после DONE/ошибка/ожидаемый UUID/разделение ключей/старый Hello; реальные JVM TCP sockets двух fake LAN устройств, одновременный dial, последующие изменения в обе стороны,70KiB audio bytes, wrongkey без DB, остановка без повторного подключения; реальная Room конфликтная копия/независимые файлы/все поля/изменённый хеш/late notebook ACK; Native Compose RU/EN toggle/forget/reset с сохранностью заметки и lifecycle subscriptions; editor transaction/load/save pause; SDK28 Wi-Fi caps private/VPN/cellular/public/IPv6. Двум моделируемым процессам назначены независимые gates: общий JVM singleton не моделирует два телефона. Assertions не ослаблялись.

Локально523 теста,0 failures/errors/skipped; lint0 ошибок/15 предупреждений/2 информации, assembleDebug успешно за5m 19s. CI/APK проверяются после публикации.

Это облачные JVM/JCA/TCP loopback/SQLite/Room/Robolectric/Compose проверки. Реальные телефоны, Wi-Fi NSD/router, Android hardware Keystore, доставка после сна/между производителями и показатели CPU/PSS/батареи не проверялись. Метаданные wholebatch и файл до32MiB in-memory остаются ограничениями;67 посвящён общей регрессии и оптимизации. Независимого внешнего криптоаудита нет. Облачный APK Android Debug/NOT-UPDATE.

Код приложения: 19344 физических строк Kotlin в158 файлах app/src/main (включая пустые/комментарии; без тестов/XML/Gradle/зависимостей), этап+941/−41, net+900.
