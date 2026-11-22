import Foundation

/// Shared app-group file contains only the Rust projection, never the archive or mission secrets.
public enum AlarmWidgetStore {
    public static func url(group: String?) -> URL? {
        guard let group, !group.isEmpty else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("alarm-widget.json")
    }
    public static func read(from url: URL?) -> AlarmWidgetSnapshot? {
        guard let url, let stream = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? stream.close() }
        guard let data = try? stream.read(upToCount: 65_537), data.count <= 65_536,
              let text = String(data: data, encoding: .utf8) else { return nil }
        return try? alarmWidgetDecode(text: text)
    }
    public static func write(_ snapshot: AlarmWidgetSnapshot?, to url: URL) throws {
        guard let snapshot else {
            if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
            return
        }
        let data = Data(try alarmWidgetEncode(snapshot: snapshot).utf8)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var resource = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try resource.setResourceValues(values)
    }
}
