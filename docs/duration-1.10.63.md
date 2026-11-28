## В работе89 — кандидат1.10.63/code86,28ноября2026

Настройка «Часы и дни в таймерах» defaulttrue, выключение показывает суммарные минуты:секунды. Под часом MM:SS, при60мин1ч00м, при24ч1д00ч00м. Полный вид хранит ненулевые секунды, компактный при>=1ч оставляет минуты; полный accessibility-label точен. RU/EN, без Locale.setDefault/переполнения formatter, сырые миллисекунды/даты/Room19 не меняются. TaskTimer.format и формат аудиозаписей остаются прежними.

Один event-driven/lifecycle settings subscription на themed root через composition local, не на карточку/тик. Новый formatter используется секундомером/обратным отсчётом/аналитикой. Новых таймеров/фоновыхjobs/wakelock/периодическогоопроса нет; существующий foreground visible clock не менялся. Уведомление идущей задачи относится к следующему90, его ещё нет.

Подготовлены4pure/2prefs/3NativeUI=9новыхрегрессий; expectedfull914НЕpass. ЛокальныхSDKGradlebuildsнет, пока компиляция/тестыНЕподтверждены. Featurecodex/duration-1.10.63; baselinee571a62/verified62. Mainapp62проверено,63НЕinstaller/89НЕзакрыт. Послеtarget/аудит→fullCI/lint/APK→autoSigned/independentAPKactualRoom19→freshreleaseGC,только потом90–97.

ПослеВСЕХновые3hаудита→3hоптимизацииоба0ms/неначаты,паузы/CIqueue/ожидание/простойисключать. Батареяобязательна,68/iPhone/новыеИИне начинать/подписьне менять. App/testприCIзаморожены,RESTforce=false,не дублироватьживуюработу. Ниже история.


Примеры:1ч02м/1ч 02м 03с;1д00ч00м/1440:00. Настройкаразмещенавкомпактнойгруппередакторасpreview. Проверкиboundary59:59→1ч,23:59:59→день/LongMINMAX/ENlocale/prefsreload-corrupt/UIclocks+analytics/сохранениесырыхданных.

## Первый target: тесты успешны, lint остановил сборку

46107006914/job140861904373/sourcea3b0430c2f0afc992e3775a36bd7fd6edcdb4afc:51tests/0fail/0ignored, включая все4pure/2prefs/3UI и прежниеclock/format/editor/settings/i18n. Lint1Error StateFlowValueCalledInComposition на начальном чтении repository.state.value в TaskDurationUi.kt:25; assemble/Signed не подтверждены, это НЕ успешныйCI. ZIP357706bytes/artifact15248167430 SHA256ea90837979359732de0ca6208b3916aab576fe8995c8188f8c3aa0979ce97593=API проверен. Все3 duration NativePNG осмотрены: RUtoggle/ENlargefont320/analyticsminutes. Это NativeCanvas, не телефон.

Исправление: вместо прямого чтения начального StateFlow.value корневойprovider использует штатный repository.state.collectAsStateWithLifecycle(), а TaskDurationStyle запоминается только по нужным2полям. Остаётся одна event/lifecycle подписка на themed root, нет новыхпериодическихjobs/опроса/переключателейнакаждыйтик. Lintнеподавлялся/assertions/таймаутыне менялись. Повтор51+lint/assemble ещёпредстоит. Guide task-duration-guide.md.

Для будущего общего аудита послеВСЕХ: проверить перенос легенды цвета в аналитике на320dp (Nativeanalytics-minutesPNG показывает разрыв Фиолетовый передпоследнейбуквой); это не изменение formatter, не физическийтелефон/не объявленный фикс и не начало3hокна.
