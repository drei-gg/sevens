package gg.seven.fast.wear

import gg.seven.fast.wire.WireSnapshot

// Display arithmetic only. Whether a fast is running, its goal and the water total come from the
// phone, where the Rust core decides them.

/** "3:07:42": SwiftUI's `Text(start, style: .timer)` for an elapsed count. */
fun elapsedText(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** "16 h", "16 h 30 m", "45 m". */
fun shortDuration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m m"
        m == 0L -> "$h h"
        else -> "$h h $m m"
    }
}

/** Progress towards the goal, past 1 once the goal is met; null without a running fast and goal. */
fun WireSnapshot.goalProgress(now: Long): Float? {
    val start = start ?: return null
    val goal = goal ?: return null
    if (goal <= start) return null
    return ((now - start).coerceAtLeast(0).toDouble() / (goal - start)).toFloat()
}
