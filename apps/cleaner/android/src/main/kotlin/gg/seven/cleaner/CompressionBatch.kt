package gg.seven.cleaner

import gg.seven.cleaner.core.CleanupItem

/**
 * Only one original/candidate pair is prepared at a time. Completed copies outlive this session.
 */
data class CompressionBatch(
    val items: List<CleanupItem>,
    val index: Int = 0,
    val saved: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val unverified: List<String> = emptyList(),
) {
    val current: CleanupItem?
        get() = items.getOrNull(index)

    val complete: Boolean
        get() = index == items.size
}
