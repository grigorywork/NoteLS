package com.notessync.app.data.local.model

/** Logical change version follows the fresh row even after clock skew; elapsed clocks still use wall time. */
fun nextChangeTimestamp(previous: Long, now: Long = System.currentTimeMillis()): Long =
    if (previous == Long.MAX_VALUE) Long.MAX_VALUE else maxOf(now, previous + 1)
