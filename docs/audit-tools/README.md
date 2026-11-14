# Облачный длительный fuzz прогон

CloudAuditFuzzer проверяет фиксированный собранный JVM artifact, не исходники, меняющиеся во время другой сборки. Нужны JDK17 и runtime classpath testDebugUnitTest. Все элементы classpath из app/build копируются в отдельный snapshot, зависимости Gradle неизменяемы. Затем javac/java запускают этот файл с аргументами `activeSeconds seed privateScratchDirectory`.

В прогоне используются только случайные публичные fixtures. Каждая итерация проверяет:

- Roundtrip размера0/1/64KiB±1/нескольких records, случайную битовую порчу, усечение и лишний хвост в явно зашифрованном формате; периодическую ротацию recipient.
- ZIP extraction: размер, пустые/непустые directories, traversal, глубину/длину имени. Бюджет fixtures128/8192 bytes, payload не больше2048 bytes; zip bomb/данные пользователя не используются.
- UTF16 clipping/emoji/strict full-document decoder. Исходный format JSON после preview прежний.
- Настоящий encrypted→ZIP validation pipeline: valid fixture, испорченный magic и отсутствующий final tag. Automatic legacy detection намеренно не заменяется проверкой произвольного raw-byte passthrough action.

Каждые15s печатаются UTC/seed/число проверок/active time. Разрыв одной итерации больше30s консервативно исключается и не приближает завершение. STOP-файл в scratch отменяет прогон. При отказе сохраняются только зашифрованные синтетические original/mutated fixtures для воспроизведения, процесс завершается ошибкой.

Ограничения: этот harness не запускает Android UI/Room/Keystore/биометрический сенсор/сеть/телефон. Он не доказывает отсутствие всех дефектов и не измеряет RAM/PSS/CPU/батарею телефона. JVM ограничена192MiB и одним активным процессором, что является настройкой облачного процесса, а не профилем слабого телефона. Во время отдельной Android Gradle сборки по-прежнему запрещено менять app/test source.
