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
