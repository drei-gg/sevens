import Foundation

public enum BackupCodec {
    public static let maxBytes = 32 * 1024 * 1024
    public static func encode(_ archive: LoopArchive) throws -> Data { let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]; return try encoder.encode(archive) }
    public static func decode(_ data: Data) throws -> LoopArchive {
        guard data.count <= maxBytes else { throw LoopError.invalid("This backup exceeds 32 MB.") }
        let a = try JSONDecoder().decode(LoopArchive.self, from: data); try a.validate(); return a
    }
    /// Imports are dormant. Receipts and pending missions belong to the original device only.
    public static func merge(_ incoming: LoopArchive, into current: LoopArchive) throws -> LoopArchive {
        try incoming.validate(); var result = current
        for routine in incoming.routines {
            if current.routines.contains(where: { $0.id == routine.id }) { continue }
            var copy = routine; copy.enabled = false; result.routines.append(copy)
        }
        for event in incoming.events {
            if let old = current.events.first(where: { $0.id == event.id }) {
                guard old == event else { throw LoopError.invalid("A history ID has conflicting contents. Nothing was imported.") }
            } else { result.events.append(event) }
        }
        try result.validate(); return result
    }
    public static func csv(_ archive: LoopArchive) -> String {
        func quote(_ s: String) -> String {
            let protected = s.first.map { "=+-@\t\r".contains($0) } == true ? "'" + s : s
            return "\"" + protected.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        let formatter = ISO8601DateFormatter()
        return "id,routine_id,routine,time_utc,event,note\r\n" + archive.events.map {
            [$0.id.uuidString,$0.routineID.uuidString,$0.name,formatter.string(from:$0.date),$0.kind.rawValue,$0.note].map(quote).joined(separator: ",")
        }.joined(separator: "\r\n")
    }
}
public actor LoopRepository {
    public typealias Writer = @Sendable (Data, URL) throws -> Void
    private let file: URL?
    private let writer: Writer
    private var cache: LoopArchive
    private var loaded: Bool
    public init(file: URL?, seed: LoopArchive = LoopArchive(), writer: @escaping Writer = LoopRepository.atomicWrite) { self.file = file; cache = seed; loaded = file == nil; self.writer = writer }
    public func read() throws -> LoopArchive {
        if !loaded {
            if let file, FileManager.default.fileExists(atPath:file.path) {
                guard (try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0) <= BackupCodec.maxBytes else { throw LoopError.invalid("Archive exceeds 32 MB.") }
                cache = try BackupCodec.decode(Data(contentsOf:file))
            }
            loaded = true
        }
        return cache
    }
    public func change(_ action: @Sendable (inout LoopArchive) throws -> Void) throws -> LoopArchive {
        var next = try read(); try action(&next); try next.validate()
        let bytes = try BackupCodec.encode(next)
        guard bytes.count <= BackupCodec.maxBytes else { throw LoopError.invalid("Archive exceeds 32 MB. Export and remove older history before adding more.") }
        if let file { try writer(bytes,file) }; cache = next; return cache
    }
    public static let atomicWrite: Writer = { data, url in
        try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
        #if os(iOS)
        try data.write(to:url,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to:url,options:.atomic)
        #endif
    }
}
