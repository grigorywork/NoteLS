package com.notessync.app.data.local.entity

import androidx.room.*
import com.notessync.app.data.quotes.QuoteDraft

@Entity(tableName = "custom_quotes", indices = [Index(value = ["fingerprint"], unique = true)])
data class CustomQuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val author: String,
    val source: String,
    val fingerprint: String
) {
    fun draft() = QuoteDraft(text, author, source)
}

@Entity(tableName = "quote_library_state", foreignKeys = [ForeignKey(entity = CustomQuoteEntity::class,
    parentColumns = ["id"], childColumns = ["pinnedQuoteId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("pinnedQuoteId")])
data class QuoteLibraryState(@PrimaryKey val id: Int = 1, val pinnedQuoteId: Long? = null)
