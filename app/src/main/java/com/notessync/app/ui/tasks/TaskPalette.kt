package com.notessync.app.ui.tasks

import com.notessync.app.i18n.uiText

import androidx.compose.ui.graphics.Color

const val DEFAULT_TASK_COLOR = "violet"

data class TaskColorOption(
    val key: String,
    private val labelRu: String,
    val completedContainer: Color,
    // Этап 35: насыщенный акцент — заголовок, галочка, полоса на карточке.
    // Цвет задачи теперь реально виден, а не только в фоне выполненной карточки.
    val accent: Color
) { val label: String get() = uiText(labelRu) }

val taskColorOptions = listOf(
    TaskColorOption("violet", "Фиолетовый", Color(0xFFDCC2FF), Color(0xFF7C4DFF)),
    TaskColorOption("blue", "Синий", Color(0xFFB9D9FF), Color(0xFF2F7CD6)),
    TaskColorOption("mint", "Мятный", Color(0xFFA9E6D2), Color(0xFF0E9F7E)),
    TaskColorOption("yellow", "Жёлтый", Color(0xFFFFE082), Color(0xFFB8860B)),
    TaskColorOption("orange", "Оранжевый", Color(0xFFFFC29E), Color(0xFFE06A1B)),
    TaskColorOption("pink", "Розовый", Color(0xFFFFC1D9), Color(0xFFD63D7F))
)

fun taskColorOption(key: String): TaskColorOption =
    taskColorOptions.firstOrNull { it.key == key } ?: taskColorOptions.first()
