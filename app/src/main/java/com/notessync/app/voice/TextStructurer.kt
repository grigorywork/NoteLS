package com.notessync.app.voice

/**
 * Этап 32: лёгкая структуризация распознанной речи.
 * Работает локально без сети: разбивает поток слов
 * на пункты или выделяет главную мысль по ключевым словам.
 */
object TextStructurer {

    /** Слова-маркеры, которыми в речи отделяют пункты друг от друга. */
    private val POINT_MARKERS = listOf(
        "во-первых", "во-вторых", "в-третьих", "в-четвёртых", "в-пятых",
        "первое", "второе", "третье",
        "потом", "затем", "дальше", "далее",
        "ещё один пункт", "еще один пункт", "следующий пункт", "пункт",
        "и ещё", "и еще", "а также", "также", "плюс"
    )

    private val STOP_WORDS = setOf(
        "и", "в", "на", "с", "по", "для", "как", "что", "это", "эта", "этот", "эти",
        "а", "но", "или", "у", "о", "об", "из", "за", "от", "до", "не", "ни",
        "мы", "я", "ты", "он", "она", "оно", "они", "вы", "его", "её", "их",
        "мне", "мой", "моя", "мои", "наш", "наша", "наши",
        "нужно", "надо", "бы", "быть", "есть", "был", "была", "были",
        "то", "там", "тут", "здесь", "очень", "просто", "вообще", "короче",
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "is", "are"
    )

    // Compile only the rules used by the selected mode, once rather than for every sentence.
    private val whitespace by lazy { Regex("\\s+") }
    private val punctuationSpace by lazy { Regex("\\s+([,.!?;:…])") }
    private val sentenceBreak by lazy { Regex("(?<=[.!?…])\\s+|\\|") }
    private val wordSeparators by lazy { Regex("[^\\p{L}\\p{N}\\s-]") }
    private val pointPatterns by lazy { POINT_MARKERS.map { Regex("(?i)(^|\\s)${Regex.escape(it)}[,:]?\\s+") } }

    /** Убрать лишние пробелы, нормализовать пунктуацию. */
    fun normalize(text: String): String =
        text.trim()
            .replace(whitespace, " ")
            .replace(punctuationSpace, "$1")

    /** Разбить текст на предложения (по пунктуации и маркерам речи). */
    fun sentences(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        // Маркеры речи превращаем в границы предложений.
        var marked = normalized
        pointPatterns.forEach { pattern ->
            marked = marked.replace(pattern) { match -> match.groupValues[1] + "|" }
        }
        return marked.split(sentenceBreak)
            .map { it.trim().trim(',', ';', ':') }
            .filter { it.isNotBlank() }
    }

    /** Структуризация «по пунктам»: маркированный список. */
    fun toPoints(text: String): String {
        val items = sentences(text)
        if (items.isEmpty()) return ""
        return items.joinToString("\n") { item ->
            "- " + capitalize(item.trimEnd('.', '!', '?', '…').trim())
        }
    }

    /**
     * Основная мысль: выбираем предложение с наибольшей плотностью
     * значимых слов (частотные, не стоп-слова), плюс список ключевых слов.
     */
    fun mainIdea(text: String): String {
        val items = sentences(text)
        if (items.isEmpty()) return ""
        if (items.size == 1) return capitalize(items[0])

        val frequencies = HashMap<String, Int>()
        items.forEach { sentence ->
            words(sentence).forEach { word -> frequencies.merge(word, 1, Int::plus) }
        }
        val keywords = frequencies.entries
            .filter { it.key.length >= 4 && it.value >= 1 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
            .map { it.key }

        fun score(sentence: String): Double {
            val sentenceWords = words(sentence)
            if (sentenceWords.isEmpty()) return 0.0
            val keywordHits = sentenceWords.count { it in keywords }
            // Небольшой бонус за длину: слишком короткие реплики («ну», «да») проигрывают.
            return keywordHits * 2.0 + sentenceWords.size.coerceAtMost(20) * 0.1
        }

        val bestIndex = items.indices.maxByOrNull { score(items[it]) } ?: 0
        val idea = capitalize(items[bestIndex].trimEnd().trimEnd('.', '!', '?', '…') + ".")
        val keywordLine = keywords.take(4).joinToString(", ")
        return if (keywordLine.isBlank()) idea else "$idea\nКлючевое: $keywordLine."
    }

    private fun words(text: String): List<String> =
        text.lowercase()
            .replace(wordSeparators, " ")
            .split(whitespace)
            .map { it.trim('-') }
            .filter { it.isNotBlank() && it !in STOP_WORDS && it.length > 2 }

    private fun capitalize(text: String): String {
        if (text.isEmpty()) return text
        return text.replaceFirstChar { it.uppercase() }
    }
}
