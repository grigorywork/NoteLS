package com.notessync.app.launcher

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherIconSelectionTest {
    private class Components : LauncherComponents {
        val active = linkedSetOf(LauncherIcon.GLASS)
        val targets = mutableListOf<LauncherIcon>()
        var fail: LauncherIcon? = null
        override fun enabled(icon: LauncherIcon) = icon in active
        override fun set(icon: LauncherIcon, enabled: Boolean) {
            if (enabled && fail == icon) { fail = null; error("Unavailable") }
            if (enabled) { targets.add(icon); active.add(icon) } else active.remove(icon)
        }
    }
    @Test fun `fast completed IO cannot admit a second callback from an unpublished frame but a fresh frame can choose again`() = runTest {
        val backend = Components(); val selection = LauncherIconSelection(LauncherIcons(backend), backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        val published = selection.state.value
        val first = selection.action(LauncherIcon.PAPER, published)
        val second = selection.action(LauncherIcon.FROSTED, published)
        first(); runCurrent() // The platform operation completed; the UI still owns the old callbacks.
        assertFalse(selection.state.value.busy)
        second(); runCurrent()
        assertEquals(listOf(LauncherIcon.PAPER), backend.targets)
        assertEquals(LauncherIcon.PAPER, selection.state.value.selected)
        selection.action(LauncherIcon.FROSTED, selection.state.value)(); runCurrent()
        assertEquals(listOf(LauncherIcon.PAPER, LauncherIcon.FROSTED), backend.targets)
        assertEquals(LauncherIcon.FROSTED, selection.state.value.selected)
    }
    @Test fun `pending operation and disposed owner reject additional callbacks`() = runTest {
        val backend = Components(); val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val selection = LauncherIconSelection(LauncherIcons(backend), owner, StandardTestDispatcher(testScheduler)); runCurrent()
        val frame = selection.state.value
        selection.action(LauncherIcon.PAPER, frame)(); selection.action(LauncherIcon.FROSTED, frame)()
        assertTrue(selection.state.value.busy); runCurrent()
        assertEquals(listOf(LauncherIcon.PAPER), backend.targets)
        val fresh = selection.action(LauncherIcon.FROSTED, selection.state.value)
        owner.cancel(); fresh(); runCurrent()
        assertEquals(listOf(LauncherIcon.PAPER), backend.targets)
    }
    @Test fun `failed operation retains platform choice rejects stale retry and permits a new published retry`() = runTest {
        val backend = Components().apply { fail = LauncherIcon.PAPER }
        val icons = LauncherIcons(backend); val selection = LauncherIconSelection(icons, backgroundScope, StandardTestDispatcher(testScheduler)); runCurrent()
        val old = selection.state.value; val stale = selection.action(LauncherIcon.FROSTED, old)
        selection.action(LauncherIcon.PAPER, old)(); runCurrent()
        assertTrue(selection.state.value.failed); assertFalse(selection.state.value.busy)
        assertEquals(LauncherIcon.GLASS, icons.current())
        val targets = backend.targets.toList(); stale(); runCurrent(); assertEquals(targets, backend.targets)
        selection.action(LauncherIcon.FROSTED, selection.state.value)(); runCurrent()
        assertFalse(selection.state.value.failed); assertEquals(LauncherIcon.FROSTED, icons.current())
    }
}
