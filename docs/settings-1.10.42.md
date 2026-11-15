# Компактные настройки74 —1.10.42/code65/Room17

Компактная часть74 проверена в1.10.42/code65:714/0fail/0ignored/lint0error и установочный подписанныйAPK независимо проверен. ДополнениеQwen продолжается в следующем43 в рамках74. Подпись/Room17/data2/security3 не меняются.

## Подготовленная компоновка

Семь прежних групп/одна открытая сохраняются. Язык — компактная строка с раскрытием; заголовки групп и переключатели имеют48dp области нажатия с меньшими отступами. Содержание закрытых групп по-прежнему не композируется, в том числе поля API-ключа. Состояние группы/Back/initialGrouptransform сохранены.

Внешний вид: компактные строки темы/размера/стиля/цвета/шрифта/списка вместо постоянных наборов плашек. Все15вариантов AppFont остаются в прокручиваемом меню с выбранным состоянием; рендерится только выбранный пример, а не все семейства. Лицензии и сведения о шрифтах в раскрытии. Пояснение матового стекла видно по требованию при выбореGLASS; параметры прозрачности/матовости относятся к76 и пока не добавляются. Цитата остаётся доступной через прежнее действие.

Редактор: связанные параметры сгруппированы в два блока «Заметки и задачи»/«Инструменты редактора». Флажки приоритета перенесены из внешнего вида к задачам. Все переключатели, completedstyle/archive, камера/фото/таблицы/история/расширенное оформление сохранены. Длинные пояснения выполненных раскрываются по запросу. Хранение/SQL не меняется.

Голос: четыре компактных выбора движка/языка/жеста/структуризации. Краткое уведомление о передаче звука системному сервису показывается для онлайн, условие Android12+/модель/безfallback — для локального; подробная информация и инструкции в раскрытии. Аудиозапись/её ограничения/сохранность вложений остаются доступны. НастройкиИИ/оплаты и критические сведения резервного копирования сохраняются.

Дополнительный Qwen/DeepSeek: пользователь пока не ответил на вопрос о бесплатных моделях OpenRouter либо конкретных Qwen/DeepSeek с квотой/оплатой. Подключение не выполняется; независимая компактность74 разрешена. См.ai-free-options.md. Платныхвызовов/подменымоделейнет.

## Проверки

Подготовлены4NativeComposeкейса: размещение7групп на320×640/48dp, выборшрифта+selected+persistent+help,4голосовыепредпочтения+privacy/help, все8переключателейредактора+completedstyle.3NativeCanvasснимка вbuild/reports/settings-preview. Старые LanguageUiTestпути адаптированы: сначала открыть менюязыка, остальные проверки activity/resources/draft/undo сохранены. Старые SettingsGroups/AIUi/Settings/UiCatalog/VoiceSettings retained.

Запущен `/workspace/tools/settings42-checks.log`: CompactSettingsUi/SettingsGroupsUi/LanguageUi/UiCatalog/VoiceSettings/TransformUi/TransformSettings +lintDebug/assembleDebug. Результат пока не подтверждён. ПолныйCI714 ожидается только после локального аудита/проверок, подписанного42 пока нет. Не выдаватьdebug/unsigned, не объявлять тестированным физический телефон.

Первый прогон6m36s:25tests/1failure (24pass),0errors/skipped. SettingsGroups4/LanguageUi3/UiCatalog6/VoiceSettings3/TransformUi4/TransformSettings1 прошли; CompactSettings3/4. Отказ новогоVoiceUI —2узла «Русский»: язык интерфейса и пунктpopup; поиск ограниченпредкомisPopup, проверкиреальных4предпочтенийсохранены.

Аудит также уплотнил6вариантовчастотыавтобэкапа в одну строку выбора; предупреждениеобудалениилокальныхкопий/прежниедействия/шестьзначенийсохранены. Приbusy выборзаблокирован, popupзакрывается и сам не возвращается послеbusy. Добавлен5-йNativeUIкейсчастоты/запретаприbusy/сохранностипредупреждения. Повторныйsettings42-checks2.logпроверяетвсе26целевыхкейсов/lint/APK. Никакихbackupоперацийвновомкейсеневыполняется.

Повторный settings42-checks2.log SUCCESS7m23s:26/26tests0failures/errors/skipped, lint/assembleDebugуспех.5CompactSettingsUI/4SettingsGroupsUI/3LanguageUI/6UiCatalog/3VoiceSettings/4TransformUI/1TransformSettings. NativeCanvas просмотрен:7групппомещаютсяна320×640; вAppearanceнашёлсялишнедлинныйпанграммныйпример, заменён короткимлокализованнымобразцомсбуквами/цифрами(max2строки). Финальный settings42-preview-final.log проверяет5NativeSettings/6UiCatalog+lint/APK послеэтойправки. Остальные15неизменённыхцелевыхкейсовужепрошли. ПолныйCI714/подписанный42ещёожидаются.

Финальныйsettings42-preview-final.log SUCCESS5m49s:11/11 (5CompactSettingsUI+6UiCatalog),0fail/errors/skipped, lintDebug0error/15warnings/2info/assembleDebugsuccess. Остальные15неизменённыхкейсовпрошлиранее: всего26разныхцелевыхподтверждены.3NativeCanvasPNGпросмотрены; overview7группполностьюв320×640/48dp, Appearanceкороткийfontpreview/всевыборы, Voice4настройки/краткаяprivacy/help.

ВсеWIPc28c0cc/75e8947/d19dfddсохраненывcodex/settings-1.10.42. Отправляетсяобычныйfast-forwardmainдляполногоCI714(ожидается, покаНЕpass) и последующегоавтоSigned. ДополнениепоQwen/DeepSeekпо-прежнемуожидаетответа, ниодногоновогопровайдера/платноговызова/подменымоделинет.


2026-11-15T15:50UTC: Полный pushCI43731374968/job132864325436 выполняется для runtime source1b5504518314cb178306d87e31a8782374548090. Исходники заморожены до результатаCI/автоSigned, локальногоGradleнет. Пока42неустановочный.


## Итог независимой проверки42

## Проверенный выпуск 1.10.42/code65/Room17 — компактная часть74

Полный [CI43731374968](https://github.com/grigorywork/NoteLS/actions/runs/43731374968), job132864325436, runtime source`1b5504518314cb178306d87e31a8782374548090`: **714 тестов,0 отказов/пропусков**, lint0error/16warnings/2info, build20m20s. Report14169058870 ZIP SHA256`47030231b02b7c1fd712b16719ff1f74a0df8d40b773779e49a56c5ea181590e`=API; HTML/XML сверены,3NativeCanvasPNG побайтово равны локальным.

[Проверенный установочный APK42 вZIP14169334476](https://github.com/grigorywork/NoteLS/actions/runs/43733357193/artifacts/14169334476). Signed43733357193/identity132870485696/release132870526879 success; actualcheckout/READMEsource1b55045, runhead9facad8docs-only. Build/lint8m2s,13signingcontracts/0fail/8.625s. ZIP15412156bytes SHA256`8ecdbbde7b73e4bdb91289202297ce3e44d4a5c65540ea31c22c6e55885ae7f0`=API; ровно3APK/README/SUMS. APK27517639bytes SHA256`264323350e94f19edf8bd73ce562bc009cf70c9f4d59144c62c77680218b9b09`=SUMS/CI. Actualapksigner v2/v3/1signer/certpin, aapt2package/version/nondebug/min26/target35 проверены; DEXновый сsettings-marker. [Доказательства](settings-1.10.42-verification.json), [отчёт](settings-1.10.42.md). Room17/data2/security3/ключ/Secret прежние. Физического телефона/платныхAIзапросовнет.

74 ещё открыт только для дополнения пользователя: Qwen через Groq Free. Следующая кандидатная версия43/code66 в рамках того же74, потом75–82. Сначала свежиеmain/дерево/проверки; не повторять компактность42/73/72/69/71/70. [ПланQwen](qwen-1.10.43-plan.md), черновикdocs/design/qwen-1.10.43-draft.patch пока не применён. Подтверждены официальные FreeусловияGroq/Qwen, отдельныйключ вприложении; никакой молчаливой оплаты/другоймодели. Приложение не проверяет тарифGroq: Freeбесплатенвквоте, Developerплатный. Ожидание старого asyncответа не считать согласием на платныйAPI.

При выдаче использовать свежуюHTTPSZIPdownloadURL изGitHubartifacttool и постояннуюссылку выше: localworkspaceссылка у клиента не скачивается. BearerURL не коммитить. Подготовленный локальныйAPK `/workspace/library-files/NoteLS-1.10.42-code65-permanent-signature.apk`.
