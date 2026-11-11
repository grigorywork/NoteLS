package com.notessync.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.ui.quotes.Quote
import com.notessync.app.ui.quotes.QuotesData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// Этап 38: «Цитаты» в боковом меню — подборка самых цитируемых в сети
// высказываний Эпикура, Шопенгауэра, Сенеки, Диогена, Платона, Пелевина
// и Кастанеды. Каждую цитату можно скопировать, отправить или сохранить
// прямо в заметки.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedAuthor by remember { mutableStateOf<String?>(null) }
    val quoteOfDay = remember { QuotesData.quoteOfDay(LocalDate.now().toEpochDay()) }
    val visibleQuotes = remember(selectedAuthor) { QuotesData.ofAuthor(selectedAuthor) }

    fun shareQuote(quote: Quote) {
        val author = QuotesData.author(quote.authorId)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "«${quote.text}» — ${author.name}")
        }
        context.startActivity(Intent.createChooser(send, "Поделиться цитатой"))
    }

    fun saveQuoteAsNote(quote: Quote) {
        scope.launch {
            val author = QuotesData.author(quote.authorId)
            withContext(Dispatchers.IO) {
                AppDatabase.get(context).noteDao().upsert(
                    NoteEntity(
                        title = "Цитата: ${author.name}",
                        content = "«${quote.text}»\n\n— ${author.name}" +
                            (quote.source?.let { ", $it" } ?: "")
                    )
                )
            }
            snackbarHostState.showSnackbar("Сохранено в заметки")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Цитаты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "quote-of-day") {
                QuoteOfDayCard(quoteOfDay)
            }
            item(key = "filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedAuthor == null,
                        onClick = { selectedAuthor = null },
                        label = { Text("Все") }
                    )
                    QuotesData.authors.forEach { author ->
                        FilterChip(
                            selected = selectedAuthor == author.id,
                            onClick = {
                                selectedAuthor = if (selectedAuthor == author.id) null else author.id
                            },
                            label = { Text(author.name) }
                        )
                    }
                }
            }
            items(visibleQuotes, key = { it.text.hashCode() }) { quote ->
                QuoteCard(
                    quote = quote,
                    onCopy = {
                        val author = QuotesData.author(quote.authorId)
                        clipboard.setText(AnnotatedString("«${quote.text}» — ${author.name}"))
                        scope.launch { snackbarHostState.showSnackbar("Скопировано") }
                    },
                    onShare = { shareQuote(quote) },
                    onSaveToNote = { saveQuoteAsNote(quote) }
                )
            }
        }
    }
}

@Composable
private fun QuoteOfDayCard(quote: Quote) {
    val author = QuotesData.author(quote.authorId)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FormatQuote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Цитата дня",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                "«${quote.text}»",
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "— ${author.name}" + (quote.source?.let { ", $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun QuoteCard(
    quote: Quote,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSaveToNote: () -> Unit
) {
    val author = QuotesData.author(quote.authorId)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "«${quote.text}»",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "— ${author.name}" + (quote.source?.let { ", $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Скопировать",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Поделиться",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSaveToNote) {
                    Icon(
                        Icons.Default.NoteAdd,
                        contentDescription = "Сохранить в заметки",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
