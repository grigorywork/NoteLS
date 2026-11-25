package com.notessync.app.launcher

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class LauncherSelectionState(
    val selected: LauncherIcon? = null,
    val busy: Boolean = true,
    val failed: Boolean = false,
    val revision: Long = 0L
)

/** A published frame owns its callbacks; completion alone does not publish a new frame. */
internal class LauncherIconSelection(
    private val icons: LauncherIcons,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutable = MutableStateFlow(LauncherSelectionState())
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            try { val icon = withContext(dispatcher) { icons.current() }; mutable.update { it.copy(selected = icon) } }
            catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(failed = true) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun action(icon: LauncherIcon, published: LauncherSelectionState): () -> Unit = {
        val before = mutable.value
        if (scope.isActive && !before.busy && before.revision == published.revision &&
            mutable.compareAndSet(before, before.copy(busy = true, failed = false, revision = before.revision + 1))) {
            scope.launch {
                try { val selected = withContext(dispatcher) { icons.select(icon) }; mutable.update { it.copy(selected = selected) } }
                catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(failed = true) } }
                finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }
}
