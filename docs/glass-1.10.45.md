# Этап76: настраиваемое стекло — кандидат1.10.45/code68/Room18

Начато после полностью проверенного44/code67/Room18 (753/0/Signed43761100021/artifact14180464095). Свежийmain60cb93b, дерево чистое, активныхGradle/CI/другогоисполнителя нет. Постоянныйключ/Secret/Room18/data2/security3 не меняются.45 пока не реализован/непроверен/неустановочный.76 существующий этап, не новый номер.

Текущийстиль: полупрозрачныеM3surfaceContainer*+статичнаяаврора. Настоящегоblurпока нет. Реализация:

- GlassAppearance в локальныхprefs: transparency(0..1,default0.5 сохраняетпрежниеслои), frost(0..1,default0 бездополнительногоblur), tint(8согласованныхсlight/darkпалитр), backdrop(AURORA/BANDS/SOLID). Без миграциибазы/ключей/заметок. Clamp/finite/unknown/corruptprefs/default/reload, одинсогласованныйsave.
- Контролы только для GLASS; отдельный диалог с локальным draft, preview/reset, Save/Cancel/Back. Поля не пишут prefs во время sliderdrag. Blurpreview обновляется по окончании жеста, чтобы не пересоздавать эффект каждый кадр. Reset меняетdraft, доSaveнеpersist. Прежние тема/акцент/шрифт/классическийстиль не теряются.
- Прозрачность меняет реальныйalphaпанелей, не имитируетblur. Повышенные минимумыalpha для плотныхслоёв/меню; впрозрачныхокнах безсобственногофона минимальнаяплотностьвыше длячитаемости. Пользователю объясняется это ограничение.
- Матовость размывает ТОЛЬКО собственныйдекоративныйфон: Android12+/SDK31+ Compose blur/RenderEffect, boundedradius≤18dp, default0. Текст/контент находятся отдельным siblingслоем. SOLID не запускает бесполезныйblur. Android8–11/SDK26–30 используют статичныймягкийградиент, не выдаватьегозасистемноеразмытие. В прозрачныхQuickNote/WidgetSettings сglassBackdrop=false своегофона/blurнет; настройкипанелейприменяются.
- Фон статичный AURORA/BANDS/SOLID, theme-adaptivecolorпалитры. drawWithCache дляBrush/геометрии, нет анимаций/таймеров/фоновыхзадач/постоянногоопроса. Материал/текстконтрастпроверитьпоцветовымкомпозициям иNativeснимкам light/dark, слабыйтелефон/реальнуюGPUскорость не обещатьбезизмерений.
- ПрименитьappearanceвMainActivity/QuickNoteActivity/WidgetSettingsActivity и helpersColor.glassified/glassBorder. Room18/table18/protocoldata2/security3/постояннаяподпись прежние.
- Purepolicy/palette/alpha/clamp/contrast/SDKtests, prefsreopen/default/corrupt/onlyonSave, NativeUI320dp/lightdark/preview/selected/background/slider/cancel/reset/RUEN/Back; старыеGlassTheme/Accent/Typography/SettingsGroups/UiCatalog сохранить. Последнийустановочный44,45толькопослеполногоCI+Signed+независимойверификацииAPK.

Следующий77 — оформление таблиц, затем78–82.68iPhoneотложен/новыхномеровэтаповне добавлять. НикакихреальныхплатныхAIвызовов.

## Реализация и незавершённая проверка

Checkpoint f0f8fc5 сохранён в codex/glass-1.10.45. Новый GlassAppearance, 5 pure/3 prefs/4 NativeUI теста; CI сохраняет glass-preview. Первоначальная компиляция main успешна, Gradle glass45-checks.log пока выполняется. Ни тесты, ни45/installable не объявлены успешными до фактических отчётов. SharedPreferences settings сохраняет только локальное оформление; Room18 и формат backup/sync прежние.

Первый glass45-checks.log остановился на компиляции нового теста: RuntimeEnvironment.getApplication в этой версии Robolectric не generic. Исправлен вызов с явным типом Application; приложение компилировалось успешно, тестовые результаты этого прогона не заявлены.

Второй glass45-checks2.log: **36тестов/5отказов/0errors/skipped**, не успех. Обнаружен реальный запас контраста4.28<4.5 при консервативном наложении всех четырёх пиков в тёмной синей палитре: альфы тёмных пятен уменьшены, порог4.5 сохранён. Ещё4 отказа — тестовый harness: singleton SettingsRepository между разными Application теперь сбрасывается как в существующих GroqSettingsTest, добавлено реальное повторное чтение prefs; PixelCopy callback не работает в Native Robolectric, заменён рисунком реального Dialog decorView через NativeCanvas; Back вызывается через настоящий ComponentDialog dispatcher вместо клавиши в Compose child. Assertions данных, Cancel/Back и доступности сохранены, таймауты не увеличены. Повторяются те же36/lint/assemble.

Третий glass45-checks3.log: **36/36/0fail/errors/skipped**, lint0error/15warnings/2info, assembleDebug SUCCESS,7m40s. Все5 pure/3prefs/4UI/9старыхGlassTheme/5Compact/4Groups/6UiCatalog подтверждены. Реальный prefs reload после сброса repository проверен. Два NativeCanvas снимка осмотрены: текст чёткий, ползунки/выборы/Save/Cancel доступны. Для дополнительной проверки ограниченной высоты тёмный EN/largefont кейс уточнён на дисплей320×640, запускаются4 UI повторно. Это не новые4 случая поверх36, а пересечение; полный CI/Signed пока не выполнены,45 НЕinstallable.

## Локальное завершение и аудит

Финальный glass45-ui-final.log **4/4/0fail/errors/skipped**, lint0error/15warnings/2info, assembleDebug SUCCESS4m12s. Это те же4 UI из36, итого36 разных целевых случаев, не40. Тёмный EN/largefont Native снимок получен на реальном тестовом окне320×640; Save/Cancel находятся в окне, помощь/сброс прокручиваются. Светлый draft и тёмный снимки осмотрены, сохранены docs/design/glass-1.10.45.

Аудит: диалог не сохраняет поля доSave; reset меняет толькоdraft; Cancel и Android ComponentDialog Back сохраняют прежние prefs. Сохранение4полей однимeditor, неверные типы/enum/NaN/Infinity читаются независимо и безопасно, повторное создание repository сохраняет результат; CLASSIC не удаляетglassprefs. Preview обособлен, текст находится вне слоя blur. В API<31 нет попыток RenderEffect, SOLID/безсобственногофона эффект не создаётся. Alpha и контраст проверены, Primary/textslot не подменяются. DrawWithCache кэширует геометрию/кисти; нет таймеров/анимаций/listeners/снимковфона/фоновыхзадач. Все остальные параметры/7групп/initialGroup/Back сохранены. Room18/schemas/data/sync/backup/AI/signingcode не менялись. Физические GPU/телефон/батарея не измерены; SDK28 Native не доказывает аппаратныйblurAPI31.

ПолныйCI765ожидаемый и autoSigned ещё не подтверждены.45 не выдавать до независимой проверки;76 не закрыт.

Локальные PNG SHA256:
```json
{
  "light-draft-320.png": "f7d0afb656b7fee1bcfd1b2b2ec06b0b4ae5ca91cdce2734f135a4873f80e96a",
  "dark-large-320.png": "216a071134ac6575053ea5296ae45e52540c2f84d46cdf09f1d1bb2c0a59b5a8"
}
```

Полный pushCI43771941590/job132989559112 запущен для runtime source d3aed90a2dee41f8d684948d5be12938518667c5. Все checkpoint включены в main. Исходники заморожены до полногоCI+autoSigned+независимой верификации. Пока45 не проверенный installable.

## Полный CI подтверждён, Signed ещё выполняется

CI43771941590/job132989559112/source d3aed90a2dee41f8d684948d5be12938518667c5 SUCCESS:765/0fail/0ignored/lint0error16warnings2info/build28m52s. Скачан и независимо проверен report14185766665:704069bytes, SHA25604f2cbfc91f3e5e75850e42c34b18c965a827c533ef6f0e6d5d253c6a7af328e=API. Helper сверил GradleHTML/XML, два glass PNG побайтово равны локальным ранее осмотренным. АвтоSigned43774701037/identity132998241930SUCCESS/release132998276354in_progress. До его завершения и независимогоAPKcheck45НЕinstallable/76НЕзакрыт, source заморожен. API runhead51b7718 docs-only, actualcheckout/README ожидается d3aed90.
