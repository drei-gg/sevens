import BloomWire
import Foundation

public struct SurfaceSnapshot: Codable, Sendable {
  public var updated: Date
  public var enabled: Bool
  public var cycleDay: Int?
  public var nextReminder: Date?
  public init(enabled: Bool, cycleDay: Int?, nextReminder: Date?) {
    updated = Date()
    self.enabled = enabled
    self.cycleDay = cycleDay
    self.nextReminder = nextReminder
  }
  public static func read(group: String?) -> Self? {
    guard let group, let data = UserDefaults(suiteName: group)?.data(forKey: "bloom.widget") else {
      return nil
    }
    return try? JSONDecoder().decode(Self.self, from: data)
  }
  public func save(group: String?) {
    guard let group else { return }
    UserDefaults(suiteName: group)?.set(try? JSONEncoder().encode(self), forKey: "bloom.widget")
  }
}
