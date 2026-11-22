import Foundation

/// Deliberately limited transfer boundary: no symptoms, notes, medication or contact fields.
public struct HealthFlowSample: Codable, Sendable {
  public var day: String
  public var flow: String
  public var period_start: Bool
  public init(day: String, flow: String, periodStart: Bool) {
    self.day = day
    self.flow = flow
    self.period_start = periodStart
  }
}

public struct HealthFlowExport: Decodable, Sendable {
  public var sync_id: String
  public var version: Int64
  public var day: String
  public var flow: String
  public var period_start: Bool
  public var at: Int64?
  public var zone: String?
}
