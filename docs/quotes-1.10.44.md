# Этап75: цитатник — кандидат1.10.44/code67/Room18

Начато после полностью проверенного43/code66/Room17 (732/0/Signed43746004909). Свежийmainc4b8489, дерево чистое, Gradle/CI/других исполнителей нет. Ключ/Secret/data2/security3 не меняются.44 пока не реализован/не проверен/не установочный. Новый номер этапа не добавляется.

Решения до реализации:

- Нижняя цитата вне прокрутки навигации. Отдельная прокручиваемая область меню; footer до180dp, обычно35% доступной высоты; для крупного шрифта резерв минимум одной строки, навигации остаётся≥96dp, до4строк с многоточием, доступ к цитатнику по нажатию. Стандартные windowInsets ModalDrawerSheet сохраняются, навигация не перекрывается, размеры/цвета/шрифт прежнего оформления поддержаны.
- Своя библиотека в Room: custom_quotes(id,text,author,source,fingerprint UNIQUE) и quote_library_state(id=1,pinnedQuoteId nullable FK/SET_NULL). Room17→18 только добавлением2таблиц/индекса, прежние заметки/данные не меняются. Библиотека/закрепление включаются в обычный зашифрованный DBbackup и атомарныйrestore; старые архивы мигрируют вstaging с пустой библиотекой. Data/securityprotocol не менять, WiFiсинхронизацию своейбиблиотеки пока не заявлять.
- Текст≤3000символов, автор≤160, источник≤240, библиотека≤2000записей. ИдентичностьSHA256 поNFC/нижнемурегистру/схлопнутымпробелам текста+автора с разделителемNUL (NULвполяхзапрещён), источник не участвует: дубликат сохраняет прежние данные.
- Импорт UTF8 (BOM разрешён),≤2MiB,≤500цитат. JSONмассив объектов text/author/source; textобязателен, author/sourceопциональны. TXT:однацитатавстроке, авторпослеTAB, источниквтретьейTABколонке; пустыестрокиигнорируются. СтрогийUTF8, boundedread/JSONdepth, типы/неизвестныеполя/ошибочнаязапись отвергают весьфайл. Предпросмотр до импорта, счётчикиновых/дублей; повторнаяпроверка текущейDBвтранзакции/лимиты/rollback, без частичнойвставки/сетевыхзапросов.
- Экран сохраняет встроенные цитаты/авторов/цитатудня/copy/share/saveasnote. +добавляет, загрузка черезSAF, фильтр«Мои», изменения/удаление/закрепление через компактное⋮. Удаление подтверждается. Своя закреплённая цитата отражает редактирование, удаление/снятие возвращает прежнийmanualcustomText/случайную.
- Прежний текст в настройках оформления не терять. Если пользователь меняет именно manualcustomText — снять DBзакрепление; изменение только шрифта/цветов не снимает закрепление. Previewоформления показывает текущую DBцитату, пока manualтекстне изменён.
- NativeUI320dp/маленькая высота/крупныйшрифт: footerне движется при прокрутке, навигация доступна; CRUD/importpreview/отмена/дубли/ошибки/Back/RUEN. NativeRoom17→18+legacy1migration/реальныйencryptedbackup/restore/rollback цитат вместе с заметками, parserbounded/malformed tests. Не считать локальныецелевые полнымCI; закрыть75толькопосле полногоCI+Signed+независимойпроверкиAPK.

Следующий76 — стекло, затем77–82.68iPhoneотложен. Последнийустановочный43, APK44 не выдавать до проверки.


2026-11-15:75вкандидате44/code67/Room18подготовлен:2Roomтаблицы+17→18migration/backuprestoreallowlist, UTF8JSON/TXTbounded/cancelledstream/depth/500/2MiB/2000/duplicates/atomictransactions/staleguard; pinnedfooterseparatescroll/boundedheight/largefont/navigation96dp; CRUD/SAF/preview/ownfilter/менюpin/delete/старыеbuiltins; manualcustomsettingsfontpreservesDBpin/textchangeclears. ПокаНЕскомпилирован/НЕпроверен/неinstallable44. Исходникисохраняютсявcodex/quotes-1.10.44;UIрегрессииещёготовятся, Gradleещёне запущен, схема18jsonпоявитсяпослеKSPинеобходимакоммиту. Последнийverified43.


К проверке подготовлены21новыйкейс:7parser/6NativeRoom/3encryptedbackup/5NativeUI. Добавлена проверкаполей/идентичностей/счётчика quoteDBsnapshotдоизмененийliveприrestore, отложенныйотчётsnackbarне держитbusy, отменаfileIOзакрываетInputStream. NativeCanvasожидаютсяbuild/reports/quotes-preview, включенывCIartifact. Запускаетсяquotes44-checks.log, schema18jsonдолжнабытьсохраненапослеKSP. ПокаНЕpass/НЕinstallable.


2026-11-15:quotes44-checks.log main/test Kotlin compilation SUCCESS, выполняются целевые тесты. fun import вKotlinдопустимкакsoftkeyword, переименовыватьбезнуждыне требуется. KSPсоздалschema18(7таблиц), онасохраняетсявcheckpoint. ПокаНЕtestPASS/неCI/неinstallable44. ЗапущенровноодинGradle/session68586, исходникиmain/testзаморожены.


Первыйquotes44-checks.log6m52s:50тестов/2fail(48pass)/0errors/skipped. Все5NativeUI/7parser/3encryptedquoteBackup/17→18migration/legacy1→18/старыеencryptedBackup7/BackupManager3/SettingsGroups4прошли. Отказы:capacitytestseedсинхронныйrunInTransactionнаMain,заменёнsuspendwithTransactionбезallowMainThread;UiCatalogнехваталEN«Редактировать»,добавленEdit. Assertions/timeoutнеослаблялись.3NativePNGосмотрены:footerне движется/крупныйшрифтсellipsis/навигациядоступна/свойцитатникCRUD. Передправкойбылкраткийexectransportdisconnect,исходникинеизменились;statusrunning/connected,повторныйreadработает. Повтор50/lint/assemble. Пока44НЕполныйCI/неinstallable.


Аудит совместимости нашёл старый assertEquals(17, db.openHelper.readableDatabase.version) в app/src/test/java/com/notessync/app/tasks/TaskDeadlinePersistenceTest.kt:61 (миграция16→текущая). После окончания живого quotes44-checks2 его нужно обновить на ожидаемую18, сохраниввсепроверкиreminder/timer/day/content/implicitdeadline; проверитьэтотклассдополнительно. Пока main/testНЕменять, TaskDeadlinePersistenceне входилвпервые50. ДругихжёсткихassertDBversion17/addMigrationsвтестахне найдено. Не отправлять753fullCIдoadaptэтогопроверочногоожидания.


quotes44-checks2.log BUILD SUCCESSFUL7m45s:50/50/0fail/errors/skipped,lint0error/16warnings/2info/assembleDebug.21новыхкейсовподтверждены,3NativePNGосмотрены. АдаптированстарыйTaskDeadlinePersistence schemaexpected17→18безпотериassertsметаданных,запускаетсяотдельнаяпроверкаэтогокласса. ПокаНЕполныйCI/НЕinstallable44.


quotes44-deadline-compat.log SUCCESS4m6s:2/2безfail/errors/skipped,lint0error/16warnings/2info/assembleDebug. Всего52разныхцелевыхкейса подтверждены. НовоеlintпредупреждениеModifierParameterустраненопорядкомoptionalmodifierпередindexвDrawerQuoteCard, вызовfooterпереведёнвименованныепараметры;финальные14(5NativeUI/3DrawerQuote/6UiCatalog)+lint/assemble выполняютсяquotes44-ui-final.log. Семантика/данныеUIнеменялись,всестарыеassertsсохранены. Пока44НЕполныйCI/НЕinstallable.


[Инструкция и примеры JSON/TXT](quote-library-guide.md) описывают фактический формат/поля/лимиты/отмену/своюобластьдедупликации/backuprestore/локальность.
