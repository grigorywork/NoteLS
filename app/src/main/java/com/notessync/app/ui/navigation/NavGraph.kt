package com.notessync.app.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.notessync.app.widget.WidgetNoteLinks
import com.notessync.app.widget.WidgetNoteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notessync.app.sync.android.SyncViewModel
import com.notessync.app.ui.screens.AnalyticsScreen
import com.notessync.app.ui.screens.CalendarScreen
import com.notessync.app.ui.screens.NoteEditorScreen
import com.notessync.app.ui.screens.NotesListScreen
import com.notessync.app.ui.screens.QuotesScreen
import com.notessync.app.ui.screens.ScannerScreen
import com.notessync.app.ui.screens.SettingsScreen
import com.notessync.app.ui.screens.SyncScreen

object Routes {
    const val NOTES = "notes"
    const val SETTINGS = "settings"
    const val SYNC = "sync"
    const val SCANNER = "scanner"
    const val ANALYTICS = "analytics"
    const val CALENDAR = "calendar"
    const val DIARY = "diary"
    const val AGENDA = "agenda" // Legacy in-app route; no Daily Planner menu item.
    const val QUOTES = "quotes"
    const val EDITOR = "editor?noteId={noteId}&isTask={isTask}&dayKey={dayKey}"

    fun editor(noteId: Long = -1L, isTask: Boolean = false, dayKey: Long = -1L) =
        "editor?noteId=$noteId&isTask=$isTask&dayKey=$dayKey"
}

@Composable
fun AppNavHost(widgetRequest: WidgetNoteRequest? = null, onWidgetHandled: (Long) -> Unit = {},
    plannerRequest: Long? = null, onPlannerHandled: (Long) -> Unit = {}) {
    val navController = rememberNavController()
    // Синхронизация — процессная сессия: ViewModel живёт в Activity,
    // чтобы сканер и экран синхронизации видели один и тот же экземпляр.
    val context = LocalContext.current
    val activity = remember(context) { requireNotNull(componentActivity(context)) { "Navigation requires a ComponentActivity" } }
    fun navigate(route: String) { activity.runOnUiThread { if (!activity.isDestroyed) navController.navigate(route) } }
    fun back() { activity.runOnUiThread { if (!activity.isDestroyed) navController.popBackStack() } }
    LaunchedEffect(widgetRequest) {
        if (widgetRequest != null) {
            try {
                if (withContext(Dispatchers.IO) { WidgetNoteLinks.canOpen(context, widgetRequest) }) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        activity.runOnUiThread {
                            if (continuation.isActive) {
                                try {
                                    if (!activity.isDestroyed) navController.navigate(Routes.editor(widgetRequest.noteId)) { launchSingleTop = true }
                                    continuation.resume(Unit)
                                } catch (error: Exception) { continuation.resumeWithException(error) }
                            }
                        }
                    }
                }
            } finally { onWidgetHandled(widgetRequest.sequence) }
        }
    }
    LaunchedEffect(plannerRequest) {
        if (plannerRequest != null) {
            try {
                val entry = navController.currentBackStackEntry
                // A warm shortcut must not abandon an editor's pending text or attachments.
                val saved = if (entry != null && entry.destination.route == Routes.EDITOR) {
                    androidx.lifecycle.ViewModelProvider(entry)[com.notessync.app.ui.notes.NoteEditorViewModel::class.java]
                        .saveNow(force = true)
                } else true
                if (saved) suspendCancellableCoroutine<Unit> { continuation ->
                    activity.runOnUiThread {
                        if (continuation.isActive) {
                            try {
                                if (!activity.isDestroyed) navController.navigate(Routes.CALENDAR) { launchSingleTop = true }
                                continuation.resume(Unit)
                            } catch (error: Exception) { continuation.resumeWithException(error) }
                        }
                    }
                }
            } finally { onPlannerHandled(plannerRequest) }
        }
    }

    NavHost(navController = navController, startDestination = Routes.NOTES) {
        composable(Routes.NOTES) { backStackEntry ->
            NotesListScreen(
                onOpenSettings = { navigate(Routes.SETTINGS) },
                onOpenSync = { navigate(Routes.SYNC) },
                onOpenAnalytics = { navigate(Routes.ANALYTICS) },
                onOpenCalendar = { navigate(Routes.CALENDAR) },
                onOpenDiary = { navigate(Routes.DIARY) },
                onOpenQuotes = { navigate(Routes.QUOTES) },
                onOpenEditor = { noteId, isTask ->
                    navigate(Routes.editor(noteId, isTask))
                },
                deletedNoteEvents = backStackEntry.savedStateHandle.getStateFlow<com.notessync.app.data.local.model.NoteIdentity?>("deleted_note_event", null),
                onDeletedEventHandled = {
                    backStackEntry.savedStateHandle["deleted_note_event"] = null
                }
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("noteId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("isTask") { type = NavType.BoolType; defaultValue = false },
                navArgument("dayKey") { type = NavType.LongType; defaultValue = -1L }
            )
        ) {
            NoteEditorScreen(
                onBack = { back() },
                onOpenCalendar = { navigate(Routes.CALENDAR) },
                onOpenTransformSettings = { navigate("${Routes.SETTINGS}?section=transform") },
                onDeleted = { event -> activity.runOnUiThread {
                    if (!activity.isDestroyed) {
                        if (event.id > 0) navController.previousBackStackEntry?.savedStateHandle?.set("deleted_note_event", event)
                        navController.popBackStack()
                    }
                } }
            )
        }
        composable(Routes.DIARY) {
            com.notessync.app.ui.screens.DiaryScreen(onBack = { back() }, onOpenNote = { id -> navigate(Routes.editor(id)) })
        }
        composable(Routes.ANALYTICS) {
            AnalyticsScreen(
                onBack = { back() },
                onOpenNote = { noteId -> navigate(Routes.editor(noteId, true)) }
            )
        }
        composable(Routes.CALENDAR) {
            CalendarScreen(
                onBack = { back() },
                onOpenNote = { noteId, isTask ->
                    navigate(Routes.editor(noteId, isTask))
                },
                onAddEntry = { dayKey, isTask ->
                    navigate(Routes.editor(-1L, isTask, dayKey))
                }
            )
        }
        composable(Routes.AGENDA) {
            CalendarScreen(onBack = { back() }, agendaMode = true,
                onOpenNote = { id, task -> navigate(Routes.editor(id, task)) },
                onAddEntry = { day, task -> navigate(Routes.editor(-1L, task, day)) })
        }
        composable(Routes.QUOTES) {
            QuotesScreen(onBack = { back() })
        }
        composable("${Routes.SETTINGS}?section={section}", arguments = listOf(
            navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null }
        )) { entry ->
            SettingsScreen(
                onBack = { back() },
                onOpenSync = { navigate(Routes.SYNC) },
                initialGroup = entry.arguments?.getString("section")
            )
        }
        composable(Routes.SYNC) {
            val syncViewModel: SyncViewModel = viewModel(viewModelStoreOwner = activity)
            DisposableEffect(activity, syncViewModel) {
                activity.lifecycle.addObserver(syncViewModel)
                onDispose { activity.lifecycle.removeObserver(syncViewModel) }
            }
            SyncScreen(
                onBack = { back() },
                onOpenScanner = { navigate(Routes.SCANNER) },
                viewModel = syncViewModel
            )
        }
        composable(Routes.SCANNER) {
            val syncViewModel: SyncViewModel = viewModel(viewModelStoreOwner = activity)
            ScannerScreen(
                onResult = { payload ->
                    syncViewModel.connectFromQr(payload)
                    back()
                },
                onBack = { back() }
            )
        }
    }
}


/** Scoped language/theme wrappers retain the Activity as a base context. */
private fun componentActivity(context: Context): ComponentActivity? {
    var current = context
    repeat(16) {
        val candidate = current
        if (candidate is ComponentActivity) return candidate
        current = (candidate as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}
