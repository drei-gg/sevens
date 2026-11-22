import CleanerCore

/// Session-only review; verified, saved copies remain when the queue closes.
struct CompressionBatch {
    let items: [CleanupItem]
    var index = 0
    var saved: [String] = []
    var skipped: [String] = []
    var unverified: [String] = []
    var current: CleanupItem? { items.indices.contains(index) ? items[index] : nil }
    var complete: Bool { index == items.count }
}
