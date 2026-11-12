package com.notessync.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.notessync.app.security.MAX_PASSWORD_LENGTH
import com.notessync.app.security.MIN_PASSWORD_LENGTH

@Composable
fun SetPasswordDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid = password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH && password == confirmation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Пароль"
                )
                PasswordField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = "Повторите пароль",
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = when {
                        password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH ->
                            "Минимум $MIN_PASSWORD_LENGTH символа"
                        confirmation.isNotEmpty() && password != confirmation -> "Пароли не совпадают"
                        else -> "Пароль не хранится в открытом виде"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = valid) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun UnlockPasswordDialog(
    title: String,
    showError: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    busy: Boolean = false,
    alternative: (@Composable () -> Unit)? = null
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Пароль"
                )
                alternative?.invoke()
                if (showError) {
                    Text(
                        text = "Неверный пароль",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty() && !busy) {
                Text("Подтвердить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= MAX_PASSWORD_LENGTH) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier.fillMaxWidth()
    )
}
