package com.notessync.app.ui.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText

@Composable
internal fun BackupPasswordDialog(mode: BackupPasswordMode, busy: Boolean, biometrics: Boolean,
    onBiometric: () -> Unit, onSubmit: (CharArray, CharArray) -> Unit, onDismiss: () -> Unit) {
    var old by remember(mode) { mutableStateOf("") }
    var fresh by remember(mode) { mutableStateOf("") }
    var repeated by remember(mode) { mutableStateOf("") }
    val setting = mode == BackupPasswordMode.SETUP || mode == BackupPasswordMode.CHANGE
    val needsOld = mode != BackupPasswordMode.SETUP
    val valid = (!needsOld || old.isNotEmpty()) && (!setting || fresh.length >= 8 && fresh == repeated)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(uiText(if (setting) "Пароль резервных копий" else "Открыть зашифрованную копию")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (setting) Text(uiText("Не менее 8 символов. Храните пароль отдельно. Старые выгруженные копии сохранят прежний пароль."))
                if (needsOld) BackupPasswordField(old, { old = it }, uiText(if (setting) "Текущий пароль" else "Пароль"), busy)
                if (setting) {
                    BackupPasswordField(fresh, { fresh = it }, uiText("Новый пароль"), busy)
                    BackupPasswordField(repeated, { repeated = it }, uiText("Повторите пароль"), busy)
                    if (repeated.isNotEmpty() && repeated != fresh) Text(uiText("Пароли не совпадают"), color = MaterialTheme.colorScheme.error)
                }
                if (biometrics && mode == BackupPasswordMode.UNLOCK) TextButton(enabled = !busy, onClick = {
                    old = ""; onBiometric()
                }) { Text(uiText("Открыть по отпечатку")) }
            }
        },
        confirmButton = { TextButton(enabled = valid && !busy, onClick = {
            val first = old.toCharArray(); val second = fresh.toCharArray()
            old = ""; fresh = ""; repeated = ""; onSubmit(first, second)
        }) { Text(uiText(if (setting) "Сохранить" else "Продолжить")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(uiText("Отмена")) } })
}

@Composable
private fun BackupPasswordField(value: String, change: (String) -> Unit, label: String, busy: Boolean) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= 256) change(it) },
        label = { Text(label) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
}
