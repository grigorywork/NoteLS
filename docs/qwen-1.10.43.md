# Qwen через Groq — дополнение74,1.10.43/code66/Room17

Версия43 независимо проверена:732/0/lint0error и Signed43746004909/artifact14174757856.74 закрыт, следующий75; ключ/Room17/data2/security3 прежние. После закрытия этого дополнения74 следующий75.

Qwen добавлен рядом с прежними LOCAL_RULES/OPENAI. Компактный выбор провайдера; fixed model qwen/qwen3.8-27b, fixed Groq HTTPS ChatCompletions endpoint, отдельные alias/prefs/AAD/vault безfallback в OpenAI. Старые OpenAIalias/prefs/model совместимы. Удаление одного APIключа не удаляет другой, OSbackupисключаетоба; обычныеархивы приложения содержат только данныеDB/вложения, vault не экспортируется.

Freeусловия видны: бесплатен только аккаунт на тарифеFree в квоте, приложение не проверяет тариф, сDeveloperаккаунтом вызовы могут оплачиваться. Получатель выбранного текста Groq, именованнаякнопкаSend, официальныеключ/условия/данные/лимиты по нажатию. Никакого автоupgrade/автоповторов/другоймодели. Providererrorпоказывает whitelistдиагностику, исходныйтекст/ключ не копируются в ошибку.

Прежние INPUT16000/OUTPUT32000/RESPONSE65536/error8192/2048outputbudget/таймауты/отмена/явныйpreviewApply/свежийдоступ/сохранностьоригинала. Groq reasoningnone; принимается только stop/assistanttext, partial/refusal/tools/ошибочныйJSON/UTF8 отвергаются. Presentedconfig передаётся при привязке провайдера, поэтому смена конфигурации до/во время чтенияключа/ответа не перенаправляет черновик другому сервису. Неотправленный APIключ очищается при смене провайдера/ON_STOP.

18новых meaningful оффлайнкейсов:8HTTP,3vault,2session,1settingspersist,4NativeUI(RU/EN/recipient/SendApply/переключениеключей/очисткаGroq/ошибкаквоты). Старые AI/EditorTransform/SettingsGroups/UiCatalog будут проверены вместе. Никакой реальной генерации/платныхвызовов/физическоготелефона. План/официальныеисточники:qwen-1.10.43-plan.md,ai-free-options.md.


Первый qwen43-checks.log завершился6m59s:50тестов/1fail,49pass/0errors/skipped. GroqSecrets3/3,GroqSession2/2,GroqSettings1/1,QwenNativeUI4/4; GroqHTTP7/8. MalformedUTF8 классифицировалсяNETWORK, исправленнаPROVIDER безослабленияassertion. NativePNGбылс прозрачнымфоном тестовогоMaterialTheme; подтвержденоalpha212216/230400нулевыхпикселей. ОболочкаNativeснимказамененаSurface,продуктовыйфоннеизменялся. Требуетсяповтор50/lint/assemble и просмотрopaquePNG; пока43неполныйCI/неinstallable.


## Итог локального аудита и повторных проверок

Повторный qwen43-checks2.log завершился BUILD SUCCESSFUL8m8s:50тестов/13классов/0fail/errors/skipped, lint0error/15warnings/2info, assembleDebug.18новыхкейсов+32прежних подтверждены. NativeCanvas320×720полностьюopaque(230400alpha255), визуально проверен: provider/Freeусловия/model/key/actionsпомещаются. PNGf58d167c0cc2896faf24c6a5c6fa4aba62a595f95be605ae87245744672533a1 сохранён docs/design/qwen-settings-1.10.43.png. Аудит: fixedorigin/model, redirectsfalse/noSDK/noauto/inference, отдельныеvault/prefs/alias/AAD и закрытаяbackupallowlistDB/attachments; старыеOpenAIsettings/keys/preview/Backпроверены. Source43 готовится кобычномуfast-forwardmain/полныйCI732ожидается(покаНЕpass)/autoSigned. Последнийinstallable42. Не менятьmain/test/не запускать второйGradle во времяпроверки. РеальныхAIвызововнет.


2026-11-15T17:58UTC: runtime sourceaf01d51ee59756bcdf060123a9b4584c1541c789 уже вmain. Полный pushCI43744048331/job132903324579 in_progress. Пока732ожидаемыйНЕpass/неinstallable43. ВсеWIPcodex/qwen-1.10.43включены, дерево чистое; локальногоGradleнет. main/testзаморожены доCI/Signed/проверкиAPK.


[Инструкция настройки Qwen RU/EN](ai-qwen-setup.md) сверена с фактическими UIстроками/источником редактора: вход через нижний + (не ⋮), выделение либо весьтекст, фиксированная модель/Freeусловия/явныеSendиApply.


2026-11-15T18:19UTC: полныйCI43744048331/job132903324579/sourceaf01d51ee59756bcdf060123a9b4584c1541c789 SUCCESS:732/0fail/0ignored/lint0error16warnings2info/build19m9s. Скачанныйreport14173967928 ZIP567029bytes SHA256b8acae7369ad1c470fe6f63cd30984d717495e98ef4afd171baf0630cd3ba0c0=API; HTML/XMLсверены. Все4settings-previewPNG побайтоворавнылокальным, QwenPNGf58d167c0cc2896faf24c6a5c6fa4aba62a595f95be605ae87245744672533a1. АвтоSigned43746004909:identity132909328311SUCCESS/release132909360292in_progress(Buildandlintrelease). Пока43НЕпроверенныйinstallable/74НЕзакрыт;дождатьсяSignedи независимойпроверкиAPK. Последнийverified42. Main/testне менять/второйGradleне запускать. Ключ/Secretпрежние.


## Итог независимой проверки

## Проверенный выпуск1.10.43/code66/Room17 —74 закрыт

Qwen добавлен через **Groq** рядом с прежними LOCAL_RULES/OpenAI: компактный выбор, отдельные ключи/vault, fixedмодель, явныеSend/preview/Apply, без автоматической смены тарифа/модели/сервиса. Бесплатно только на аккаунтеGroqFree в квоте; Developerможет тарифицироваться, приложение не проверяет тариф. [Инструкция RU/EN](ai-qwen-setup.md), [отчёт74/Qwen](qwen-1.10.43.md), [машиночитаемые доказательства](qwen-1.10.43-verification.json).

Полный [CI43744048331](https://github.com/grigorywork/NoteLS/actions/runs/43744048331)/job132903324579, runtime source`af01d51ee59756bcdf060123a9b4584c1541c789`: **732 теста/0fail/0ignored**, lint0error/16warnings/2info, build19m9s. Скачан report14173967928 ZIP567029bytes SHA256`b8acae7369ad1c470fe6f63cd30984d717495e98ef4afd171baf0630cd3ba0c0`=API, HTML/XML сверены;4settings-previewPNG равны локальным, QwenPNG`f58d167c0cc2896faf24c6a5c6fa4aba62a595f95be605ae87245744672533a1`.

[Проверенный установочный APK43 внутри ZIP14174757856](https://github.com/grigorywork/NoteLS/actions/runs/43746004909/artifacts/14174757856). Signed43746004909/identity132909328311/release132909360292SUCCESS; actualcheckout/READMEsourceaf01d51, runheadaff1d7edocs-only. Build/lint10m5s,13signingcontracts/0fail/10.276s. ZIP15414119bytes SHA256`6b3f6415a5d75d2758e16c894f77323564fcc89014327899988e7b1c3a523027`=API, ровно3уникальныхAPK/README/SUMS. APK27517639bytes SHA256`f55e2bb10763563e1e360523b378c67646bdfc87e7f709442544495c5677ee9d`=SUMS/CI. Actualapksigner v2/v3/1signer/certpin, aapt2com.notessync.app/1.10.43/66/nondebug/min26/target35 проверены. DEXновыйсGROQ_QWEN/model/apiGroq/vault/Sendmarkers.

Room17/data2/security3/постоянныйключ/Secret прежние. РеальныхAIгенераций/платныхвызовов/физического телефона не было; Qwenпроверен в fakeHTTP/UI, доступность конкретного аккаунта/тарифа не проверена. ЛокальныйAPK `/workspace/library-files/NoteLS-1.10.43-code66-permanent-signature.apk`.

74 полностью закрыт, компактность42 и дополнениеQwen43 не повторять. Следующий **75 — закреплённая цитата и свой цитатник**, затем76–82;75 ещё НЕначат, source44/code67ещёнет. ВсеWIPcodex/qwen-1.10.43 включенывmain, draft.patchприменён/удалён — не применятьповторно. Новыхэтапов/68iPhoneне начинать. Передправкой fetchmain/проверкатекущихветок/дерева/Gradle/CI/активнойработы, не reset/clean/force-push/не терять дерево/не запускать второгоисполнителя/Gradle/не редактироватьmain/testпри проверке. Послекаждогоэтапааудит/тесты/lint/APK/полныйCI/Signed/независимаяверификация/журнал/main.

Выдавать вФИНАЛЕ свежуюHTTPSdownloadURLZIP изGitHubartifacttool и постояннуюссылку выше: absoluteworkspaceлинк у клиента не скачивается («Нетапк»), инструментаотдельногоAPKuploadнет. Не называтьlocalpathвложением. BearerURLживётминуты/не коммитить. Архив нужно распаковать и установить.apk. Согласованная очередь/самостоятельноевозобновление разрешены пользователем; резервнаятажеautomation после доставки17:37обновлена на19:41:57UTC/is_enabledtrue и APIподтвердил, не создаватьдубль.
