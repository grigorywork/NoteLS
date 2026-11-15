# Qwen через Groq — дополнение74,1.10.43/code66/Room17

Кандидат, пока не проверен. Последний установочный42 подтверждён714/0/lint0error/Signed43733357193/artifact14169334476; ключ/Room17/data2/security3 прежние. После закрытия этого дополнения74 следующий75.

Qwen добавлен рядом с прежними LOCAL_RULES/OPENAI. Компактный выбор провайдера; fixed model qwen/qwen3.8-27b, fixed Groq HTTPS ChatCompletions endpoint, отдельные alias/prefs/AAD/vault безfallback в OpenAI. Старые OpenAIalias/prefs/model совместимы. Удаление одного APIключа не удаляет другой, OSbackupисключаетоба; обычныеархивы приложения содержат только данныеDB/вложения, vault не экспортируется.

Freeусловия видны: бесплатен только аккаунт на тарифеFree в квоте, приложение не проверяет тариф, сDeveloperаккаунтом вызовы могут оплачиваться. Получатель выбранного текста Groq, именованнаякнопкаSend, официальныеключ/условия/данные/лимиты по нажатию. Никакого автоupgrade/автоповторов/другоймодели. Providererrorпоказывает whitelistдиагностику, исходныйтекст/ключ не копируются в ошибку.

Прежние INPUT16000/OUTPUT32000/RESPONSE65536/error8192/2048outputbudget/таймауты/отмена/явныйpreviewApply/свежийдоступ/сохранностьоригинала. Groq reasoningnone; принимается только stop/assistanttext, partial/refusal/tools/ошибочныйJSON/UTF8 отвергаются. Presentedconfig передаётся при привязке провайдера, поэтому смена конфигурации до/во время чтенияключа/ответа не перенаправляет черновик другому сервису. Неотправленный APIключ очищается при смене провайдера/ON_STOP.

18новых meaningful оффлайнкейсов:8HTTP,3vault,2session,1settingspersist,4NativeUI(RU/EN/recipient/SendApply/переключениеключей/очисткаGroq/ошибкаквоты). Старые AI/EditorTransform/SettingsGroups/UiCatalog будут проверены вместе. Никакой реальной генерации/платныхвызовов/физическоготелефона. План/официальныеисточники:qwen-1.10.43-plan.md,ai-free-options.md.


Первый qwen43-checks.log завершился6m59s:50тестов/1fail,49pass/0errors/skipped. GroqSecrets3/3,GroqSession2/2,GroqSettings1/1,QwenNativeUI4/4; GroqHTTP7/8. MalformedUTF8 классифицировалсяNETWORK, исправленнаPROVIDER безослабленияassertion. NativePNGбылс прозрачнымфоном тестовогоMaterialTheme; подтвержденоalpha212216/230400нулевыхпикселей. ОболочкаNativeснимказамененаSurface,продуктовыйфоннеизменялся. Требуетсяповтор50/lint/assemble и просмотрopaquePNG; пока43неполныйCI/неinstallable.


## Итог локального аудита и повторных проверок

Повторный qwen43-checks2.log завершился BUILD SUCCESSFUL8m8s:50тестов/13классов/0fail/errors/skipped, lint0error/15warnings/2info, assembleDebug.18новыхкейсов+32прежних подтверждены. NativeCanvas320×720полностьюopaque(230400alpha255), визуально проверен: provider/Freeусловия/model/key/actionsпомещаются. PNGf58d167c0cc2896faf24c6a5c6fa4aba62a595f95be605ae87245744672533a1 сохранён docs/design/qwen-settings-1.10.43.png. Аудит: fixedorigin/model, redirectsfalse/noSDK/noauto/inference, отдельныеvault/prefs/alias/AAD и закрытаяbackupallowlistDB/attachments; старыеOpenAIsettings/keys/preview/Backпроверены. Source43 готовится кобычномуfast-forwardmain/полныйCI732ожидается(покаНЕpass)/autoSigned. Последнийinstallable42. Не менятьmain/test/не запускать второйGradle во времяпроверки. РеальныхAIвызововнет.


2026-11-15T17:58UTC: runtime sourceaf01d51ee59756bcdf060123a9b4584c1541c789 уже вmain. Полный pushCI43744048331/job132903324579 in_progress. Пока732ожидаемыйНЕpass/неinstallable43. ВсеWIPcodex/qwen-1.10.43включены, дерево чистое; локальногоGradleнет. main/testзаморожены доCI/Signed/проверкиAPK.


[Инструкция настройки Qwen RU/EN](ai-qwen-setup.md) сверена с фактическими UIстроками/источником редактора: вход через нижний + (не ⋮), выделение либо весьтекст, фиксированная модель/Freeусловия/явныеSendиApply.
