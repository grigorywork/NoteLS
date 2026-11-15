# GPT73 —1.10.41/code64/Room17

Работа продолжается. Последний проверенный установочный выпуск —1.10.40/code63, полныйCI701/0; proof voice-1.10.40-verification.json. Постоянный ключ/Room17/data2/security3 не меняются.

## Диагностика и подготовленные изменения

В OpenAiTransport HTTP429 всегда считался временным rate limit, даже при insufficient_quota. HTTP401/403 объединялись, model_not_found/платёжные/региональные ошибки скрывались под общей ошибкой. Ответ incomplete не отличал исчерпание output budget. Ошибка создания соединения могла обходить transport mapping и становиться общей ошибкой провайдера. Это подтверждённые свойства кода; конкретный реальный аккаунт/провайдер пользователя не проверен.

Подготовлены отдельные безопасные причины: ключ401, доступ403, регион по известному коду, модель, API-баланс/квота, временный лимит, параметры400/422, сервис5xx и timeout. Читается только ограниченное тело ошибки (8192+1байт максимум до отказа), stream закрывается. Из JSON используются только известные code/type; rawmessage/ключ/текст заметки не выводятся. Повреждённый/слишком большой/неUTF8ответ сохраняет fallback по HTTPстатусу. Переадресации и автоматические повторы по-прежнему запрещены.

Incomplete max_output_tokens объясняется отдельно; частичный текст не применяется. Прежний бюджет2048outputtokens, лимиты текста/байтов, фиксированныйTLSendpoint/storefalse/backgroundfalse/nohistory/notools сохранены. Стоимость запроса скрыто не увеличивается.

В настройках поясняется, что ChatGPT-подписка не включает API: нужны API-ключ, доступная модель и отдельный баланс проекта. Идентификатор модели указывается явно, без обещания доступности конкретной модели. В preview отсутствующая модель видна до отправки, отправка запрещена. Кнопка настроек закрывает/отменяет сессию и открывает нужную группу настроек; заметка предварительно сохраняется, обычный маршрут настроек и Back сохраняются. AIdefaultoff/явныйSend/оригинал/редактируемыйpreview/явныйApply/свежиеaccessguards сохраняются.

## Проверки

Запущены все ai.*Test, SettingsGroupsUiTest, UiCatalogTest +lintDebug/assembleDebug: `/workspace/tools/transform41-checks.log`. Результат ещё не подтверждён. Подготовлены5HTTP-контрактов (billing/rate,model/access/region/server,malformed/oversized+close,outputbudget,setupnetwork),2ComposeUI (модель/config иbilling/original/settings),1открытие нужной группы/Back. Старые проверки cancel/disconnect/secrets/access/предпросмотра не ослаблялись.

Настоящие платные запросы не выполнялись. Ключ пользователя не запрашивался/не читался в чате. AndroidTransformSecrets — отдельный локальный API-ключ пользователя; это не ключ подписи приложения, постоянный signingSecret не затрагивается. ПолныйCI709 ожидается только после локальных проверок, пока НЕpass/НЕinstallable41.

## Локальный результат

`transform41-checks.log`:32/32tests,0failures/errors/skipped,8m12s. OpenAiTransport8/TransformSession4/TransformSecrets2/TransformSettings1/EditorTransform3/TransformUi4/SettingsGroups4/UiCatalog6. LintDebug0error/15warnings/2info иassembleDebugSUCCESS, metadata1.10.41/code64. Все8 новых кейсов прошли с первого исполнения; старые assertions/caps не менялись. Аудит: providererror не выводит rawmessage/ключ/черновик, чтение ошибки≤8193байт/close, 2048budget не растёт; source/access/session guards/explicitApply сохранены.

Нормальный pushmain запускает полный Android checks (709 ожидается, но пока неpass). После полного успеха проверить автоматическийSigned и скачанный установочныйAPK с прежнимpin. До этого последний проверенныйinstallable40.
