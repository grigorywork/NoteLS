package com.notessync.app.ui.navigation

import androidx.activity.ComponentActivity
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
    const val QUOTES = "quotes"
    const val EDITOR = "editor?noteId={noteId}&isTask={isTask}&dayKey={dayKey}"

    fun editor(noteId: Long = -1L, isTask: Boolean = false, dayKey: Long = -1L) =
        "editor?noteId=$noteId&isTask=$isTask&dayKey=$dayKey"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    // Синхронизация — процессная сессия: ViewModel живёт в Activity,
    // чтобы сканер и экран синхронизации видели один и тот же экземпляр.
    val activity = LocalContext.current as ComponentActivity

    NavHost(navController = navController, startDestination = Routes.NOTES) {
        composable(Routes.NOTES) { backStackEntry ->
            NotesListScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSync = { navController.navigate(Routes.SYNC) },
                onOpenAnalytics = { navController.navigate(Routes.ANALYTICS) },
                onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                onOpenQuotes = { navController.navigate(Routes.QUOTES) },
                onOpenEditor = { noteId, isTask ->
                    navController.navigate(Routes.editor(noteId, isTask))
                },
                deletedNoteEvents = backStackEntry.savedStateHandle.getStateFlow("deleted_note_id", -1L),
                onDeletedEventHandled = {
                    backStackEntry.savedStateHandle["deleted_note_id"] = -1L
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
                onBack = { navController.popBackStack() },
                onDeleted = { id ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("deleted_note_id", id)
                    navController.popBackStack()
                }
            )
        }
        composable(Routes.ANALYTICS) {
            AnalyticsScreen(
                onBack = { navController.popBackStack() },
                onOpenNote = { noteId -> navController.navigate(Routes.editor(noteId, true)) }
            )
        }
        composable(Routes.CALENDAR) {
            CalendarScreen(
                onBack = { navController.popBackStack() },
                onOpenNote = { noteId, isTask ->
                    navController.navigate(Routes.editor(noteId, isTask))
                },
                onAddEntry = { dayKey, isTask ->
                    navController.navigate(Routes.editor(-1L, isTask, dayKey))
                }
            )
        }
        composable(Routes.QUOTES) {
            QuotesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSync = { navController.navigate(Routes.SYNC) }
            )
        }
        composable(Routes.SYNC) {
            val syncViewModel: SyncViewModel = viewModel(viewModelStoreOwner = activity)
            DisposableEffect(activity, syncViewModel) {
                activity.lifecycle.addObserver(syncViewModel)
                onDispose { activity.lifecycle.removeObserver(syncViewModel) }
            }
            SyncScreen(
                onBack = { navController.popBackStack() },
                onOpenScanner = { navController.navigate(Routes.SCANNER) },
                viewModel = syncViewModel
            )
        }
        composable(Routes.SCANNER) {
            val syncViewModel: SyncViewModel = viewModel(viewModelStoreOwner = activity)
            ScannerScreen(
                onResult = { payload ->
                    syncViewModel.connectFromQr(payload)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

