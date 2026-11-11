    package com.notessync.app.ui.notes.md

    import androidx.compose.ui.text.AnnotatedString
    import androidx.compose.ui.text.SpanStyle
    import androidx.compose.ui.text.buildAnnotatedString
    import androidx.compose.ui.text.font.FontStyle
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.input.OffsetMapping
    import androidx.compose.ui.text.input.TransformedText
    import androidx.compose.ui.text.input.VisualTransformation
    import androidx.compose.ui.text.style.TextDecoration
    import androidx.compose.ui.text.withStyle
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.unit.em

    /**
     * WYSIWYG в редакторе (хотфикс HF-1, 2026-11-10; доработка этапа 25, 2026-11-11).
     *
     * Маркеры markdown скрываются при отображении: **жирный**, *курсив* и
     * ***жирный курсив*** видны сразу стилями, без звёздочек; заголовки крупнее;
     * «- [ ] »/«- [x] »/«- [X] » рисуются как ☐/☑, «- » — как «• ».
     * Исходный текст и автосохранение не меняются.
     *
     * Ключевое правило этапа 25: строка, где находится курсор ([activeLine],
     * диапазон позиций в исходном тексте), показывается КАК ЕСТЬ — со всеми
     * маркерами. Поэтому курсор, выделение и Backspace в активной строке
     * работают один в один по реальным символам, а скрытие маркеров действует
     * только на неактивные строки.
     *
     * Курсив и жирный применяются только при корректных границах: содержимое
     * маркеров не пустое и не начинается/не кончается пробелом. Поэтому
     * «2 * 3 * 4» остаётся формулой, а не становится курсивом.
     *
     * OffsetMapping строится по каждому символу, поэтому курсор и выделение
     * остаются точными.
     */
    class MarkdownVisualTransformation(
        private val activeLine: IntRange? = null,
        // Этап 27: выполненный пункт чек-листа — зелёный и зачёркнутый.
        private val completedColor: Color = Color(0xFF4CAF50)
    ) : VisualTransformation {

        override fun filter(text: AnnotatedString): TransformedText {
            val source = text.text
            val hasMarkup = source.contains('*') || source.contains("# ") ||
                source.contains("- ") || source.contains("- [")
            if (!hasMarkup) return TransformedText(text, OffsetMapping.Identity)

            val o2t = IntArray(source.length + 1) { -1 }
            val t2o = ArrayList<Int>(source.length + 1)
            val builder = AnnotatedString.Builder()
            var outLength = 0
            var original = 0 // глобальная позиция в исходнике

            fun appendOut(str: String, sourceIndex: Int) {
                builder.append(str)
                repeat(str.length) { t2o.add(sourceIndex) }
                outLength += str.length
            }

            fun appendChar(index: Int) {
                o2t[index] = outLength
                appendOut(source[index].toString(), index)
            }

            fun skipMarker(start: Int, length: Int) {
                for (k in 0 until length) o2t[start + k] = outLength
            }

            // Содержимое маркеров: непустое и без пробелов по краям.
            fun validInner(from: Int, to: Int): Boolean =
                from < to && !source[from].isWhitespace() && !source[to - 1].isWhitespace()

            // Внутристрочная разметка: ***жирный курсив***, **жирный**, *курсив*.
            fun inline(start: Int, end: Int) {
                var i = start
                while (i < end) {
                    when {
                        source.startsWith("***", i) -> {
                            val close = source.indexOf("***", i + 3)
                            if (close in (i + 3)..(end - 3) && validInner(i + 3, close)) {
                                skipMarker(i, 3)
                                builder.withStyle(
                                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                                ) {
                                    for (k in i + 3 until close) appendChar(k)
                                }
                                skipMarker(close, 3)
                                i = close + 3
                            } else {
                                appendChar(i); i++
                            }
                        }
                        source.startsWith("**", i) -> {
                            val close = source.indexOf("**", i + 2)
                            if (close in (i + 2)..(end - 2) && validInner(i + 2, close)) {
                                skipMarker(i, 2)
                                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    for (k in i + 2 until close) appendChar(k)
                                }
                                skipMarker(close, 2)
                                i = close + 2
                            } else {
                                appendChar(i); i++
                            }
                        }
                        source[i] == '*' -> {
                            val close = source.indexOf('*', i + 1)
                            if (close in (i + 1) until end && validInner(i + 1, close)) {
                                skipMarker(i, 1)
                                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                    for (k in i + 1 until close) appendChar(k)
                                }
                                skipMarker(close, 1)
                                i = close + 1
                            } else {
                                appendChar(i); i++
                            }
                        }
                        else -> { appendChar(i); i++ }
                    }
                }
            }

            // Активная строка (с курсором) выводится сырьём, со всеми маркерами:
            // маппинг в ней один к одному, редактирование предсказуемо.
            fun raw(start: Int, end: Int) {
                for (k in start until end) appendChar(k)
            }

            source.split('\n').forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) {
                    o2t[original] = outLength
                    appendOut("\n", original)
                    original++
                }
                val lineStart = original
                val lineEnd = lineStart + line.length
                val isActive = activeLine != null &&
                    lineStart <= activeLine.last && lineEnd >= activeLine.first
                when {
                    isActive -> raw(lineStart, lineEnd)
                    line.startsWith("## ") -> {
                        skipMarker(lineStart, 3)
                        builder.withStyle(SpanStyle(fontSize = 1.35f.em, fontWeight = FontWeight.Bold)) {
                            inline(lineStart + 3, lineEnd)
                        }
                    }
                    line.startsWith("# ") -> {
                        skipMarker(lineStart, 2)
                        builder.withStyle(SpanStyle(fontSize = 1.6f.em, fontWeight = FontWeight.Bold)) {
                            inline(lineStart + 2, lineEnd)
                        }
                    }
                    line.startsWith("- [ ] ") || line.startsWith("- [x] ") ||
                        line.startsWith("- [X] ") -> {
                        val done = !line.startsWith("- [ ] ")
                        skipMarker(lineStart, 6)
                        if (done) {
                            builder.withStyle(SpanStyle(color = completedColor)) {
                                appendOut("☑ ", lineStart)
                            }
                            builder.withStyle(
                                SpanStyle(
                                    color = completedColor,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            ) {
                                inline(lineStart + 6, lineEnd)
                            }
                        } else {
                            appendOut("☐ ", lineStart)
                            inline(lineStart + 6, lineEnd)
                        }
                    }
                    line.startsWith("- ") -> {
                        skipMarker(lineStart, 2)
                        appendOut("• ", lineStart)
                        inline(lineStart + 2, lineEnd)
                    }
                    else -> inline(lineStart, lineEnd)
                }
                original = lineEnd
            }

            // Финальные и незаполненные позиции (конец строки и т.п.).
            o2t[source.length] = outLength
            for (i in o2t.indices) if (o2t[i] < 0) o2t[i] = outLength
            t2o.add(source.length)
            val transformed = builder.toAnnotatedString()

            val mapping = object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    o2t[offset.coerceIn(0, source.length)]
                override fun transformedToOriginal(offset: Int): Int =
                    t2o[offset.coerceIn(0, t2o.lastIndex)]
            }
            return TransformedText(transformed, mapping)
        }
    }
