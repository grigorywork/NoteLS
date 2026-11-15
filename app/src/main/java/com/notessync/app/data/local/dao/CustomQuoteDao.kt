package com.notessync.app.data.local.dao

import androidx.room.*
import com.notessync.app.data.local.entity.CustomQuoteEntity
import com.notessync.app.data.local.entity.QuoteLibraryState
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomQuoteDao {
    @Query("SELECT * FROM custom_quotes ORDER BY id DESC LIMIT 2000") fun observeAll(): Flow<List<CustomQuoteEntity>>
    @Query("SELECT q.* FROM custom_quotes q INNER JOIN quote_library_state s ON s.pinnedQuoteId = q.id WHERE s.id = 1") fun observePinned(): Flow<CustomQuoteEntity?>
    @Query("SELECT q.* FROM custom_quotes q INNER JOIN quote_library_state s ON s.pinnedQuoteId = q.id WHERE s.id = 1") suspend fun pinned(): CustomQuoteEntity?
    @Query("SELECT * FROM custom_quotes WHERE id = :id") suspend fun get(id: Long): CustomQuoteEntity?
    @Query("SELECT * FROM custom_quotes WHERE fingerprint = :fingerprint LIMIT 1") suspend fun duplicate(fingerprint: String): CustomQuoteEntity?
    @Query("SELECT COUNT(*) FROM custom_quotes") suspend fun count(): Int
    @Query("SELECT fingerprint FROM custom_quotes") suspend fun fingerprints(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(quote: CustomQuoteEntity): Long
    @Update suspend fun update(quote: CustomQuoteEntity): Int
    @Query("DELETE FROM custom_quotes WHERE id = :id") suspend fun delete(id: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun pin(state: QuoteLibraryState)
}
