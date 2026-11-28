## Этап 92: первый целевой прогон — 55 из 56, тестовая фикстура исправлена

Кандидат 1.10.66/code89/Room20. CI 46144810199 подтвердил компиляцию приложения и тестов; 56 тестов, 1 сбой, 0 пропусков. Девять из десяти новых тестов прошли, включая оба NativeCompose: RU 320dp и EN шрифт 1.6 с клавиатурой 240dp. Оба изображения осмотрены и сохранены в docs/design/editor-dates-1.10.66. Lint/assemble не достигнуты, полный 986/Signed/actual release ещё не проверялись. Версия 66 не установочная, этап 92 не закрыт.

Единственный сбой — фикстура теста удаления блокнота использовала upsert(REPLACE): SQLite удалял старую строку и отвязывал заметку через FK SET NULL. Теперь используются настоящий softDelete и update, дополнительно проверяется сохранение связи и восстановление отображения перед блокировкой. Код приложения не изменялся; прежние assertions и 15 секунд сохранены. Повторный CI ещё не запущен. Отрицательное доказательство: docs/editor-dates-1.10.66-first-targeted.json.

Работа продолжается в чате 6b0a9b29-9cdc-83ed-9024-8bed96fec058; исходный чат idle. Следующий шаг — повторить целевые проверки, затем полный CI, подпись и независимая проверка APK вместе со свежими Android gates. Новый native probe дат ещё требуется. После 92 очередь 93–97, оба postqueue 3h окна ещё 0ms. GHA only, без локальных SDK/Gradle, прежняя подпись; app/test замораживать во время CI. Ниже история.

## Этап92 подхвачен в новом чате; кандидат ещё не проверен

Исполнитель: чат 6b0a9b29-9cdc-83ed-9024-8bed96fec058, /workspace/NoteLS, ветка codex/editor-dates-1.10.66 от07e0e948508138e5d8c505ba7616a1659a229858. Исходный чат6af8f9a9-0620-8191-98f9-a99f3829107e и оба его агента idle/interrupted; этап91 закрыт. Проверка передачи и старый fallback отключены при подхвате, не дублировать исполнителя.

Кандидат1.10.66/code89/Room20/data2/security3: даты создания слева, изменения справа внизу редактора, обе позиции панели/preview/IME; абсолютный локализованный формат без тиков/фоновой работы. Новый узкий Room projection только дат/access flags, свежая проверка identity/удаления/блокнота/защиты, даты только из committedDB. Новая запись до commit показывает «—», SQLrollback/no-op не публикуют выдуманную дату. При save сохраняется свежий createdAt из DB. Schema/signing не менялись.

Добавлены10 проверок:5 NativeRoom/VM(new+existing rollback/retry/no-op/livepeer/freshcreation/lock/identity/notebookdelete),3 formatter(locale/timezone/midnight/invalid/extreme),2 NativeComposeRU320/ENfont1.6+fakeIME240+TOP. Пока НЕ запускались, код НЕcompile/testpass. Следующий целевой CI с сохранением полного normalworkflow в feature; diagnosticworkflow вmain не сливать. Затем аудит/полный986ОЖИДАЕТСЯ/Signed/independentAPK/freshnativeGC до закрытия92. Последний verifiedinstaller65/code88, stage91closed; 66 ещё НЕinstaller. Очередь93–97 и обаpostqueue3hокна ещё не начаты/0ms.

GHAonly/no localSDKGradlebuilds; app/testзамораживатьприCI; RESTforce=false+lease/no resetcleanforcepush. PermanentNOTELS_SIGNING_MATERIAL/pin сохранять; 68/iPhone/newAI/paidcalls не начинать. Нижние записи — история.

