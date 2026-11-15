# Этап75: цитатник — кандидат1.10.44/code67/Room18

Начато после полностью проверенного43/code66/Room17 (732/0/Signed43746004909). Свежийmainc4b8489, дерево чистое, Gradle/CI/других исполнителей нет. Ключ/Secret/data2/security3 не меняются.44 полностью проверен:753/0/lint0error и Signed43761100021/artifact14180464095 независимо сверены.75 закрыт. Новый номер этапа не добавляется.

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


## Итог локального аудита

Локальные проверки подтверждены: quotes44-checks2.log50/50/7m45s (lint0error16warnings2info), quotes44-deadline-compat.log2/2/4m6s, финальныйquotes44-ui-final.log14/14/6m54s/lint0error15warnings2info/assembleDebug.14финальныхперекрываютсяспредыдущими: **52разныхцелевыхкейса**, не66.21новыйкейс, старыеasserts/таймаутынеослаблены. НовыйModifierParameterwarningисправленпорядкомoptionalparam, NativePNGпобайтовонеизменились/осмотрены. Schema18identityb1c6c07627d80657a69a8afd3c98f6aa/SHA256f6f988c94c5342796142e29df703f6d6e27bd0fc664a76b8ac8939dd006c1973; все5прежнихRoomentitiesпобайтовоструктурноравныschema17, добавленытолько2таблицы. Новыеданныеbackuprestore/legacy/stale/rollback/capacity/UI/RUENпроверены, реальноготелефонанет. ОтправляетсяmainдляполногоCI753ожидаемого/autoSigned; покаНЕpass/НЕinstallable44,75не закрыт. Последнийverified43. Не менятьmain/test/не запускать второйGradleво времяпроверки.


2026-11-15T20:37UTC:75runtime source70b5a4ebb26888a769e2c85f3f258caa6de7a3a0 УЖЕmain. Полный pushCI43759092671/job132949595069 in_progress. ВсеWIPcodex/quotes-1.10.44 включены. LocalGradleнет/дерево чистое/main/testзамороженыдоCI+Signed+независимойверификацииAPK.753ожидаемыйНЕpassпока/44НЕinstallable. Последнийverified43.


2026-11-15T21:00UTC: полныйCI43759092671/job132949595069/source70b5a4ebb26888a769e2c85f3f258caa6de7a3a0 SUCCESS=753/0fail/0ignored/lint0error16warnings2info/build21m7s. Скачанreport14180141775 ZIP613136bytes SHA256d8b9fa6149ae31900712f0ddb8760579e79142a44a5c7d27b5d5815a7ec78bc0=API/HTMLXMLсверены;3quoteNativePNGпобайтоворавнылокальным. АвтоSigned43761100021/identity132955891683SUCCESS/release132955925460in_progress. Пока44НЕпроверенныйinstallable/75НЕзакрыт;дождатьсяSignedи независимойпроверкиAPK. Main/testне менять/второйGradleне запускать. Последнийverified43,ключ/Secretпрежние.


## Итог независимой проверки44

## Проверенный выпуск1.10.44/code67/Room18 —75 закрыт

Цитатник: своицитаты/автор/источник/CRUD/закрепление, boundedUTF8JSON/TXTимпорт сpreview/дубликатами/атомарностью; нижняяцитатаотдельноотпрокрутки/ограничениевысоты/крупныйшрифт. Прежние5Roomentitiesточноравны17, новые2таблицыдобавлены17→18миграцией;зашифрованныйbackup/restore/legacy/stale/capacity/rollbackпроверены. [Инструкция/примеры](quote-library-guide.md), [отчёт75](quotes-1.10.44.md), [машиночитаемыедоказательства](quotes-1.10.44-verification.json). Своябиблиотекапокалокальная+backup,WiFiсинхронизациюцитатнеобъявлять.

Полный [CI43759092671](https://github.com/grigorywork/NoteLS/actions/runs/43759092671)/job132949595069, runtime source`70b5a4ebb26888a769e2c85f3f258caa6de7a3a0`: **753теста/0fail/0ignored**, lint0error/16warnings/2info, build21m7s. Скачанreport14180141775 ZIP613136bytes SHA256`d8b9fa6149ae31900712f0ddb8760579e79142a44a5c7d27b5d5815a7ec78bc0`=API; HTML/XMLсверены;3quoteNativePNGпобайтоворавнылокальным.

[Проверенный установочный APK44 в ZIP14180464095](https://github.com/grigorywork/NoteLS/actions/runs/43761100021/artifacts/14180464095). Signed43761100021/identity132955891683/release132955925460SUCCESS; actualcheckout/READMEsource70b5a4e, runhead8615297docs-only. Build/lint13m55s,13signingcontracts0fail12.106s. ZIP15444377bytes SHA256`3e1a0703964fd3e0951f68ad1a984bcc8c98bea53d19625ab24680c67a907bc8`=API; ровно3уникальныхAPK/README/SUMS. APK27550407bytes SHA256`0354745524cc2106e57ca14e6dfb8b5f365573c25d8f7bad6755ceaeee9ad68f`=SUMS/CI. Actualapksigner v2/v3/1signer/certpin и aapt2com.notessync.app/1.10.44/67/nondebug/min26/target35проверены. DEXновыйсcustom_quotes/quote_library_state/UIиactualRoom18identity`b1c6c07627d80657a69a8afd3c98f6aa`. Schema18JSONSHA256`f6f988c94c5342796142e29df703f6d6e27bd0fc664a76b8ac8939dd006c1973`.

Room18/data2/security3, постоянныйключ/Secretпрежние. Физическойустановки/телефонных/батарейных/реальныхAIгенерацийнет. ЛокальныйAPK `/workspace/library-files/NoteLS-1.10.44-code67-permanent-signature.apk`. ПроверятьвесьAPKпередкаждойвыдачей; debug/unsignedневыдавать.

75закрыт, следующие **76 — настраиваемое матовое стекло, затем77–82**.76ещёНЕначат/source45/code68ЕЩЁнет. ВсеWIPcodex/quotes-1.10.44вmain, не повторять75/74/73/72/69/71/70. Свежийmain/дерево/ветки/Gradle/CI/активнуюработупроверитьпередправкой;не reset/clean/force-push/не терятьизменения/не запускатьвторогоисполнителя/Gradle/не редактироватьmain/testпри проверке. Послеэтапааудит/исправления/тесты/lint/APK/полныйCI/Signed/независимаяверификация/журнал/main.68iPhone/новыхномеровэтаповненачинать.

Выдача: freshHTTPSdownloadZIPизGitHubartifacttoolвФИНАЛЕ +постояннаяссылканадартефактвыше. Абсолютныйworkspaceпутьуклиентанескачивается/инструментаотдельногоAPKuploadнет;не называтьlocalpathвложением. Архивраспаковать/установить.apk. BearerURLживётминуты/не сохранятьвGit. Тажеautomationразрешенадляпродолженияследующейочередибездублей.
