##89 готов к обычному полномуCI —28ноября2026

Кандидат1.10.63/code86/Room19/data2/security3. Featureapp-source`62ee82774572d08d7ebe8e8c2f036e1436c250de`. СвежийtargetCI46108755335/job140867333989/source7d7d2a3874e565cfa09b3b178f5c2312a7b2416eSUCCESS: **51тест/0отказов/0пропусков**, lint0errors/27warnings/2info,build7m18s. Artifact15248716716 ZIP356712bytesSHA256b074622b72903b543cbb73e171e4d5cab7f9cc397312cf7a43ec615b64898c76=API/HTML/lintXML. Все9новых(4pure/2prefs/3NativeUI) и прежниеtimer/clock/editor/settings/analytics/i18n прошли. ТриdurationNativePNGпобайтово равны осмотренным ранее:RUtoggle/ENlargefont320/analyticsminutes;этоНЕтелефон. Первыйlint-fix УЖЕприменён, HTTP429впредыдущемзапускеподтверждёнвсеми27HTMLcases;текущийуспехнаТОМЖЕsource, зависимости/таймауты/проверкине менялись. [Proof](duration-1.10.63-targeted-verification.json),[отчёт](duration-1.10.63.md),[инструкция](task-duration-guide.md).

ДалееобычныйmainpushfullCI: **914ожидается,покаНЕpass**. ПотомautoSigned/independentZIPAPIexact3SUMS/source/package/version/nondebug/actualapksignerv2v3/1signer/прежнийpin/newDEXautomatic_duration_units+Englishlabel/actualRoom19/freshminifiedreleaseGC. **89НЕзакрыт/63НЕinstaller**,последнийverified62/sourcecebd4e4/905/Signed46099655259/GC46100198012. До полного закрытия89→90 не переходить. Normalworkflowполныйcommandсохранён+duration-previewpath;diagnosticcommandНЕпереносится. App/testзаморозить приCI/mainнепродвигатьdocs-onlyдоSignedGCчтобыhead/sourceсовпали. ЛокальныхSDKGradlebuildsнет/RESTforce=false/не дублироватьживуюработу.

Аудит89:formatter boundedLong/negativeclamp/explicitRUEN/deviceLocaleunchanged;rawtiming/audio/schema/pinпобайтово совпадаютс62. Однаlifecycle/eventподписканатhemedroot+remember2fields,никакихновыхticker/jobs/wakelock;UIпоказываетfullprecisionaccessibilityприкомпактныхминутах. Следом90–97. ПослеВСЕХновые3hаудита→3hоптимизацииещё0ms/паузыожиданиеCIпростойперекрытияисключать. Батареяобязательна,68/iPhone/новыеИИне начинать/подписьне менять. Нижние записи исторические.

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

## Повтор2: инфраструктурный HTTP429

46108181195/job140865553214/source7d7d2a3874e565cfa09b3b178f5c2312a7b2416e:compilemain/testуспех,51tests/27fail/0ignored; все27отказов проверены поHTML и вызваны HTTP429 при загрузке org.robolectric:android-all-instrumented с официального repo.maven.apache.org, 26случаевSDK28/9-robolectric-4913185-2-i7,1случайSDK35/15-robolectric-12650502-i7. Телаэтихтестовне выполнялись. Это не кодовыйотказ, ноНЕуспешнаяпроверка/lintassembleнеподтверждены. ZIP46184bytesSHA256ad31c53d55ad43fc2288871b9dec43e3b94b26545ac8e4f4b484ad0b1de6c6e8=API/HTML проверен.

Запущен свежийCI46108755335/job140867333989 наТОМЖЕsource7d7d2a3 (app/test62ee827), без изменениякода/репозиториязависимостей/таймаутов/проверок. Результатещёнеподтверждён. Приповторном429нужнадиагностикаустойчивойзагрузкиофициальныхзависимостейссохранениемподлинностиSDK,нельзяобходитьтесты.

Текущий51/0/0/lint0error/assembleSUCCESS подтверждён для62ee827; этоНЕполный914/неSigned. 3финальныхNativePNGравныосмотренным. ОфициальныйMavenApacheendpointне менялся/кэшSDKне внедрялся;метаданныеSHA512готовилисьтолькоинспекционно.
