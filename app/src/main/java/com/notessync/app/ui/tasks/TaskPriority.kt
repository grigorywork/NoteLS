package com.notessync.app.ui.tasks

import com.notessync.app.i18n.uiText

import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

enum class TaskPriority(val value: Int, private val labelRu: String, val light: Long, val dark: Long) {
    NONE(0, "Без приоритета", 0xFF616161, 0xFFBDBDBD),
    LOW(1, "Низкий", 0xFF806000, 0xFFFFD54F),
    MEDIUM(2, "Средний", 0xFFA84300, 0xFFFFB74D),
    HIGH(3, "Высокий", 0xFFB71C1C, 0xFFEF9A9A);
    val label: String get() = uiText(labelRu)
    companion object { fun from(value: Int) = entries.firstOrNull { it.value == value } ?: NONE }
}

@Composable
fun priorityColor(value: Int): Color = TaskPriority.from(value).let {
    Color(if (LocalContentColor.current.luminance() > 0.5f) it.dark else it.light)
}

@Composable
fun TaskPriorityPicker(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        AssistChip(onClick = { open = true }, label = { Text(TaskPriority.from(value).label) },
            leadingIcon = { Icon(Icons.Default.Flag, null, tint = priorityColor(value)) })
        DropdownMenu(open, { open = false }) {
            TaskPriority.entries.forEach { priority ->
                DropdownMenuItem(text = { Text(priority.label) },
                    leadingIcon = { Icon(Icons.Default.Flag, null, tint = priorityColor(priority.value)) },
                    onClick = { onChange(priority.value); open = false })
            }
        }
    }
}
