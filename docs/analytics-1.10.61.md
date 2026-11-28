## Этап87 закрыт28ноября2026 — проверенная1.10.61/code84/Room19

Source `2e1502ef5b9c544c2bdfb581d59e8b3019516a0b`. ПолныйCI46084992355/job140792370402:**893теста/0отказов/0пропусков**, lint0error/27warnings/2info,build20m8s. Signed46086854266/identity140798331610/release140798367781SUCCESS,build5m52s/13contracts0fail. Artifact15239850994 ZIP19019502bytesSHA2568d87deac2107ecfd10b6b4c0225263775f05b946ef473fcfbba2812166686591=API;APK31122287bytesSHA256a9aae4e2df26d16baa94d866953065f8e307a02389918f294a32af99e963c506=SUMS/CI. Source/package/version/nondebug/actualapksigner v2/v3/1signer/прежнийpin/новыеcompiledDEXmarkers/actualRoom19identity61703504e840fea926257eeba0d419e1независимопроверены. ТриanalyticsNativePNGравныосмотреннымtarget. FreshactualminifiedreleaseGC46087472043/job140800331017/artifact15239652519SUCCESS:ARTGCиlauncherAPI28/35,хешAPKтотже;нефизическийтелефон/небатарейныйзамер. [Доказательства](analytics-1.10.61-verification.json),[отчёт](analytics-1.10.61.md).

Следующий88:созданиезаписейввыбранномблокноте,кандидат1.10.62/code85. Реализация88ещёненачата;87неповторять. Затем89–97по[очереди](requests-2026-11-27.md); послеВСЕХновые3hактивногоаудита→3hисправлений/оптимизации,оба0ms/неначаты. Паузы/CIqueue/ожидание/простой/перекрытияисключать;старые1h+1hзаменены/историческиеокнанеповторять. Энергопотреблениеобязательно.68/iPhone/новыеИИ/платныеinferenceне начинать,подпись/Secretне менять. RESTforce=false/локальныхSDKGradlebuildsнет/app-testзамороженыприCI/не дублироватьживуюработу. Пользовательпродолжаетздесь,переноспозже.

APK: https://github.com/grigorywork/NoteLS/actions/runs/46086854266/artifacts/15239850994 —вZIPустановочный.apk/README/SUMS; временныйHTTPSURLобновитьпередфинальнойвыдачей. СтарыеworkspaceAPKссылкиуклиентанескачиваются.

# Этап87 — аналитика, кандидат1.10.61/code84/Room19

База603c51c (verified60/b1491b9,876/0). После нового разрешения пользователя основнойmain получил только очередь/документациюc7dbde4, создана feature codex/analytics-1.10.61. ЛокальныхSDK/Gradle builds нет. Реализация пока НЕпроверена/НЕустановочная;87 не закрыт.

Добавлены7/30 локальных календарных дней и предыдущие непересекающиеся окна, исключение будущего timestamp (включая сегодня), абсолютная разница. Просрочка толькоvalid taskDueAt<now незавершённой задачи. Публичные SQLscalar-проекции дополнены syncId/taskDueAt; Notebookmetadata скрывает private/diarynames, isDiary добавлен сdefault. СхемыRoom19/data2/security3 прежние.

Drilldown текущий/предыдущий/активные/просроченные:20строк, точное число, stable sort, clamping при удалении/изменении количества, новый page сбрасываетscroll. Расчёт наDefault из одного privacy/scoped набора. UIкеш проверяет scope/period/request/excludedIDs, свежий open проверяетNoteAccess/syncId/flags, защищённое/diary не допускается даже после разблокировки. Свежая проверка повторяет принадлежность текущемувидимомунабору послеIO. Старые графики/выбор блокнотов сохранены, новыйблок доemptycompletedreturn.

Часы:foregroundLifecycleStart/systemTIME/DATE/TIMEZONE+один ближайшийdeadline/полночь; backgroundjobs/постоянногоопроса нет. Application-only constructor сохраняется черезJvmOverloads, проверяетсяAndroidViewModelFactory. Новые pure9/Room4/NativeUI4 подготовлены; ожидаемыйполный893 покаНЕpass. NativeUIожидаетположительнуюпубликацию и затем строгиеassertions; таймауты существующихтестов не менялись. Первая целевая проверка описана ниже; полный893 пока не выполнялся.

После87 очередь88–97 из requests-2026-11-27.md. Новоеокно3hаудита→3hоптимизации ПОСЛЕВСЕХ, оба ещё не начаты, ledgerpost-queue-2026-11-27-state.json. Старые1h+1h заменены; историческиеокна не повторять. Постоянныйключ/Secret не менять;68/iPhone/новыеИИмодели не начинать.

В ближайшую foreground-границу включён будущий timestamp завершения: после его наступления сравнение обновляется одним событием, без опроса. В NativeUI замороженный clock/futuretoday+1h, отдельный pureкейс проверяет точное исключение и переход.

## Первая целевая проверка — отрицательный результат

Run46082648047/job140784940993, diagnostic source9002530527c07992337034ee629e4ded0e359f39, app-sourcebe86a9724059d3f13f9ef4200d50c98e6aaf8cfe. Компиляцияmain/test успешна;49тестов/4отказа/0пропусков, build7m26s. Все4отказа — начальное ожидание счётчика в новыхNativeUI; остальные45, включая9pure/4Room/factory и старыеUI, прошли. Lint/assemble после отказа не подтверждены. ZIP отчёта15237921685 SHA256b03f6997848d5b9561a9fd0c20bf997987942ac28bf01ec10f283fa50eafbf2b независимо совпал сAPI.

Диагностика: clickable Row/Button объединяет count в помеченный testTag узел, прежний helper искал только потомка. Helper теперь допускает сам тегированный узел или потомка с ТОЧНЫМ числом; прежние15s/всеassertions сохранены, production не менялся. Это гипотеза до повторного целевогоCI, не заявленный успех. Пользователь отменил перенос прямо сейчас: продолжать здесь, перенос позже.

## Повторная целевая проверка успешна, НЕ полныйCI

46084088740/job140789503700/source d29a22c5e00e73b94f5e731c74f608d840946866:49/0fail/0ignored; lint0error27warnings2info, test/lint/assembleDebugBUILD SUCCESS7m9s. ПровереныZIP/API119140bytes/c68464fae2ef31e4b807c6eae075e18e0135678b372ab20dcd07b35621a2be92/HTML/lintXML. Новые9pure/4Roomfactory/4NativeUI прошли. Диагностическийsource отличается от featuref425049 толькофильтромкомандыworkflow; вmainобычныйполныйcommand. ТриPNGосмотрены, подписи/кнопки/пагинациядоступныRU/ENlargefont320dp;нефизическийтелефон. Room19schemaиcertificatepinпобайтовосовпадаютсverified60.

Аудит87: privacy SQLнечитаетbody/tables/attachments; scalar metadata masks private/diarynames; cachedprivacy/filterguards/freshNoteAccess+syncId, безwrites; nearestforegrounddeadline/midnight/futurecompletion/timezoneбезbackgroundpolling. Старыеcompletedarchive/notebookscopeсохранены. Полный893/lint/APK→Signed→независимыйAPKactualRoom19→actualreleaseGC ещёпредстоят;87НЕзакрыт. Guideanalytics-comparison-guide.md.
