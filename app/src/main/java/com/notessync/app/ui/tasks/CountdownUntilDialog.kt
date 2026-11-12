package com.notessync.app.ui.tasks

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownUntilDialog(onDismiss: () -> Unit, onStart: (Long) -> Boolean) {
    val zone = remember { ZoneId.systemDefault() }
    var reference by remember { mutableStateOf(Instant.now()) }
    val initial = remember { reference.atZone(zone).plusHours(1) }
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    val target = remember(time.hour, time.minute, reference) { CountdownTarget.next(reference, time.hour, time.minute, zone) }
    var expired by remember { mutableStateOf(false) }
    val local = target.atZone(zone)
    val day = if (local.toLocalDate() == reference.atZone(zone).toLocalDate()) uiText("Сегодня") else uiText("Завтра")
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("Таймер до времени")) }, text = {
        Column(Modifier.fillMaxWidth()) {
            TimeInput(time)
            Text("$day, ${local.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}")
            Text(uiText("Отсчёт начнётся сейчас."))
            if (expired) Text(uiText("Это время уже прошло. Проверьте новую дату."), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        if (onStart(target.toEpochMilli())) onDismiss()
        else { reference = Instant.now(); expired = true }
    }) { Text(uiText("Запустить")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Отмена")) } })
}
