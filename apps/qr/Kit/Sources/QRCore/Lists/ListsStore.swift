import Foundation

/// Where an accepted `lists.json` is kept between launches (SPEC §4.3): Application Support, in
/// the app group's container when the app has one so the extensions read the same file.
/// Holds no state beyond the folder; reads are capped at `ListsBundle.maxBytes`.
public struct ListsStore: Sendable {
    public static let fileName = "lists.json"
    private static let folderName = "QRLists"

    private let directory: URL?

    /// - Parameter directory: tests pass a temporary folder; nil means the app's own location.
    public init(directory: URL? = nil) {
        self.directory = directory ?? Self.defaultDirectory()
    }

    /// The saved file, or nil when there is nowhere to keep one.
    public var fileURL: URL? { directory?.appendingPathComponent(Self.fileName) }

    /// The saved bytes, or nil when nothing is saved, the file is unreadable or it is too large.
    public func read() -> Data? {
        guard let url = fileURL,
              let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber,
              size.intValue > 0, size.intValue <= ListsBundle.maxBytes else { return nil }
        return try? Data(contentsOf: url)
    }

    /// Replaces the saved file. Throws when there is nowhere to write or the write fails.
    public func write(_ data: Data) throws {
        guard let url = fileURL, let directory else { throw CocoaError(.fileWriteUnknown) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }

    /// Deletes the saved file; nothing happens when there is none.
    public func remove() {
        guard let url = fileURL else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private static func defaultDirectory() -> URL? {
        var base: URL?
        #if canImport(Darwin)
        if let group = Bundle.main.object(forInfoDictionaryKey: "SevensAppGroup") as? String {
            let name = group.trimmingCharacters(in: .whitespacesAndNewlines)
            if !name.isEmpty, !name.contains("$(") {
                base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: name)?
                    .appendingPathComponent("Library/Application Support", isDirectory: true)
            }
        }
        #endif
        if base == nil {
            base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                appropriateFor: nil, create: false)
        }
        return base?.appendingPathComponent(folderName, isDirectory: true)
    }
}
