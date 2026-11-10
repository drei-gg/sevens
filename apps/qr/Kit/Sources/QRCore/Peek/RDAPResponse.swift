import Foundation

public enum RDAPResponse {
    /// Bodies larger than this are ignored.
    static let maxBytes = 256 * 1024

    /// The `events[]` entry whose `eventAction` is `registration`, or nil.
    public static func registrationDate(json: Data) -> Date? {
        guard json.count <= maxBytes,
              let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let events = root["events"] as? [Any] else { return nil }
        for case let event as [String: Any] in events {
            guard let action = event["eventAction"] as? String,
                  action.trimmingCharacters(in: .whitespaces).lowercased() == "registration",
                  let text = event["eventDate"] as? String,
                  let date = ISO8601Lite.date(from: text) else { continue }
            return date
        }
        return nil
    }
}
