package com.notessync.app.ui.analytics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.model.AnalyticsNotebook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val requestedScope = MutableStateFlow<AnalyticsScope>(AnalyticsScope.All)
    val scope = requestedScope.asStateFlow()
    private val refreshToken = MutableStateFlow(0)
    val notebooks: StateFlow<List<AnalyticsNotebook>?> = db.notebookDao().observeForAnalytics().distinctUntilChanged().flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null)

    val state: StateFlow<AnalyticsState> = combine(db.noteDao().observeForAnalytics().distinctUntilChanged().flowOn(Dispatchers.Default), notebooks, requestedScope, refreshToken) {
            entries, books, requested, _ -> Triple(entries, books, requested)
        }.mapLatest { (entries, books, requested) ->
            if (books == null) AnalyticsState(scope = requested) else {
                val computed = withContext(Dispatchers.Default) {
                    val zone = ZoneId.systemDefault()
                    AnalyticsSummary.build(entries, books, requested, LocalDate.now(zone), zone)
                }
                if (requestedScope.value == requested && computed.scope != requested) requestedScope.value = computed.scope
                computed
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), AnalyticsState())

    fun selectScope(scope: AnalyticsScope) { requestedScope.value = scope }
    fun refresh() { refreshToken.value++ }
}
