import Foundation

/// Defaults only affect newly created alarms. Existing schedules keep their own settings.
public struct AlarmDefaults: Codable, Equatable, Sendable {
    public var tone: AlarmTone = .system
    public var snoozeMinutes = 5
    public var snoozeLimit = 3
    public var followUpMinutes = 0
    public init() {}

    public func validate() throws {
        guard (1...30).contains(snoozeMinutes), (0...10).contains(snoozeLimit),
              (0...30).contains(followUpMinutes) else {
            throw LoopError.invalid("Default snooze or wake-up check settings are out of range.")
        }
    }

    public func routine(kind: RoutineKind = .alarm) -> Routine {
        var routine = Routine(kind:kind)
        if kind == .alarm {
            routine.tone = tone
            routine.snoozeMinutes = snoozeMinutes
            routine.snoozeLimit = snoozeLimit
            routine.followUpMinutes = followUpMinutes
        }
        return routine
    }
}
