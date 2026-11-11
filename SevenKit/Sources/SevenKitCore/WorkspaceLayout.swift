import Foundation

/// Layout follows the current content proposal, never a device model, screen or orientation.
public struct WorkspaceLayout: Equatable, Sendable {
    public enum Arrangement: Sendable { case stacked, columns }
    public let arrangement: Arrangement
    public let primaryWidth: Double
    public let detailWidth: Double
    public static let gap = 24.0
    public init(width: Double, accessibilityText: Bool = false) {
        let primaryMinimum = 320.0, detailMinimum = 360.0
        let available = width.isFinite ? max(0, width) : 0
        if !accessibilityText && available >= primaryMinimum + detailMinimum + Self.gap {
            arrangement = .columns
            primaryWidth = min(420, max(primaryMinimum, (available - Self.gap) * 0.42))
            detailWidth = available - Self.gap - primaryWidth
        } else {
            arrangement = .stacked; primaryWidth = available; detailWidth = available
        }
    }
}
