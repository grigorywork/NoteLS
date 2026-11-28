package com.notessync.app.notes.subtasks

import kotlinx.serialization.Serializable
import com.notessync.app.tasks.running.RunningClockTime

/** IDs are scoped to their parent note's global identity, never a standalone note. */
@Serializable
data class Subtask(val id: String, val text: String = "", val completed: Boolean = false,
    val elapsedMillis: Long = 0, val startedAt: Long? = null,
    val countdownMillis: Long = 0, val countdownEndAt: Long? = null, val countdownFinishedAt: Long? = null) {
    fun paused(now: Long) = copy(elapsedMillis = RunningClockTime.elapsed(elapsedMillis, startedAt, now),
        startedAt = null, countdownMillis = countdownEndAt?.let { RunningClockTime.remaining(it, now) } ?: countdownMillis,
        countdownEndAt = null)
}

object Subtasks {
    const val MAX_ITEMS = 100
    const val MAX_TEXT = 2000
    const val MAX_TOTAL_TEXT = 64_000
    private val identity = Regex("[A-Za-z0-9_-]{1,80}")
    fun validId(id: String) = identity.matches(id)
    fun checked(rows: List<Subtask>): List<Subtask> {
        require(rows.size <= MAX_ITEMS && rows.map { it.id }.toSet().size == rows.size) { "Invalid subtask count or identity" }
        require(rows.sumOf { it.text.length.toLong() } <= MAX_TOTAL_TEXT) { "Subtask text limit" }
        for (r in rows) require(validId(r.id) && r.text.length <= MAX_TEXT &&
            r.elapsedMillis >= 0 && r.countdownMillis >= 0 && (r.startedAt == null || r.startedAt > 0) &&
            (r.countdownEndAt == null || r.countdownEndAt > 0) &&
            (r.countdownFinishedAt == null || (r.countdownFinishedAt > 0 && r.countdownEndAt == null && r.countdownMillis == 0L)) &&
            (!r.completed || (r.startedAt == null && r.countdownEndAt == null))) { "Invalid subtask" }
        return rows
    }

    /** Local text never restarts an externally paused clock; an external removal is not revived. */
    fun merge(local: List<Subtask>, baseline: List<Subtask>, fresh: List<Subtask>): List<Subtask> {
        val old = baseline.associateBy { it.id }; val remote = fresh.associateBy { it.id }
        val result = local.mapNotNull { row ->
            val before = old[row.id]
            val current = remote[row.id]
            if (before == null) row
            else if (current == null) null
            else {
                val watchChanged = row.elapsedMillis != before.elapsedMillis || row.startedAt != before.startedAt
                val timerChanged = row.countdownMillis != before.countdownMillis || row.countdownEndAt != before.countdownEndAt || row.countdownFinishedAt != before.countdownFinishedAt
                val completed = if (row.completed == before.completed) current.completed else row.completed
                row.copy(text = if (row.text == before.text) current.text else row.text,
                    completed = completed,
                    elapsedMillis = if (watchChanged) row.elapsedMillis else current.elapsedMillis,
                    startedAt = if (completed) null else if (watchChanged) row.startedAt else current.startedAt,
                    countdownMillis = if (timerChanged) row.countdownMillis else current.countdownMillis,
                    countdownEndAt = if (completed) null else if (timerChanged) row.countdownEndAt else current.countdownEndAt,
                    countdownFinishedAt = if (timerChanged) row.countdownFinishedAt else current.countdownFinishedAt)
            }
        } + fresh.filter { it.id !in old && local.none { item -> item.id == it.id } }
        return checked(result)
    }

    /** History restores document fields only, preserving live/external clock commands. */
    fun history(current: List<Subtask>, from: List<Subtask>, to: List<Subtask>, now: Long): List<Subtask> {
        val source = from.associateBy { it.id }; val target = to.associateBy { it.id }
        val result = current.mapNotNull { row ->
            val before = source[row.id]; val after = target[row.id]
            when {
                before == null -> row
                after == null -> null
                else -> row.copy(text = if (before.text != after.text) after.text else row.text,
                    completed = if (before.completed != after.completed) after.completed else row.completed)
                    .let { if (it.completed) it.paused(now) else it }
            }
        }.toMutableList()
        for ((index, row) in to.withIndex()) if (row.id !in source && result.none { it.id == row.id }) {
            result.add(index.coerceAtMost(result.size), row.paused(now))
        }
        return checked(result)
    }
}
