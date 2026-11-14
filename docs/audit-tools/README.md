# Облачный длительный fuzz прогон

CloudAuditFuzzer проверяет фиксированный собранный JVM artifact, не исходники, меняющиеся во время другой сборки. Нужны JDK17 и runtime classpath testDebugUnitTest. Все элементы classpath из app/build копируются в отдельный snapshot, зависимости Gradle неизменяемы. Затем javac/java запускают этот файл с аргументами `activeSeconds seed privateScratchDirectory`.

В прогоне используются только случайные публичные fixtures. Каждая итерация проверяет:

- Roundtrip размера0/1/64KiB±1/нескольких records, случайную битовую порчу, усечение и лишний хвост в явно зашифрованном формате; периодическую ротацию recipient.
- ZIP extraction: размер, пустые/непустые directories, traversal, глубину/длину имени. Бюджет fixtures128/8192 bytes, payload не больше2048 bytes; zip bomb/данные пользователя не используются.
- UTF16 clipping/emoji/strict full-document decoder. Исходный format JSON после preview прежний.
- Настоящий encrypted→ZIP validation pipeline: valid fixture, испорченный magic и отсутствующий final tag. Automatic legacy detection намеренно не заменяется проверкой произвольного raw-byte passthrough action.

Каждые15s печатаются UTC/seed/число проверок/active time. Разрыв одной итерации больше30s консервативно исключается и не приближает завершение. STOP-файл в scratch отменяет прогон. При отказе сохраняются только зашифрованные синтетические original/mutated fixtures для воспроизведения, процесс завершается ошибкой.

Ограничения: этот harness не запускает Android UI/Room/Keystore/биометрический сенсор/сеть/телефон. Он не доказывает отсутствие всех дефектов и не измеряет RAM/PSS/CPU/батарею телефона. JVM ограничена192MiB и одним активным процессором, что является настройкой облачного процесса, а не профилем слабого телефона. Во время отдельной Android Gradle сборки по-прежнему запрещено менять app/test source.

## Native Room long audit

CloudRoomLongAudit — отдельный внешний test source, не обычная JUnit/CI suite. Перед длительным запуском прошли30s smoke с307 случайными операциями, все11 case groups исполнены. Каждая итерация создаёт ограниченные синтетические данные, завершает принятые editor jobs, закрывает ViewModelStore, снимает test alarms, очищает Room и grants. Проверяются свежая защита/пароль/конверсия, peer targets, отмена scope/новые UUID/target/generation, encrypted create/restore, stale home card, tags/versions/старый ACK/day uncheck/blank clock tasks. Никаких внешних AI/peer вызовов, биометрического сенсора или телефона.

Для запуска временно подключить этот source к unit test sourceSet через внешний Gradle init, выполнить compile/smoke с Native SQLite/API28, экспортировать runtime classpath/system properties. Каждый app/build classpath element копируется в неизменяемый snapshot. Пути Android test_config.properties к resource APK/manifest/assets также копируются и заменяются абсолютными snapshot paths. Gradle dependencies остаются в кэше. Standalone JUnitCore запускает только этот класс с `-Dnotels.audit.active.seconds=N -Dnotels.audit.seed=202611143`; JVM768MiB/SerialGC/1processor — ограничения облачного процесса, не эмуляция слабого телефона. Не включать внешний класс в итоговый обычный JUnit count.

Реальное активное время берётся из host RuntimeMXBean.uptime через reflection, UTC heartbeat15s; Android API не содержит compile-time java.lang.management, поэтому прямой import убран после ошибки компиляции harness. Интервал итерации>30s исключается. STOP /workspace/tools/notels-native-audit/STOP останавливает прогон; failure печатает case/seed/iteration. Исходные первые smoke false assumptions (raw legacy passthrough / разрезанный surrogate в искусственной table fixture) исправлены и не считаются находками приложения. Длительный прогон ещё не объявлен завершённым.

### Первый long run остановился, не полный успех

Первый Native long run source5c1dd28 завершился ошибкой12:35:57 UTC после1383 итераций: timeout15s в group7 и queued Main looper runnables. Он не засчитывается как4h. Для диагностики в harness добавлена обработка Android main queue между пользовательскими взаимодействиями и после teardown итерации, явные этапы open/tags и thread dump при отказе. Реальный Android main loop работает между такими событиями; standalone длинный цикл раньше его не прокачивал. **Timeout15s/исходные проверки/ограничения памяти не увеличены и не ослаблены.** Новый30s smoke прошёл394 операции во всех11 группах. Причина долгого отказа ещё проверяется сравнительным replay старой неизменяемой production сборки с новым test harness; такой control отличается только harness, не новым app fix. Будущий новый long run требует отдельного фактического итога.

### Второй long assertion был неверным предположением fixture

V2 наiteration10523 получил равный old/new body (334 одинаковых повтора). No-op save правильно не меняет logical version. RandomFixtureReplay.java независимо воспроизводит java.util.Random и все11 counters до этого шага. В generator branch0 versions теперь новый body имеет гарантированно отличающийся префикс changed-, assertion увеличения версии сохранён. Тесты no-op semantics приложения не ослаблены. Новая30s smoke358 операций/все11 groups прошла; длительный v3 всё ещё требует фактического запуска/итога.
