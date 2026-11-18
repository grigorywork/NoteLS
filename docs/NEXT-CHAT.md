## Сейчас: зависания настроек,3ч аудита →3ч оптимизации

18ноября2026 работа явно возобновлена пользователем после паузы. Актуальный план3ч+3ч до82–84; прежние2ч+2ч заменены. Окно аудита начато04:12:56.539632UTC, ночной простой исключён. Аудит/оптимизация НЕ завершены, приложение по-прежнему verified51/code74. Прочитать [актуальный журнал](freeze-audit-2026-11-17.md) и [время/находки](freeze-window-state.json). Native long target2h44155003357 упал поOOM реестра закрытых диалогов Robolectric; это не объявлять app/phone leak или успешными2часами. Новый диагностический smoke в работе, production-код не изменён. Автозадача disabled для отсутствия дублей.

## Новое задание: зависания, аудит2ч → оптимизация2ч

2026-11-17: пользователь сообщил о зависаниях интерфейса и заменил прежнее окно3ч+3ч на2ч аудита, затем2ч оптимизации СЕЙЧАС, до82–84. Прежняя automation18:59UTC отключена. Прочитать [текущий журнал](freeze-audit-2026-11-17.md) и [состояние времени](freeze-window-state.json). Окно В РАБОТЕ, не считать завершённым. Последний installable51/code74.

## Пауза после1.10.51 и подтверждённое автопродолжение

Пользователю выдан проверенный APK1.10.51/code74. Автопродолжение назначено на2026-11-17T18:59:24Z (через2ч после выдачи); Automation_9bad489b952c8191be90c89cd157657d. Прочитать [handoff](CONTINUE-AFTER-1.10.51.md) и [точные WIP-пути](continuation-1.10.51-wip.json).82/83 сохранены в отдельных непроверенных ветках;84/3ч+3ч ещё не начаты. Последние плашки настроек и постоянную галочку задачи сохранить.

## 81 закрыт — проверенный NoteLS1.10.51/code74

Этап81 закрыт: NoteLS1.10.51/code74, Room18/data2/security3. Source `d9ab945ff33be6fb4d1bb2217f2a16c025879d9e`. Полный CI[44123579356](https://github.com/grigorywork/NoteLS/actions/runs/44123579356) =**800 тестов/0 отказов/0 пропусков**, lint0errors/16warnings/2info. Signed[44129055942](https://github.com/grigorywork/NoteLS/actions/runs/44129055942) SUCCESS. Независимо сверены ZIP/API digest/состав/SHA256SUMS/APK/source/package/version/nondebug/настоящий apksigner v2/v3/один signer/постоянный сертификат/DEX/Room18. Физическая установка не выполнялась.

- По явному уточнению пользователя возвращён более ранний интерфейс с плашками, а не компактные выпадающие строки. Источник AppearanceSettings, EditorSettings и VoiceSettings — c66c91749fa34e24023b181c63626fda0c16ed3f, до этапа74.
- Плашки языка, темы, размера, стиля интерфейса, акцента, всех15 шрифтов, вида заметок, выполненных пунктов и четырёх голосовых предпочтений снова доступны непосредственно в группах настроек.
- Флажки приоритета возвращены во «Внешний вид», как в выбранном старом варианте. Параметры редактора снова представлены отдельными карточками; все переключатели и пояснения сохранены.
- Позднейшие функции сохранены: свой цитатник, зашифрованное резервное копирование, преобразователь, настройка стекла с предпросмотром/Save/Cancel/Back. Кнопка «Настроить стекло» под плашками доступна только при выборе стекла.
- По последнему скриншоту пользователя: кружок завершения, цвет задачи треугольником и квадратный переключатель задачи расположены в строке редактора. Переключатель виден также у обычной заметки: зелёный при isTask=true, обычный при false. Дубли цвета задачи и преобразования из меню ⋮ удалены; цвет обычной заметки сохранён в её меню.
- Цвет фона и мягкость градиента Android8–11 меняются за220мс. Анимация системного blur не добавлена; смена светлой/тёмной основы происходит сразу для читаемости. Оттенок читается в рисовании, без пересборки панели на каждом кадре.
- Старые функциональные UI-проверки переведены с popup-путей на плашки: язык без смены Activity, все шрифты/выбранное состояние/лицензии, все голосовые предпочтения, все переключатели редактора. Добавлен NativeCanvas-сценарий стекла на320dp с крупным английским шрифтом.
- Кнопка настройки стекла имеет минимальную высоту48dp, включая крупный шрифт на узком экране.

По указанию пользователя после выдачи этого APK остановиться и возобновить работу примерно через2часа. Следующий82 — четыре бесплатные нейросети: рабочий выбор Qwen/Groq, Gemini, Gemma/OpenRouter и Nemotron/OpenRouter. Mistral Free mode больше не включает API; прежнее предположение о четырёх разных провайдерах не подтверждалось пользователем. Локальный82 содержит непроверенный черновик транспорта/форм/хранилищ/тестов; требуется аудит и CI. Затем83 компактное квадратное удаление фото44dp,84 изолированный дневник с паролем и переносом данных ежедневника. Только после закрытия80–84 — отдельные3ч аудита, затем3ч оптимизации с фактическими UTC-интервалами. Прежние80/81 не повторять, оформление настроек с плашками сохранить. Сохранить последнее уточнение редактора: цвет задачи в строке, постоянная галочка задачи зелёная во включённом состоянии; эти действия в ⋮ не возвращать.

[Отчёт](settings-1.10.51.md), [доказательства](settings-1.10.51-verification.json).

APK: https://github.com/grigorywork/NoteLS/actions/runs/44129055942/artifacts/14349852310

## 80 закрыт — проверенный NoteLS1.10.50/code73

Этап80 закрыт: NoteLS1.10.50/code73, Room18/data2/security3. Source `17cc6f5d9e9722851ef6267428cebe801913565f`. Полный CI[44066927705](https://github.com/grigorywork/NoteLS/actions/runs/44066927705) =**799 тестов/0 отказов/0 пропусков**, lint0errors/16warnings/2info. Signed[44071638394](https://github.com/grigorywork/NoteLS/actions/runs/44071638394) SUCCESS. Независимо сверены ZIP/API digest/состав/SHA256SUMS/APK/source/package/version/nondebug/настоящий apksigner v2/v3/один signer/постоянный сертификат/DEX/Room18. Физическая установка не выполнялась.

- «Запущенные» первым чипом в фильтрах незавершённых задач: работающий секундомер или ещё не истёкший отсчёт; совместимо со сроком, приоритетом, блокнотом и тегом.
- Цвет задачи только в верхнем меню ⋮; кружок цвета из строки задачи удалён.
- «Сделать заметкой» — одна квадратная пиктограмма-галочка в области44dp. У заметки «Сделать задачей» остаётся в ⋮.
- Убрана дублирующая верхняя Aa: оформление текста открывается в единственной панели. Меню оформления таблиц сохранено.
- Завершение отсчёта обновляет фильтр без записи в Room и без зависимости от уведомления. Используется один передний таймер границы, без периодического фонового опроса.
- Для проверки времени выделен общий источник epoch-времени списка и планировщика; штатный источник System.currentTimeMillis и Application-конструктор AndroidViewModelFactory сохранены.
- Добавлены проверки SQL/кэша/чипа, трёх сценариев редактора и фактического исчезновения истёкшего отсчёта из списка. NativeCanvas task-320 и large-font/IME снимки осмотрены.

Следующий81 — восстановить компактный вид настроек до76 и ограниченные переходы фона стекла; локальный черновик1.10.51/code74 пока не проверен. Далее82 четыре бесплатные нейросети (актуальные условия API перепроверить: Mistral Free mode больше не включает API),83 компактное удаление фото,84 изолированный дневник с паролем и переносом прежних данных. Затем отдельные3ч аудита и3ч оптимизации с фактическими UTC-интервалами.

[Отчёт](tasks-1.10.50.md), [доказательства](tasks-1.10.50-verification.json).

APK: https://github.com/grigorywork/NoteLS/actions/runs/44071638394/artifacts/14324265636

## 79 — панель оформления заметки: проверенный NoteLS1.10.49/code72/Room18

Одна панель оформления вместо жёстко нижней: настройка `FormattingBarPosition` (`BOTTOM` по умолчанию/`TOP`), компонент `EditorFormattingPanel` рисуется ровно в одном месте — под заголовком или над клавиатурой; меню вставки (таблицы/блокнот/теги/сигнал/аудио/галерея/камера) переезжают вместе с панелью. Переключение — пункт «Панель сверху»/«Панель снизу» в меню⋮ редактора, верхняя кнопка «Aa» осталась popup. Выделение читается внутри `topBar`/`bottomBar`, чтобы ввод символа не пересобирал весь экран. [Отчёт](ui-1.10.49.md), [доказательства](ui-1.10.49-verification.json).

Runtime source `be99e5d910c452e4ac2ab1433d68777b06f37112`. CI `44036888370`/job `133881942850`=**792/0fail/0ignored** (0.48:789,+3), lint0/16/2, build23m55s. Main fast-forward `bd98316→be99e5d` (via `0677fad`). Signed `44039953479`/identity `133891829755`/release `133891886965` SUCCESS,9m39s,13contracts/0fail. Artifact `14308619912` ZIP15463892bytes SHA256 `74350e849a3b0d4e2def82e483a61119a99b805e2855ee35690a0a62b0864d27`; APK `NoteLS-1.10.49-code72-permanent-signature.apk` 27583175bytes SHA256 `04b20c27824e4888bbf8ce743bde9381c9e134cc92ece2dcec05adfd05d3d026`=SUMS/CI; source/README/version/code/nondebug/certpin `c94bdf…5661c`/v2v3 (`0x7109871a`,`0xf05368c0`) сверены. Следующий **80** — задачи.

## 78 — зависания: проверенный NoteLS1.10.48/code71/Room18

Причина зависаний виджета «Месяц»: автосохранение заметки до2 раз в секунду вызывало полную пересборкуRemoteViews и повторную привязку адаптера через Binder в UI-потоке; лаунчер со временем переставал принимать нажатия (работала только Activity настроек). Сделано: фоновый коалессинг без повторов по подписи снимка, data-notify не чаще1.5с, запрос кRoom в сервисе ограничен2с с откатом на прошлый снимок; палитра/типографика темы мемоизированы, поэтому посторонние настройки больше не перекрашивают всё дерево. [Отчёт](perf-1.10.48.md), [доказательства](perf-1.10.48-verification.json).

Runtime source `f108421968abce41413009ea0eb6512c4fb36800`. CI `44029753094`/job `133858684383`=**789/0fail/0ignored**, lint0/16/2, build21m16s. Main fast-forward `eb27620→f108421`. Signed `44032480703`/identity `133867452865`/release `133867497055` SUCCESS,8m18s,13contracts/0fail. Artifact `14306326544` ZIP15463443bytes SHA256 `5130b8d52dfaf75b8e1d62b6df2d389ab96437078c285ac26f8b9a093f3dfdf3`; APK `NoteLS-1.10.48-code71-permanent-signature.apk` 27566791bytes SHA256 `921be1a6a2496695d68e3286d882de5327d6e7c4fc9671dad2cfa428e78bb32e`=SUMS/CI; source/README/version/code/nondebug/certpin/v2v3 сверены.

Ограничения: одиночная рекомпозиция при переключении классика→стекло остаётся, а «периодические зависания» без logcat/ANR не воспроизводятся — устранён крупнейший известный источник Binder-шторма. Следующим после 78 был **79** — одна панель форматирования (закрыт выше).

План после 78: 79 одна панель в заметке (сверху или снизу), 80 задачи (список запущенных, запрет дублей, дубль цвета, компактная «Сделать заметкой»), 81 настройки как до76 + плавное стекло, 82 преобразователь на4 бесплатных нейросетях, 83 компактная квадратная кнопка удаления фото, **84 дневник** (убрать ежедневник, отдельный пункт бокового меню, пароль, записи дневника не в общих заметках; данные ежедневника перенести, а не потерять), 85 аналитика, 86 календарь по блокнотам, 87 Google CSV/XLSX, 88 Evernote ENEX, 89 аудит Wi-Fi. 68/iPhone отложен.

## 77 патч — проверенный NoteLS1.10.47/code70/Room18

Фиксы аудита0.46 в рамках77: отступ в ячейках12sp на уровень (заметки20sp), WCAG-контраст наследуемого текста на явной тёмной подложке (явный COLOR не переопределяется), убран дубль stateDescription у кнопки области, кнопка размеров заблокирована в preview/accessDenied. [Отчёт](tables-1.10.47.md), [доказательства](tables-1.10.47-verification.json).

Runtime source `520f0f822c66fcb6897b7a98432f36432fc79c99`. ВеткаCI `43882873610`/job `133353143813`=**789/0fail/0ignored** (0.46:786,+3), lint0error/16warnings/2info, build19m06s. Main fast-forward `3518e926→520f0f8`. Signed `43886294105`/identity `133365246925`/release `133365331803` SUCCESS,13contracts/0fail, build12m50s. Artifact `14236340827` ZIP15462951bytes SHA256 `c91105d4eba42db233dee407076f21b298fdb2c8b8fc9d543705c541bbce2a24`; APK `NoteLS-1.10.47-code70-permanent-signature.apk` 27566791bytes SHA256 `bb375fdcd2e64aa13fd59de840188161c16bed75f235a3beda89870de1b0a047`=SUMS/CI. Source/README/version1.10.47/code70/nondebug/1signer/v2v3/certpin `c94bdfcf4d5a4ce0cfa51809707bf94f9034b1a1a47cd89a2a13e9577845661c` сверены; Room18/data2/security3 без изменений.

APK: постоянная ссылка https://github.com/grigorywork/NoteLS/actions/runs/43886294105/artifacts/14236340827 . 0.47 — текущий installable, 0.46 — предыдущий проверенный.

## 77 закрыт — проверенный NoteLS1.10.46/code69/Room18

Оформление текста в таблицах: whole-cell styles с областями CELL/ROW/COLUMN/TABLE, общие с заметками шрифт/размер/цвет/подложка/B/I/U/strike/выравнивание/отступы, отдельный сброс, version2 sparse только при реальном оформлении, legacy1, строгие границы, атомарная история/учёт памяти, компактная панель undo/redo/⋮, размеры за кнопкой, IME, высота строки под крупный шрифт. Room18 без миграции; `tablesData` остаётся opaque для backup/sync.

Runtime source `f7168c3e5e2a8326c7a60a0e5ff75b5bc6019d67`. ПолныйCI `43872142172`/job `133315794765`=**786/0fail/0ignored**, lint0error/16warnings/2info, build27m35s; report `14230197847` ZIP726838bytes SHA256 `b3eac07adadf4d61d001c07c49fd5a8e6f685356006de2ae2ffe3201f6439c7a`, HTML/XML и оба tablePNG сверены. Signed `43877555616`/identity `133334460173`/release `133334518287` SUCCESS, build10m36s,13contracts/0fail. Artifact `14230793846` ZIP15463414bytes SHA256 `bef2e673f34fe12e6b4923bbef2e4cfea5c3b25d90cbb98ad5079c00a5e1d78c`; APK27566791bytes SHA256 `3eccc0662aa4bee0a53e19a37e2349ed927877a8fdaa7305d8c455e39d04c6be`=SUMS/CI. Source/README/version1.10.46/code69/nondebug/actualapksigner v2/v3/1signer/certpin проверены; Room18 schema/identity прежние. [Доказательства](tables-1.10.46-verification.json), [отчёт](tables-1.10.46.md).

APK: постоянная ссылка https://github.com/grigorywork/NoteLS/actions/runs/43877555616/artifacts/14230793846 . В финале выдавать свежую HTTPS ZIPdownloadURL из github_download_workflow_artifact + постоянную ссылку; распаковать ZIP и установить.apk. Временный bearerURL не сохранять в Git.

Следующий **78 — аналитика**, затем79 календарь по блокнотам,80 импорт Google CSV/XLSX,81 Evernote ENEX,82 аудит Wi-Fi. 68/iPhone отложен, новых номеров не добавлять, 77 не повторять. Физического телефона/клавиатуры и реальных платныхAI-вызовов нет.

## 77: кандидат1.10.46/code69/Room18, checkpoint и первый прогон

Локальнаяmain содержит незавершённую реализацию; удалённый checkpoint `codex/tables-1.10.46/9beec0a`. Origin/main покаbe4dc4b (план поверх verified45). В новой среде fetch checkpoint и свежие последующие коммиты перед продолжением, не повторять77 сначала.

Модель version2 sparse whole-cell styles и CELL/ROW/COLUMN/TABLE scopes, legacyversion1 без стилей, reset/removal последних стилей возвращаетversion1, strict bounds/unknown preservation. Общие FormatKind/меню/палитры/SpanStyle/ParagraphStyle с заметками. История/учёт памяти/доступ, компактная панель таблицы/undo сверху/размеры раскрываются/приIME скрываются, высота строки минимум на одну строку большого текста. Новые model/history/render/NativeRoom+backup+wire/VMfreshlock/UI320×640+IME+RUEN проверки подготовлены.

Единственный Gradle `/workspace/tools/tables46-checks.log` (session97501), результаты компиляции/тестов/lint/APK пока НЕ подтверждены. Main/test заморожены до завершения/второй Gradle не запускать. После фактических отказов исправления/аудит/NativePNG, локальные checks/lint/assemble, полный CI+autoSigned+независимая верификация перед закрытием77/78. **46 НЕ installable; последний verified45**. Room18/data2/security3/ключ/Secret не менялись. [Отчёт](tables-1.10.46.md).

## Начат77 — оформление таблиц (план1.10.46/code69/Room18)

Свежийmain3b6072c, Gradle/CI завершены, дерево чистое. Решение/границы в [tables-1.10.46.md](tables-1.10.46.md).45 остаётся последним проверенным installable.46 ещё не реализован/не проверен.

Автопродолжение77 сохранено в прежней Automation_071fe34793b881919672b7f4cf8dca94 на2026-11-15T23:50:41Z/is_enabledtrue; API подтвердил обновлённый prompt с закрытым76 и verified45, дубль не создавался.77 пока не начат. При новом запуске проверить свежие main/процессы/CI и не дублировать живого исполнителя.

## 76 закрыт — проверенный NoteLS1.10.45/code68/Room18

Runtime source `d3aed90a2dee41f8d684948d5be12938518667c5`, Room18/data2/security3. Стекло: прозрачность/матовость/8 цветов/3 фона, draft preview/сброс/Save/Cancel/Back. Android12+ blur только собственного фона≤18dp; Android8–11 — мягкий градиент, не системное размытие. Контраст/непрозрачность меню, чёткий текст, статичный фон без таймеров. Main/QuickNote/WidgetSettings подключены, остальные настройки сохранены.

ПолныйCI43771941590/job132989559112: **765/0fail/0ignored**, lint0error/16warnings/2info/build28m52s. Report14185766665 ZIP704069bytes SHA25604f2cbfc91f3e5e75850e42c34b18c965a827c533ef6f0e6d5d253c6a7af328e=API/GradleHTML/lintXML; оба NativePNG равны осмотренным локальным.

Signed43774701037/identity132998241930/release132998276354 SUCCESS, build10m1s,13 signing contracts/0fail/10.174s. Actualcheckout/README source d3aed90; runhead51b7718 docs-only. Artifact14185219770 ZIP15454533bytes SHA2561ca7c3c3b5bce002db5d5f05f68ad11e4cf69ac7c80539f49bceabd36da0f8a2=API; APK27566791bytes SHA256a56777399b0eafdac9e44b03c9a4d18de06db1aab78ad27a6e3129ac27b14e13=SUMS/CI. Ровно3файла/source/package/version/nondebug/actualapksigner v2/v3/1signer/pin проверены. НовыйDEX с glass-pref/UI/BlurEffect markers; R8 переименовывает/inline обычные имена Kotlinметодов. Room18identity/schema прежние.

[Доказательства](glass-1.10.45-verification.json), [отчёт](glass-1.10.45.md), [инструкция](glass-settings-guide.md). Постоянныйключ/Secret не менялись. Нет физических GPU/телефонных/батарейных измерений и реальных платныхAI запросов.

Следующий **77 — оформление текста в таблицах**, затем78–82.77 ЕЩЁ НЕ начат,46/code69 ещё нет. Если main уже продвинулся, смотреть свежие факты. Не повторять76/75/74/73/72/69/71/70 и не применять старые WIP поверх main.68/iPhone не начинать, новых номеров этапов не добавлять.

APK: `/workspace/library-files/NoteLS-1.10.45-code68-permanent-signature.apk` — локальный проверенный файл, НЕ скачиваемое вложение для клиента. Постоянная ссылка https://github.com/grigorywork/NoteLS/actions/runs/43774701037/artifacts/14185219770 . В финале выдавать свежую HTTPS ZIPdownloadURL из github_download_workflow_artifact + постоянную ссылку; распаковать ZIP и установить.apk. Временный bearerURL не сохранять в Git.

## 76: полный CI765/0 подтверждён; Signed выполняется

Кандидат1.10.45/code68/Room18/data2/security3, runtime `d3aed90a2dee41f8d684948d5be12938518667c5`. ПолныйCI43771941590/job132989559112 SUCCESS: **765/0fail/0ignored**, lint0error/16warnings/2info, build28m52s. Report14185766665:704069bytes, ZIP SHA256 `04f2cbfc91f3e5e75850e42c34b18c965a827c533ef6f0e6d5d253c6a7af328e`=API; GradleHTML/XML проверены. Два glass NativePNG побайтово равны локальным.

АвтоSigned **43774701037**, identity132998241930 SUCCESS, release132998276354 in_progress. API runhead51b7718 — docs-only; при проверке нужен actualcheckout/README runtime d3aed90. Пока45 НЕ проверенный установочный,76 НЕ закрыт; последний verified44. Не менять main/test/не запускать второйGradle. После Signed независимо проверить APK/ZIP/API/SUMS/source/version/nondebug/actualapksignerpin/newDEX и только затем закрыть76/перейти77. [Отчёт](glass-1.10.45.md).

## 76: полный CI запущен, исходники заморожены

Runtime source `d3aed90a2dee41f8d684948d5be12938518667c5` уже в main. Полный pushCI **43771941590/job132989559112 — in_progress**, не подтверждённые765 не считать успехом. Локальные36/36 и финальные4 UI/4 (пересечение) успешны, lint0error/assembleDebug. Локальных Gradle нет, дерево чистое; все WIP codex/glass-1.10.45 включены в main. Не менять main/test и не запускать второй Gradle при CI/Signed. После полного успеха дождаться autoSigned того же runtime source, независимо проверить APK и только тогда закрыть76/перейти77. Последний проверенный installable44,45 пока не выдавать. [Отчёт76](glass-1.10.45.md).

## 76: кандидат1.10.45/code68/Room18 — локально проверен, полный CI следующий

Вся реализация и WIP `codex/glass-1.10.45` включаются в main обычным fast-forward. Локальные **36 разных тестов** успешны (`glass45-checks3.log`,7m40s); дополнительные4 UI из этого же набора успешны на коротком дисплее320×640/крупный EN шрифт (`glass45-ui-final.log`,4m12s). Не складывать их в40. lint0error/15warnings/2info, assembleDebug SUCCESS.12 новых случаев:5 pure/3prefs/4NativeUI. Снимки осмотрены и сохранены в docs/design/glass-1.10.45.

Прозрачность, матовость,8 адаптивных цветов и3 рисунка фона; локальный draft/preview/reset/Save/Cancel/Back. Android12+ размывается только собственный фон≤18dp, старые версии используют мягкий градиент, SOLID/быстрые окна без blur. Контраст≥4.5 проверен по консервативным композициям цветов; аппаратная GPU-производительность/телефон не измерялись. Main/QuickNote/WidgetSettings подключены; Room18/data2/security3/ключ/Secret прежние.

Полный CI **765 ожидается, пока НЕ подтверждён**; затем autoSigned и независимая проверка ZIP/API/source/version/nondebug/apksignerpin. До этого76 НЕ закрыт,45 НЕ проверенный установочный; последний verified44. Main/test заморозить во время CI/Signed, второй Gradle не запускать. Следующий77 только после закрытия76. [Отчёт](glass-1.10.45.md), [инструкция](glass-settings-guide.md).

## Текущий76 — кандидат1.10.45/code68/Room18, локальная проверка

Исходник сохранён в удалённой `codex/glass-1.10.45`, checkpoint `f0f8fc5`; локальная main содержит его. Remote main пока30b6ea8 (документация поверх проверенного44). В новой среде получить checkpoint и актуальные последующие коммиты; не начинать76 заново.

GlassAppearance: прозрачность/матовость/8 адаптивных цветов/3 фона, безопасное чтение prefs и единый Save. Диалог с локальным draft, preview/reset/Save/Cancel/Back. Android12+ — настоящий blur только собственного фона ≤18dp; старые Android — мягкий градиент, SOLID/быстрые окна без blur. Кэшированные кисти, нет таймеров/анимаций/фоновых задач. Не заявлять физически измеренную производительность или GPU-результат по NativeCanvas SDK28.

Единственный Gradle `/workspace/tools/glass45-checks.log` (session47458): 36 целевых ожидается, компиляция main прошла, результаты тестов/lint/APK пока не подтверждены. Не менять main/test при проверке/не запускать второй Gradle. После фактических результатов — аудит/исправления/NativePNG, полный pushCI, autoSigned и независимая верификация. Последний verified installable44,76 НЕ закрыт. [Отчёт](glass-1.10.45.md).

# NoteLS: продолжение разработки

## Текущий76 — стекло1.10.45/code68/Room18, план до реализации

[Решения/отчёт](glass-1.10.45.md). Свежийmain60cb93b/деревочистое/активныхGradleиCIнет. Собственныйфон/alphaразделить, trueblurтолькофонAndroid12+/bounded18dp/default0; старыйAndroidsoftgradientсчестнымпояснением. Локальныйdraftpreview/reset/save/cancel, themeadaptiveпалитры/3фона, noanimations/polling. Покаappsource45ещёнеизменён/НЕinstallable. Последнийverified44ниже.

## Проверенный выпуск1.10.44/code67/Room18 —75 закрыт

Цитатник: своицитаты/автор/источник/CRUD/закрепление, boundedUTF8JSON/TXTимпорт сpreview/дубликатами/атомарностью; нижняяцитатаотдельноотпрокрутки/ограничениевысоты/крупныйшрифт. Прежние5Roomentitiesточноравны17, новые2таблицыдобавлены17→18миграцией;зашифрованныйbackup/restore/legacy/stale/capacity/rollbackпроверены. [Инструкция/примеры](quote-library-guide.md), [отчёт75](quotes-1.10.44.md), [машиночитаемыедоказательства](quotes-1.10.44-verification.json). Своябиблиотекапокалокальная+backup,WiFiсинхронизациюцитатнеобъявлять.

Полный [CI43759092671](https://github.com/grigorywork/NoteLS/actions/runs/43759092671)/job132949595069, runtime source`70b5a4ebb26888a769e2c85f3f258caa6de7a3a0`: **753теста/0fail/0ignored**, lint0error/16warnings/2info, build21m7s. Скачанreport14180141775 ZIP613136bytes SHA256`d8b9fa6149ae31900712f0ddb8760579e79142a44a5c7d27b5d5815a7ec78bc0`=API; HTML/XMLсверены;3quoteNativePNGпобайтоворавнылокальным.

[Проверенный установочный APK44 в ZIP14180464095](https://github.com/grigorywork/NoteLS/actions/runs/43761100021/artifacts/14180464095). Signed43761100021/identity132955891683/release132955925460SUCCESS; actualcheckout/READMEsource70b5a4e, runhead8615297docs-only. Build/lint13m55s,13signingcontracts0fail12.106s. ZIP15444377bytes SHA256`3e1a0703964fd3e0951f68ad1a984bcc8c98bea53d19625ab24680c67a907bc8`=API; ровно3уникальныхAPK/README/SUMS. APK27550407bytes SHA256`0354745524cc2106e57ca14e6dfb8b5f365573c25d8f7bad6755ceaeee9ad68f`=SUMS/CI. Actualapksigner v2/v3/1signer/certpin и aapt2com.notessync.app/1.10.44/67/nondebug/min26/target35проверены. DEXновыйсcustom_quotes/quote_library_state/UIиactualRoom18identity`b1c6c07627d80657a69a8afd3c98f6aa`. Schema18JSONSHA256`f6f988c94c5342796142e29df703f6d6e27bd0fc664a76b8ac8939dd006c1973`.

Room18/data2/security3, постоянныйключ/Secretпрежние. Физическойустановки/телефонных/батарейных/реальныхAIгенерацийнет. ЛокальныйAPK `/workspace/library-files/NoteLS-1.10.44-code67-permanent-signature.apk`. ПроверятьвесьAPKпередкаждойвыдачей; debug/unsignedневыдавать.

75закрыт, следующие **76 — настраиваемое матовое стекло, затем77–82**.76ещёНЕначат/source45/code68ЕЩЁнет. ВсеWIPcodex/quotes-1.10.44вmain, не повторять75/74/73/72/69/71/70. Свежийmain/дерево/ветки/Gradle/CI/активнуюработупроверитьпередправкой;не reset/clean/force-push/не терятьизменения/не запускатьвторогоисполнителя/Gradle/не редактироватьmain/testпри проверке. Послеэтапааудит/исправления/тесты/lint/APK/полныйCI/Signed/независимаяверификация/журнал/main.68iPhone/новыхномеровэтаповненачинать.

Выдача: freshHTTPSdownloadZIPизGitHubartifacttoolвФИНАЛЕ +постояннаяссылканадартефактвыше. Абсолютныйworkspaceпутьуклиентанескачивается/инструментаотдельногоAPKuploadнет;не называтьlocalpathвложением. Архивраспаковать/установить.apk. BearerURLживётминуты/не сохранятьвGit. Тажеautomationразрешенадляпродолженияследующейочередибездублей.

## Проверенный выпуск1.10.43/code66/Room17 —74 закрыт

Qwen добавлен через **Groq** рядом с прежними LOCAL_RULES/OpenAI: компактный выбор, отдельные ключи/vault, fixedмодель, явныеSend/preview/Apply, без автоматической смены тарифа/модели/сервиса. Бесплатно только на аккаунтеGroqFree в квоте; Developerможет тарифицироваться, приложение не проверяет тариф. [Инструкция RU/EN](ai-qwen-setup.md), [отчёт74/Qwen](qwen-1.10.43.md), [машиночитаемые доказательства](qwen-1.10.43-verification.json).

Полный [CI43744048331](https://github.com/grigorywork/NoteLS/actions/runs/43744048331)/job132903324579, runtime source`af01d51ee59756bcdf060123a9b4584c1541c789`: **732 теста/0fail/0ignored**, lint0error/16warnings/2info, build19m9s. Скачан report14173967928 ZIP567029bytes SHA256`b8acae7369ad1c470fe6f63cd30984d717495e98ef4afd171baf0630cd3ba0c0`=API, HTML/XML сверены;4settings-previewPNG равны локальным, QwenPNG`f58d167c0cc2896faf24c6a5c6fa4aba62a595f95be605ae87245744672533a1`.

[Проверенный установочный APK43 внутри ZIP14174757856](https://github.com/grigorywork/NoteLS/actions/runs/43746004909/artifacts/14174757856). Signed43746004909/identity132909328311/release132909360292SUCCESS; actualcheckout/READMEsourceaf01d51, runheadaff1d7edocs-only. Build/lint10m5s,13signingcontracts/0fail/10.276s. ZIP15414119bytes SHA256`6b3f6415a5d75d2758e16c894f77323564fcc89014327899988e7b1c3a523027`=API, ровно3уникальныхAPK/README/SUMS. APK27517639bytes SHA256`f55e2bb10763563e1e360523b378c67646bdfc87e7f709442544495c5677ee9d`=SUMS/CI. Actualapksigner v2/v3/1signer/certpin, aapt2com.notessync.app/1.10.43/66/nondebug/min26/target35 проверены. DEXновыйсGROQ_QWEN/model/apiGroq/vault/Sendmarkers.

Room17/data2/security3/постоянныйключ/Secret прежние. РеальныхAIгенераций/платныхвызовов/физического телефона не было; Qwenпроверен в fakeHTTP/UI, доступность конкретного аккаунта/тарифа не проверена. ЛокальныйAPK `/workspace/library-files/NoteLS-1.10.43-code66-permanent-signature.apk`.

74 полностью закрыт, компактность42 и дополнениеQwen43 не повторять. Следующий **75 — закреплённая цитата и свой цитатник**, затем76–82;75 ещё НЕначат, source44/code67ещёнет. ВсеWIPcodex/qwen-1.10.43 включенывmain, draft.patchприменён/удалён — не применятьповторно. Новыхэтапов/68iPhoneне начинать. Передправкой fetchmain/проверкатекущихветок/дерева/Gradle/CI/активнойработы, не reset/clean/force-push/не терять дерево/не запускать второгоисполнителя/Gradle/не редактироватьmain/testпри проверке. Послекаждогоэтапааудит/тесты/lint/APK/полныйCI/Signed/независимаяверификация/журнал/main.

Выдавать вФИНАЛЕ свежуюHTTPSdownloadURLZIP изGitHubartifacttool и постояннуюссылку выше: absoluteworkspaceлинк у клиента не скачивается («Нетапк»), инструментаотдельногоAPKuploadнет. Не называтьlocalpathвложением. BearerURLживётминуты/не коммитить. Архив нужно распаковать и установить.apk. Согласованная очередь/самостоятельноевозобновление разрешены пользователем; резервнаятажеautomation после доставки17:37обновлена на19:41:57UTC/is_enabledtrue и APIподтвердил, не создаватьдубль.

## Проверенный выпуск 1.10.42/code65/Room17 — компактная часть74

Полный [CI43731374968](https://github.com/grigorywork/NoteLS/actions/runs/43731374968), job132864325436, runtime source`1b5504518314cb178306d87e31a8782374548090`: **714 тестов,0 отказов/пропусков**, lint0error/16warnings/2info, build20m20s. Report14169058870 ZIP SHA256`47030231b02b7c1fd712b16719ff1f74a0df8d40b773779e49a56c5ea181590e`=API; HTML/XML сверены,3NativeCanvasPNG побайтово равны локальным.

[Проверенный установочный APK42 вZIP14169334476](https://github.com/grigorywork/NoteLS/actions/runs/43733357193/artifacts/14169334476). Signed43733357193/identity132870485696/release132870526879 success; actualcheckout/READMEsource1b55045, runhead9facad8docs-only. Build/lint8m2s,13signingcontracts/0fail/8.625s. ZIP15412156bytes SHA256`8ecdbbde7b73e4bdb91289202297ce3e44d4a5c65540ea31c22c6e55885ae7f0`=API; ровно3APK/README/SUMS. APK27517639bytes SHA256`264323350e94f19edf8bd73ce562bc009cf70c9f4d59144c62c77680218b9b09`=SUMS/CI. Actualapksigner v2/v3/1signer/certpin, aapt2package/version/nondebug/min26/target35 проверены; DEXновый сsettings-marker. [Доказательства](settings-1.10.42-verification.json), [отчёт](settings-1.10.42.md). Room17/data2/security3/ключ/Secret прежние. Физического телефона/платныхAIзапросовнет.

74 ещё открыт только для дополнения пользователя: Qwen через Groq Free. Следующая кандидатная версия43/code66 в рамках того же74, потом75–82. Сначала свежиеmain/дерево/проверки; не повторять компактность42/73/72/69/71/70. [ПланQwen](qwen-1.10.43-plan.md), черновикdocs/design/qwen-1.10.43-draft.patch пока не применён. Подтверждены официальные FreeусловияGroq/Qwen, отдельныйключ вприложении; никакой молчаливой оплаты/другоймодели. Приложение не проверяет тарифGroq: Freeбесплатенвквоте, Developerплатный. Ожидание старого asyncответа не считать согласием на платныйAPI.

При выдаче использовать свежуюHTTPSZIPdownloadURL изGitHubartifacttool и постояннуюссылку выше: localworkspaceссылка у клиента не скачивается. BearerURL не коммитить. Подготовленный локальныйAPK `/workspace/library-files/NoteLS-1.10.42-code65-permanent-signature.apk`.

## Проверенный выпуск1.10.41/code64/Room17 —73 закрыт

GPT: отдельная безопасная диагностика квоты/лимита/ключа/модели/доступа/региона/сервиса, outputlimit без увеличения2048budget, ModelMissing до отправки, объяснение отдельного APIбаланса, preview→настройкиtransform. Оригинал/явныйSend/preview/явныйApply/cancel/freshaccess сохранены. [Отчёт73](gpt-1.10.41.md), [проверкаJSON](gpt-1.10.41-verification.json).

[ПолныйCI43716992091](https://github.com/grigorywork/NoteLS/actions/runs/43716992091)/job132820552853/source`8d2074a709683d059ac4937e780986eed2ea8ba1`:709/0fail/0ignored, lint0error/16warnings/2info, build18m45s. Скачанныйreportartifact14163332799 ZIP413915bytes SHA2569f208c046a42aa28c5bacf0fefdf80cc062dace10ad1aea24ff6305d53769ef1=API; HTML/XML сверены.

[УстановочныйAPK41 в ZIP:Signed43718750157/artifact14164360720](https://github.com/grigorywork/NoteLS/actions/runs/43718750157/artifacts/14164360720). Identity132825891371/release132825930921SUCCESS; actualcheckout/READMEsource8d2074a; runhead6798474docs-onlyworkflow_run. Build/lint12m15s,13signingcontracts0fail11.711s. ZIP15411387bytes SHA2561d8d1bdb15ff963a5acd39f58c5823fc64e72755e223f4a7c782568a5b1bed8b=API,ровно3уникальныхфайлаAPK/README/SUMS. APK27517639bytes SHA256e8ddd2f34a1ae2f204ddcd3662af5c4bc0faee22dd8dee00d44c8f7fe759e786=SUMS/CI; actualapksigner v2/v3/1signer/certpin, aapt2com.notessync.app/1.10.41/64/nondebug/min26/target35. DEXновыйсGpt-маркерами.

ЛокальныйпроверенныйAPK `/workspace/library-files/NoteLS-1.10.41-code64-permanent-signature.apk`. Ключ/Secret/Room17/data2/security3 прежние. Sourceприложенияпри закрытии совпадал с8d2074a; всеWIPcodex/transform-1.10.41 включены вmain.73/72/69/71/70 не повторять. ПлатныхAPIзапросов/физическоготелефонанет.

Следующий74(компактныенастройки), затем75–82 автономно.74 ещё не начат, source42/code65не создаватьповторноеслиmainужепродвинулся. При новом изменении приложения увеличиватьверсию. Сначала свежиеmain/дерево/Gradle/CI; не reset/clean/force-push/не терять дерево/не запускать второйGradle и не редактироватьmain/test при проверке. ПлатныеAIзапросы безотдельногоразрешенияне делать, APIключвчате не просить.

ВыдачаAPK: абсолютная ссылка workspace у пользователя НЕоткрылась («Нетапк»). Вфинальном ответе выдаётся свежаяHTTPSdownloadZIPссылка GitHubartifacttool и постоянныйGitHubartifactURL выше; ZIPсодержитпроверенныйAPK/README/SUMS. Доступныеинструментыне прикрепляют отдельныйAPK — не называтьlocalpathвложением. BearerURLне сохранять вGit, обновлятьчерезdownloadtoolпри необходимости. Послефинальнойвыдачипродолжить74–82; тажеautomation15:16:41UTC/is_enabledtrueсохранена,новыйдубльненужен.

## Проверенный выпуск1.10.40/code63/Room17 —72 закрыт

Диктовка: компактная панель внеpopup/scroll, режимы удержания/нажатия и выбор языка SYSTEM/RU/EN, отмена при preview/modal/смене владельца, защита поздней вставки, корректные кандидаты/Bundle/пробелы между словами/история. [Отчёт72](voice-1.10.40.md), [проверкаJSON](voice-1.10.40-verification.json), [NativeCanvas из CI с имитированнойIME](design/voice-panel-ime-1.10.40.png).

Полный [CI43712089734](https://github.com/grigorywork/NoteLS/actions/runs/43712089734)/job132805662061, source `87f65add7bad85414f9da83344c54012b32935b7`:701/0fail/0ignored, lint0error/16warnings/2info, build23m37s. Скачанный reportartifact14160944000 ZIP SHA256a5aab21b96f94c05cebb1d4ef2a714626e030e03a70cc02d11c79c0bd4b9a4ea=API; HTML/XML и7PNG проверены. VoicePNG побайтово совпал с локальным NativeCanvas, SHA25672a386ddfe063c6a20de6bf7437f0c15f0f61eea7b895e9bef06108755c46b96; первоначально пустой просмотр в чате был ошибкой отображения, файл содержит интерфейс.

[УстановочныйAPK40:Signed43714199063/artifact14161866812](https://github.com/grigorywork/NoteLS/actions/runs/43714199063/artifacts/14161866812), identity132812083366/release132812117580SUCCESS. Actualcheckout/READMEsource87f65ad; runheadcd13ccb — docs-onlyworkflow_run. Build/lint9m58s,13signing contracts0fail10.653s. ZIP15409084bytes SHA25664f8e41e529da08670f4e69de57cc149afee31c511ef12f5c0c9d4f99533c8d4=API; ровно3 уникальныхфайла APK/README/SUMS. APK27517639bytes SHA25652764f93a946e0f86e05a8af4678a065db2a255e92388082b35e4d3b064accbe=SUMS/CI. Actualapksigner v2/v3/1signer/certificate строго pin; aapt2com.notessync.app/1.10.40/63/nondebug/min26/target35. DEX изменён и содержит новыеvoice-marker.

Локальный проверенный APK `/workspace/library-files/NoteLS-1.10.40-code63-permanent-signature.apk`. Ключ/Secret/Room17/data2/security3 прежние. На закрытие приложение main совпадало сsource87f65ad; все WIPcodex/voice-1.10.40 уже включены. Не начинать72/69/71/70 заново. Физического телефона/микрофона/батарейных измерений нет.

Следующий73(GPT), затем74–82 автономно. При изменении приложения увеличивать версию. Сначала свежиеmain/дерево/Gradle/CI; не reset/clean/force-push и не терять изменения; не запускать второйGradle/не редактироватьmain/test при проверке. ПлатныеAI-запросы без отдельного разрешения не выполнять; ключи вводятся в приложении, в чате не запрашивать.

Automation12:52 доставлена в уже активнуюработу без дубля.73 теперь выполняется (верхнийраздел); та же резервнаязадача обновлена черезAPI на2026-11-15T15:16:41Z/is_enabledtrue с checkpointcodex/transform-1.10.41/6f7a6b9. При доставке в активнуюработу не запускать второгоисполнителя/Gradle.

## Проверенный выпуск1.10.39/code62/Room17 —69 закрыт

Компактные⋮, выполненные с настройкой defaultfalse без физических копий/переноса/удаления, дневник/ежедневник реализованы. Исправлено сохранение старойCalendarNoteEntity пригалочке; свежиеUUID/состояние/доступ проверяются втранзакции, свежийдокумент/даты/вложения сохраняются. [Отчёт39](navigation-1.10.39.md), [проверкаJSON](navigation-1.10.39-verification.json).

[ПолныйCI43696196924](https://github.com/grigorywork/NoteLS/actions/runs/43696196924)/job132757148859, source`3e16a942b4b3f9aaa355520fb5eaf9c3b6d80942`:690/0fail/0ignored, lint0error/16warnings/2info, build25m59s. Reportartifact14155640520 ZIP SHA256`e375c4201776770771ff045f355201e70b243b0626949bbc98bca13929fb20bd`=API; HTML/XML и6NativeCanvas-снимков проверены.

[УстановочныйAPK39:Signed43698349185/artifact14154919194](https://github.com/grigorywork/NoteLS/actions/runs/43698349185/artifacts/14154919194), identity132763740508/release132763765265SUCCESS:actualcheckout3e16a94, build/lint9m56s,13signing contracts0fail9.860s. Runhead2a66a05 — docs-onlyworkflow_run. НезависимыйZIP SHA256`d79c35a657eee9697bfeae5b075cbdb62212fe542f4ba27d10a24fc291511807`=API; внутри ровноAPK/README/SHA256SUMS. APK27517639bytes SHA256`6a17ce64878b472302b406961c02fe4836d91df78585b4aab5fdd401b8779e24`=SHA256SUMS/CI. Actualapksigner v2/v3/1signer/certificate по прежнемуpin, aapt2com.notessync.app/1.10.39/62/non-debuggable/min26/target35. DEX содержит новуюнавигацию.

ЛокальныйпроверенныйAPK `/workspace/library-files/NoteLS-1.10.39-code62-permanent-signature.apk`. СхемаRoom17/data2/security3 прежняя, ключне менялся. На закрытие69 дерево приложенияmain совпадало сsource3e16a94. ВсеWIP169a710/d78d7cf уже включены вmain —69/редакторзаново не начинать.

Следующий72, затем73–82 автономно. Версию увеличивать при новом изменении приложения. Проверить свежийmain/дерево/живыеGradle/CI передправкой; не reset/clean/force-push, не терять дерево и не запускать второйGradle/не редактироватьmain/test при проверке.

## Проверенный выпуск1.10.38/code61/Room17 —71 и70 закрыты

Утверждённый компактный редактор и свободное поле ввода реализованы. [Эталон](design/editor-approved-1.10.38.png), [отчёт с историей исправлений](editor-1.10.38.md), [машиночитаемая проверка](editor-1.10.38-verification.json). Семь инструментов снизу, undo/redo/Aa/⋮ сверху, секундомер и таймер рядом, таблица/автоспискиRU/EN, контекстное оформление, пароль задач только в меню карточки списка; общая прокрутка и bringIntoView курсора.

[ПолныйCI43689688187](https://github.com/grigorywork/NoteLS/actions/runs/43689688187)/job132737492213 наsource`ebc0c0147048923f7db1fe652377f4562ada6300`:680/0fail/0ignored, lint0error/16warnings/2info, assembleDebug success,15m24s. Reportartifact14152581241 ZIP SHA256e9124315a0bd98d601110bf6b249f700252f59515e723f02d8626be0b627fc33=API; скачанныеHTML/XML и3NativeCanvas-снимка проверены. ПервыйCI43687997788 был680/4fail из-за старыхUI-путей аудио/таблиц; они адаптированы и все680 повторно прошли. Старые assertions/timeouts/forkEvery не ослаблялись.

[УстановочныйAPK:Signed43690896800/artifact14152792633](https://github.com/grigorywork/NoteLS/actions/runs/43690896800/artifacts/14152792633). Identity132741133723/release132741157017 SUCCESS; build/lint12m37s,13signing contracts0fail11.594s. Реальныйcheckout иREADMEsourceebc0c01; runhead048c9f7 — последующая документация workflow_run.

Независимая проверка:ZIP SHA256`8b9c984b700081e056b3f38cad3eabedcf82ee1cc299bdb238c3734479021686`=API digest, внутри ровноAPK/README/SHA256SUMS. APK27501255bytes SHA256`330f6fb61d7a233c0bd5d09b626b42509bdf2f0fd4eb4a345a88e0d91d8f7c57`=SHA256SUMS/CI. aapt2com.notessync.app/1.10.38/61/non-debuggable/min26/target35; actualapksigner v2/v3/1signer/certificate строго по pin ниже. DEX содержит новый редактор. На закрытие38 (dcb960e) дерево приложения main совпадало сsourceebc0c01; текущие изменения39 описаны выше. Room17/data2/security3 прежние. Локальный проверенныйAPK `/workspace/library-files/NoteLS-1.10.38-code61-permanent-signature.apk`.

## Следующие72–82

Пользователь разрешил автономно идти по согласованной очереди без вопросов, пока он спит. [План69–82](improvements-after-1.10.37.md).71→70→69 закрыты. Дальше72(голос),73(GPT),74(настройки),75(цитаты),76(стекло),77(оформлениетаблиц),78(аналитика),79(календарьпоблокноту),80(GoogleSheetsфайлы),81(EvernoteENEX),82(Wi-Fiаудит). Новыхэтаповне добавлять,68/iPhoneне начинать.

Дополнение69: настройка автоматического ухода отмеченных галочкой записей в «Выполненные задачи». Default сохраняет прежнее поведение; без физических копий/переноса/удаления; сохраняются блокнот, даты и вложения; снятие галочки возвращает в рабочие списки. После каждого этапа аудит, исправления, подходящие тесты/lint/APK, журнал и сохранение вmain, затем следующий этап. Новых этапов не добавлять,68/iPhone не начинать.

Рабочая папка `/workspace/NoteLS`, main. Сначала получить свежий origin/main, проверить дерево, живыеGradle/CI/другую работу. Не reset/clean/force-push, не терять изменения, не запускать второйGradle и не редактировать main/test во время проверки. Незавершённый checkpoint154e521 вcodex/editor-1.10.38 уже включён вmain; редактор заново не начинать. Общаться кратко по-русски.

Пользователь разрешил самостоятельное возобновление после паузы лимита. Автопродолжение09:15UTC доставлено в активнуюработу бездублирования. Та же одноразоваяautomation обновлена на15 ноября11:45UTC (API подтвердил); не создавать дубликаты. При живом исполнителе/Gradle продолжать его текущую работу.

## Постоянный ключ уже сохранён — не заменять

Repository Actions Secret `NOTELS_SIGNING_MATERIAL` — атомарный JSON keystore_base64/password. Допустимый SHA256 сертификата в `.github/signing-certificate.sha256`:

`c94bdfcf4d5a4ce0cfa51809707bf94f9034b1a1a47cd89a2a13e9577845661c`

Не генерировать другую identity, не bootstrap/первую настройку, не просить повторныйGitHublogin. Secrets403 в облаке не потеря ключа; обычныйActions используетSecret напрямую. Ключ/пароли не выводить/не коммитить/не добавлять вартефакты. При каждом новом installableAPK независимо проверять ZIP/API digest, состав, SHA256SUMS/APK hash, sourceSHA/package/version/non-debuggable и actualapksigner certificate по pin. Локальныеhelpers `/workspace/tools/verify-notels-apk.py`, `verify-notels-ci-report.py`; они не создают/не читают signingmaterial.

Версия37 с этой постоянной подписью обновляется поверх, удалять приложение при обычном обновлении не требуется. История первой смены старой подписи и проверенного37 — [signing-transition](signing-transition-1.10.37.md); перед переустановкой из старой другой подписи нужна копия вне app data и её пароль, прежняя биометрия его не заменяет.

Физического телефона/настоящейIME/батарейных/биометрических замеров нет. NativeCanvas/Compose и имитацияIME — облачные проверки. ПлатныеAIзапросы без отдельного разрешения не выполнять, APIключ вчате не запрашивать.


Автопродолжение76 сохранено втойже Automation_071fe34793b881919672b7f4cf8dca94 на 2026-11-15T21:38:38.000Z /is_enabledtrue, APIподтвердил. Дубльнесоздан; следующий76ещёненачат. ПослефинальнойвыдачиAPK44продолжитьсвежийmain/передачу, старыйfallback22:02supersededэтимрасписанием.
