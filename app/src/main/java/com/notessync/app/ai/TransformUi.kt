package com.notessync.app.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notessync.app.i18n.uiText
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.SettingsState
import com.notessync.app.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

fun SettingsState.transformConfig() = TransformConfig(aiEnabled, aiProvider, aiProvider.fixedModel ?: aiModel)
fun TransformProblem.label(provider: AiProvider = AiProvider.OPENAI): String {
    if (provider.isAdditionalFreeModel) additionalFreeProblemLabel(this)?.let { return it }
    if (provider == AiProvider.GROQ_QWEN) return uiText(when (this) {
        TransformProblem.BILLING -> "Groq отклонил запрос по квоте аккаунта. Проверьте тариф Free и его лимиты в Groq. Автоматического платного перехода нет."
        TransformProblem.REGION -> "Groq API недоступен для региона этого подключения."
        TransformProblem.BAD_REQUEST -> "Groq не принял запрос Qwen. Проверьте доступность модели в Groq."
        TransformProblem.SERVICE_UNAVAILABLE -> "Сервис Groq временно недоступен. Повторите позже."
        TransformProblem.MODEL_UNAVAILABLE -> "Qwen сейчас недоступен в Groq. Автоматической замены другой моделью нет."
        TransformProblem.OUTPUT_LIMIT, TransformProblem.INCOMPLETE -> "Ответ Qwen неполный. Сократите текст и отправьте заново."
        else -> return label(AiProvider.OPENAI)
    })
    return uiText(when (this) {
    TransformProblem.DISABLED -> "Преобразователь выключен."
    TransformProblem.EMPTY -> "Сначала введите текст."
    TransformProblem.TOO_LARGE -> "Текст слишком длинный. Выделите меньший фрагмент."
    TransformProblem.KEY_MISSING -> "Добавьте свой API-ключ в настройках преобразователя."
    TransformProblem.KEY_UNAVAILABLE -> "Ключ недоступен. Введите его заново на этом устройстве."
    TransformProblem.MODEL_MISSING -> "Укажите доступную модель в настройках преобразователя."
    TransformProblem.STALE, TransformProblem.ACCESS -> "Запись или доступ изменились. Откройте предпросмотр заново."
    TransformProblem.NETWORK -> "Не удалось связаться с провайдером."
    TransformProblem.TIMEOUT -> "Провайдер не ответил вовремя."
    TransformProblem.UNAUTHORIZED -> "API-ключ отклонён. Проверьте ключ и проект в настройках."
    TransformProblem.RATE_LIMIT -> "Временный лимит запросов API. Повторите позже."
    TransformProblem.BILLING -> "Недостаточно API-квоты. Проверьте баланс и лимиты API-проекта. Подписка ChatGPT не включает оплату API."
    TransformProblem.MODEL_UNAVAILABLE -> "Модель не найдена или недоступна вашему API-проекту. Укажите доступный API-идентификатор модели."
    TransformProblem.FORBIDDEN -> "Нет доступа к API. Проверьте разрешения ключа, проект и доступность сервиса."
    TransformProblem.REGION -> "OpenAI API недоступен для региона этого подключения."
    TransformProblem.BAD_REQUEST -> "Провайдер не принял запрос. Проверьте поддержку выбранной модели в Responses API."
    TransformProblem.SERVICE_UNAVAILABLE -> "Сервис OpenAI временно недоступен. Повторите позже."
    TransformProblem.OUTPUT_LIMIT -> "Модель исчерпала лимит ответа. Сократите текст или выберите другую модель."
    TransformProblem.INCOMPLETE -> "Ответ неполный. Сократите текст или выберите другую модель."
    TransformProblem.REFUSED -> "Провайдер отказался преобразовывать этот текст."
    TransformProblem.SAVE -> "Не удалось сохранить. Текст оставлен в редакторе."
    TransformProblem.PROVIDER -> "Не удалось получить результат от провайдера."
})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransformSettingsSection(state: SettingsState, settings: SettingsRepository, secretStore: TransformSecrets? = null) {
    val context = LocalContext.current.applicationContext
    val credentialProvider = if (state.aiProvider.isNetwork) state.aiProvider else AiProvider.OPENAI
    val vault = secretStore ?: remember(context, credentialProvider) { AndroidTransformSecrets(context, provider = credentialProvider) }
    val scope = rememberCoroutineScope()
    var key by remember(state.aiProvider) { mutableStateOf("") }
    var model by remember(state.aiModel) { mutableStateOf(state.aiModel) }
    var present by remember(state.aiProvider) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember(state.aiProvider) { mutableStateOf<TransformProblem?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state.aiProvider) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) key = "" }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); key = "" }
    }
    LaunchedEffect(state.aiEnabled, state.aiProvider, vault) {
        if (state.aiEnabled && state.aiProvider.isNetwork) present = runCatching { vault.present() }.getOrDefault(false)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(uiText("Преобразователь текста"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(state.aiEnabled, settings::setAiEnabled, enabled = !busy)
        }
        Text(uiText("Предпросмотр перед заменой. Ничего не отправляется автоматически."), style = MaterialTheme.typography.bodySmall)
        if (state.aiEnabled) {
            com.notessync.app.ui.settings.SettingsDropdown("ai-provider", uiText("Провайдер"), state.aiProvider, AiProvider.entries,
                settings::setAiProvider, { it.label }, enabled = !busy)
            if (state.aiProvider == AiProvider.LOCAL_RULES) Text(uiText("Локальные правила без ИИ и сети. Модели не скачиваются."), style = MaterialTheme.typography.bodySmall)
            else {
                if (state.aiProvider == AiProvider.GROQ_QWEN) {
                    Text(uiText("Qwen через Groq. Бесплатно только на тарифе Free в пределах квоты. Приложение не проверяет тариф аккаунта."), style = MaterialTheme.typography.bodySmall)
                    Text(GroqQwenTransport.MODEL, style = MaterialTheme.typography.bodySmall)
                    com.notessync.app.ui.settings.SettingsHelp("groq-account", uiText("Лимиты и аккаунт Groq")) {
                        Text(uiText("Создайте свой ключ в Groq и оставайтесь на тарифе Free. С платным аккаунтом запросы могут оплачиваться. При исчерпании квоты приложение остановится без другой модели, платного перехода или автоматического повтора."), style = MaterialTheme.typography.bodySmall)
                        ProviderLinks(state.aiProvider)
                    }
                    Text(uiText("Только выбранный текст отправляется в Groq после нажатия отправки. Условия хранения определяет Groq."), style = MaterialTheme.typography.bodySmall)
                } else if (state.aiProvider.isAdditionalFreeModel) {
                    FreeModelSettingsInfo(state.aiProvider)
                } else {
                    Text(uiText("Только выбранный текст отправляется в OpenAI после нажатия отправки. Условия хранения и стоимость зависят от сервиса и модели."), style = MaterialTheme.typography.bodySmall)
                    Text(uiText("Подписка ChatGPT не включает API. Нужны API-ключ, доступная модель и отдельный баланс API-проекта."), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(model, { if (it.length <= 160) model = it }, label = { Text(uiText("Модель OpenAI")) },
                        supportingText = { Text(uiText("Идентификатор модели с доступом к Responses API")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedButton(onClick = { settings.setAiModel(model) }, enabled = !busy && TransformLimits.validModel(model.trim()) && model.trim() != state.aiModel) { Text(uiText("Сохранить модель")) }
                    ProviderLinks(state.aiProvider)
                }
                Text(if (present) uiText("Ключ сохранён на этом устройстве") else uiText("API-ключ не настроен"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(key, { if (it.length <= 4096) key = it }, label = { Text(newTransformKeyLabel(state.aiProvider)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { busy = true; problem = null; scope.launch {
                        try { vault.save(key); key = ""; present = true }
                        catch (error: Exception) { if (error is CancellationException) throw error; problem = TransformProblem.KEY_UNAVAILABLE }
                        finally { busy = false }
                    } }, enabled = !busy && TransformLimits.validKey(key.trim())) { Text(uiText("Сохранить ключ")) }
                    if (present) TextButton(onClick = { busy = true; scope.launch {
                        try { vault.clear(); present = false; key = "" }
                        catch (error: Exception) { if (error is CancellationException) throw error; problem = TransformProblem.KEY_UNAVAILABLE }
                        finally { busy = false }
                    } }, enabled = !busy) { Text(uiText("Удалить ключ")) }
                }
                problem?.let { Text(it.label(state.aiProvider), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderLinks(provider: AiProvider) {
    if (provider.isAdditionalFreeModel) { FreeModelProviderLinks(provider); return }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val groq = provider == AiProvider.GROQ_QWEN
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (groq) TextButton(onClick = { uri.openUri("https://console.groq.com/keys") }) { Text(uiText("Получить ключ Groq")) }
        TextButton(onClick = { uri.openUri(if (groq) "https://console.groq.com/docs/legal/services-agreement" else "https://openai.com/policies/services-agreement/") }) { Text(uiText("Условия")) }
        TextButton(onClick = { uri.openUri(if (groq) "https://console.groq.com/docs/your-data" else "https://developers.openai.com/api/docs/guides/your-data") }) { Text(uiText("Данные")) }
        TextButton(onClick = { uri.openUri(if (groq) "https://console.groq.com/docs/rate-limits" else "https://developers.openai.com/api/docs/pricing") }) { Text(uiText(if (groq) "Лимиты" else "Тарифы")) }
    }
}

@Composable
fun EditorTransformDialog(viewModel: NoteEditorViewModel, settings: SettingsRepository, onOpenSettings: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val state by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var source by remember { mutableStateOf<EditorTransformSource?>(null) }
    var problem by remember { mutableStateOf<TransformProblem?>(null) }
    LaunchedEffect(viewModel) {
        try { source = viewModel.captureTransformSource() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { problem = (error as? TransformException)?.problem ?: TransformProblem.SAVE }
    }
    LaunchedEffect(state.aiEnabled) { if (!state.aiEnabled) onDismiss() }
    val captured = source
    if (captured == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("Преобразователь текста")) }, text = {
            Text(problem?.label() ?: uiText("Загрузка…"))
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(uiText("Закрыть")) } })
    } else {
        val session = remember(captured) {
            val presented = settings.state.value.transformConfig()
            val provider = presented.provider
            TransformSession({ settings.state.value.transformConfig() },
                AndroidTransformSecrets(context, provider = if (provider.isNetwork) provider else AiProvider.OPENAI),
                createTransformTransport(provider),
                { viewModel.transformSourceCurrent(captured) }, { viewModel.applyTransform(captured, it) }, presented = presented)
        }
        val sessionState by session.state.collectAsStateWithLifecycle()
        DisposableEffect(session) { onDispose { session.close() } }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, session) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { session.close(); onDismiss() } }
            lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(captured, session) {
            viewModel.observeTransformAccess(captured.access.id).combine(ProtectionSession.state) { access, protection ->
                access == captured.access && access?.allowed(protection) == true
            }.collect { allowed -> if (!allowed && session.state.value.phase != TransformPhase.APPLYING) { session.close(); onDismiss() } }
        }
        TransformPreview(captured.text, state.transformConfig(), sessionState, session::generate, session::replace, { session.close(); onDismiss() },
            onSettings = onOpenSettings?.let { open -> { session.close(); onDismiss(); open() } })
        LaunchedEffect(sessionState.phase) { if (sessionState.phase == TransformPhase.CLOSED) onDismiss() }
    }
}

@Composable
internal fun TransformPreview(input: String, config: TransformConfig, state: TransformState,
    onGenerate: (String, TransformMode) -> Unit, onApply: (String) -> Unit, onDismiss: () -> Unit, onSettings: (() -> Unit)? = null) {
    var draft by remember(input) { mutableStateOf(input) }
    var result by remember(state.result) { mutableStateOf(state.result) }
    var mode by remember { mutableStateOf(TransformMode.TEXT) }
    val working = state.phase == TransformPhase.WORKING || state.phase == TransformPhase.APPLYING
    val preview = state.phase == TransformPhase.RESULT || state.phase == TransformPhase.APPLYING
    val configProblem = if (config.provider.isNetwork && !TransformLimits.validModel(config.model)) TransformProblem.MODEL_MISSING else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText(if (preview) "Результат преобразования" else "Преобразователь текста")) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(config.provider.label + if (config.provider.isNetwork) " · ${config.model}" else "", style = MaterialTheme.typography.bodySmall)
            if (preview) {
                Text(uiText("Проверьте результат перед заменой."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(result, { if (it.length <= TransformLimits.OUTPUT) result = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, enabled = !working)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TransformMode.entries.forEach { item -> FilterChip(mode == item, { mode = item }, enabled = !working, label = { Text(item.label) }) } }
                OutlinedTextField(draft, { if (it.length <= TransformLimits.INPUT) draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, enabled = !working,
                    label = { Text(uiText("Текст для преобразования")) })
                if (config.provider.isNetwork) {
                    Text(transformNetworkDisclosure(config.provider), style = MaterialTheme.typography.bodySmall)
                    ProviderLinks(config.provider)
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            (state.problem ?: configProblem)?.let { Text(it.label(config.provider), color = MaterialTheme.colorScheme.error) }
            if (config.provider.isNetwork && onSettings != null) {
                TextButton(onClick = onSettings, enabled = !working) { Text(uiText("Настройки преобразователя")) }
            }
        } }, confirmButton = {
            TextButton(onClick = { if (preview) onApply(result) else onGenerate(draft, mode) },
                enabled = !working && (if (preview) result.isNotBlank() && result.length <= TransformLimits.OUTPUT else draft.isNotBlank() && config.enabled && configProblem == null)) {
                Text(if (preview) uiText("Заменить текст") else transformSendLabel(config.provider))
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(uiText(if (working) "Отменить" else "Закрыть")) } })
}
