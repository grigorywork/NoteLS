# NoteLS: продолжение разработки

## Текущий этап72 —1.10.40/code63/Room17, полный CI701/0 подтверждён, Signed ожидается

[Отчёт72](voice-1.10.40.md). Панель диктовки вне popup/scroll, язык SYSTEM/RU/EN, preview/modal/owner cancellation и VMguard, непустой кандидат/защита Bundle, вставка между словами и история реализованы. Локальные63 разных целевых кейса подтверждены: первый фактический прогон62/63, ошибочный новый owner-setup задал ожидание начала без перестановки курсора; исправлена явная TextRange(0), затем8/8VoiceEditor. LintDebug0error/15warnings/2info, assembleDebug SUCCESS4m26s; лог `/workspace/tools/voice40-ui-final.log`. Компактный редактор/IME/язык/аудиоресурсы/история также прошли. Полный CI теперь подтверждён ниже; installable40 ещё ожидается.

Полный [CI43712089734](https://github.com/grigorywork/NoteLS/actions/runs/43712089734), job132805662061, source `87f65add7bad85414f9da83344c54012b32935b7` SUCCESS:701/0fail/0ignored/lint0error/16warnings/2info, build23m37s. Скачанный reportartifact14160944000 ZIP SHA256a5aab21b96f94c05cebb1d4ef2a714626e030e03a70cc02d11c79c0bd4b9a4ea=API; HTML/XML проверены. AutoSigned43714199063/identity132812083366success/release132812117580in_progress. ExistingSecret/pin checks success; actualsourceAPK должен быть87f65ad (runheadcd13ccb docs-only). CIvoicePNG пустой, не визуальное доказательство; локальный NativeCanvas реальный/проверен, CIsemantics/геометрия прошли. Main отправлен fast-forward, дерево чистое; локальных Gradle нет. WIP a94dd02/08b5d1c/6440c7c из `codex/voice-1.10.40` уже включены в main. В свежей среде проверить новые коммитыmain/CI, не повторять72 с нуля. Проверить живыеGradle/CI и не менять main/test во время проверки/не запускать второй Gradle. После успешного полногонабора проверить автоматическийSigned поactualcheckout/READMEsource, ZIP/API/SUMS/APKhash/package/version/nondebug/actualapksignerpin. Следующие73–82 только после закрытия72.

Та же резервная automation перенесена на2026-11-15T14:06:02Z, API подтвердил is_enabled=true. Не создавать дубль; если исходный исполнитель активен, продолжать его работу без параллельного запуска. Последний проверенный installable —39, ниже.

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
